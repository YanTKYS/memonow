package com.yantkys.memonow.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateState(val busy: Boolean = false, val message: String = "確認操作時だけGitHubへ接続します。", val release: ReleaseUpdate? = null,
    val newer: Boolean = false, val downloaded: Long = 0, val ready: Boolean = false)
class UpdateViewModel @JvmOverloads constructor(app: Application,
    private val network: UpdateSource = UpdateNetwork(),
    private val verify: (File, ReleaseUpdate, InstalledApp) -> Unit = UpdateVerifier(app)::verify,
    private val identity: () -> InstalledApp = { installedApp(app) },
    private val availableBytes: (File) -> Long = { it.usableSpace },
) : AndroidViewModel(app) {
    val installed = identity()
    private val folder = File(app.cacheDir, "updates")
    private val session = java.util.UUID.randomUUID().toString()
    private val part = File(folder, "$session.part")
    private val ready = File(folder, "ready/$session.apk")
    private val _state = MutableStateFlow(UpdateState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var handedOff = false
    init {
        activeSessions.add(session)
        folder.listFiles()?.filter { it.isFile && it.extension == "part" && it.nameWithoutExtension !in activeSessions }?.forEach { it.delete() }
        File(folder, "ready").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60_000L }?.forEach { it.delete() }
    }
    fun check() = operate {
        ready.delete(); handedOff = false
        _state.value = UpdateState(busy = true, message = "最新版を確認中…")
        val release = network.latest()
        val comparison = release.version.compareTo(Version.parse(installed.name))
        _state.value = UpdateState(message = when {
            comparison > 0 -> "新しい正式版があります。更新前にJSONを保存してください。"
            comparison == 0 -> "最新の正式版を利用中です。"
            else -> "配布版は現在のアプリより古いため更新しません。"
        }, release = release, newer = comparison > 0)
    }
    fun download() {
        val release = state.value.release ?: return
        if (!state.value.newer) return
        operate {
            _state.value = state.value.copy(busy = true, message = "APKを取得・検証中…", ready = false, downloaded = 0)
            withContext(Dispatchers.IO) {
                if (!folder.exists() && !folder.mkdirs()) throw UpdateFailure("一時ファイルを保存できません。")
                if (availableBytes(folder) < release.size + 1024 * 1024) throw UpdateFailure("端末の空き容量が不足しています。")
                ready.delete(); handedOff = false
            }
            try {
                network.download(release, part) { count -> _state.value = state.value.copy(downloaded = count) }
                withContext(Dispatchers.IO) {
                    verify(part, release, installed)
                    currentCoroutineContext().ensureActive()
                    ready.parentFile!!.mkdirs()
                    if (!part.renameTo(ready)) throw UpdateFailure("検証済みAPKを保存できません。")
                }
                _state.value = state.value.copy(busy = false, message = "検証できました。OSの確認画面で更新を承認してください。", ready = true)
            } finally { part.delete() }
        }
    }
    fun cancel() { job?.cancel() }
    fun close() { cancel(); if (!handedOff) { ready.delete(); _state.value = state.value.copy(ready = false) } }
    private fun operate(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) {
                if (!handedOff) ready.delete()
                _state.value = state.value.copy(busy = false, ready = false, message = "更新処理を中断しました。端末の記録は保持されています。")
                throw cancelled
            } catch (failure: UpdateFailure) {
                ready.delete(); _state.value = state.value.copy(busy = false, ready = false, message = failure.message ?: "更新できません。")
            } catch (error: IOException) {
                val message = when {
                    error is java.net.UnknownHostException || error is java.net.SocketException ||
                        error is java.net.SocketTimeoutException || error is javax.net.ssl.SSLException -> "通信できません。ネット接続を確認してお試しください。"
                    generateSequence<Throwable>(error) { it.cause }.any { it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC } -> "端末の空き容量が不足しています。"
                    else -> "通信またはファイル保存に失敗しました。接続と空き容量を確認してください。"
                }
                ready.delete(); _state.value = state.value.copy(busy = false, ready = false, message = message)
            } catch (_: Exception) {
                ready.delete(); _state.value = state.value.copy(busy = false, ready = false, message = "APKを確認できないため更新を中止しました。")
            }
        }
    }
    fun openPermission(open: (Intent) -> Unit) {
        try { open(installPermissionIntent()) }
        catch (_: Exception) { _state.value = state.value.copy(message = "端末のインストール許可画面を開けません。端末のアプリ設定を確認してください。") }
    }
    fun externalPageFailed() { _state.value = state.value.copy(message = "ブラウザを開けません。GitHubの正式Releaseを別のブラウザで確認してください。") }
    fun installPermissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${getApplication<Application>().packageName}"))
    fun install(open: (Intent) -> Unit) {
        val app = getApplication<Application>()
        if (!state.value.ready || state.value.busy) return
        if (!app.packageManager.canRequestPackageInstalls()) { _state.value = state.value.copy(message = "この提供元からのインストール許可が必要です。端末の設定で選択してください。"); return }
        val release = state.value.release ?: return
        operate {
            _state.value = state.value.copy(busy = true)
            withContext(Dispatchers.IO) { verify(ready, release, installed) }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", ready)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri("verified update", uri)
            try {
                open(intent); handedOff = true
                _state.value = state.value.copy(busy = false, message = "OSの更新確認を開きました。承認・取消はOS画面で選択します。記録は保持されます。")
            } catch (_: Exception) { throw UpdateFailure("インストーラーを開けません。正式Releaseの案内を確認してください。") }
        }
    }
    override fun onCleared() { if (!handedOff) ready.delete(); part.delete(); activeSessions.remove(session); super.onCleared() }
    companion object {
        private val activeSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
}
