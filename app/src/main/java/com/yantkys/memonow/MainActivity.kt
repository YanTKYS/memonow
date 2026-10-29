package com.yantkys.memonow

import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.yantkys.memonow.backup.BackupState
import com.yantkys.memonow.backup.BackupJson
import com.yantkys.memonow.backup.backupCapacityWarning
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.platform.LocalContext
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this, PostViewModel.Factory)[PostViewModel::class.java] }
    private val zoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val zone = intent.getStringExtra("time-zone")?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneId.systemDefault()
            model.refreshTimeZone(zone)
        }
    }
    override fun onStart() {
        super.onStart()
        model.refreshTimeZone()
        ContextCompat.registerReceiver(this, zoneReceiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val app = application as MemonowApplication
        app.screenStarted()
        if (!app.locationSource.hasPermission()) {
            app.metadataSettings.setEnabled(false)
            app.postMetadata.interrupt(com.yantkys.memonow.metadata.MetadataProblem.PERMISSION)
        }
    }
    override fun onStop() {
        unregisterReceiver(zoneReceiver)
        if ((application as MemonowApplication).screenStopped()) model.cancelMetadata()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MemonowScreen(model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemonowScreen(model: PostViewModel) {
    val app = LocalContext.current.applicationContext as MemonowApplication
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    val backup by model.backupState.collectAsStateWithLifecycle()
    val feed by model.feed.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val moodId by model.moodId.collectAsStateWithLifecycle()
    val writing by model.busy.collectAsStateWithLifecycle()
    val busy = writing || backup != BackupState.Idle
    val error by model.error.collectAsStateWithLifecycle()
    val sentId by model.sentId.collectAsStateWithLifecycle()
    val editingId by model.editingId.collectAsStateWithLifecycle()
    val editDraft by model.editDraft.collectAsStateWithLifecycle()
    val editMoodId by model.editMoodId.collectAsStateWithLifecycle()
    val deletingId by model.deletingId.collectAsStateWithLifecycle()
    val posts = (feed as? FeedState.Ready)?.posts
    val capacityWarning = remember(posts) { posts?.let(::backupCapacityWarning) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("memonow") }, actions = {
                AppActions(calendarEnabled = !busy && feed is FeedState.Ready, writing = writing, busy = busy,
                    onCalendar = { focus.clearFocus(); keyboard?.hide(); showCalendar = true },
                    onBackup = { showBackup = true }, onSettings = { showSettings = true })
            })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp)) {
                    capacityWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    MoodPicker(moodId, !busy, model::toggleMood, model::clearMood)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = model::updateDraft,
                            modifier = Modifier.weight(1f),
                            label = { Text("いまの一文") },
                            placeholder = { Text("その瞬間のことを残す") },
                            minLines = 1,
                            maxLines = 5,
                            enabled = !busy,
                            isError = draft.length > BackupJson.MAX_BODY,
                            supportingText = { if (draft.length > BackupJson.MAX_BODY) Text("本文は10万文字までです（絵文字などは2文字）。") },
                        )
                        Button(onClick = model::send, enabled = !busy && draft.isNotBlank() && draft.length <= BackupJson.MAX_BODY) {
                            Text(if (busy) "保存中" else "送信")
                        }
                    }
                }
            }
        },
    ) { contentPadding ->
        PostTimeline(feed, sentId, busy, model::consumeSentId, model::showAllDates, model::retryLoading,
            model::startEditing, { model.requestDelete(it.id) }, Modifier.fillMaxSize().padding(contentPadding))
    }

    val ready = feed as? FeedState.Ready
    if (showCalendar && ready != null) {
        CalendarDialog(ready.dateCounts, ready.selectedDate, ready.zone,
            onSelect = { if (model.selectDate(it)) showCalendar = false }, onDismiss = { showCalendar = false })
    }

    if (showBackup || backup != BackupState.Idle) {
        BackupDialog(model, backup) { showBackup = false }
    }
    if (showSettings) {
        SettingsDialog(app, onDisable = model::cancelMetadata, onDismiss = { showSettings = false })
    }
    if (editingId != null) {
        AlertDialog(
            onDismissRequest = model::cancelEditing,
            title = { Text("投稿を編集") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = editDraft,
                        onValueChange = model::updateEditDraft,
                        label = { Text("本文") },
                        minLines = 3,
                        maxLines = 8,
                        enabled = !busy,
                        isError = editDraft.length > BackupJson.MAX_BODY,
                        supportingText = { if (editDraft.length > BackupJson.MAX_BODY) Text("本文は10万文字までです（絵文字などは2文字）。") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MoodPicker(editMoodId, !busy, model::toggleEditMood, model::clearEditMood)
                }
            },
            confirmButton = {
                TextButton(onClick = model::edit, enabled = !busy && editDraft.isNotBlank() && editDraft.length <= BackupJson.MAX_BODY) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = model::cancelEditing, enabled = !busy) { Text("キャンセル") } },
        )
    }
    if (deletingId != null) {
        AlertDialog(
            onDismissRequest = model::cancelDeleting,
            title = { Text("投稿を削除しますか？") },
            text = { Text("削除した投稿は元に戻せません。") },
            confirmButton = { TextButton(onClick = model::delete, enabled = !busy) { Text("削除") } },
            dismissButton = { TextButton(onClick = model::cancelDeleting, enabled = !busy) { Text("キャンセル") } },
        )
    }
    if (error != null) {
        AlertDialog(
            onDismissRequest = model::clearError,
            title = { Text("操作を完了できませんでした") },
            text = { Text(error.orEmpty()) },
            confirmButton = { TextButton(onClick = model::clearError) { Text("閉じる") } },
        )
    }
}
