package com.hongguotv.core

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdatesTest {
    private val bytes = "signed-apk-test-bytes".toByteArray()
    private val fileName = "hongguoTV-laozhang-v0.10.8.apk"
    private val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun json() = JSONObject().put("schema", 1).put("packageName", "com.hongguotv.laozhangapp")
        .put("versionCode", 25).put("versionName", "0.10.8").put("minSdk", 26)
        .put("apk", JSONObject().put("url", "https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName").put("name", fileName)
            .put("size", bytes.size).put("sha256", checksum))
    private fun update() = AppUpdate.parse(json().toString())
    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("update-test").toFile()
        try { block(File(dir, "update.apk")) } finally { dir.deleteRecursively() }
    }

    @Test fun onlyHigherCompatibleVersionsAreUpdates() {
        val update = update()
        assertTrue(update.newerThan(24, 26)); assertFalse(update.newerThan(25, 36));
        assertFalse(update.newerThan(26, 36)); assertFalse(update.newerThan(24, 25))
    }

    @Test fun manifestAcceptsFirstPartyApkUrlAndKeepsUserFilename() {
        val parsed = update()
        assertEquals("https://github.com/laozhangaa/HongGuoTV/releases/latest/download/update.json", AppUpdate.MANIFEST_URL)
        assertEquals("https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName", parsed.downloadUrl)
        assertEquals(fileName, parsed.fileName)
    }

    @Test fun manifestRejectsOtherHostsPathsQueriesAndMismatchedNames() {
        val values = listOf(
            "https://github.com/N3urda/hongguoTV/releases/download/v0.10.8/$fileName" to fileName,
            "https://github.com/laozhangaa/TDXY/releases/download/v0.10.8/$fileName" to fileName,
            "https://api.laozhangone.top/apk/$fileName" to fileName,
            "https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName?download=1" to fileName,
            "https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/other.apk" to fileName,
            "https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName" to "../bad.apk",
            "https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName" to "bad name.apk",
            "https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/nested/$fileName" to fileName,
            "https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/update.json" to "update.json",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/$fileName" to fileName
        )
        values.forEach { (url, name) ->
            val o = json(); o.getJSONObject("apk").put("url", url).put("name", name)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
    }

    @Test fun allowGithubManifestAndSignedReleaseAssetRedirects() {
        val urls = listOf(
            AppUpdate.MANIFEST_URL,
            "https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/update.json",
            update().downloadUrl,
            "https://release-assets.githubusercontent.com/github-production-release-asset/123/asset?sig=signed&se=expiry",
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/123/asset?X-Amz-Signature=signed"
        )
        urls.forEach { assertTrue(it, UpdateUrls.allows(it.toHttpUrl())) }
    }

    @Test fun rejectRedirectsOutsideGithubReleaseDownloadHosts() {
        val urls = listOf(
            "http://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName",
            "https://github.com:444/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName",
            "https://user:password@github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName",
            "https://github.com/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName#fragment",
            "https://github.com/laozhangaa/TDXY/releases/download/v0.10.8/update.json",
            "https://github.com/laozhangaa/HongGuoTV/releases/latest/download/update.json?other=1",
            "https://github.com.evil.example/laozhangaa/HongGuoTV/releases/download/v0.10.8/$fileName",
            "https://release-assets.githubusercontent.com.evil.example/github-production-release-asset/123/asset",
            "http://release-assets.githubusercontent.com/github-production-release-asset/123/asset",
            "https://release-assets.githubusercontent.com:444/github-production-release-asset/123/asset",
            "https://objects.githubusercontent.com/unrelated/asset",
            "https://raw.githubusercontent.com/laozhangaa/HongGuoTV/main/update.json",
            "https://127.0.0.1/$fileName"
        )
        urls.forEach { assertFalse(it, UpdateUrls.allows(it.toHttpUrl())) }
    }

    @Test fun rejectOtherPackageAndInvalidSchema() {
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("packageName", "other.app").toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("schema", 2).toString()) }
    }

    @Test fun enforceVersionSizeAndChecksumBounds() {
        for (code in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("versionCode", code).toString()) }
        for (size in listOf(0L, -1L, AppUpdate.MAX_APK_BYTES + 1)) {
            val o = json(); o.getJSONObject("apk").put("size", size)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
        val o = json(); o.getJSONObject("apk").put("sha256", "invalid")
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
    }

    @Test fun verifiedDownloadAtomicallyReplacesOldFile() = withFile { file ->
        file.writeText("previous"); val progress = mutableListOf<Int>()
        UpdateFiles.receive(ByteArrayInputStream(bytes), file, update(), progress::add)
        assertTrue(UpdateFiles.verify(file, update())); assertEquals(100, progress.last()); assertFalse(File(file.path + ".part").exists())
    }

    @Test fun truncatedDownloadPreservesPreviousFile() = withFile { file ->
        file.writeText("previous")
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes.copyOf(4)), file, update()) }
        assertEquals("previous", file.readText()); assertFalse(File(file.path + ".part").exists())
    }

    @Test fun oversizedDownloadIsRejectedAndRemoved() = withFile { file ->
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes + byteArrayOf(1)), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path + ".part").exists())
    }

    @Test fun checksumMismatchCannotBecomeInstallableFile() = withFile { file ->
        val corrupt = bytes.copyOf(); corrupt[0] = 0
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(corrupt), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path + ".part").exists())
    }

    @Test fun interruptedDownloadCleansUpPartialFile() = withFile { file ->
        Thread.currentThread().interrupt()
        try { assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes), file, update()) } }
        finally { Thread.interrupted() }
        assertFalse(file.exists()); assertFalse(File(file.path + ".part").exists())
    }

    @Test fun cacheMustStillMatchExpectedContent() = withFile { file ->
        file.writeBytes(bytes); assertTrue(UpdateFiles.verify(file, update()))
        file.writeBytes(ByteArray(bytes.size)); assertFalse(UpdateFiles.verify(file, update()))
    }
}
