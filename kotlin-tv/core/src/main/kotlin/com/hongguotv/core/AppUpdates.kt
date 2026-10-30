// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** Immutable metadata received from the first-party update manifest. */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val notes: String
) {
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    val downloadUrl get() = apkUrl

    companion object {
        const val REPOSITORY = "laozhangaa/HongGuoTV"
        const val MANIFEST_URL = "https://github.com/$REPOSITORY/releases/latest/download/update.json"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        private val fileNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,159}\\.apk")

        fun parse(json: String): AppUpdate {
            val o = JSONObject(json)
            require(o.getInt("schema") == 1 && o.getString("packageName") == "com.hongguotv.laozhangapp") { "更新信息不匹配" }
            val apk = o.getJSONObject("apk")
            val apkUrl = apk.getString("url").trim()
            val fileName = apk.getString("name").trim()
            val update = AppUpdate(
                o.getLong("versionCode"), o.getString("versionName"), o.getInt("minSdk"), apkUrl,
                fileName, apk.getLong("size"), apk.getString("sha256").lowercase(), o.optString("notes").take(1500)
            )
            val url = runCatching { apkUrl.toHttpUrl() }.getOrNull() ?: throw IllegalArgumentException("更新地址不正确")
            require(UpdateUrls.isReleaseAsset(url) && url.pathSegments.lastOrNull()?.endsWith(".apk") == true) { "更新地址不受支持" }
            require(fileNamePattern.matches(fileName) && fileName != "update.json") { "安装包文件名不正确" }
            require(url.pathSegments.lastOrNull() == fileName) { "更新地址与文件名不一致" }
            require(update.versionCode in 1..Int.MAX_VALUE.toLong() && update.minSdk in 26..100)
            require(update.versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")))
            require(update.size in 1..MAX_APK_BYTES && update.sha256.matches(Regex("[0-9a-f]{64}")))
            return update
        }
    }
}

/** The manifest chooses only an asset in this repository; GitHub controls CDN redirects. */
internal object UpdateUrls {
    private val releaseAsset = Regex("/${AppUpdate.REPOSITORY}/releases/download/[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}/(?:update\\.json|[A-Za-z0-9][A-Za-z0-9._+\\-]{0,159}\\.apk)")

    private fun secure(url: HttpUrl) = url.scheme == "https" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null

    fun isReleaseAsset(url: HttpUrl) = secure(url) && url.host == "github.com" &&
        url.query == null && releaseAsset.matches(url.encodedPath)

    fun allows(url: HttpUrl): Boolean {
        if (!secure(url)) return false
        return when (url.host) {
            "github.com" -> url.toString() == AppUpdate.MANIFEST_URL || isReleaseAsset(url)
            // Release downloads use signed query parameters on these exact hosts.
            "release-assets.githubusercontent.com", "objects.githubusercontent.com" ->
                url.encodedPath.startsWith("/github-production-release-asset/") ||
                    url.encodedPath.startsWith("/github-production-release-asset-")
            else -> false
        }
    }
}

/** Streaming validation also protects a cached APK from partial downloads and stale metadata. */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32768)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }

    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L; var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常（下载已超过清单大小）")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            replaceDownloadedFile(partial, destination)
        } finally { partial.delete() }
    }

    /** File.renameTo is allowed to refuse replacing an existing file on Android and Windows. */
    private fun replaceDownloadedFile(partial: File, destination: File) {
        try {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.io.IOException) {
            if (!partial.renameTo(destination)) throw IOException("无法保存安装包", e)
        }
    }
}

class AppUpdateRepository : java.io.Closeable {
    private val http = OkHttpClient.Builder().dns(Ipv4FirstDns()).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!UpdateUrls.allows(url))
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Cache-Control", "no-cache").build()

    fun latest(): AppUpdate {
        val call = http.newCall(request(AppUpdate.MANIFEST_URL))
        call.timeout().timeout(25, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException(if (response.code == 404) "尚无可用的自动更新版本" else "无法检查更新，请稍后重试")
            val bytes = response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 65536) throw IOException("更新信息过大"); out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: throw IOException("更新信息为空")
            try { AppUpdate.parse(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { throw IOException("更新信息格式不正确") }
        }
    }

    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        http.newCall(request(update.downloadUrl)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("安装包下载失败（HTTP ${response.code}），请稍后重试")
            val body = response.body ?: throw IOException("安装包为空")
            if (body.contentLength() >= 0 && body.contentLength() != update.size)
                throw IOException("安装包长度异常（服务器 ${body.contentLength()} 字节，清单 ${update.size} 字节）")
            body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
        }
    }

    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
