package dev.pcremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What the latest GitHub release offers, read from its latest.json (see tools/make-manifest.ps1). */
data class ReleaseInfo(
    val appVersion: String,
    val appVersionCode: Int,
    val appFile: String,
    val appSha256: String,
    val agentVersion: String,
    val notes: String,
)

object Updates {
    private const val BASE = "https://github.com/WRL-SPR/pc-remote/releases/latest/download"

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    @Suppress("DEPRECATION")
    fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = open("$BASE/latest.json")
        try {
            if (conn.responseCode != 200) error("업데이트 정보를 가져오지 못했습니다 (${conn.responseCode})")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val app = o.getJSONObject("app")
            ReleaseInfo(
                appVersion = app.getString("version"),
                appVersionCode = app.getInt("versionCode"),
                appFile = app.getString("file"),
                appSha256 = app.getString("sha256"),
                agentVersion = o.optJSONObject("agent")?.optString("version").orEmpty(),
                notes = o.optString("notes"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK into the app's cache and checks its SHA-256 against the release info. */
    suspend fun download(context: Context, info: ReleaseInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "PcRemote-${info.appVersion}.apk")
            val conn = open("$BASE/${info.appFile}")
            try {
                if (conn.responseCode != 200) error("내려받지 못했습니다 (${conn.responseCode})")
                val total = conn.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hash.equals(info.appSha256, ignoreCase = true)) {
                    file.delete()
                    error("받은 파일이 릴리스 정보와 다릅니다. 다시 시도해 주세요")
                }
                file
            } finally {
                conn.disconnect()
            }
        }

    /** Whether Android lets this app open the package installer ("출처를 알 수 없는 앱 설치" for 8.0+). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Hands the APK to Android's installer, which asks the user to confirm. Android only accepts it
     * as an update if it is signed with the same key as the installed app.
     */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true   // github.com -> objects.githubusercontent.com
            setRequestProperty("User-Agent", "PcRemote-Android")
        }
}

/** True if version [a] ("1.10.0") is newer than [b] ("1.9.2"). */
fun isNewerVersion(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank()) return false
    if (b.isNullOrBlank()) return true
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}
