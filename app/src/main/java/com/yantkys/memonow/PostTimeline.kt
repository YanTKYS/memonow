package com.yantkys.memonow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yantkys.memonow.data.Post
import com.yantkys.memonow.metadata.weatherLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun PostTimeline(state: FeedState, sentId: String?, busy: Boolean, onSentVisible: (String) -> Unit,
                          onShowAll: () -> Unit, onRetry: () -> Unit,
                          onEdit: (Post) -> Unit, onDelete: (Post) -> Unit, modifier: Modifier = Modifier) {
    val ready = state as? FeedState.Ready
    val selected = ready?.selectedDate
    val allState = rememberLazyListState()
    // Don't consume a restored day-list position during the initial Loading frame.
    val dayState = if (selected != null) rememberSaveable(selected.toString(), saver = LazyListState.Saver) { LazyListState() } else null
    var initiallyPositioned by rememberSaveable { mutableStateOf(false) }
    val sentIndex = if (selected == null && sentId != null) ready?.posts?.indexOfFirst { it.id == sentId } ?: -1 else -1
    // Body/mood/metadata edits don't restart a scroll. A pending sent ID waits for
    // its actual Room emission, with all/day lists retaining separate positions.
    LaunchedEffect(sentId, sentIndex, ready != null, selected) {
        if (ready != null && selected == null) {
            if (sentIndex >= 0) {
                allState.animateScrollToItem(sentIndex)
                initiallyPositioned = true
                sentId?.let(onSentVisible)
            } else if (!initiallyPositioned && sentId == null) {
                if (ready.posts.isNotEmpty()) allState.scrollToItem(ready.posts.lastIndex)
                initiallyPositioned = true
            }
        }
    }
    Column(modifier) {
        if (selected != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${selected.year}年${selected.monthValue}月${selected.dayOfMonth}日 · ${ready.visiblePosts.size}件",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onShowAll, enabled = !busy) { Text("全件表示") }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (state) {
                FeedState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                FeedState.Failed -> Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Text("履歴を読み込めませんでした。")
                    TextButton(onClick = onRetry) { Text("再試行") }
                }
                is FeedState.Ready -> {
                    if (state.posts.isEmpty()) Text("まだ記録はありません。\nいまの一文から始めましょう。",
                        Modifier.align(Alignment.Center).padding(24.dp))
                    LazyColumn(state = dayState ?: allState,
                        modifier = Modifier.fillMaxSize().testTag("post-timeline")) {
                        items(state.visiblePosts, key = { it.id }) { post ->
                            PostRow(post, state.zone, busy, { onEdit(post) }, { onDelete(post) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostRow(post: Post, zone: ZoneId, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("post-${post.id}")) {
        Text(post.body, style = MaterialTheme.typography.bodyLarge)
        MoodBadge(post.moodId)
        Text(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(zone).format(Instant.ofEpochMilli(post.createdAt)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val attributes = listOfNotNull(post.placeName?.let { "📍$it" }, weatherLabel(post.weatherCode),
            post.temperatureC?.let { String.format(Locale.getDefault(), "%.1f°C", it) })
        if (attributes.isNotEmpty()) Text(attributes.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = onEdit, enabled = !busy) { Text("編集") }
            TextButton(onClick = onDelete, enabled = !busy) { Text("削除") }
        }
    }
}
