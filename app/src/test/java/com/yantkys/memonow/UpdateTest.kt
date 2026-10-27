package com.yantkys.memonow

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.android.apksig.ApkVerifier
import com.yantkys.memonow.update.*
import java.io.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    @org.junit.Before fun resetProvider() { resetUpdateProviderCache() }
    private val digest = "a".repeat(64)
    private fun json(tag: String = "v0.1.4") = """{"tag_name":"$tag","name":"memonow","draft":false,"prerelease":false,"assets":[{"name":"memonow-$tag.apk","size":8,"digest":"sha256:$digest","browser_download_url":"$RELEASES/download/$tag/memonow-$tag.apk"}]}"""
    @Test fun strictMetadataVersionsAndFixedUrls() {
        val release = ReleaseUpdate.parse(json())
        assertTrue(release.version > Version.parse("0.1.3"))
        assertEquals(Version.parse("0.1.4"), release.version)
        assertTrue(Version.parse("0.1.2") < Version.parse("0.1.3"))
        for (value in listOf(json("v00.1.4"),json("v1.2"),json().replace("false","true"),json().replace(digest,"bad"),
            json().replace("\"size\":8","\"size\":999999999"),json().replace("github.com/YanTKYS","evil.example/YanTKYS"),json().replace("\"size\":8","\"size\":1.5"),"{}")) {
            assertTrue(runCatching { ReleaseUpdate.parse(value) }.isFailure)
        }
        val root = org.json.JSONObject(json()); root.getJSONArray("assets").put(root.getJSONArray("assets").getJSONObject(0))
        assertTrue(runCatching { ReleaseUpdate.parse(root.toString()) }.isFailure)
    }
    @Test fun redirectAllowlistRejectsOtherOriginsCredentialsAndHttp() {
        val initial = ReleaseUpdate.parse(json()).apkUrl
        assertTrue(allowedRedirect("https://release-assets.githubusercontent.com/path?signature=opaque", initial))
        for (url in listOf("http://release-assets.githubusercontent.com/path","https://evil.example/a","https://github.com/other/repo/a",
            "https://user@release-assets.githubusercontent.com/a","https://release-assets.githubusercontent.com:444/a","https://release-assets.githubusercontent.com.evil.example/a")) assertFalse(allowedRedirect(url,initial))
    }
    @Test fun boundedStreamingAndCancellation() = runTest {
        val out = ByteArrayOutputStream()
        assertEquals(8, copyBounded(ByteArrayInputStream(ByteArray(8)),out,8) {} )
        assertTrue(runCatching { copyBounded(ByteArrayInputStream(ByteArray(9)),ByteArrayOutputStream(),8) {} }.isFailure)
        assertTrue(runCatching { copyBounded(ByteArrayInputStream(ByteArray(8)),ByteArrayOutputStream(),8) { throw CancellationException() } }.exceptionOrNull() is CancellationException)
    }
    @Test fun httpFailuresRedirectsTruncationAndCleanup() = runTest {
        val release = ReleaseUpdate.parse(json())
        for (status in listOf(403,429,404,503)) {
            val connection = FakeConnection(LATEST_API,status,ByteArray(0))
            assertTrue(runCatching { UpdateNetwork { connection }.latest() }.isFailure)
            assertTrue(connection.disconnected)
        }
        val file = File(context.cacheDir,"network-update.part")
        val truncated = FakeConnection(release.apkUrl,200,ByteArray(7))
        assertTrue(runCatching { UpdateNetwork { truncated }.download(release,file) {} }.isFailure)
        assertFalse(file.exists())
        val success = FakeConnection(release.apkUrl,200,ByteArray(8))
        UpdateNetwork { success }.download(release,file) {}
        assertEquals(8,file.length()); file.delete()
        val bad = FakeConnection(release.apkUrl,302,ByteArray(0),"https://evil.example/a")
        assertTrue(runCatching { UpdateNetwork { bad }.download(release,file) {} }.isFailure)
        assertFalse(file.exists())
    }
    @Test fun realSignedApkAndTamperingWrongSignerAndIdentityAreRejected() {
        val apk = File(requireNotNull(System.getProperty("memonow.fixture.apk")))
        val verified = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).setMaxCheckedPlatformVersion(36).build().verify()
        assertTrue(verified.isVerified)
        val certs = verified.signerCertificates.map { certificateHash(it.encoded) }.toSet()
        val release = ReleaseUpdate("0.1.3","fixture",apk.length(),UpdateVerifier.sha256(apk))
        val installed = InstalledApp("0.1.2",3,certs)
        val archive = PackageInfo().apply { packageName=APP_ID; versionName="0.1.3"; versionCode=4 }
        shadowOf(context.packageManager).setPackageArchiveInfo(apk.absolutePath,archive)
        UpdateVerifier(context).verify(apk,release,installed)
        assertTrue(runCatching { UpdateVerifier(context).verify(apk,release.copy(sha256="b".repeat(64)),installed) }.isFailure)
        assertTrue(runCatching { UpdateVerifier(context).verify(apk,release,installed.copy(certificates=setOf(digest))) }.isFailure)
        for ((id,name,code) in listOf(Triple("other.app","0.1.3",4L),Triple(APP_ID,"0.1.4",4L),Triple(APP_ID,"0.1.3",3L),Triple(APP_ID,"0.1.3",2L)))
            assertTrue(runCatching { UpdateVerifier.validateIdentity(id,name,code,release,installed) }.isFailure)
        val changed = File(context.cacheDir,"tampered.apk")
        apk.copyTo(changed,true)
        RandomAccessFile(changed,"rw").use { it.seek(50); val original=it.read(); it.seek(50); it.write(original xor 1) }
        val tampered = release.copy(sha256=UpdateVerifier.sha256(changed)) // Matching hash cannot bypass crypto verification.
        assertTrue(runCatching { UpdateVerifier(context).verify(changed,tampered,installed) }.isFailure)
        changed.delete()
    }
    @Test fun fileProviderOnlyExposesVerifiedDirectory() {
        val ready = File(context.cacheDir,"updates/ready/update.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val uri = FileProvider.getUriForFile(context,"${context.packageName}.updates",ready)
        assertEquals("content",uri.scheme)
        assertTrue(runCatching { FileProvider.getUriForFile(context,"${context.packageName}.updates",File(context.cacheDir,"updates/download.part")) }.isFailure)
        ready.delete()
    }
}

private class FakeConnection(url: String, private val status: Int, private val bytes: ByteArray, private val redirect: String? = null) : HttpsURLConnection(URL(url)) {
    var disconnected = false
    override fun connect() = Unit
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getContentLengthLong() = -1L
    override fun getHeaderField(name: String?): String? = if (name=="Location") redirect else null
    override fun getCipherSuite() = "fixture"
    override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
    override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
}

// AndroidX caches canonical roots across Robolectric's per-test temporary app directories.
@Suppress("UNCHECKED_CAST")
fun resetUpdateProviderCache() {
    val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    (field.get(null) as MutableMap<Any?, Any?>).clear()
}
