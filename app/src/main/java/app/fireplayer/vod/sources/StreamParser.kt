package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Failure
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Offer
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceFacts
import org.json.JSONArray
import org.json.JSONObject
import app.fireplayer.vod.parseLink
import java.util.Locale
import kotlin.math.abs

/** One stream of an add-on's answer, read. Its link stays inside [offer] (memory only). */
class ParsedStream(
    val offer: Offer,
    val release: ReleaseKey?,
    val facts: SourceFacts,
    /** The file's name normalised for merging re-uploads ("film 2021 2160p web dl flux"); null when unknown. */
    val sameName: String?,
    /** CAM, TeleSync, TeleCine, screener. */
    val cinema: Boolean,
    val threeD: Boolean,
    /** MediaFusion's or Torz's exact size (behaviorHints.videoSize) rather than a rounded "💾 29.26 GB". */
    val exactSize: Boolean = false,
    /**
     * The only size the add-on gave may be the torrent's, not the file's (MediaFusion's one size for an episode, when its
     * names don't say one episode: a season pack's total, [PackWords]), so [facts] has no size of the file's own: an
     * estimate at most ([SourceFacts.sizeEstimated]), never a wrong one.
     */
    val torrentSize: Boolean = false,
    /**
     * MediaFusion's one size for an episode (its videoSize, else its words'): the season pack's or the episode's, as
     * [packRead] and the other add-ons tell ([SourceMerge]); null for every other stream.
     */
    val oneSize: Long? = null,
    /** What MediaFusion's names say of [oneSize]'s torrent; null without [oneSize]. */
    val packRead: PackWords.Read? = null,
    /** [oneSize] as videoSize gave it to the byte: the file's own when it is another add-on's exact size ([SourceMerge]). */
    val looseExact: Long? = null,
) {
    /** This stream with [facts] (a season pack's size worked out, [SourceMerge]), the rest as it is. */
    fun with(facts: SourceFacts, torrentSize: Boolean): ParsedStream =
        ParsedStream(offer, release, facts, sameName, cinema, threeD, exactSize, torrentSize, oneSize, packRead, looseExact)
}

/**
 * An add-on's answer, read: its streams, and what it said instead of them ([notice]: an error row such as "Invalid
 * RealDebrid ApiKey" (or another debrid service's); [busy]: ElfHosted's 🐢 "rate-limit exceeded" row). [service]: the
 * debrid service the answer names ("Real-Debrid"), a cached source's first ([Debrids]); null when it names none.
 */
class ParsedAnswer(
    val streams: List<ParsedStream>,
    val notice: Failure? = null,
    val busy: Boolean = false,
    val service: String? = null,
)

/**
 * Reads Torrentio's, MediaFusion's and StremThru Torz's stream lists (Stremio add-on protocol) as r3_sources-organize
 * §1.3 plans: the add-on's structured fields first (MediaFusion's bingeGroup, videoSize and fileIdx; Torrentio's name
 * line 2 and movie bingeGroup; Torz's emoji-keyed description), then the release name through a token parser. What
 * isn't stated stays null, never guessed. Cached marks work for any debrid service (Real-Debrid, TorBox…). The info hash
 * and file index are read from the link in memory; the link itself goes only into [Offer.link].
 */
object StreamParser {

    /**
     * [json]: the add-on's answer to /stream/…json; [runtimeMin]: the film's or episode's length, for "≈25 Mb/s";
     * [episode]: it answers for an episode (MediaFusion's one size is then its torrent's, [ParsedStream.torrentSize]).
     */
    fun parse(addon: Addon, json: String, runtimeMin: Int?, episode: Boolean = false): ParsedAnswer {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return ParsedAnswer(emptyList())
        val streams = root.optJSONArray("streams") ?: JSONArray()
        val out = ArrayList<ParsedStream>(streams.length())
        var notice: Failure? = null
        var busy = false
        var service: String? = null
        var serviceCached = false
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val name = s.optString("name")
            val text = s.optString("description").ifEmpty { s.optString("title") }
            if (BUSY.containsMatchIn(name) || BUSY.containsMatchIn(text)) {
                busy = true
                continue
            }
            val url = s.optString("url").takeIf { it.isNotBlank() }
            // A notice where a film should be ("RD error", a /videos/ or /static/exceptions/ link): the strip's, not a source.
            val noticed = url?.let { Notices.classify(it, addon) }
                ?: if (name.contains("error", ignoreCase = true)) Notices.listError(text, addon) else null
            if (noticed != null) {
                if (notice == null) notice = noticed
                if (service == null) service = Debrids.of(name, null)
                continue
            }
            val link = url?.let { SecretUrl.of(it) } ?: continue
            val parsed = when (addon) {
                Addon.TORRENTIO -> torrentio(s, name, text, link, runtimeMin)
                Addon.MEDIAFUSION -> mediaFusion(s, name, text, link, runtimeMin, episode)
                Addon.TORZ -> torz(s, name, text, link, runtimeMin)
            } ?: continue
            out += parsed
            // Which debrid service serves it: the first source's that tells, a cached one's when there is one.
            val cached = parsed.offer.readiness == Readiness.PROBABLY_READY
            if (!serviceCached && (service == null || cached)) Debrids.of(name, url)?.let {
                service = it
                serviceCached = cached
            }
        }
        return ParsedAnswer(out, notice, busy && out.isEmpty(), service)
    }

    // ---- Torrentio ----

    private fun torrentio(s: JSONObject, name: String, text: String, link: SecretUrl, runtimeMin: Int?): ParsedStream? {
        val hints = s.optJSONObject("behaviorHints")
        val nameLines = name.split('\n')
        val quality = nameLines.drop(1).joinToString(" ")
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val stats = lines.indexOfFirst { it.contains("👤") || it.contains("💾") || it.contains("⚙") }
        val names = if (stats >= 0) lines.take(stats) else lines.take(1)
        val torrent = names.firstOrNull().orEmpty()
        val file = names.getOrNull(1)?.substringAfterLast('/')
        val filename = hints?.optString("filename")?.takeIf { it.isNotBlank() } ?: file
        val statsLine = if (stats >= 0) lines[stats] else ""
        val langLine = if (stats >= 0) lines.drop(stats + 1).joinToString(" ") else ""
        val bingeGroup = hints?.optString("bingeGroup")?.takeIf { it.isNotBlank() }
        val release = releaseOf(link, s)
        // "[RD+]" / "[TB+]" (any debrid service's "+"): cached; "[RD download]" / "[TB download]": not yet.
        val readiness = when {
            TIO_READY.containsMatchIn(name) -> Readiness.PROBABLY_READY
            TIO_DOWNLOAD.containsMatchIn(name) || NOT_READY_WORDS.containsMatchIn(name) -> Readiness.NOT_READY
            else -> Readiness.UNKNOWN
        }
        val releaseName = filename ?: torrent
        val fromName = Words.read(listOf(releaseName, torrent).distinct().joinToString(" "))
        // Line 2 of the name is Torrentio's own parse (resolution, or CAM/TeleSync, then HDR and 3D).
        val fromQuality = Words.read(quality, quality = true)
        // A movie's bingeGroup is quality|source|codec|bitDepth|HDR…; a pack episode's is the info hash (no facts).
        val fromGroup = bingeGroup?.takeUnless { HEX40.matches(it.substringAfterLast('|')) }?.let { Words.read(it.replace('|', ' ')) }
        val size = Words.size(statsLine)
        val facts = SourceFacts(
            height = fromQuality.height ?: fromGroup?.height ?: fromName.height,
            hdr = fromQuality.hdr + (fromGroup?.hdr ?: emptySet()) + fromName.hdr,
            dvProfile = fromName.dvProfile,
            origin = fromGroup?.origin ?: fromName.origin,
            codec = fromGroup?.codec ?: fromName.codec,
            bitDepth = fromGroup?.bitDepth ?: fromName.bitDepth,
            audio = fromName.audio,
            channels = fromName.channels,
            sizeBytes = size,
            avgMbps = avgMbps(size, runtimeMin),
            group = Words.group(releaseName),
            languages = Words.languages(langLine),
            subs = fromName.subs,
            seeders = Words.seeders(statsLine),
            pack = file != null && file != torrent,
        )
        val offer = Offer(Addon.TORRENTIO, readiness, listOf(torrent, file).filterNotNull().distinct().joinToString("\n"),
            filename, bingeGroup, link)
        return ParsedStream(offer, release, facts, Words.sameName(releaseName), fromQuality.cinema || fromName.cinema,
            fromQuality.threeD || fromName.threeD)
    }

    // ---- MediaFusion ----

    private fun mediaFusion(s: JSONObject, name: String, text: String, link: SecretUrl, runtimeMin: Int?, episode: Boolean): ParsedStream? {
        val hints = s.optJSONObject("behaviorHints")
        val filename = hints?.optString("filename")?.takeIf { it.isNotBlank() }
            ?: Words.field(text, "📂")?.substringAfterLast("┈➤")?.trim()
        val bingeGroup = hints?.optString("bingeGroup")?.takeIf { it.isNotBlank() }
        val release = releaseOf(link, s)
        val readiness = when {
            name.contains("⚡") -> Readiness.PROBABLY_READY
            name.contains("⏳") || NOT_READY_WORDS.containsMatchIn(name) || NOT_READY_WORDS.containsMatchIn(text) -> Readiness.NOT_READY
            else -> Readiness.UNKNOWN
        }
        // bingeGroup ignores the user's template, so it is read first; the last part is the resolution ("4K", "N/A").
        val groupFacts = bingeGroup?.let { fields(it.substringBeforeLast('-')) }
        val textFacts = fields(text)
        val nameRes = Words.read(name.substringAfterLast(' '), quality = true)
        val tail = bingeGroup?.substringAfterLast('-')?.let { Words.read(it, quality = true) }
        val fromName = Words.read(filename.orEmpty())
        val video = hints?.optLong("videoSize", 0L)?.takeIf { it > 0 }
        // A film's videoSize is its file's. An episode's one size (videoSize, "📦 10.2 GB") is its torrent's: a season
        // pack's total, or the episode's when the torrent is that one episode. Only MediaFusion's two sizes, "📦 1.01 GB /
        // 10.2 GB", name the file (the first), and videoSize is the file's to the byte when it is that one.
        val file = if (episode) Words.fileOfTwo(text) else null
        val exact = if (!episode) video else video?.takeIf { v -> file != null && Words.near(v, file) }
        val one = if (episode && exact == null && file == null) video ?: Words.size(text) else null
        // What its names say of that torrent (the file's, the torrent's, each line of the description): a pack's total is
        // never the episode's size (shown in parentheses or shared out, [SourceMerge]); one episode's is its own.
        val read = one?.let { PackWords.read(listOf(filename) + text.lines().flatMap { it.split("┈➤") }) }
        val single = read?.kind == PackWords.Kind.SINGLE
        val size = when {
            exact != null -> exact
            single -> one
            episode -> file
            else -> Words.size(text)
        }
        val torrentSize = one != null && !single
        val facts = SourceFacts(
            height = tail?.height ?: nameRes.height ?: groupFacts?.height ?: textFacts.height ?: fromName.height,
            hdr = (groupFacts?.hdr ?: emptySet()) + textFacts.hdr + fromName.hdr,
            dvProfile = fromName.dvProfile,
            origin = groupFacts?.origin ?: textFacts.origin ?: fromName.origin,
            codec = groupFacts?.codec ?: textFacts.codec ?: fromName.codec,
            bitDepth = fromName.bitDepth,
            audio = groupFacts?.audio ?: textFacts.audio ?: fromName.audio,
            channels = textFacts.channels ?: fromName.channels,
            sizeBytes = size,
            avgMbps = avgMbps(size, runtimeMin),
            group = filename?.let { Words.group(it) },
            languages = Words.languageNames(Words.field(text, "🌐")),
            subs = fromName.subs,
            seeders = Words.seeders(text),
            pack = false,
            sizeExact = exact != null,
            packBytes = one?.takeIf { torrentSize },
            packUnclear = torrentSize && read?.kind == PackWords.Kind.UNSAID,
        )
        val cinema = (groupFacts?.cinema ?: false) || textFacts.cinema || fromName.cinema
        val offer = Offer(Addon.MEDIAFUSION, readiness, text.lines().filter { it.isNotBlank() }.joinToString("\n"),
            filename, bingeGroup, link)
        return ParsedStream(offer, release, facts, filename?.let { Words.sameName(it) }, cinema,
            fromName.threeD || textFacts.threeD, exactSize = exact != null, torrentSize = torrentSize, oneSize = one,
            packRead = read, looseExact = video?.takeIf { one != null })
    }

    // ---- StremThru Torz ----

    /**
     * StremThru Torz, as its default stream template writes (internal/stremio/transformer/stream_template_default.go in
     * StremThru's source): the name is "[✨ ][⚡️ ][TB][ 🔑]", the indexer (or "Torz"), the resolution, one per line, ⚡
     * when the debrid service has the file cached and a store code without ⚡ when it would have to download it; the
     * description is "💿 quality 🎞️ codec", "📺 HDR 🎧 audio | channels", "💾 file size 📦 size 〽️ rate 👤 seeders",
     * "🌐 flags", "🏷️ edition ⚙️ group 🔍 indexer", "📄 file" (or "📁 torrent"). The play link holds the hash and file
     * index ([releaseOf]); a template it doesn't know still gives the standard fields (behaviorHints, the name's words).
     */
    private fun torz(s: JSONObject, name: String, text: String, link: SecretUrl, runtimeMin: Int?): ParsedStream? {
        val hints = s.optJSONObject("behaviorHints")
        val file = torzField(text, "📄")
        val torrent = torzField(text, "📁")
        val filename = hints?.optString("filename")?.takeIf { it.isNotBlank() } ?: file
        val releaseName = filename ?: torrent
        val bingeGroup = hints?.optString("bingeGroup")?.takeIf { it.isNotBlank() }
        val release = releaseOf(link, s)
        val nameLines = name.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val readiness = when {
            name.contains("⚡") -> Readiness.PROBABLY_READY
            STORE_CODE.containsMatchIn(nameLines.firstOrNull().orEmpty()) || NOT_READY_WORDS.containsMatchIn(name) ||
                NOT_READY_WORDS.containsMatchIn(text) -> Readiness.NOT_READY
            else -> Readiness.UNKNOWN
        }
        // The name's last line is the resolution ("2160p", "4k") when there is more than one line.
        val fromRes = Words.read(if (nameLines.size >= 2) nameLines.last() else "", quality = true)
        val fields = listOfNotNull(torzField(text, "💿"), torzField(text, "🎞"), torzField(text, "📺"))
        val fromFields = Words.read(fields.joinToString(" "), quality = true)
        val sound = torzSound(torzField(text, "🎧"))
        val fromName = Words.read(releaseName.orEmpty())
        val exact = hints?.optLong("videoSize", 0L)?.takeIf { it > 0 }
        val size = exact ?: Words.size(text)
        val facts = SourceFacts(
            height = fromRes.height ?: fromFields.height ?: fromName.height,
            hdr = fromFields.hdr + fromName.hdr,
            dvProfile = fromName.dvProfile,
            origin = fromFields.origin ?: fromName.origin,
            codec = fromFields.codec ?: fromName.codec,
            bitDepth = fromName.bitDepth,
            audio = sound?.audio ?: fromName.audio,
            channels = sound?.channels ?: fromName.channels,
            sizeBytes = size,
            avgMbps = avgMbps(size, runtimeMin),
            group = releaseName?.let { Words.group(it) } ?: torzField(text, "⚙"),
            languages = Words.languages(torzField(text, "🌐") ?: torzField(text, "🎙").orEmpty()),
            subs = fromName.subs,
            seeders = Words.seeders(text),
            pack = false,
            sizeExact = exact != null,
        )
        val offer = Offer(Addon.TORZ, readiness, text.lines().filter { it.isNotBlank() }.joinToString("\n"),
            filename, bingeGroup, link)
        return ParsedStream(offer, release, facts, releaseName?.let { Words.sameName(it) }, fromFields.cinema || fromName.cinema,
            fromFields.threeD || fromName.threeD, exactSize = exact != null)
    }

    private fun torzField(text: String, marker: String): String? = Words.field(text, marker, Words.TORZ_MARKERS)

    /** Torz's "🎧 DDP, Atmos | 5.1" (go-ptt's words: "DTS Lossless", "DTS Lossy") read as FirePlayer's sound. */
    private fun torzSound(field: String?): Words.Read? {
        if (field.isNullOrBlank()) return null
        val t = field.replace(DTS_LOSSLESS, "DTS-HD MA").replace(DTS_LOSSY, "DTS").replace(',', ' ').replace('|', ' ')
        return Words.read(t)
    }

    /** MediaFusion's emoji-keyed fields: 🎨 HDR, 📺 source, 🎞 codec, 🎵 audio, 🔊 channels. */
    private fun fields(text: String): Words.Read {
        val hdr = Words.field(text, "🎨")
        val origin = Words.field(text, "📺")
        val codec = Words.field(text, "🎞")
        val audio = Words.field(text, "🎵")
        val channels = Words.field(text, "🔊")
        val parts = Words.read(listOfNotNull(hdr?.replace('|', ' '), codec).joinToString(" "))
        val source = origin?.let { Words.read(it, quality = true) }
        return Words.Read(
            height = source?.height,
            hdr = parts.hdr,
            origin = source?.origin,
            codec = parts.codec,
            audio = audio?.let { Words.mediaFusionAudio(it) },
            channels = channels?.let { Words.read(it).channels },
            cinema = source?.cinema ?: false,
        )
    }

    // ---- The link: info hash and file index, read in memory only ----

    /**
     * The release a stream plays: Torrentio's `/resolve/<debrid>/<key>/<hash>/<name>/<fileIdx>/<file>`, MediaFusion's
     * `/playback/<debrid>/<hash>/…` plus its top-level fileIdx, StremThru's `/_/strem/<id>/<store>/<hash>/<fileIdx>/…`,
     * or a top-level infoHash; else any 40-hex part after the secret. Null when none.
     */
    fun releaseOf(link: SecretUrl, stream: JSONObject?): ReleaseKey? {
        val path = parseLink(link.reveal())?.rawPath.orEmpty()
        val parts = path.split('/').filter { it.isNotEmpty() }
        val topIdx = stream?.takeIf { it.has("fileIdx") && !it.isNull("fileIdx") }?.optInt("fileIdx", -1)
        stream?.optString("infoHash")?.takeIf { it.isNotBlank() }?.let { ReleaseKey.of(it, topIdx)?.let { k -> return k } }
        val resolve = parts.indexOf("resolve")
        if (resolve >= 0 && parts.size > resolve + 3) {
            val idx = parts.getOrNull(resolve + 5)?.toIntOrNull()
            ReleaseKey.of(parts[resolve + 3], idx ?: topIdx)?.let { return it }
        }
        val playback = parts.indexOf("playback")
        if (playback >= 0 && parts.size > playback + 2) {
            ReleaseKey.of(parts[playback + 2], topIdx)?.let { return it }
        }
        // StremThru Torz clears infoHash and fileIdx: its link holds them (a private torrent's hash has "-<link>" after it).
        val strem = parts.indices.firstOrNull { parts[it] == "_" && parts.getOrNull(it + 1) == "strem" }
        if (strem != null && parts.size > strem + 4) {
            val idx = parts.getOrNull(strem + 5)?.toIntOrNull()
            ReleaseKey.of(parts[strem + 4].substringBefore('-'), idx ?: topIdx)?.let { return it }
        }
        // Past the secret's own place (the first part after the add-on's base can't be a hash we trust).
        val hash = parts.drop(1).firstOrNull { HEX40.matches(it.lowercase(Locale.ROOT)) } ?: return null
        return ReleaseKey.of(hash, topIdx)
    }

    /** Average Mb/s from size and runtime; null without both. */
    fun avgMbps(sizeBytes: Long?, runtimeMin: Int?): Double? {
        if (sizeBytes == null || sizeBytes <= 0 || runtimeMin == null || runtimeMin <= 0) return null
        return sizeBytes * 8.0 / (runtimeMin * 60.0) / 1_000_000.0
    }

    private val HEX40 = Regex("[0-9a-fA-F]{40}")
    private val BUSY = Regex("""🐢|rate[- ]?limit(ed)? exceeded""", RegexOption.IGNORE_CASE)

    /**
     * Torrentio's short names of the debrid services: Real-Debrid, TorBox, AllDebrid, Premiumize, Debrid-Link,
     * EasyDebrid, Offcloud, Put.io.
     */
    private const val DEBRIDS = "RD|TB|AD|PM|DL|ED|OC|Putio"
    private val NOT_READY_WORDS = Regex("""\buncached\b|\bnot cached\b|\[download\]|\b(?:$DEBRIDS) download\b""", RegexOption.IGNORE_CASE)
    private val TIO_READY = Regex("""^\[(?:$DEBRIDS)\+]""", RegexOption.IGNORE_CASE)
    private val TIO_DOWNLOAD = Regex("""^\[(?:$DEBRIDS) download]""", RegexOption.IGNORE_CASE)

    /** Torz's store code on the name's first line ("[TB]", "⚡️ [RD]"). */
    private val STORE_CODE = Regex("""\[[A-Za-z0-9]{2,6}]""")
    private val DTS_LOSSLESS = Regex("""dts[ ._-]?lossless""", RegexOption.IGNORE_CASE)
    private val DTS_LOSSY = Regex("""dts[ ._-]?lossy""", RegexOption.IGNORE_CASE)
}

/** The release-name token parser (r3_sources-organize §1.3): a small vocabulary, word-bounded, case-blind. */
internal object Words {

    class Read(
        val height: Int? = null,
        val hdr: Set<Hdr> = emptySet(),
        val dvProfile: Int? = null,
        val origin: String? = null,
        val codec: String? = null,
        val bitDepth: Int? = null,
        val audio: String? = null,
        val channels: String? = null,
        val cinema: Boolean = false,
        val threeD: Boolean = false,
        /** Subtitle languages the name states ("ESub", "Eng.Subs": "en"). */
        val subs: List<String> = emptyList(),
    )

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val HEIGHT = rx("""(?<![0-9])(2160|1440|1080|720|576|480|360)[pi](?![a-z0-9])""")
    private val UHD = rx("""(?<![a-z0-9])(4k|uhd)(?![a-z0-9])""")
    private val DV = rx("""(?<![a-z0-9])(dv|dovi|dolby[ ._-]?vision)(?![a-z0-9])""")
    /** "DV.P8", "DoVi Profile 7", and profile 8's base after it: "DV.P8.4" (HLG), "P8.1" (HDR10), "P8.6" (HDR10). */
    private val DV_PROFILE = rx("""(?<![a-z0-9])(?:dv|dovi)[ ._-]?(?:p|profile[ ._-]?)([578])(?:[._]([1246]))?(?![0-9])""")
    private val DV_FEL = rx("""(?<![a-z0-9])(fel|mel)(?![a-z0-9])""")
    private val HDR10P = rx("""hdr10(\+|plus|p(?![a-z]))""")
    private val HDR = rx("""(?<![a-z0-9])hdr(10)?(?![+a-z0-9])""")
    private val HLG = rx("""(?<![a-z0-9])hlg(?![a-z0-9])""")
    private val HEVC = rx("""(?<![a-z0-9])(x265|h[ .]?265|hevc)(?![0-9])""")
    private val AVC = rx("""(?<![a-z0-9])(x264|h[ .]?264|avc)(?![0-9])""")
    private val AV1 = rx("""(?<![a-z0-9])av1(?![0-9])""")
    private val VP9 = rx("""(?<![a-z0-9])vp9(?![0-9])""")
    private val MPEG4 = rx("""(?<![a-z0-9])(xvid|divx)(?![a-z0-9])""")
    private val BITS = rx("""(?<![0-9])(10|12)[ -]?bits?(?![a-z])|(?<![a-z0-9])hi10p?(?![a-z0-9])""")
    private val REMUX = rx("""(?<![a-z0-9])remux(?![a-z0-9])""")
    private val BLURAY = rx("""(?<![a-z0-9])(blu[ .-]?ray|bdrip|brrip|bd[ .-]?rip|bluray)(?![a-z0-9])""")
    private val WEBDL = rx("""(?<![a-z0-9])web[ .-]?dl(?![a-z0-9])""")
    private val WEBRIP = rx("""(?<![a-z0-9])web[ .-]?rip(?![a-z0-9])""")
    private val WEB = rx("""(?<![a-z0-9])web(?![a-z0-9])""")
    private val HDTV = rx("""(?<![a-z0-9])(hdtv|pdtv)(?![a-z0-9])""")
    private val DVD = rx("""(?<![a-z0-9])dvd(rip|r)?(?![a-z0-9])""")
    private val CINEMA = rx("""(?<![a-z0-9])(hd[ .-]?cam|camrip|cam|hd[ .-]?ts|telesync|tsrip|hd[ .-]?tc|telecine|dvd[ .-]?scr|screener|scr)(?![a-z0-9])""")
    /** Only in a quality field (Torrentio's name line 2, MediaFusion's 📺), never in a release name. */
    private val CINEMA_SHORT = rx("""(?<![a-z0-9])(ts|tc)(?![a-z0-9])""")
    private val THREE_D = rx("""(?<![a-z0-9])(3d|h[ .-]?sbs|sbs|h[ .-]?ou|half[ .-]?ou)(?![a-z0-9])""")
    private val TRUEHD = rx("""(?<![a-z0-9])true[ .-]?hd""")
    private val DTS_MA = rx("""(?<![a-z0-9])dts[ .-]?hd[ .-]?ma(?![a-z])""")
    private val DTS_X = rx("""(?<![a-z0-9])dts[ .:-]?x(?![a-z0-9])""")
    private val DTS_HD = rx("""(?<![a-z0-9])dts[ .-]?hd(?![a-z])""")
    private val DTS = rx("""(?<![a-z0-9])dts(?![a-z])""")
    private val DDP = rx("""(?<![a-z0-9])(ddp|dd\+|e[ .-]?ac[ .-]?3)""")
    private val DD = rx("""(?<![a-z0-9])(dd(?![+p])|ac[ .-]?3)(?![a-z])""")
    private val AAC = rx("""(?<![a-z0-9])aac""")
    private val OPUS = rx("""(?<![a-z0-9])opus(?![a-z])""")
    private val FLAC = rx("""(?<![a-z0-9])flac(?![a-z])""")
    private val PCM = rx("""(?<![a-z0-9])l?pcm(?![a-z])""")
    private val ATMOS = rx("""(?<![a-z0-9])atmos(?![a-z])""")
    private val CHANNELS = Regex("""(?<![0-9])(?<![0-9]\.)([1257])[ .]([01])(?![0-9])""")
    private val ENGLISH_SUBS = rx("""(?<![a-z0-9])(e[ .-]?subs?|eng(lish)?[ ._-]?subs?)(?![a-z0-9])""")

    fun read(text: String, quality: Boolean = false): Read {
        if (text.isBlank()) return Read()
        val t = stripExtension(text)
        val height = HEIGHT.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: if (UHD.containsMatchIn(t)) 2160 else null
        val hdr = LinkedHashSet<Hdr>()
        if (DV.containsMatchIn(t)) hdr += Hdr.DOLBY_VISION
        if (HDR10P.containsMatchIn(t)) hdr += Hdr.HDR10_PLUS
        if (HDR.containsMatchIn(t)) hdr += Hdr.HDR10
        if (HLG.containsMatchIn(t)) hdr += Hdr.HLG
        val dvMatch = DV_PROFILE.find(t)
        val dvProfile = dvMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: if (Hdr.DOLBY_VISION in hdr && DV_FEL.containsMatchIn(t)) 7 else null
        // Profile 8's base layer, where the name says it: 8.4 is HLG, 8.1 and 8.6 HDR10.
        if (dvProfile == 8) when (dvMatch?.groupValues?.get(2)) {
            "4" -> hdr += Hdr.HLG
            "1", "6" -> hdr += Hdr.HDR10
        }
        val codec = when {
            HEVC.containsMatchIn(t) -> "HEVC"
            AV1.containsMatchIn(t) -> "AV1"
            AVC.containsMatchIn(t) -> "H.264"
            VP9.containsMatchIn(t) -> "VP9"
            MPEG4.containsMatchIn(t) -> "MPEG-4"
            else -> null
        }
        val bits = BITS.find(t)?.let { m -> m.groupValues[1].toIntOrNull() ?: 10 }
        val cinema = CINEMA.containsMatchIn(t) || (quality && CINEMA_SHORT.containsMatchIn(t))
        val origin = when {
            cinema -> cinemaWord(t)
            REMUX.containsMatchIn(t) -> "REMUX"
            BLURAY.containsMatchIn(t) -> "BluRay"
            WEBDL.containsMatchIn(t) -> "WEB-DL"
            WEBRIP.containsMatchIn(t) -> "WEBRip"
            HDTV.containsMatchIn(t) -> "HDTV"
            WEB.containsMatchIn(t) -> "WEB"
            DVD.containsMatchIn(t) -> "DVD"
            else -> null
        }
        val atmos = ATMOS.containsMatchIn(t)
        val audioName = when {
            TRUEHD.containsMatchIn(t) -> "TrueHD"
            DTS_MA.containsMatchIn(t) -> "DTS-HD MA"
            DTS_X.containsMatchIn(t) -> "DTS:X"
            DTS_HD.containsMatchIn(t) -> "DTS-HD"
            DDP.containsMatchIn(t) -> "DD+"
            DTS.containsMatchIn(t) -> "DTS"
            DD.containsMatchIn(t) -> "DD"
            FLAC.containsMatchIn(t) -> "FLAC"
            PCM.containsMatchIn(t) -> "PCM"
            OPUS.containsMatchIn(t) -> "Opus"
            AAC.containsMatchIn(t) -> "AAC"
            else -> null
        }
        val audio = when {
            audioName != null && atmos -> "$audioName Atmos"
            audioName == null && atmos -> "Atmos"
            else -> audioName
        }
        val channels = CHANNELS.find(t)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
        val subs = if (ENGLISH_SUBS.containsMatchIn(t)) listOf("en") else emptyList()
        return Read(height, hdr, dvProfile, origin, codec, bits, audio, channels, cinema, THREE_D.containsMatchIn(t), subs)
    }

    private fun cinemaWord(t: String): String {
        val l = t.lowercase(Locale.ROOT)
        return when {
            "telesync" in l || Regex("""(?<![a-z0-9])(hd[ .-]?)?ts(rip)?(?![a-z0-9])""").containsMatchIn(l) -> "TeleSync"
            "telecine" in l || Regex("""(?<![a-z0-9])(hd[ .-]?)?tc(?![a-z0-9])""").containsMatchIn(l) -> "TeleCine"
            "scr" in l -> "Screener"
            else -> "CAM"
        }
    }

    /** MediaFusion's audio words ("Atmos|Dolby Digital Plus", "DTS Lossless") as FirePlayer's ("DD+ Atmos", "DTS-HD MA"). */
    fun mediaFusionAudio(words: String): String? {
        val parts = words.split('|').map { it.trim().lowercase(Locale.ROOT) }
        val atmos = "atmos" in parts
        val name = when {
            "truehd" in parts -> "TrueHD"
            "dts lossless" in parts -> "DTS-HD MA"
            "dts lossy" in parts -> "DTS"
            "dolby digital plus" in parts -> "DD+"
            "dolby digital" in parts -> "DD"
            "flac" in parts -> "FLAC"
            "pcm" in parts -> "PCM"
            "opus" in parts -> "Opus"
            "aac" in parts -> "AAC"
            else -> null
        }
        return when {
            name != null && atmos -> "$name Atmos"
            name == null && atmos -> "Atmos"
            else -> name
        }
    }

    private val SIZE = Regex("""(?:💾|📦)\s*([0-9]+(?:[.,][0-9]+)?)\s*(TB|GB|MB|KB)""", RegexOption.IGNORE_CASE)
    private val TWO_SIZES = Regex(
        """(?:💾|📦)\s*([0-9]+(?:[.,][0-9]+)?)\s*(TB|GB|MB|KB)\s*/\s*[0-9]+(?:[.,][0-9]+)?\s*(?:TB|GB|MB|KB)""", RegexOption.IGNORE_CASE,
    )
    private val SEEDERS = Regex("""👤\s*([0-9]+)""")

    /** "💾 29.26 GB" / "📦 31.42 GB" in bytes: binary units, as both add-ons write them. */
    fun size(text: String): Long? = SIZE.find(text)?.let { bytes(it.groupValues[1], it.groupValues[2]) }

    /**
     * MediaFusion's two sizes, "📦 1.01 GB / 10.2 GB" (the file, then the torrent it sits in): the file's, in bytes
     * ([size]'s units); null when the text has only one.
     */
    fun fileOfTwo(text: String): Long? = TWO_SIZES.find(text)?.let { bytes(it.groupValues[1], it.groupValues[2]) }

    /**
     * Whether an exact size in bytes is the one [stated] in words ([size]) within [NEAR]: the words' rounding, in binary
     * or decimal units. A torrent's total is at least two files', so it is never this near one of them.
     */
    fun near(exact: Long, stated: Long): Boolean = stated > 0 && abs(exact - stated) <= stated * NEAR

    /** [near]'s share: wide enough for "1.08 GB" written in decimal units where [size] reads binary ones (7 %). */
    const val NEAR = 0.1

    private fun bytes(number: String, unitWord: String): Long? {
        val n = number.replace(',', '.').toDoubleOrNull() ?: return null
        val unit = when (unitWord.uppercase(Locale.ROOT)) {
            "TB" -> 1L shl 40
            "GB" -> 1L shl 30
            "MB" -> 1L shl 20
            else -> 1L shl 10
        }
        return (n * unit).toLong()
    }

    fun seeders(text: String): Int? = SEEDERS.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The text after an emoji marker, up to the next marker or line end ("🎨 DV|HDR 📺 WEB-DL" → "DV|HDR" for 🎨).
     */
    fun field(text: String, marker: String, markers: List<String> = MARKERS): String? {
        val i = text.indexOf(marker)
        if (i < 0) return null
        val start = i + marker.length
        var end = text.length
        for (m in markers) {
            val j = text.indexOf(m, start)
            if (j in start until end) end = j
        }
        val nl = text.indexOf('\n', start)
        if (nl in start until end) end = nl
        return text.substring(start, end).replace("️", "").trim().ifEmpty { null }
    }

    private val MARKERS = listOf("🎨", "📺", "🎞", "🎵", "🔊", "📦", "💾", "👤", "🌐", "🔗", "🧑", "⚙", "📂", "⚡", "⏳", "🧲")

    /** StremThru Torz's markers (its default stream template). */
    val TORZ_MARKERS = listOf(
        "✨", "⚡", "🔑", "💿", "🎞", "📺", "🎧", "💾", "📦", "〽", "👤", "⏱", "🌐", "🎙", "💬", "🏷", "⚙", "🔍", "🔗", "📄", "📁",
    )

    private val TAGS = Regex("""\[[^\]]*\]|\{[^\}]*\}""")
    private val SITE = Regex("""^\s*(www\.)?[a-z0-9-]+\.(com|org|net|to|se|me|ru|xyz|in|cc)\s*[-–]\s*""", RegexOption.IGNORE_CASE)
    private val EXT = Regex("""\.(mkv|mp4|avi|ts|m2ts|m4v|mov|wmv|webm)$""", RegexOption.IGNORE_CASE)

    fun stripExtension(text: String): String = EXT.replace(text.trim(), "")

    /** The name re-uploads share: lower case, no extension, no "[TGx]"-style tags or site prefix, words only. */
    fun sameName(name: String): String? {
        val base = SITE.replace(stripExtension(name.substringAfterLast('/')), "")
        val clean = TAGS.replace(base, " ").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
        return clean.ifEmpty { null }
    }

    private val NOT_GROUPS = setOf("dl", "rip", "hd", "x", "ma", "ac3", "web", "bit", "264", "265", "sbs", "ou", "p", "aac")

    /** The release group at the end of a name ("…H.265-FLUX[TGx]" → "FLUX"); null when there's none. */
    fun group(name: String): String? {
        val base = TAGS.replace(stripExtension(name.substringAfterLast('/')), "").trim().trimEnd('.', ' ')
        val m = Regex("""-([A-Za-z0-9]{2,20})$""").find(base) ?: return null
        val g = m.groupValues[1]
        if (g.lowercase(Locale.ROOT) in NOT_GROUPS || g.all { it.isDigit() }) return null
        return g
    }

    /** Torrentio's language line ("🇬🇧 / 🇮🇹", "Multi Audio / 🇫🇷"): short codes, "multi" for several not named. */
    fun languages(line: String): List<String> {
        if (line.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        if (Regex("""multi|dual audio""", RegexOption.IGNORE_CASE).containsMatchIn(line)) out += MULTI
        // Code points by hand: CharSequence.codePoints() is API 24, and the app runs from API 22.
        val cps = ArrayList<Int>()
        var at = 0
        while (at < line.length) {
            val cp = Character.codePointAt(line, at)
            cps += cp
            at += Character.charCount(cp)
        }
        var i = 0
        while (i < cps.size - 1) {
            val a = cps[i]
            val b = cps[i + 1]
            if (a in REGIONAL && b in REGIONAL) {
                val country = "${'A' + (a - REGIONAL.first)}${'A' + (b - REGIONAL.first)}"
                out += COUNTRY_LANGUAGE[country] ?: country.lowercase(Locale.ROOT)
                i += 2
            } else {
                i++
            }
        }
        return out.toList()
    }

    /** MediaFusion's "English + Hindi": short codes. */
    fun languageNames(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split('+', ',', '|', '/').mapNotNull { NAMES[it.trim().lowercase(Locale.ROOT)] }.distinct()
    }

    const val MULTI = "multi"
    private val REGIONAL = 0x1F1E6..0x1F1FF

    private val COUNTRY_LANGUAGE = mapOf(
        "GB" to "en", "US" to "en", "AU" to "en", "CA" to "en", "NZ" to "en", "IE" to "en", "IT" to "it", "FR" to "fr",
        "ES" to "es", "MX" to "es", "AR" to "es", "DE" to "de", "AT" to "de", "RU" to "ru", "IN" to "hi", "JP" to "ja",
        "KR" to "ko", "CN" to "zh", "TW" to "zh", "HK" to "zh", "PT" to "pt", "BR" to "pt", "PL" to "pl", "NL" to "nl",
        "TR" to "tr", "UA" to "uk", "SA" to "ar", "AE" to "ar", "EG" to "ar", "SE" to "sv", "NO" to "no", "DK" to "da",
        "FI" to "fi", "GR" to "el", "RO" to "ro", "HU" to "hu", "CZ" to "cs", "IL" to "he", "TH" to "th", "VN" to "vi",
        "ID" to "id", "MY" to "ms", "PH" to "tl", "IR" to "fa", "PK" to "ur", "BD" to "bn", "LT" to "lt", "LV" to "lv",
        "EE" to "et", "HR" to "hr", "RS" to "sr", "BG" to "bg", "SK" to "sk", "SI" to "sl",
    )

    private val NAMES = mapOf(
        "english" to "en", "hindi" to "hi", "tamil" to "ta", "telugu" to "te", "malayalam" to "ml", "kannada" to "kn",
        "bengali" to "bn", "french" to "fr", "spanish" to "es", "latino" to "es", "german" to "de", "italian" to "it",
        "russian" to "ru", "japanese" to "ja", "korean" to "ko", "chinese" to "zh", "portuguese" to "pt", "polish" to "pl",
        "dutch" to "nl", "turkish" to "tr", "arabic" to "ar", "ukrainian" to "uk", "swedish" to "sv", "norwegian" to "no",
        "danish" to "da", "finnish" to "fi", "greek" to "el", "romanian" to "ro", "hungarian" to "hu", "czech" to "cs",
        "hebrew" to "he", "thai" to "th", "vietnamese" to "vi", "indonesian" to "id", "persian" to "fa", "urdu" to "ur",
        "multi audio" to MULTI, "multi" to MULTI, "dual audio" to MULTI,
    )
}
