package com.youss17x.playbeam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A release published on GitHub (Releases page of the repository). */
data class AppRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

/**
 * Checks GitHub Releases for a newer PlayBeam, downloads the APK and opens the Android installer.
 * A release must have a tag like "v1.2.0" and a .apk file attached.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/youss17x/PlayBeam/releases/latest"

    fun currentVersion(ctx: Context): String = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "0"

    /** true if [a] is a higher version than [b] ("1.10.0" > "1.9.2"). */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The latest release, or null if there is none yet. Throws on network errors. */
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PlayBeam")
        }
        try {
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode != 200) throw IOException("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size")
                    break
                }
            }
            if (apkUrl.isEmpty()) return@withContext null
            AppRelease(
                version = json.optString("tag_name").removePrefix("v"),
                notes = json.optString("body").trim(),
                apkUrl = apkUrl,
                apkSize = apkSize,
                pageUrl = json.optString("html_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** A newer release than the installed app, unless the user chose to skip it. */
    suspend fun available(ctx: Context, ignoreSkipped: Boolean = false): AppRelease? {
        val r = latest() ?: return null
        if (!isNewer(r.version, currentVersion(ctx))) return null
        if (!ignoreSkipped && Store.prefs(ctx).getString("skipVersion", null) == r.version) return null
        return r
    }

    fun skip(ctx: Context, version: String) {
        Store.prefs(ctx).edit().putString("skipVersion", version).apply()
    }

    /** Downloads the APK into the app cache. */
    suspend fun download(ctx: Context, r: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // old downloads
        val out = File(dir, "PlayBeam-PS5-${r.version}.apk")
        val conn = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PlayBeam")
        }
        try {
            if (conn.responseCode != 200) throw IOException("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            conn.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        out
    }

    /** Android 8+ asks once for permission to install apps from PlayBeam. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens the Android installer for the downloaded APK. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
