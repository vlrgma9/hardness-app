package com.carbon.hardness

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Releases 기반 앱 업데이트 */
object Updater {

    private const val API_LATEST =
        "https://api.github.com/repos/vlrgma9/hardness-app/releases/latest"

    data class Release(val tag: String, val apkUrl: String?, val title: String)

    fun currentVersion(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

    /** 최신 릴리즈 조회 (IO 스레드에서 호출) */
    fun fetchLatest(): Release? {
        return try {
            val conn = URL(API_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val o = JSONObject(body)
            val tag = o.optString("tag_name", "")
            if (tag.isBlank()) return null
            var apkUrl: String? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name", "").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            Release(tag, apkUrl, o.optString("name", tag))
        } catch (_: Exception) { null }
    }

    /** "v0.5.0" vs "0.4.0" 숫자 비교 */
    fun isNewer(tag: String, current: String): Boolean {
        fun parts(s: String) = s.removePrefix("v").split(".", "-")
            .mapNotNull { it.toIntOrNull() }
        val a = parts(tag); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** APK 다운로드 (IO 스레드) → 파일 반환 */
    fun downloadApk(context: Context, url: String): File? {
        return try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update.apk")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (out.length() > 1_000_000) out else null
        } catch (_: Exception) { null }
    }

    /** 설치 화면 띄우기 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "com.carbon.hardness.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
