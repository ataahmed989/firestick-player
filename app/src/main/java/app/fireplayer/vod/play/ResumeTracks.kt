package app.fireplayer.vod.play

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import app.fireplayer.vod.Kind
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.data.KeptWithCopy
import app.fireplayer.vod.data.type
import java.util.Locale

/**
 * The sound and subtitles last chosen by hand while watching a film or an episode from the add-ons, put back when it's
 * resumed the ways the copy left is ([PlayRequest.preferred]): Resume on its page, a Continue card on Home or in Glass
 * Home, the Episodes sheet's resume. On the copy they were chosen on, the very tracks; on another copy, the closest
 * ([match]). The rules are here, pure; [KeptTracks] keeps them.
 */
object ResumeTracks {

    /** What a sound or subtitle track was like, and where it was chosen. */
    data class Track(
        val language: String?,
        val label: String?,
        /** Media3's role flags: [match] compares commentary, described video and for the hard of hearing (SDH). */
        val roleFlags: Int,
        /** Forced (or signs-only) subtitles. */
        val forced: Boolean,
        /** The sound's channels (6 for 5.1), -1 when not known. */
        val channels: Int,
        /** The copy it was chosen on (its release, "<info hash>|<file index>", or its add-on's name); never a link. */
        val copy: String? = null,
        /** Its id in that copy's file ("2"): it only means something on the same copy. */
        val id: String? = null,
    )

    /** The subtitles last chosen. */
    sealed class Subs {
        data object Off : Subs()

        /** One of the film's own tracks. */
        data class Embedded(val track: Track) : Subs()

        /**
         * Subtitles downloaded with Find subtitles: their file in SavedSubtitles' folder, their language, and the video
         * they were on ([app.fireplayer.subs.SavedSubtitles]' key for it, the copy's, never a link), whose timing for
         * them comes along to another copy.
         */
        data class Downloaded(val file: String, val language: String?, val video: String?) : Subs()
    }

    /** What's kept for one film or episode: the last sound and subtitles chosen (either may be missing), and when. */
    data class Kept(val audio: Track?, val subs: Subs?, val at: Long)

    /** What a resume does about subtitles ([pickSubs]). */
    sealed class SubsPick {
        data object Off : SubsPick()

        /** The track at this place in the list of the film's own subtitles. */
        data class Embedded(val index: Int) : SubsPick()

        /** This downloaded file, still kept. */
        data class File(val name: String) : SubsPick()

        /** Nothing fits: today's defaults stay. */
        data object Default : SubsPick()
    }

    /** The kinds [match] tells apart besides forced: main or commentary sound, described video, SDH subtitles. */
    const val KIND_FLAGS = C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_DESCRIBES_VIDEO or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND

    /**
     * The kinds of track a resume has just put back ([put]), which the player's matching of the kinds picked before
     * (its reapplyChoices, which the sound remembered for the show can set off in the same moment) leaves as they are.
     * Matched again by its looser rules, the very subtitles put back could give way to another track of their language:
     * the film's own over a downloaded file, or the first of two alike. Held until what was put back is on ([settled]),
     * and never past another video ([clear]), whose tracks are matched as ever.
     */
    class PutBack {
        private val types = HashSet<Int>()

        fun put(type: Int) {
            types += type
        }

        fun holds(type: Int): Boolean = type in types

        /** The tracks' change has been seen to: let go, unless what was put back is still to come on ([waiting]). */
        fun settled(waiting: Boolean) {
            if (!waiting) types.clear()
        }

        fun clear() = types.clear()
    }

    /**
     * Whether [request] puts back the sound and subtitles last chosen: only a play from where it stopped (Resume on the
     * title page or an episode card there, a Sources row played from there, a Continue card on Home or Glass Home, the
     * Episodes sheet's resume). Not from the beginning, nor Next episode (always from its beginning); Live TV and
     * playlists never come as a [PlayRequest].
     */
    fun applies(request: PlayRequest): Boolean {
        if (request.start !is StartAt.At) return false
        return when (request.origin) {
            PlayOrigin.TITLE_PAGE, PlayOrigin.SOURCES_PANEL, PlayOrigin.CONTINUE_CARD -> true
            // From the player: Next episode starts at its beginning; the Episodes sheet resumes one that stopped.
            PlayOrigin.NEXT_EPISODE -> true
        }
    }

    /**
     * Which of [tracks] (one kind, in the file's order) to pick for [wish]; -1 when none fits, so today's default stays.
     * On the copy it was chosen on ([copy] the same), the very track by its id, while it's still what it was. Else
     * (another copy, or that track not found) the best of the same language: forced or not first, then the same kind
     * (main, commentary or described sound, as flagged or named; SDH or not), then the same name, then (sound only,
     * [audio]) the same channels; on a tie, the first. A language written another way ("en" and "en-US") counts when none is written the same way.
     */
    fun match(wish: Track, tracks: List<Track>, copy: String?, audio: Boolean): Int {
        val id = wish.id
        if (copy != null && wish.copy == copy && id != null) {
            val same = tracks.indexOfFirst { t -> t.id != null && sameId(t.id, id) && alike(t, wish, audio) }
            if (same >= 0) return same
        }
        val lang = language(wish.language)
        var pool = tracks.indices.filter { language(tracks[it].language) == lang }
        if (pool.isEmpty() && lang != null) {
            val primary = lang.substringBefore('-')
            pool = tracks.indices.filter { language(tracks[it].language)?.substringBefore('-') == primary }
        }
        // The first with the best score: maxByOrNull keeps the first of equals.
        return pool.maxByOrNull { score(tracks[it], wish, audio) } ?: -1
    }

    /**
     * What a resume does about subtitles last chosen as [kept], with the film's own subtitle tracks [embedded] (in the
     * file's order) on [copy]: off stays off; one of the film's tracks, its match ([match]); a downloaded file, that
     * file while it's still kept ([fileKept]), else the film's best track in its language. [SubsPick.Default] when
     * nothing fits.
     */
    fun pickSubs(kept: Subs, embedded: List<Track>, copy: String?, fileKept: (String) -> Boolean): SubsPick {
        fun embeddedOr(index: Int) = if (index >= 0) SubsPick.Embedded(index) else SubsPick.Default
        return when (kept) {
            Subs.Off -> SubsPick.Off
            is Subs.Embedded -> embeddedOr(match(kept.track, embedded, copy, audio = false))
            is Subs.Downloaded -> when {
                fileKept(kept.file) -> SubsPick.File(kept.file)
                // Gone (let go after a month unused): the film's own full subtitles in that language, when it has some.
                kept.language == null -> SubsPick.Default
                else -> embeddedOr(match(Track(kept.language, null, 0, false, -1), embedded, null, audio = false))
            }
        }
    }

    /** The key of [kind]/[id] (a film's "tt…", an episode's "tt…:S:E"), as the library's. */
    fun key(kind: Kind, id: String): String = "${kind.type}|$id"

    // ---- Kept as a short line ----
    //
    // "1", when, then the sound and the subtitles, each as eight fields: what ("" none, "t" a track, "off", "f" a
    // downloaded file), language, name (a file's name), role flags, forced (1/0), channels, copy (a file's video), id.
    // Later versions only add fields at the end: this reads theirs, and theirs read this.

    private const val SEP = '\u0001'
    private const val VERSION = "1"
    private const val GROUP = 8

    fun encode(kept: Kept): String {
        val audio = kept.audio?.let { group("t", it) } ?: empty()
        val subs = when (val s = kept.subs) {
            null -> empty()
            Subs.Off -> listOf("off") + List(GROUP - 1) { "" }
            is Subs.Embedded -> group("t", s.track)
            is Subs.Downloaded -> listOf("f", s.language.orEmpty(), s.file, "0", "0", "-1", s.video.orEmpty(), "")
        }
        return (listOf(VERSION, kept.at.toString()) + audio + subs).joinToString(SEP.toString())
    }

    /** [value] read back; null when there's nothing usable in it. */
    fun decode(value: String?): Kept? {
        val p = value?.split(SEP) ?: return null
        if (p.size < 2 || (p[0].toIntOrNull() ?: 0) < 1) return null
        val at = p[1].toLongOrNull() ?: 0L
        val audio = p.track(2)?.takeIf { p[2] == "t" }
        val subs = when (p.getOrNull(2 + GROUP)) {
            "off" -> Subs.Off
            "t" -> p.track(2 + GROUP)?.let { Subs.Embedded(it) }
            "f" -> p.getOrNull(4 + GROUP)?.takeIf { it.isNotEmpty() }
                ?.let { Subs.Downloaded(it, p[3 + GROUP].ifEmpty { null }, p.getOrNull(8 + GROUP)?.ifEmpty { null }) }
            else -> null
        }
        if (audio == null && subs == null) return null
        return Kept(audio, subs, at)
    }

    private fun group(what: String, t: Track) = listOf(
        what, clean(t.language), clean(t.label), t.roleFlags.toString(), if (t.forced) "1" else "0", t.channels.toString(),
        clean(t.copy), clean(t.id),
    )

    private fun empty() = List(GROUP) { "" }

    /** The track in the eight fields from [at]; null when they're cut short. */
    private fun List<String>.track(at: Int): Track? {
        if (size < at + GROUP) return null
        return Track(
            this[at + 1].ifEmpty { null }, this[at + 2].ifEmpty { null }, this[at + 3].toIntOrNull() ?: 0, this[at + 4] == "1",
            this[at + 5].toIntOrNull() ?: -1, this[at + 6].ifEmpty { null }, this[at + 7].ifEmpty { null },
        )
    }

    private fun clean(s: String?) = s.orEmpty().replace(SEP, ' ').replace('\n', ' ')

    // ---- Matching ----

    /** The same track as far as can be told: language, name, kind and (sound) channels. */
    private fun alike(t: Track, wish: Track, audio: Boolean) = language(t.language) == language(wish.language) &&
        t.label.orEmpty().equals(wish.label.orEmpty(), ignoreCase = true) && t.forced == wish.forced &&
        kind(t, audio) == kind(wish, audio) && (!audio || t.channels == wish.channels)

    private fun score(t: Track, wish: Track, audio: Boolean): Int {
        var s = 0
        // Forced subtitles show almost nothing: for full ones, full ones first, then SDH (full, with sound notes).
        if (t.forced == wish.forced) s += 8
        if (kind(t, audio) == kind(wish, audio)) s += 4
        if (wish.label != null && t.label.equals(wish.label, ignoreCase = true)) s += 2
        if (audio && t.channels == wish.channels) s += 1
        return s
    }

    /**
     * [t]'s kind as compared ([KIND_FLAGS]): its role flags and, for a sound, commentary or described video when only
     * its name says so ("Director's Commentary", "Audio Description"). Media3 flags them only when the file does, and
     * many releases just name them: on another copy such a track could otherwise win over the main sound on its
     * channels. (A subtitle named SDH is kept as SDH already, as the player reads it.)
     */
    private fun kind(t: Track, audio: Boolean): Int {
        var roles = t.roleFlags
        val label = t.label
        if (audio && label != null) {
            if (COMMENTARY.containsMatchIn(label)) roles = roles or C.ROLE_FLAG_COMMENTARY
            if (DESCRIBED.containsMatchIn(label) || DESCRIBED_SHORT.containsMatchIn(label)) {
                roles = roles or C.ROLE_FLAG_DESCRIBES_VIDEO
            }
        }
        return roles and KIND_FLAGS
    }

    private val COMMENTARY = Regex("""(?i)\bcomment(ary|aries|aire|aires|o)\b|\bcomentarios?\b|\bkommentar\b""")
    private val DESCRIBED = Regex("""(?i)audio\s*-?\s*descri|audiodeskription|\bdescriptive\b|\bdescribed video\b|visually impaired""")
    // "AD" only in capitals: "ad" in a name is too often something else.
    private val DESCRIBED_SHORT = Regex("""\bAD\b""")

    /** Track ids gain a "0:" style prefix when subtitles are added to the video: only that prefix is ignored. */
    private fun sameId(a: String, b: String) = a == b || a.endsWith(":$b") || b.endsWith(":$a")

    /** A language as compared: lower case, and none for undetermined. */
    private fun language(l: String?): String? =
        l?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "und" }
}

/**
 * Where [ResumeTracks.Kept] lines are kept: a small settings file ("resume_tracks"), a line per film or episode under
 * [ResumeTracks.key], only the latest [KEPT] (the oldest go first). Nothing in it is a link. Forgotten with the copy
 * left ([KeptWithCopy]) when the title leaves Continue Watching. Any thread.
 */
class KeptTracks(private val store: Store) : KeptWithCopy {

    /** The lines, by key. */
    interface Store {
        fun get(key: String): String?

        fun all(): Map<String, String>

        /** Puts [put] and takes [remove] away, in one go. */
        fun write(put: Map<String, String>, remove: Collection<String>)
    }

    /** What's kept for [kind]/[id]; null when nothing is. */
    @Synchronized
    fun read(kind: Kind, id: String): ResumeTracks.Kept? = ResumeTracks.decode(store.get(ResumeTracks.key(kind, id)))

    /**
     * The sound ([audio]) or subtitles ([subs]) just chosen by hand for [kind]/[id], kept over the last of that kind; the
     * other stays as it was.
     */
    @Synchronized
    fun chose(kind: Kind, id: String, audio: ResumeTracks.Track? = null, subs: ResumeTracks.Subs? = null, now: Long) {
        if (audio == null && subs == null) return
        val key = ResumeTracks.key(kind, id)
        val old = ResumeTracks.decode(store.get(key))
        val line = ResumeTracks.encode(ResumeTracks.Kept(audio ?: old?.audio, subs ?: old?.subs, now))
        val all = store.all()
        // Only the latest few hundred titles are kept.
        val over = all.size + (if (key in all) 0 else 1) - KEPT
        val drop = if (over <= 0) emptyList() else all.entries.filter { it.key != key }
            .sortedBy { ResumeTracks.decode(it.value)?.at ?: 0L }.take(over).map { it.key }
        store.write(mapOf(key to line), drop)
    }

    @Synchronized
    override fun has(kind: Kind, id: String): Boolean = store.all().keys.any { of(it, kind, id) }

    @Synchronized
    override fun forget(kind: Kind, id: String) {
        val gone = store.all().keys.filter { of(it, kind, id) }
        if (gone.isNotEmpty()) store.write(emptyMap(), gone)
    }

    /** Key [key] is [kind]/[id]'s: the film's, or the show's or one of its episodes'. */
    private fun of(key: String, kind: Kind, id: String): Boolean {
        val own = ResumeTracks.key(kind, id)
        return key == own || (kind == Kind.SERIES && key.startsWith("$own:"))
    }

    /** The settings file, opened the first time it's used (never at the app's start). */
    private class PrefsStore(private val app: Context) : Store {
        private val sp: SharedPreferences by lazy { app.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

        override fun get(key: String): String? = runCatching { sp.getString(key, null) }.getOrNull()

        override fun all(): Map<String, String> = sp.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

        override fun write(put: Map<String, String>, remove: Collection<String>) {
            val e = sp.edit()
            remove.forEach { e.remove(it) }
            put.forEach { (k, v) -> e.putString(k, v) }
            e.apply()
        }
    }

    companion object {
        /** As many titles as the sound picked for each is kept for (PlayerActivity's "audio_choices"). */
        const val KEPT = 300
        const val FILE = "resume_tracks"

        @Volatile private var shared: KeptTracks? = null

        /** The app's, in its settings file. */
        fun of(context: Context): KeptTracks = shared ?: synchronized(this) {
            shared ?: KeptTracks(PrefsStore(context.applicationContext)).also { shared = it }
        }
    }
}
