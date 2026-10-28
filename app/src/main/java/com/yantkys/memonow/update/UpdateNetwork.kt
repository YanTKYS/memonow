package com.yantkys.memonow.update

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface UpdateSource {
    suspend fun latest(): ReleaseUpdate
    suspend fun download(release: ReleaseUpdate, file: File, progress: (Long) -> Unit)
}
class UpdateNetwork(private val open: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection }) : UpdateSource {
    override suspend fun latest(): ReleaseUpdate = withContext(Dispatchers.IO) {
        connection(LATEST_API, false).useResponse { input, _ ->
            val output = java.io.ByteArrayOutputStream()
            copyBounded(input, output, 1024 * 1024L) { currentCoroutineContext().ensureActive() }
            ReleaseUpdate.parse(output.toString("UTF-8"))
        }
    }
    override suspend fun download(release: ReleaseUpdate, file: File, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        try {
            connection(release.apkUrl, true).useResponse { input, length ->
                if (length > 0 && length != release.size) throw UpdateFailure("APKの応答サイズが一致しません。")
                file.outputStream().use { output ->
                    val count = copyBounded(input, output, release.size) {
                        currentCoroutineContext().ensureActive(); progress(it)
                    }
                    if (count != release.size) throw UpdateFailure("APKのダウンロードが途中で終了しました。")
                }
            }
        } catch (error: Exception) { file.delete(); throw error }
    }
    private suspend fun connection(initial: String, redirects: Boolean): HttpsURLConnection {
        var url = initial
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val connection = open(url)
            try {
                connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", if (redirects) "application/octet-stream" else "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "memonow-update")
                when (val code = connection.responseCode) {
                    200 -> return connection
                    301, 302, 303, 307, 308 -> {
                        val target = URI(url).resolve(connection.getHeaderField("Location") ?: "").toString()
                        if (!redirects || !allowedRedirect(target, initial)) throw UpdateFailure("安全なダウンロード先を確認できません。")
                        url = target
                    }
                    403, 429 -> throw UpdateFailure("GitHubの利用制限中です。時間をおいてお試しください。")
                    404 -> throw UpdateFailure("正式版またはAPKが見つかりません。")
                    else -> throw UpdateFailure("配布サーバーに接続できません（HTTP $code）。")
                }
                connection.disconnect()
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
        throw UpdateFailure("ダウンロード先の転送回数が上限を超えました。")
    }
    private suspend fun <T> HttpsURLConnection.useResponse(block: suspend (InputStream, Long) -> T): T = try {
        inputStream.use { block(it, contentLengthLong) }
    } finally { disconnect() }
}

suspend fun copyBounded(input: InputStream, output: OutputStream, limit: Long, progress: suspend (Long) -> Unit): Long {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        progress(total)
        val count = input.read(buffer)
        if (count < 0) return total
        if (count.toLong() > limit - total) throw UpdateFailure("取得サイズが上限を超えました。")
        output.write(buffer, 0, count); total += count
    }
}
