package app.fireplayer.vod.data

import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.Position
import app.fireplayer.vod.Title
import app.fireplayer.vod.UpNext
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

/**
 * Where the library is kept (vod.db on the TV). A change is a row with its time, and leaving a list sets a flag rather
 * than deleting, so syncing could be added later (design-brief.md §6.2: no syncing now). Called on one thread.
 */
interface LibraryStore {
    class Saved(
        val listed: List<Listed>,
        val watched: List<Watched>,
        val progress: List<Progress>,
        val positions: List<EpisodePosition> = emptyList(),
        val releases: List<ReleaseRow> = emptyList(),
    )

    /**
     * The rows of one change, written together ([saveAll]): a season's marks, "Mark watched up to here", a mark
     * unwatched with the positions and Continue Watching record it clears, a playback record with its mark.
     */
    class Change(
        val watched: List<Watched> = emptyList(),
        val positions: List<EpisodePosition> = emptyList(),
        val progress: List<Progress> = emptyList(),
    ) {
        val isEmpty: Boolean get() = watched.isEmpty() && positions.isEmpty() && progress.isEmpty()
    }

    fun load(): Saved
    fun saveListed(l: Listed)
    fun saveWatched(w: Watched)

    /** Every row of [change] in one write (one transaction on the TV: hundreds of rows cost about what one does). */
    fun saveAll(change: Change) {
        change.watched.forEach { saveWatched(it) }
        change.positions.forEach { saveEpisodePosition(it) }
        change.progress.forEach { saveProgress(it) }
    }

    fun saveProgress(p: Progress)
    fun saveEpisodePosition(p: EpisodePosition)

    /** The release a film or episode last played from (step 3). */
    fun saveRelease(r: ReleaseRow)

    /** Forgets these releases ([VodLibrary.forgetReleases]). */
    fun dropReleases(rows: List<ReleaseRow>)
}

/**
 * What else is kept for a film or an episode beside the copy it was left on ([ReleaseRow]), and forgotten with it when
 * the title leaves Continue Watching (the sound and subtitles last chosen, vod/play/ResumeTracks). [kind]/[id] is a
 * film's "tt…" or a show's: a show's covers its episodes' "tt…:S:E". Any thread.
 */
interface KeptWithCopy {
    /** Whether anything is kept for [kind]/[id] (a show: for any of its episodes). */
    fun has(kind: Kind, id: String): Boolean

    /** Forgets what's kept for [kind]/[id] (a show: every episode's). */
    fun forget(kind: Kind, id: String)
}

/** A title in My List ([inList] true) or taken out of it, at [at] (ms). */
data class Listed(val title: Title, val at: Long, val inList: Boolean)

/**
 * A watched mark set ([watched] true) or cleared at [at]. [id] is a title's IMDb id, or an episode's "tt…:S:E" (kind
 * SERIES) for the marks of single episodes.
 */
data class Watched(val kind: Kind, val id: String, val at: Long, val watched: Boolean)

/**
 * Where playback of a title stopped: the episode for a series, the position and length in ms, when ([at]), and
 * whether it was taken out of Continue Watching ([hidden]; the resume point stays).
 */
data class Progress(
    val title: Title,
    val episode: EpisodeRef?,
    val positionMs: Long,
    val durationMs: Long,
    val at: Long,
    val hidden: Boolean = false,
)

/** Where playback of one episode ([id], "tt…:S:E") stopped: its card's progress bar and "12m left". */
data class EpisodePosition(val id: String, val positionMs: Long, val durationMs: Long, val at: Long)

/**
 * The copy a film or an episode last played from (design-brief §6.2): [id] is the film's IMDb id or the episode's
 * "tt…:S:E"; [addon] the add-on's name ("TORRENTIO"); info hash + file index (-1: none), or, for a copy whose add-on gave
 * no info hash, its key in [hash] ("x|mediafusion|<print>|tt…:S:E", [app.fireplayer.vod.sources.CopyKey]) with file index
 * -1: versions before that read no release from it. Never a link.
 */
data class ReleaseRow(val kind: Kind, val id: String, val addon: String, val hash: String, val fileIdx: Int, val at: Long)

/** A [LibraryStore] in memory only, for tests and for a TV whose database can't be opened. */
class MemoryLibraryStore : LibraryStore {
    val listed = LinkedHashMap<String, Listed>()
    val watched = LinkedHashMap<String, Watched>()
    val progress = LinkedHashMap<String, Progress>()
    val positions = LinkedHashMap<String, EpisodePosition>()
    val releases = LinkedHashMap<String, ReleaseRow>()
    /** How many writes of several rows ([saveAll]) were made. */
    var batches = 0
    override fun load() = LibraryStore.Saved(
        listed.values.toList(), watched.values.toList(), progress.values.toList(), positions.values.toList(),
        releases.values.toList(),
    )
    override fun saveListed(l: Listed) { listed["${l.title.kind.type}|${l.title.id}"] = l }
    override fun saveWatched(w: Watched) { watched["${w.kind.type}|${w.id}"] = w }
    override fun saveAll(change: LibraryStore.Change) {
        batches++
        super.saveAll(change)
    }
    override fun saveProgress(p: Progress) { progress["${p.title.kind.type}|${p.title.id}"] = p }
    override fun saveEpisodePosition(p: EpisodePosition) { positions[p.id] = p }
    override fun saveRelease(r: ReleaseRow) { releases["${r.kind.type}|${r.id}"] = r }
    override fun dropReleases(rows: List<ReleaseRow>) { rows.forEach { releases.remove("${it.kind.type}|${it.id}") } }
}

/**
 * The user's library on this TV: My List, watched marks (titles and single episodes), Continue Watching and where
 * playback of each episode stopped (written by playback, step 3). Kept in memory, so the main thread can ask at once,
 * and written to [store] on [writer] (which also reads it, first). Titles are keyed by kind and IMDb id; the
 * contract's id-only questions match either kind. [changed] is called after every change, on the calling thread.
 *
 * An episode counts as watched by the newer of its own mark and the whole show's mark; the show's covers only the
 * episodes that had aired when it was set. Times come from [clock] but never repeat or go back ([stamp]), so of two
 * marks the later one always wins.
 *
 * A show's place in Continue Watching follows its episodes ([UpNext]): [episodesOf] gives a show's episodes as stored
 * here, null when they aren't (it may read the disk, so it's never called under the lock).
 *
 * What [keptWithCopy] keeps for a film or episode is forgotten with the copy it was left on, as the title leaves
 * Continue Watching ([left]).
 *
 * Watched: what the player decided where it stopped ([recordProgress]'s watched mark, by the watched rule,
 * [app.fireplayer.vod.countsAsWatched]) and the marks set since. A stop in the last 3 minutes with no mark since is held
 * open ([Position.held]): its known credits (or a scene after them) weren't reached. Nothing else is stored for it.
 *
 * A caught-up show's new episode (Next Episodes') is in Continue Watching instead where the page's Next Episodes row is
 * off ([continueWatching]'s withNew); [newInContinue] says whether any page's is, for the questions asked of the title
 * alone ([inContinueWatching], [continueCard], Mark unwatched's "was it in Continue Watching").
 */
class VodLibrary(
    private val store: LibraryStore,
    private val clock: () -> Long,
    private val writer: Executor,
    private val episodesOf: (Title) -> List<Episode>? = { null },
    private val keptWithCopy: KeptWithCopy? = null,
    private val newInContinue: () -> Boolean = { false },
    private val changed: () -> Unit,
) {
    private val lock = Any()
    private val listed = LinkedHashMap<String, Listed>()
    private val watched = HashMap<String, Watched>()
    private val progress = HashMap<String, Progress>()
    private val positions = HashMap<String, EpisodePosition>()
    private val releases = HashMap<String, ReleaseRow>()
    /**
     * Records Mark unwatched kept out of Continue Watching (hidden, at 0:00) while the app runs ([unwatch],
     * [unwatchEpisodes]): only those come back in on Mark watched ([setWatched], [setEpisodesWatched]). A record taken
     * out with Remove (hidden too, maybe at 0:00) stays out until it's played again. In memory only, nothing stored
     * changes: after a restart a record kept out stays out until played, as one taken out.
     */
    private val keptOut = HashSet<String>()
    private val saved = FutureTask { runCatching { store.load() }.getOrNull() }
    @Volatile private var ready = false
    private var lastStamp = Long.MIN_VALUE

    init {
        writer.execute(saved)
    }

    fun inMyList(id: String): Boolean = state { Kind.entries.any { listed[key(it, id)]?.inList == true } }

    /** My List, the latest added first. */
    fun myList(): List<Title> = state { listed.values.filter { it.inList }.sortedByDescending { it.at }.map { it.title } }

    /**
     * Adds [title] to My List, or takes its id out whichever kind it was added as (the pages ask [inMyList] by id, so a
     * film and a series sharing an id are both "in the list" and both leave it).
     */
    fun setInMyList(title: Title, inList: Boolean) {
        val entries = state {
            if (inList) {
                val k = key(title.kind, title.id)
                if (listed[k]?.inList == true) return
                listOf(Listed(title, stamp(), true).also { listed[k] = it })
            } else {
                Kind.entries.mapNotNull { k -> listed[key(k, title.id)]?.takeIf { it.inList } }
                    .map { Listed(it.title, stamp(), false).also { l -> listed[key(l.title.kind, l.title.id)] = l } }
            }
        }
        if (entries.isEmpty()) return
        writer.execute { entries.forEach { runCatching { store.saveListed(it) } } }
        changed()
    }

    fun isWatched(id: String): Boolean = state { Kind.entries.any { watched[key(it, id)]?.watched == true } }

    /**
     * Whether [title] has a history Mark unwatched clears ([setWatched]): a watched mark (its id, either kind; a show's
     * episodes' own), or where playback of it stopped (a show's: of any episode).
     */
    fun hasHistory(title: Title): Boolean = state {
        if (Kind.entries.any { watched[key(it, title.id)]?.watched == true }) return@state true
        if ((progress[key(title.kind, title.id)]?.positionMs ?: 0L) > 0L) return@state true
        val prefix = "${title.id}:"
        title.kind == Kind.SERIES &&
            (anyEpisodeMarked(title.id) || positions.values.any { it.positionMs > 0 && it.id.startsWith(prefix) })
    }

    /**
     * Marks [title] watched (it leaves Continue Watching), or not watched ([unwatch]). A show is marked anew each time,
     * so its mark covers the episodes aired by then; its record [unwatch] kept out comes back, over at the mark.
     */
    fun setWatched(title: Title, watched: Boolean) {
        if (!watched) {
            unwatch(title)
            return
        }
        val (mark, record) = state {
            val k = key(title.kind, title.id)
            if (title.kind != Kind.SERIES && this.watched[k]?.watched == true) return
            val mark = Watched(title.kind, title.id, stamp(), true).also { this.watched[k] = it }
            // A show's record Mark unwatched kept out at 0:00: back, over now ([unwatch] undone). Not one taken out.
            val record = progress[k]?.takeIf { title.kind == Kind.SERIES && it.hidden && it.positionMs == 0L && k in keptOut }
                ?.copy(hidden = false)?.also { progress[k] = it; keptOut -= k }
            mark to record
        }
        val change = record?.let { LibraryStore.Change(watched = listOf(mark), progress = listOf(it)) }
        writer.execute { runCatching { if (change == null) store.saveWatched(mark) else store.saveAll(change) } }
        changed()
        forgetIfOut(title.kind, title.id)
    }

    /**
     * Mark unwatched on a film or a whole show, in one write and one call of [changed]: its watched marks (its id
     * whichever kind was marked, as [isWatched] asks by id; a show's every episode's own mark too), where playback of
     * it stopped (a show's: of each episode) and its Continue Watching record's place in it are cleared. In Continue
     * Watching, it stays there (in its place in the row) at 0:00 of the film, or of the show's first episode; one not
     * in it isn't put in it (its record is kept out). Nothing to clear: nothing written, nobody told.
     */
    private fun unwatch(title: Title) {
        val show = title.kind == Kind.SERIES
        val wasIn = continueEntry(title.kind, title.id) != null
        // A show's record goes to its first episode: from its stored episodes (read outside the lock), else S1 E1.
        val first = if (show && state { progress[key(title.kind, title.id)] } != null) {
            EpisodeMarks.first(runCatching { episodesOf(title) }.getOrNull())
        } else {
            null
        }
        val change = state {
            val at = stamp()
            val marks = ArrayList<Watched>()
            for (k in Kind.entries) {
                if (watched[key(k, title.id)]?.watched == true || (k == Kind.SERIES && anyEpisodeMarked(title.id))) {
                    marks += Watched(k, title.id, at, false)
                }
            }
            val cleared = ArrayList<EpisodePosition>()
            if (show) {
                val prefix = "${title.id}:"
                // Each episode's own mark too: the show's covers only episodes with an air date.
                for (w in watched.values) {
                    if (w.watched && w.kind == Kind.SERIES && w.id.startsWith(prefix)) marks += w.copy(at = at, watched = false)
                }
                for (p in positions.values) {
                    if (p.positionMs > 0 && p.id.startsWith(prefix)) cleared += p.copy(positionMs = 0, at = at)
                }
            }
            val records = listOfNotNull(
                progress[key(title.kind, title.id)]?.let { p ->
                    val ep = if (show) first?.ref ?: EpisodeMarks.FIRST else p.episode
                    val length = if (show && ep != null) lengthOf(title.id, ep, first?.runtimeMin, p) else p.durationMs
                    keepOut(p, wasIn)
                    p.copy(episode = ep, positionMs = 0, durationMs = length, hidden = !wasIn).takeIf { it != p }
                },
            )
            put(marks, cleared, records)
        } ?: return
        writer.execute { runCatching { store.saveAll(change) } }
        changed()
    }

    /**
     * Mark unwatched on episodes of one show (one, or "Mark unwatched up to here"), in one write and one call of
     * [changed]: their watched marks and where playback of each stopped are cleared; the other episodes' are left as
     * they are. The show in Continue Watching stays there (in its place in the row) at 0:00 of [at], the episode the
     * action was used on; one not in it isn't put in it (a record about one of [episodes], or one the cleared marks
     * would bring back in, goes to [at], kept out; Mark watched on [at] lets it out again). Nothing to clear: nothing
     * written, nobody told.
     */
    fun unwatchEpisodes(episodes: List<Episode>, at: Episode) {
        val list = (episodes + at).distinctBy { it.id }
        val showId = at.showId
        val withNew = newInContinue()
        val wasIn = continueEntry(Kind.SERIES, showId, withNew) != null
        // The show's episodes as Continue Watching reads them (outside the lock): whether the marks cleared bring it in.
        val lists = state { progress[key(Kind.SERIES, showId)] }?.let { episodesFor(listOf(it)) }.orEmpty()
        val change = state {
            val now = stamp()
            val marks = list.filter { episodeMark(it.id, it.released)?.watched == true }
                .mapTo(ArrayList()) { Watched(Kind.SERIES, it.id, now, false) }
            // In the library first, so the record below is worked out with them.
            for (w in marks) watched[key(w.kind, w.id)] = w
            val cleared = list.mapNotNull { e -> positions[e.id]?.takeIf { it.positionMs > 0 }?.copy(positionMs = 0, at = now) }
            val records = listOfNotNull(
                progress[key(Kind.SERIES, showId)]?.let { p ->
                    val about = isAbout(p, list)
                    // Not in Continue Watching and not about these: left as it is, unless the marks cleared would now
                    // bring the show in (an episode after its record's, of a show done): then kept out as one about them.
                    if (!wasIn && !about && (p.hidden || entryOf(p, upOf(p, lists, clock()), withNew) == null)) return@let null
                    val length = lengthOf(showId, at.ref, at.runtimeMin, p)
                    keepOut(p, wasIn)
                    val moved = p.copy(episode = at.ref, positionMs = 0, durationMs = length, hidden = !wasIn)
                    if (moved == p) return@let null
                    // Its own mark, newer than any of the show's: the record never reads as watched since.
                    if (wasIn && marks.none { it.id == at.id }) marks += Watched(Kind.SERIES, at.id, now, false)
                    moved
                },
            )
            put(marks, cleared, records)
        } ?: return
        writer.execute { runCatching { store.saveAll(change) } }
        changed()
    }

    /** Whether [episode] counts as watched (see the class). */
    fun isEpisodeWatched(episode: Episode): Boolean = state { episodeMark(episode.id, episode.released)?.watched == true }

    /**
     * Marks [episodes] watched: one write, one call of [changed]; those already so are left as they are (nothing to
     * do: no write, no call). Not watched: [unwatchEpisodes], about the last of them.
     */
    fun setEpisodesWatched(episodes: List<Episode>, watched: Boolean) {
        if (!watched) {
            episodes.lastOrNull()?.let { unwatchEpisodes(episodes, it) }
            return
        }
        val change = state {
            val at = stamp()
            val marks = episodes.distinctBy { it.id }
                .filter { episodeMark(it.id, it.released)?.watched != true }
                .map { Watched(Kind.SERIES, it.id, at, true).also { w -> this.watched[key(Kind.SERIES, it.id)] = w } }
            if (marks.isEmpty()) return
            // A record Mark unwatched kept out at 0:00 of one of these: back, over now ([unwatchEpisodes] undone). Not
            // one taken out with Remove: that comes back only when played.
            val record = episodes.firstOrNull()?.let { progress[key(Kind.SERIES, it.showId)] }
                ?.takeIf { it.hidden && it.positionMs == 0L && isAbout(it, episodes) && key(Kind.SERIES, it.title.id) in keptOut }
                ?.copy(hidden = false)?.also { key(Kind.SERIES, it.title.id).let { k -> progress[k] = it; keptOut -= k } }
            LibraryStore.Change(watched = marks, progress = listOfNotNull(record))
        }
        writer.execute { runCatching { store.saveAll(change) } }
        changed()
        forgetIfOut(Kind.SERIES, episodes.first().showId)
    }

    /**
     * Continue Watching for [kind] (null: films and series), the latest played first ([continueEntries]). Each card
     * carries its progress and caption ("12m left", "S2 E5 · 12m left"; a show gone on to its next episode "S3 E1", with
     * no progress until that one has been played).
     */
    fun continueWatching(kind: Kind?, withNew: Boolean = newInContinue()): List<Card> = continueEntries(kind, withNew).map { cardOf(it) }

    /**
     * [kind]/[id]'s Continue Watching card as [continueWatching] has it now; null when it isn't in it. A page changes
     * that one card in place with it (Mark unwatched: back to 0:00), with no reload of its rows.
     */
    fun continueCard(kind: Kind, id: String): Card? = continueEntry(kind, id, newInContinue())?.let { cardOf(it) }

    private fun cardOf(p: Progress) = Card(
        title = p.title,
        progress = Captions.fraction(p.positionMs, p.durationMs),
        caption = Captions.caption(p.episode, p.positionMs, p.durationMs),
        episode = p.episode,
    )

    /**
     * Continue Watching's entries for [kind] (null: films and series), the latest played first, with where each stopped
     * and when (also Glass Home's Continue Watching row, history/VodContinue): titles played and not taken out of it.
     * A film leaves once marked watched (finishing it marks it). A show stays until there's nothing more to watch: one
     * whose episode is over (finished, or marked watched since) goes on with the next ([UpNext.of]: across seasons, from
     * that episode's own resume point, else its start), is kept as it was while its episodes aren't known here (not
     * once the whole show is marked watched since), and leaves when no episode after it is left to watch, or when the
     * next aired only after it was over ([nextEpisodes]). With [withNew] (the page's Next Episodes row is off), that new
     * episode is in it instead, from its start, placed by when it came out (its air date) among the others' last plays.
     */
    fun continueEntries(kind: Kind? = null, withNew: Boolean = newInContinue()): List<Progress> {
        val now = clock()
        val open = state { progress.values.filter { !it.hidden && (kind == null || it.title.kind == kind) }.sortedByDescending { it.at } }
        val lists = episodesFor(open)
        // Sorted again: a new episode's entry is placed by its air date. Only those move (the sort is stable).
        return state { open.mapNotNull { entryOf(it, upOf(it, lists, now), withNew) } }.sortedByDescending { it.at }
    }

    /**
     * [kind]/[id]'s entry in Continue Watching ([continueEntries], [withNew] as there), reading only its own show's
     * episodes; null: not in it.
     */
    fun continueEntry(kind: Kind, id: String, withNew: Boolean = newInContinue()): Progress? {
        val now = clock()
        val p = state { progress[key(kind, id)]?.takeIf { !it.hidden } } ?: return null
        val lists = episodesFor(listOf(p))
        return state { entryOf(p, upOf(p, lists, now), withNew) }
    }

    /** Whether [id] is in Continue Watching ([continueEntries], [withNew] as there), either kind. */
    fun inContinueWatching(id: String, withNew: Boolean = newInContinue()): Boolean =
        Kind.entries.any { continueEntry(it, id, withNew) != null }

    /**
     * Takes [id] out of Continue Watching; its resume point stays (and it comes back when played again). [gone] hears of
     * the titles taken out before anyone is told of the change (their copies in Glass Home's row go with them).
     */
    fun removeFromContinueWatching(id: String, gone: (List<Title>) -> Unit = {}) {
        val entries = state {
            Kind.entries.mapNotNull { k -> progress[key(k, id)]?.takeIf { !it.hidden } }
                .map { it.copy(hidden = true).also { p -> key(p.title.kind, p.title.id).let { k -> progress[k] = p; keptOut -= k } } }
        }
        if (entries.isEmpty()) return
        writer.execute { entries.forEach { runCatching { store.saveProgress(it) } } }
        runCatching { gone(entries.map { it.title }) }
        changed()
        entries.forEach { left(it.title.kind, it.title.id) }
    }

    /**
     * Where playback of [title] stopped (playback, step 3): it goes to the front of Continue Watching, back in it if it
     * was taken out; a series' [episode] keeps its own position too. [watched] is the decision that marks the film or
     * the episode watched, in the same write: the player's, by the watched rule ([app.fireplayer.vod.countsAsWatched]);
     * by default a finished position (the last 3 minutes). A stop in the last 3 minutes not counted as watched gets no
     * mark, and is held open by that ([Position.held]). One call of [changed]. Any thread.
     */
    fun recordProgress(
        title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long,
        watched: Boolean = Position.inLastMinutes(positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0)),
    ) {
        val pos = positionMs.coerceAtLeast(0)
        val dur = durationMs.coerceAtLeast(0)
        val (entry, own, mark) = state {
            val at = stamp()
            val entry = Progress(title, episode, pos, dur, at).also { key(title.kind, title.id).let { k -> progress[k] = it; keptOut -= k } }
            val own = if (title.kind == Kind.SERIES && episode != null) {
                EpisodePosition(Episode.idOf(title.id, episode.season, episode.episode), pos, dur, at)
                    .also { positions[it.id] = it }
            } else {
                null
            }
            val mark = if (watched) {
                val id = own?.id ?: title.id
                Watched(title.kind, id, stamp(), true).also { this.watched[key(title.kind, id)] = it }
            } else {
                null
            }
            Triple(entry, own, mark)
        }
        // One write for the record, its episode's position and its mark.
        val change = LibraryStore.Change(watched = listOfNotNull(mark), positions = listOfNotNull(own), progress = listOf(entry))
        writer.execute { runCatching { store.saveAll(change) } }
        changed()
        // Watched (to the end, or into known credits or the scene after them): a film leaves Continue Watching, a show
        // at its last episode.
        if (mark != null) forgetIfOut(title.kind, title.id)
    }

    /** The release [kind]/[id] (a film, or an episode's "tt…:S:E") last played from; null when never. */
    fun lastRelease(kind: Kind, id: String): ReleaseRow? = state { releases[key(kind, id)] }

    /** Keeps the release a film or episode played from (the player's first frame). Listeners aren't called. */
    fun recordRelease(row: ReleaseRow) {
        val entry = state {
            val at = stamp()
            row.copy(at = at).also { releases[key(row.kind, row.id)] = it }
        }
        writer.execute { runCatching { store.saveRelease(entry) } }
    }

    /**
     * Forgets the releases [kind]/[id] last played from (a show's: every episode's), so its next resume plays the
     * ranking's pick: it left Continue Watching, or a show's episode had no copy of the kind it followed.
     */
    fun forgetReleases(kind: Kind, id: String) {
        val gone = state { releasesOf(kind, id).onEach { releases.remove(key(it.kind, it.id)) } }
        if (gone.isNotEmpty()) writer.execute { runCatching { store.dropReleases(gone) } }
    }

    /** Forgets the release [kind]/[id] (a film, or an episode's "tt…:S:E") last played from, that one only. */
    fun forgetRelease(kind: Kind, id: String) {
        val gone = state { releases.remove(key(kind, id)) } ?: return
        writer.execute { runCatching { store.dropReleases(listOf(gone)) } }
    }

    /**
     * [kind]/[id] isn't in Continue Watching (any more): its releases are forgotten, and what's kept with them ([left]).
     * Asked only when it has some.
     */
    private fun forgetIfOut(kind: Kind, id: String) {
        if (state { releasesOf(kind, id).isEmpty() } && !keptWith(kind, id)) return
        if (continueEntry(kind, id) == null) left(kind, id)
    }

    /**
     * [kind]/[id] left Continue Watching (marked watched, taken out, watched to the end): the copies it was left on are
     * forgotten (a show's: every episode's), and with them what [keptWithCopy] keeps for it.
     */
    private fun left(kind: Kind, id: String) {
        forgetReleases(kind, id)
        keptWithCopy?.let { k -> runCatching { k.forget(kind, id) } }
    }

    /** Whether [keptWithCopy] keeps anything for [kind]/[id]. */
    private fun keptWith(kind: Kind, id: String): Boolean =
        keptWithCopy?.let { k -> runCatching { k.has(kind, id) }.getOrNull() } == true

    /**
     * Next Episodes: shows played (not taken out of Continue Watching) whose next episode ([UpNext.of]) aired only after
     * their episode was over, a new episode of a show the viewer had caught up on, with that episode; the latest played
     * first. Continue Watching has the shows that go on with an episode aired before; never both. A page whose Next
     * Episodes row is off has these in its Continue Watching instead ([continueEntries]' withNew).
     */
    fun nextEpisodes(): List<Pair<Title, Episode>> {
        val now = clock()
        val open = state { progress.values.filter { !it.hidden && it.title.kind == Kind.SERIES }.sortedByDescending { it.at } }
        val lists = episodesFor(open)
        return state {
            open.mapNotNull { p ->
                val up = upOf(p, lists, now) as? UpNext.Up.Next
                if (up != null && up.fresh) p.title to up.episode else null
            }
        }
    }

    /**
     * The episode [title]'s show goes on with, as its Continue card plays it (the banner's Watch now): its record's own
     * until that is over, then the next ([UpNext.of], new or not), its own while the show's episodes aren't known here.
     * Null for a film, a show never played, or one with nothing left to watch. Also when taken out of Continue Watching.
     */
    fun upNext(title: Title): EpisodeRef? {
        if (title.kind != Kind.SERIES) return null
        val p = state { progress[key(Kind.SERIES, title.id)] } ?: return null
        val lists = episodesFor(listOf(p))
        return when (val up = state { upOf(p, lists, clock()) }) {
            UpNext.Up.Own, UpNext.Up.Unknown -> p.episode
            is UpNext.Up.Next -> up.episode.ref
            UpNext.Up.Done -> null
        }
    }

    /** The resume point of [title], also when it was taken out of Continue Watching. */
    fun progressOf(kind: Kind, id: String): Progress? = state { progress[key(kind, id)] }

    /** Every title's record (in Continue Watching, taken out of it, or done), each with when it was last played. */
    fun played(): List<Progress> = state { progress.values.toList() }

    /**
     * [kind]/[id]'s Continue Watching record, with whether the film (or the series' episode) was marked watched after
     * it was saved; null when never played.
     */
    fun lastPlayed(kind: Kind, id: String): LastPlayed? = state {
        val p = progress[key(kind, id)] ?: return@state null
        LastPlayed(p.episode, positionOf(p), p.hidden, markedSince(p))
    }

    /**
     * Where playback of [episode] stopped; for one played before positions were kept per episode, the series' record
     * when it is about that episode. Null when never played.
     */
    fun positionOf(episode: Episode): Position? = state {
        positions[episode.id]?.let { return@state positionOf(it) }
        val p = progress[key(Kind.SERIES, episode.showId)] ?: return@state null
        val ref = p.episode ?: return@state null
        if (ref.season == episode.season && ref.episode == episode.episode) positionOf(p) else null
    }

    /**
     * The episodes of the shows among [entries] whose episode is over, by show id ([episodesOf]; null: not stored).
     * Read outside the lock, as that may take a while.
     */
    private fun episodesFor(entries: List<Progress>): Map<String, List<Episode>?> {
        val over = state { entries.filter { isOver(it) } }
        val lists = HashMap<String, List<Episode>?>()
        for (p in over) if (!lists.containsKey(p.title.id)) lists[p.title.id] = runCatching { episodesOf(p.title) }.getOrNull()
        return lists
    }

    // ---- Under the lock ----

    /** A time for a change: the clock, but always after the last one. */
    private fun stamp(): Long {
        val now = clock()
        lastStamp = if (lastStamp == Long.MIN_VALUE || now > lastStamp) now else lastStamp + 1
        return lastStamp
    }

    /**
     * The mark that decides whether an episode is watched: its own, or the show's when that is newer and the episode
     * ([released], epoch ms) had aired by then; null when neither applies.
     */
    private fun episodeMark(id: String, released: Long?): Watched? {
        val own = watched[key(Kind.SERIES, id)]
        val show = watched[key(Kind.SERIES, id.substringBefore(':'))]
            ?.takeIf { released != null && released <= it.at }
        return when {
            show == null -> own
            own == null || show.at > own.at -> show
            else -> own
        }
    }

    /**
     * The film, or the series' episode, of [p] was marked watched after [p] was saved (the show's mark counts for an
     * episode: one being played has aired).
     */
    private fun markedSince(p: Progress): Boolean = markSince(p) != null

    /** The mark that makes [p] watched since it was saved ([markedSince]); null when there's none. */
    private fun markSince(p: Progress): Watched? {
        val title = watched[key(p.title.kind, p.title.id)]
        val ep = p.episode
        if (p.title.kind != Kind.SERIES || ep == null) return title?.takeIf { it.watched && it.at >= p.at }
        val own = watched[key(Kind.SERIES, Episode.idOf(p.title.id, ep.season, ep.episode))]
        return listOfNotNull(own, title).maxByOrNull { it.at }?.takeIf { it.watched && it.at >= p.at }
    }

    /**
     * Show record [p] is over by the whole show's mark, no episode's own mark set since: every episode aired by then is
     * watched, so once its episodes are known it's done, or in Next Episodes (one aired since); never Continue Watching.
     */
    private fun overByShowMark(p: Progress): Boolean {
        val mark = markSince(p)?.takeIf { it.id == p.title.id } ?: return false
        val prefix = "${Kind.SERIES.type}|${p.title.id}:"
        return watched.none { (k, w) -> w.at > mark.at && k.startsWith(prefix) }
    }

    /**
     * Show record [p]'s episode is over: finished ([Position.finished]), or marked watched since ([LastPlayed.finished]).
     * A finished stop was marked watched in the same write ([recordProgress]), and one held open isn't finished.
     */
    private fun isOver(p: Progress): Boolean = p.title.kind == Kind.SERIES && p.episode != null &&
        (markSince(p) != null || positionOf(p).finished)

    /** Where record [p] stopped: held open when in the last 3 minutes with no watched mark since ([Position.held]). */
    private fun positionOf(p: Progress): Position =
        Position(p.positionMs, p.durationMs, p.at, held = Position.inLastMinutes(p.positionMs, p.durationMs) && markSince(p) == null)

    /**
     * Where episode position [e] stopped, held as a record is ([positionOf]): no mark since, its own or the whole show's
     * (one played had aired, so the show's covers it).
     */
    private fun positionOf(e: EpisodePosition): Position {
        val held = Position.inLastMinutes(e.positionMs, e.durationMs) && run {
            val own = watched[key(Kind.SERIES, e.id)]
            val show = watched[key(Kind.SERIES, e.id.substringBefore(':'))]
            listOfNotNull(own, show).maxByOrNull { it.at }?.takeIf { it.watched && it.at >= e.at } == null
        }
        return Position(e.positionMs, e.durationMs, e.at, held)
    }

    /**
     * Where record [p] goes on ([UpNext.of]); a show's episode is over ([isOver]) when marked watched since, else when
     * it was saved. [lists] are the shows' episodes ([episodesFor]). A film: [UpNext.Up.Own].
     */
    private fun upOf(p: Progress, lists: Map<String, List<Episode>?>, now: Long): UpNext.Up {
        val ep = p.episode
        if (ep == null || !isOver(p)) return UpNext.Up.Own
        return UpNext.of(ep, true, markSince(p)?.at ?: p.at, lists[p.title.id], now) { e -> episodeMark(e.id, e.released)?.watched == true }
    }

    /**
     * [p] as Continue Watching shows it after [up] ([continueEntries]); null when it isn't there. A new episode ([up]
     * fresh: Next Episodes') only [withNew], at its air date when that's later than [p]'s last play.
     */
    private fun entryOf(p: Progress, up: UpNext.Up, withNew: Boolean): Progress? = when (up) {
        UpNext.Up.Own -> p.takeUnless { markedSince(it) }
        // Kept while its episodes aren't known, unless the whole show was marked watched since: then never in it.
        UpNext.Up.Unknown -> p.takeUnless { overByShowMark(it) }
        is UpNext.Up.Next -> if (up.fresh && !withNew) null else {
            // From the next episode's own resume point when it has one, else its start: as its card plays it.
            val own = positions[up.episode.id]?.let { positionOf(it) }?.takeIf { it.resumable }
            val at = if (up.fresh) maxOf(p.at, up.episode.released ?: p.at) else p.at
            p.copy(episode = up.episode.ref, positionMs = own?.positionMs ?: 0L, durationMs = own?.durationMs ?: 0L, at = at)
        }
        UpNext.Up.Done -> null
    }

    /** Puts a change's rows in the library; null when there are none (nothing to write, nobody to tell). */
    private fun put(marks: List<Watched>, cleared: List<EpisodePosition>, records: List<Progress>): LibraryStore.Change? {
        if (marks.isEmpty() && cleared.isEmpty() && records.isEmpty()) return null
        for (w in marks) watched[key(w.kind, w.id)] = w
        for (p in cleared) positions[p.id] = p
        for (p in records) progress[key(p.title.kind, p.title.id)] = p
        return LibraryStore.Change(marks, cleared, records)
    }

    /**
     * How long episode [ref] of [showId] is, as known here: its own position's length, else [p]'s when that record is
     * about it, else its usual runtime ([runtimeMin]); 0 when none is known (its card then has no bar).
     */
    private fun lengthOf(showId: String, ref: EpisodeRef, runtimeMin: Int?, p: Progress): Long {
        positions[Episode.idOf(showId, ref.season, ref.episode)]?.durationMs?.takeIf { it > 0 }?.let { return it }
        val r = p.episode
        if (r != null && r.season == ref.season && r.episode == ref.episode && p.durationMs > 0) return p.durationMs
        return ((runtimeMin ?: 0) * 60_000L).takeIf { it > Position.END_MS } ?: 0L
    }

    /**
     * Mark unwatched on [p]'s title, which was in Continue Watching ([wasIn]: it stays in, visible) or not (hidden: kept
     * out). Kept out only when it was out on its own (done, or kept out already), not taken out with Remove ([keptOut]).
     * Under the lock.
     */
    private fun keepOut(p: Progress, wasIn: Boolean) {
        val k = key(p.title.kind, p.title.id)
        if (!wasIn && (!p.hidden || k in keptOut)) keptOut += k else keptOut -= k
    }

    /** Show record [p] is about one of [episodes]. */
    private fun isAbout(p: Progress, episodes: List<Episode>): Boolean =
        p.episode?.let { r -> episodes.any { it.season == r.season && it.episode == r.episode } } == true

    /** The releases kept for [kind]/[id]: a film's own, a show's episodes'. */
    private fun releasesOf(kind: Kind, id: String): List<ReleaseRow> =
        releases.values.filter { it.kind == kind && (it.id == id || (kind == Kind.SERIES && it.id.startsWith("$id:"))) }

    /** Some episode of [showId] has its own watched mark set. */
    private fun anyEpisodeMarked(showId: String): Boolean {
        val prefix = "${Kind.SERIES.type}|$showId:"
        return watched.any { (k, w) -> w.watched && k.startsWith(prefix) }
    }

    /** Runs [block] on the library, read from the store first (it waits for that the first time only). */
    private inline fun <T> state(block: () -> T): T {
        if (!ready) merge()
        return synchronized(lock) { block() }
    }

    private fun merge() {
        val loaded = runCatching { saved.get() }.getOrNull()
        synchronized(lock) {
            if (ready) return
            loaded?.let { s ->
                // What changed before the store was read wins over what it holds. (getOrPut, not putIfAbsent: that
                // is API 24, and the app runs from API 22.)
                s.listed.forEach { l -> listed.getOrPut(key(l.title.kind, l.title.id)) { l } }
                s.watched.forEach { w -> watched.getOrPut(key(w.kind, w.id)) { w } }
                s.progress.forEach { p -> progress.getOrPut(key(p.title.kind, p.title.id)) { p } }
                s.positions.forEach { p -> positions.getOrPut(p.id) { p } }
                s.releases.forEach { r -> releases.getOrPut(key(r.kind, r.id)) { r } }
                // Stamps stay after anything stored (a clock set back since).
                val stored = (s.listed.map { it.at } + s.watched.map { it.at } + s.progress.map { it.at } + s.positions.map { it.at })
                    .maxOrNull()
                if (stored != null && stored > lastStamp) lastStamp = stored
            }
            ready = true
        }
    }

    private fun key(kind: Kind, id: String) = "${kind.type}|$id"
}

/** The words and numbers on Continue Watching cards. */
object Captions {

    /** How far along, 0..1; null when the length isn't known. */
    fun fraction(positionMs: Long, durationMs: Long): Float? =
        if (durationMs <= 0) null else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()

    /** "12m left", "1h 10m left", "1h left"; with an episode "S2 E5 · 12m left". Null for a film of unknown length. */
    fun caption(episode: EpisodeRef?, positionMs: Long, durationMs: Long): String? {
        val left = if (durationMs > 0) left(durationMs - positionMs) else null
        val ep = episode?.let { "S${it.season} E${it.episode}" }
        return listOfNotNull(ep, left).joinToString(" · ").ifEmpty { null }
    }

    /** What's left, rounded to the minute (at least 1 m). */
    fun left(remainingMs: Long): String {
        val minutes = ((remainingMs.coerceAtLeast(0) + 30_000) / 60_000).coerceAtLeast(1)
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "${m}m left"
            m == 0L -> "${h}h left"
            else -> "${h}h ${m}m left"
        }
    }
}
