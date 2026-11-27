package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Offer
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceSession
import app.fireplayer.vod.SourceTarget

/**
 * The link of the one copy OK would play, checked while the viewer rests on it (a Continue card, a title page's Play:
 * [app.fireplayer.vod.play.LinkAhead]), so the play starts from it rather than waiting for the debrid service's answer,
 * which is most of a slow start (on the TV: "link +4462ms · … · frame +9286ms" beside "link +312ms · … · frame +4254ms").
 *
 * - Only from the title's list held in memory already, as last shown ([DefaultSourcesRepository.holds]: a title page's
 *   look-up, one kept from a recent look): nothing is ever looked up for it, so a rest costs no list's reading and
 *   ranking (on the TV a Continue card's look-up at a cold start was 66 MB more for a while), and it changes nothing of
 *   the list a page shows. With no list held, or one partly run out, nothing at all.
 * - Only the copy the play itself would pick from that list, found as its session finds it ([DefaultSourceSession]
 *   picking only: the copy a resume left, else the ranking's pick), and only its first offer, the one the play checks
 *   first. A list that can't tell it with nothing more on its way for it (an add-on not asked) leaves it there.
 * - Only when that offer is cached ([Readiness.PROBABLY_READY]): nothing checked ahead has the debrid service fetch.
 * - One at a time (a newer rest drops the one before), never while a film plays ([filmPlays]), none of another title
 *   once a play has begun ([handOver]), and none at all while a play handed over is still starting ([starting]): its own
 *   check is the only one sent then.
 *
 * A link checked is kept [KEEP_MS] in memory, never stored or logged: far within a debrid link's life (a film plays from
 * one link for hours, its stalls reopening that same link). It goes when focus moves on ([drop], through the rest's
 * Cancel), the network or the add-ons change ([DefaultSourcesRepository.forget]), or a play opens another copy. The play
 * takes it once, for its first check of that very offer ([claim]), or joins its check still under way: never sent twice.
 * A link kept that the player then can't open is checked afresh once, uncounted ([DefaultSourceSession.failed]); a check
 * ahead that failed is forgotten and the play checks for itself, as it always did. Main thread; the check itself runs on
 * the engine's [DefaultSourcesRepository.io].
 */
internal class LinkPrep(
    private val repo: DefaultSourcesRepository,
    private val filmPlays: () -> Boolean,
    private val log: (String) -> Unit,
) {
    /** One check ahead: [target]'s offer from [addon] whose add-on link is [link]; [handle] stops it. */
    private class Job(val target: SourceTarget, val addon: Addon, val link: SecretUrl, val startedAt: Long) {
        val handle = CallHandle()
        /** Its answer, once it came and while it's kept (a link ready; a failure isn't kept). */
        var result: LinkCheck.Result.Ready? = null
        var doneAt = 0L
        /** The play that joined it while it was being checked: it gets the answer, whatever it is. */
        var claimer: ((LinkCheck.Result) -> Unit)? = null
    }

    /** The pick under way (the play's own rules, waiting for the add-on's list), or null. */
    private var picking: DefaultSourceSession? = null
    private var pickOp: Cancel? = null
    /** The check ahead under way, or its link kept; null once a play took it. */
    private var job: Job? = null
    private var expiry: Runnable? = null
    /** Bumped by every rest and every drop: a rest's Cancel acts only while it is still the one under way. */
    private var run = 0
    /**
     * The play handed over last ([handOver]) while it starts: until its film has begun or it ends ([ended]), at most
     * [STARTING_MS], nothing new is checked ahead (OK pressed late in a rest: the page's rest may still end before the
     * page goes, and its check would be a second one of the same link beside the play's).
     */
    private var starting: SourceSession? = null
    private var startingAt = 0L

    /** Whether a pick or a check ahead is under way, or a link is kept (for tests). */
    val busy: Boolean get() = picking != null || job != null

    /**
     * The viewer rests on what OK would play, [request]: its copy is picked from the list held as its play would, then
     * its link checked.
     */
    fun start(request: PlayRequest): Cancel {
        // A play is starting: nothing beside its own check (and what it may still take from a rest stays for it).
        if (playStarting()) {
            log("ahead: a play is starting: nothing more is checked ahead")
            return Cancel {}
        }
        drop()
        if (runCatching { filmPlays() }.getOrDefault(true)) return Cancel {}
        val target = request.target
        if (!repo.holds(target)) {
            log(NO_LIST)
            return Cancel {}
        }
        val mine = run
        val pick = DefaultSourceSession(request, repo, pickOnly = { s, o -> if (run == mine) picked(target, s, o) })
        picking = pick
        // Nothing OK would play by itself (none cached, none listed), or the list can't tell yet with nothing more on its
        // way for it (only others' look-ups ever fill it): nothing is checked ahead.
        val op = pick.open(null, OpenWhy.START) { o ->
            val waits = o is Opened.Checking || (o is Opened.Looking && o.list.states.values.any { it is AddonState.Asking })
            if (run == mine && picking === pick && !waits) endPick()
        }
        if (picking === pick) pickOp = op else op.cancel()
        return Cancel { if (run == mine) drop() }
    }

    /**
     * The play of [target] checks [offer]'s link first: the link checked ahead for that very offer, once. A kept one comes
     * to [onResult] (posted, with when its check began and when it answered, elapsedRealtime ms); one still being checked
     * is joined (the answer when it comes, with null for the second time). Returns what stops it (the play's own
     * cancel), or null when there's none for that offer: the play checks it itself, and anything checked ahead for
     * another copy is dropped.
     */
    fun claim(target: SourceTarget, offer: Offer, onResult: (LinkCheck.Result, Long, Long?) -> Unit): CallHandle? {
        val j = job ?: return null
        if (j.target.key != target.key || j.addon != offer.addon || j.link != offer.link) {
            log("ahead: the play opens another copy; the link checked ahead is dropped")
            drop()
            return null
        }
        job = null
        expiry?.let { repo.main.remove(it) }
        expiry = null
        val kept = j.result
        if (kept == null) {
            j.claimer = { r -> onResult(r, j.startedAt, null) }
            return j.handle
        }
        val doneAt = j.doneAt
        if (repo.elapsed() - doneAt >= KEEP_MS) return null
        repo.main.post { onResult(kept, j.startedAt, doneAt) }
        return CallHandle()
    }

    /**
     * A play of [target] begins ([session]): a pick still under way is left to it; a link of another title is dropped;
     * nothing new is checked ahead until it has begun its film or ended ([ended]).
     */
    fun handOver(target: SourceTarget, session: SourceSession) {
        starting = session
        startingAt = repo.elapsed()
        endPick()
        if (job?.target?.key?.let { it != target.key } == true) drop()
    }

    /** [session]'s film began, or it ended: no longer a play starting ([starting]). */
    fun ended(session: SourceSession) {
        if (starting === session) starting = null
    }

    private fun playStarting(): Boolean {
        if (starting == null) return false
        if (repo.elapsed() - startingAt < STARTING_MS) return true
        starting = null
        return false
    }

    /** Focus moved on, a newer rest, the network or the add-ons changed: everything ahead stops, unless a play took it. */
    fun drop() {
        run++
        endPick()
        val j = job
        job = null
        expiry?.let { repo.main.remove(it) }
        expiry = null
        if (j != null) {
            j.handle.cancel()
            log(if (j.result != null) "ahead: the link kept for OK is dropped" else "ahead: the link check ahead is stopped")
        }
    }

    private fun endPick() {
        val p = picking ?: return
        picking = null
        pickOp?.cancel()
        pickOp = null
        p.close()
    }

    /** The play would open [o] of [s] first: checked now when it's cached. */
    private fun picked(target: SourceTarget, s: PlaybackSource, o: Offer) {
        endPick()
        if (o.readiness != Readiness.PROBABLY_READY || s.readiness != Readiness.PROBABLY_READY) return
        if (runCatching { filmPlays() }.getOrDefault(true)) return
        val j = Job(target, o.addon, o.link, repo.elapsed())
        job = j
        val confirmed = s.measured?.spec != null
        log("ahead: checking the link of the copy OK plays (${o.addon.label}, cached)")
        repo.io.execute {
            // Stopped while it waited for a thread (focus moved on): nothing is sent.
            if (j.handle.cancelled) return@execute
            val result = runCatching { repo.linkCheck.check(o.addon, o.link, confirmed, j.handle) }
                .getOrElse { LinkCheck.Result.Failed(Failure(FailureKind.UNKNOWN, o.addon, it.javaClass.simpleName)) }
            repo.main.post { done(j, result) }
        }
    }

    private fun done(j: Job, result: LinkCheck.Result) {
        // Stopped half way (dropped, or the play that joined it went): it says nothing of the add-on.
        if (j.handle.cancelled) return
        val ready = result as? LinkCheck.Result.Ready
        val failed = (result as? LinkCheck.Result.Failed)?.failure
        repo.noted(j.addon, AddonCheck.fromLinkCheck(ready?.link?.host, failed))
        val now = repo.elapsed()
        // A link opened: the "just added" rule counts from here, as for a play's own check.
        repo.lastAddAt = now
        val took = now - j.startedAt
        j.claimer?.let { give ->
            log("ahead: link ${if (ready != null) "ready" else "check failed"} in $took ms, for the play that joined it")
            give(result)
            return
        }
        if (job !== j) return
        if (ready == null) {
            job = null
            log("ahead: link check failed in $took ms (${failed?.kind}): OK checks it itself")
            return
        }
        j.result = ready
        j.doneAt = now
        val gone = Runnable {
            expiry = null
            if (job === j) {
                job = null
                log("ahead: the link kept for OK is dropped after ${KEEP_MS / 1000} s")
            }
        }
        expiry = gone
        repo.main.postDelayed(gone, KEEP_MS)
        log("ahead: link ready in $took ms, kept ${KEEP_MS / 1000} s for OK")
    }

    companion object {
        /** The log's line for a rest with no list held for its title: nothing looked up, picked or checked. */
        const val NO_LIST = "ahead: no list held: nothing checked ahead"

        /**
         * How long a link checked ahead is kept for OK, ms: briefly, as agreed. The debrid services' links live for hours
         * (FirePlayer plays a whole film from one and reopens it on a stall), so a kept one is fresh when it's used.
         */
        const val KEEP_MS = 2 * 60_000L

        /**
         * At most how long a play handed over counts as starting ([starting]), ms: far beyond the page going once the
         * player is up (a second or so on a Stick), only so a play that never says it ended can't stop rests for good.
         */
        const val STARTING_MS = 30_000L
    }
}
