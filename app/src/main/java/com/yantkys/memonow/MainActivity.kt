package com.yantkys.memonow

import android.os.Bundle
import com.yantkys.memonow.backup.BackupState
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.platform.LocalContext
import com.yantkys.memonow.metadata.weatherLabel
import com.yantkys.memonow.data.Post
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this, PostViewModel.Factory)[PostViewModel::class.java] }
    override fun onStart() {
        super.onStart()
        val app = application as MemonowApplication
        app.screenStarted()
        if (!app.locationSource.hasPermission()) app.metadataSettings.setEnabled(false)
    }
    override fun onStop() {
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
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    val backup by model.backupState.collectAsStateWithLifecycle()
    val feed by model.feed.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val writing by model.busy.collectAsStateWithLifecycle()
    val busy = writing || backup != BackupState.Idle
    val error by model.error.collectAsStateWithLifecycle()
    val sentId by model.sentId.collectAsStateWithLifecycle()
    val editingId by model.editingId.collectAsStateWithLifecycle()
    val editDraft by model.editDraft.collectAsStateWithLifecycle()
    val deletingId by model.deletingId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var initiallyPositioned by rememberSaveable { mutableStateOf(false) }
    val posts = (feed as? FeedState.Ready)?.posts

    LaunchedEffect(posts, sentId) {
        if (posts != null) {
            val sentIndex = posts.indexOfFirst { it.id == sentId }
            if (sentIndex >= 0) {
                listState.animateScrollToItem(sentIndex)
                sentId?.let(model::consumeSentId)
                initiallyPositioned = true
            } else if (!initiallyPositioned) {
                if (posts.isNotEmpty()) listState.scrollToItem(posts.lastIndex)
                initiallyPositioned = true
            }
            // Editing, deletion and unrelated DB emissions never trigger scrolling.
            // Stable UUID keys retain the visible anchor while reading old entries.
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("memonow") }, actions = {
                TextButton(onClick = { showBackup = true }, enabled = !writing) { Text("バックアップ") }
                TextButton(onClick = { showSettings = true }, enabled = !busy) { Text("設定") }
            })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp),
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
                    )
                    Button(onClick = model::send, enabled = !busy && draft.isNotBlank()) {
                        Text(if (busy) "保存中" else "送信")
                    }
                }
            }
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
            when (val state = feed) {
                FeedState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                FeedState.Failed -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Text("履歴を読み込めませんでした。")
                    TextButton(onClick = model::retryLoading) { Text("再試行") }
                }
                is FeedState.Ready -> {
                    if (state.posts.isEmpty()) {
                        Text("まだ記録はありません。\nいまの一文から始めましょう。", Modifier.align(Alignment.Center).padding(24.dp))
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.posts, key = { it.id }) { post ->
                            PostRow(post, busy, { model.startEditing(post) }, { model.requestDelete(post.id) })
                        }
                    }
                }
            }
        }
    }

    if (showBackup || backup != BackupState.Idle) {
        BackupDialog(model, backup) { showBackup = false }
    }
    if (showSettings) {
        MetadataSettingsDialog(app, onDisable = model::cancelMetadata, onDismiss = { showSettings = false })
    }
    if (editingId != null) {
        AlertDialog(
            onDismissRequest = model::cancelEditing,
            title = { Text("投稿を編集") },
            text = {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = model::updateEditDraft,
                    label = { Text("本文") },
                    minLines = 3,
                    maxLines = 8,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = model::edit, enabled = !busy && editDraft.isNotBlank()) { Text("保存") }
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

@Composable
private fun PostRow(post: Post, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(post.body, style = MaterialTheme.typography.bodyLarge)
        Text(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(post.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val attributes = listOfNotNull(
            post.placeName?.let { "📍$it" },
            weatherLabel(post.weatherCode),
            post.temperatureC?.let { String.format(java.util.Locale.getDefault(), "%.1f°C", it) },
        )
        if (attributes.isNotEmpty()) {
            Text(attributes.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row {
            TextButton(onClick = onEdit, enabled = !busy) { Text("編集") }
            TextButton(onClick = onDelete, enabled = !busy) { Text("削除") }
        }
    }
}
