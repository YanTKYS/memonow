package com.yantkys.memonow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yantkys.memonow.backup.BackupState
import java.time.LocalDate

@Composable
fun BackupDialog(model: PostViewModel, state: BackupState, onClose: () -> Unit) {
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), model::exportTo)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::importFrom)
    val working = state is BackupState.Processing
    fun close() { if (!working) { model.dismissBackup(); onClose() } }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text("バックアップ・復元") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    BackupState.Idle -> {
                        Text("JSONは暗号化されない平文です。本文・日時・気分・地名・天気が含まれるため、保存先と共有先を慎重に選んでください。")
                        Text("保存先にクラウドを選ぶ場合は、そのサービスへ手動で保存されます。アプリの自動同期ではありません。設定・位置権限は含みません。")
                        Text("上限：10MiB・1万件・本文各10万文字。復元は全件置換のみです。先に現在の記録を保存しておくことをおすすめします。")
                        Button(onClick = {
                            if (model.beginBackup(true)) create.launch("memonow-${LocalDate.now()}.json")
                        }) { Text("JSONを保存") }
                        Button(onClick = {
                            if (model.beginBackup(false)) open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        }) { Text("JSONから復元") }
                    }
                    is BackupState.Picking -> Text("ファイル選択を待っています。選択をキャンセルすると記録は変更されません。")
                    is BackupState.Processing -> { CircularProgressIndicator(); Text(state.label) }
                    is BackupState.Confirm -> {
                        Text("既存：${state.plan.existingCount}件\n復元対象：${state.plan.document.posts.size}件")
                        Text("端末内の既存記録はすべて置き換わります。追加・マージではありません。この操作は元に戻せません。")
                        Text("設定や位置権限は変更しません。内容を確認し、置き換える場合だけ実行してください。")
                    }
                    is BackupState.Finished -> Text(state.message)
                }
            }
        },
        confirmButton = {
            if (state is BackupState.Confirm) {
                TextButton(onClick = model::confirmRestore) { Text("全件を置き換えて復元") }
            } else if (!working) TextButton(onClick = { close() }) { Text("閉じる") }
        },
        dismissButton = {
            if (state is BackupState.Confirm) TextButton(onClick = { close() }) { Text("キャンセル") }
        },
    )
}
