package app.fireplayer.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.fireplayer.ui.FireDialog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub releases for a newer build and installs it.
 * Release tags are "v1.0.<build>", and <build> is also the app's versionCode.
 */
class Updater(private val activity: Activity) {

    data class Release(val versionCode: Long, val name: String, val apkUrl: String)

    private val handler = Handler(Looper.getMainLooper())

    val installedName: String
        get() = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""

    /**
     * Looks for a newer release off the main thread; [onResult] gets it, or null if there's none.
     * [onFailed] runs when GitHub couldn't be reached.
     */
    fun check(onFailed: () -> Unit = {}, onResult: (Release?) -> Unit) {
        Thread {
            val latest = runCatching { latest() }
            handler.post {
                if (gone()) return@post
                latest.fold({ onResult(it.takeIf { release -> release.versionCode > installedCode() }) }, { onFailed() })
            }
        }.start()
    }

    fun offer(release: Release) {
        if (gone()) return
        FireDialog.ask(
            activity,
            "FirePlayer ${release.name} is ready to install.",
            listOf<Pair<String, () -> Unit>>("Update" to { download(release) }, "Later" to {}),
        )
    }

    private fun gone() = activity.isFinishing || activity.isDestroyed

    private fun tell(message: String) {
        FireDialog.ask(activity, message, listOf<Pair<String, () -> Unit>>("OK" to {}))
    }

    private fun latest(): Release {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "FirePlayer")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) apkUrl = asset.getString("browser_download_url")
        }
        return Release(tag.substringAfterLast('.').toLong(), tag.removePrefix("v"), apkUrl ?: error("No APK"))
    }

    private fun installedCode(): Long =
        PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))

    private fun download(release: Release) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            askForInstallPermission()
            return
        }
        // A message with no answers; Back hides it and the download carries on.
        val progress = FireDialog.ask(activity, "Downloading update…", emptyList())
        Thread {
            val result = runCatching { fetch(release.apkUrl) }
            handler.post {
                if (progress?.isShowing == true && !activity.isDestroyed) runCatching { progress.dismiss() }
                result.onSuccess { runCatching { install(it) } }.onFailure {
                    if (!gone()) tell("The update couldn't be downloaded. Try again later.")
                }
            }
        }.start()
    }

    private fun fetch(url: String): File {
        // Before Android 7 the installer can't read content:// links, so use app-specific external storage.
        val dir = if (Build.VERSION.SDK_INT >= 24) File(activity.cacheDir, "updates") else activity.externalCacheDir!!
        dir.mkdirs()
        val file = File(dir, "FirePlayer.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "FirePlayer")
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun install(file: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun askForInstallPermission() {
        FireDialog.ask(
            activity,
            "Allow FirePlayer to install updates, then choose Update again.",
            listOf<Pair<String, () -> Unit>>(
                "Open settings" to {
                    val opened = runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                        )
                    }.isSuccess
                    if (!opened) {
                        tell("On your Fire TV open Settings, My Fire TV, Developer options, Install unknown apps, and turn on FirePlayer.")
                    }
                },
                "Later" to {},
            ),
        )
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/ataahmed989/firestick-player/releases/latest"
    }
}
