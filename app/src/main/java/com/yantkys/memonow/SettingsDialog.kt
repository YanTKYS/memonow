package com.yantkys.memonow

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yantkys.memonow.update.RELEASES
import com.yantkys.memonow.update.UpdateViewModel

@Composable
fun SettingsDialog(app: MemonowApplication, onDisable: () -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf("general") }
    when (page) {
        "location" -> MetadataSettingsDialog(app, onDisable) { page = "general" }
        "update" -> AppUpdateDialog { page = "general" }
        else -> AlertDialog(onDismissRequest = onDismiss, title = { Text("設定") }, text = {
            Column {
                TextButton(onClick = { page = "location" }) { Text("位置・天気") }
                TextButton(onClick = { page = "update" }) { Text("アプリの更新") }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
    }
}

@Composable
private fun AppUpdateDialog(onBack: () -> Unit) {
    val model: UpdateViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permitted = context.packageManager.canRequestPackageInstalls()
            if (event == Lifecycle.Event.ON_STOP) model.cancel()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.close() }
    }
    fun back() { model.close(); onBack() }
    AlertDialog(onDismissRequest = ::back, title = { Text("アプリの更新") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("インストール済み：${model.installed.name} (${model.installed.code})")
            Text(state.message)
            state.release?.let { release ->
                Text("正式版：${release.title}（${release.versionName}）")
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page))) }.onFailure { model.externalPageFailed() } }) { Text("リリースノート") }
            }
            if (state.busy) {
                LinearProgressIndicator()
                if (state.downloaded > 0) Text("取得済み ${state.downloaded / 1024} KiB / ${(state.release?.size ?: 0) / 1024} KiB")
                TextButton(onClick = model::cancel) { Text("取消") }
            } else {
                TextButton(onClick = model::check) { Text("最新版を確認") }
                if (state.newer && !state.ready) TextButton(onClick = model::download) { Text("更新する（APKを取得・検証）") }
                if (state.ready) {
                    if (!permitted) {
                        Text("Androidの「この提供元のアプリを許可」が必要です。許可後に戻り、インストーラーを開いてください。")
                        TextButton(onClick = { model.openPermission { context.startActivity(it) } }) { Text("インストール許可の設定") }
                    }
                    TextButton(onClick = { model.install { context.startActivity(it) } }) { Text("インストーラーを開く") }
                }
            }
            Text("利用者の操作時だけGitHubへ接続します。投稿・位置・履歴は送信しません。ハッシュ・署名・版を確認し、OSの承認後に上書き更新します。")
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$RELEASES/latest"))) }.onFailure { model.externalPageFailed() } }) { Text("正式Releaseをブラウザで開く") }
        }
    }, confirmButton = { TextButton(onClick = ::back) { Text("設定に戻る") } })
}
