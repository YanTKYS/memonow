package com.yantkys.memonow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yantkys.memonow.data.Mood

@Composable
internal fun MoodPicker(selectedId: String?, enabled: Boolean, onToggle: (String) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("気分（任意）", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mood in Mood.entries) {
                FilterChip(
                    selected = selectedId == mood.id,
                    onClick = { onToggle(mood.id) },
                    enabled = enabled,
                    label = { Text(mood.display) },
                )
            }
        }
        if (selectedId != null && Mood.find(selectedId) == null) {
            TextButton(onClick = onClear, enabled = enabled) { Text("未対応の気分を解除") }
        }
    }
}

@Composable
internal fun MoodBadge(id: String?) {
    if (id != null) {
        Text(Mood.find(id)?.display ?: "気分：未対応", style = MaterialTheme.typography.labelLarge)
    }
}
