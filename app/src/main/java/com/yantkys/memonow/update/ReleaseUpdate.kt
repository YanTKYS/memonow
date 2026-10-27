package com.yantkys.memonow.update

import org.json.JSONObject
import java.net.URI

const val RELEASES = "https://github.com/YanTKYS/memonow/releases"
const val LATEST_API = "https://api.github.com/repos/YanTKYS/memonow/releases/latest"
const val MAX_APK = 64L * 1024 * 1024
class UpdateFailure(message: String) : Exception(message)
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(text: String): Version {
            if (!Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(text)) throw UpdateFailure("バージョンの形式が不正です。")
            val parts = text.split('.').map { it.toIntOrNull() ?: throw UpdateFailure("バージョンの形式が不正です。") }
            return Version(parts[0], parts[1], parts[2])
        }
    }
}
data class ReleaseUpdate(val versionName: String, val title: String, val size: Long, val sha256: String) {
    val version get() = Version.parse(versionName)
    val page get() = "$RELEASES/tag/v$versionName"
    val apkUrl get() = "$RELEASES/download/v$versionName/memonow-v$versionName.apk"
    companion object {
        fun parse(body: String): ReleaseUpdate = try {
            val json = JSONObject(body)
            if (json.get("draft") != false || json.get("prerelease") != false) throw UpdateFailure("正式公開版ではありません。")
            val tag = json.getString("tag_name")
            if (!tag.startsWith('v')) throw UpdateFailure("リリース情報が不正です。")
            val name = tag.drop(1)
            Version.parse(name)
            val assets = json.getJSONArray("assets")
            if (assets.length() > 100) throw UpdateFailure("リリース資産が不正です。")
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name") == "memonow-v$name.apk" }
            if (matches.size != 1) throw UpdateFailure("正式APKが見つからないか重複しています。")
            val apk = matches.single()
            val sizeValue = apk.get("size") as? Number ?: throw UpdateFailure("APKサイズが不正です。")
            val size = sizeValue.toLong()
            if (sizeValue.toDouble() != size.toDouble() || size !in 1..MAX_APK) throw UpdateFailure("APKサイズが上限を超えるか不正です。")
            val digest = apk.optString("digest")
            if (!Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) throw UpdateFailure("APKのSHA-256情報を確認できません。")
            val result = ReleaseUpdate(name, json.optString("name", tag).filter { !it.isISOControl() }.take(100), size, digest.drop(7).lowercase())
            if (apk.getString("browser_download_url") != result.apkUrl) throw UpdateFailure("APKの配布先が正式リポジトリと一致しません。")
            result
        } catch (failure: UpdateFailure) { throw failure }
        catch (_: Exception) { throw UpdateFailure("リリース情報が不正です。") }
    }
}

fun allowedRedirect(url: String, initial: String): Boolean = try {
    val uri = URI(url)
    uri.scheme == "https" && uri.rawUserInfo == null && uri.fragment == null && uri.port in listOf(-1, 443) &&
        (url == initial || uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
} catch (_: Exception) { false }
