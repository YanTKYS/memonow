package com.yantkys.memonow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yantkys.memonow.data.Mood

@Composable
internal fun MoodPicker(selectedId: String?, enabled: Boolean, onToggle: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // Normally one 48dp row including the heading. Only the heading wraps on
        // narrower screens / larger text; each emoji keeps its full touch target.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("気分（任意）", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (mood in Mood.selectable) {
                    val selected = selectedId == mood.id
                    OutlinedIconToggleButton(
                        checked = selected,
                        onCheckedChange = { onToggle(mood.id) },
                        enabled = enabled,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = mood.accessibilityName
                            stateDescription = if (selected) "選択中" else "未選択"
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(mood.emoji, Modifier.clearAndSetSemantics {}, fontSize = 22.sp)
                            if (selected) Text("✓", Modifier.align(Alignment.TopEnd).padding(end = 3.dp)
                                .clearAndSetSemantics {}, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        val existing = Mood.find(selectedId)
        if (existing != null && existing !in Mood.selectable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ 以前の気分：${existing.emoji}", Modifier.clearAndSetSemantics {
                    contentDescription = "以前の気分：${existing.accessibilityName}"
                    stateDescription = "選択中"
                }, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onClear, enabled = enabled) { Text("解除") }
            }
        } else if (selectedId != null && existing == null) {
            TextButton(onClick = onClear, enabled = enabled) { Text("未対応の気分を解除") }
        }
    }
}

@Composable
internal fun MoodBadge(id: String?) {
    if (id != null) {
        val mood = Mood.find(id)
        Text(mood?.emoji ?: "気分：未対応", Modifier.clearAndSetSemantics {
            contentDescription = mood?.let { "気分：${it.accessibilityName}" } ?: "気分：未対応"
        }, style = MaterialTheme.typography.bodyLarge)
    }
}
