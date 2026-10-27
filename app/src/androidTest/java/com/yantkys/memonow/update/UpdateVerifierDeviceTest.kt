package com.yantkys.memonow.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs in Android's process, without launching UI, opening the DB, or installing an update. */
@RunWith(AndroidJUnit4::class)
class UpdateVerifierDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var apk: File
    private lateinit var installed: InstalledApp
    private lateinit var release: ReleaseUpdate

    @Before fun copyInstalledSignedApk() {
        directory = File(context.cacheDir, "signature-smoke").apply { mkdirs() }
        apk = File(context.applicationInfo.sourceDir).copyTo(File(directory, "signed.apk"), overwrite = true)
        val actual = installedApp(context)
        assertFalse("Android PackageManager must return the installed signers", actual.certificates.isEmpty())
        // Keep the real installed certificate. Only pretend the installed version is older,
        // so the same CI-signed APK can exercise the complete production verifier.
        installed = actual.copy(name = "0.0.0", code = 0)
        release = ReleaseUpdate(actual.name, "Runtime smoke test", apk.length(), UpdateVerifier.sha256(apk))
    }

    @After fun removeCopies() { if (::directory.isInitialized) directory.deleteRecursively() }

    @Test fun signedApkVerifiesAndItsCertificatesMatchAndroidPackageManager() {
        val result = verifier(apk).verify()
        assertTrue("APK signature errors: ${result.errors}", result.isVerified)
        assertEquals(installed.certificates, result.signerCertificates.map { certificateHash(it.encoded) }.toSet())
        // Also exercise real getPackageArchiveInfo, version checks, and certificate comparison.
        UpdateVerifier(context).verify(apk, release, installed)
    }

    @Test fun modifiedApkIsRejectedEvenWithMatchingDownloadHash() {
        RandomAccessFile(apk, "rw").use {
            it.seek(50)
            val original = it.read()
            check(original >= 0)
            it.seek(50)
            it.write(original xor 1)
        }
        // Matching size/hash ensures rejection comes from signature verification.
        val tampered = release.copy(sha256 = UpdateVerifier.sha256(apk))
        assertNotEquals(release.sha256, tampered.sha256)
        assertFalse("Modified signed bytes must fail authentication", verifier(apk).verify().isVerified)
        assertThrows(UpdateFailure::class.java) { UpdateVerifier(context).verify(apk, tampered, installed) }
    }

    private fun verifier(file: File) = ApkVerifier.Builder(file)
        .setMinCheckedPlatformVersion(26).setMaxCheckedPlatformVersion(36).build()
}
