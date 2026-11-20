package app.fireplayer.vod.sources

import org.json.JSONArray
import org.json.JSONObject

/**
 * Synthetic add-on answers. Every link is on the reserved ".invalid" domain with a made-up key ("FAKEKEY…") and made-up
 * info hashes: no real add-on link, key or configuration is ever used in tests.
 */
object SourceFixtures {
    const val TIO = "https://tio.invalid/FAKEKEY_TIO"
    const val MF = "https://mf.invalid/FAKECONFIG_MF"

    val H1 = "1".repeat(40)
    val H2 = "2".repeat(40)
    val H3 = "3".repeat(40)
    val H4 = "4".repeat(40)
    val H5 = "5".repeat(40)

    fun tioLink(hash: String, idx: Int, file: String = "file.mkv") = "https://tio.invalid/resolve/realdebrid/FAKEKEY_TIO/$hash/null/$idx/$file"

    fun mfLink(hash: String) = "https://mf.invalid/streaming_provider/FAKECONFIG_MF/playback/realdebrid/$hash"

    fun torrentio(
        hash: String,
        idx: Int = 0,
        ready: Boolean = true,
        quality: String = "4k DV | HDR",
        name: String = "Film.Name.2021.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX[TGx]",
        file: String? = null,
        size: String = "29.26 GB",
        seeders: Int = 593,
        langs: String? = null,
        bingeGroup: String? = null,
    ): JSONObject = JSONObject().apply {
        put("name", (if (ready) "[RD+] Torrentio" else "[RD download] Torrentio") + "\n" + quality)
        val lines = listOfNotNull(name, file, "👤 $seeders 💾 $size ⚙️ SiteName", langs)
        put("title", lines.joinToString("\n"))
        put("url", tioLink(hash, idx))
        put("behaviorHints", JSONObject().apply {
            put("filename", (file ?: name).substringAfterLast('/') + if ((file ?: name).endsWith(".mkv")) "" else ".mkv")
            bingeGroup?.let { put("bingeGroup", it) }
        })
    }

    fun mediaFusion(
        hash: String,
        ready: Boolean = true,
        resolution: String = "4k",
        hdr: String = "DV|HDR",
        origin: String = "WEB-DL",
        codec: String = "hevc",
        audio: String = "Atmos|Dolby Digital Plus",
        channels: String = "5.1",
        filename: String = "Film.Name.2021.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-MFGRP.$hash.mkv",
        videoSize: Long = 31_420_000_000L,
        fileIdx: Int? = 0,
        languages: String = "English",
    ): JSONObject = JSONObject().apply {
        put("name", "MediaFusion | ElfHosted 🧲 RD ${if (ready) "⚡️" else "⏳"} $resolution")
        put("description", "🎨 $hdr 📺 $origin 🎞️ $codec 🎵 $audio 🔊 $channels\n📦 29.26 GB 👤 50\n🌐 $languages\n🔗 Source | 🧑‍💻 Uploader")
        put("url", mfLink(hash))
        fileIdx?.let { put("fileIdx", it) }
        put("behaviorHints", JSONObject().apply {
            put("bingeGroup", "MediaFusion-|-ElfHosted-🎨 $hdr 📺 $origin 🎞️ $codec 🎵 $audio-${resolution.uppercase()}")
            put("filename", filename)
            put("videoSize", videoSize)
        })
    }

    fun answer(vararg streams: JSONObject): String = JSONObject().put("streams", JSONArray(streams.toList())).toString()

    /** Torrentio's error row for a refused key. */
    fun torrentioKeyError(): JSONObject = JSONObject().apply {
        put("name", "Torrentio\nRD error")
        put("title", "Invalid RealDebrid ApiKey/Token!")
        put("url", "https://tio.invalid/videos/failed_access_v3.mp4")
    }

    /** ElfHosted's tortoise row. */
    fun busyRow(): JSONObject = JSONObject().apply {
        put("name", "🐢 MediaFusion")
        put("description", "public rate-limit exceeded, try again later")
        put("url", "https://mf.invalid/static/rate.mp4")
    }
}
