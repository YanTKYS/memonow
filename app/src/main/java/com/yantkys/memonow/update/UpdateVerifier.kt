package com.yantkys.memonow.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

const val APP_ID = "com.yantkys.memonow"
data class InstalledApp(val name: String, val code: Long, val certificates: Set<String>)
@Suppress("DEPRECATION")
fun PackageInfo.code(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
fun certificateHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
@Suppress("DEPRECATION")
fun installedApp(context: Context): InstalledApp {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val info = context.packageManager.getPackageInfo(context.packageName, flags)
    val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return InstalledApp(info.versionName ?: "不明", info.code(), signers.orEmpty().map { certificateHash(it.toByteArray()) }.toSet())
}

class UpdateVerifier(private val context: Context) {
    fun verify(file: File, release: ReleaseUpdate, installed: InstalledApp) {
        if (file.length() != release.size || sha256(file) != release.sha256) throw UpdateFailure("APKのサイズまたはSHA-256が一致しません。")
        // Archive PackageInfo alone does not authenticate an APK. Verify its actual signatures first.
        val verified = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).setMaxCheckedPlatformVersion(36).build().verify()
        if (!verified.isVerified) throw UpdateFailure("APKの署名を検証できません。")
        val certs = verified.signerCertificates.map { certificateHash(it.encoded) }.toSet()
        if (installed.certificates.isEmpty() || certs != installed.certificates) throw UpdateFailure("現在のアプリと署名が異なるため上書き更新できません。記録を保持したまま正式版の案内を確認してください。")
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: throw UpdateFailure("APKのパッケージ情報を確認できません。")
        validateIdentity(info.packageName, info.versionName, info.code(), release, installed)
    }
    companion object {
        fun validateIdentity(id: String?, name: String?, code: Long, release: ReleaseUpdate, installed: InstalledApp) {
            if (id != APP_ID || name != release.versionName || code <= installed.code || release.version <= Version.parse(installed.name))
                throw UpdateFailure("APKのパッケージ名またはバージョンが更新条件と一致しません。")
        }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(32 * 1024); while (true) {
                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
