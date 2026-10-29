package com.yantkys.memonow

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@Composable
internal fun AppActions(calendarEnabled: Boolean, writing: Boolean, busy: Boolean,
                        onCalendar: () -> Unit, onBackup: () -> Unit, onSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = onCalendar, enabled = calendarEnabled, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.DateRange, contentDescription = "カレンダー")
    }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !writing, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "メニュー")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("バックアップ") }, enabled = !writing,
                onClick = { expanded = false; onBackup() })
            DropdownMenuItem(text = { Text("設定") }, enabled = !busy,
                onClick = { expanded = false; onSettings() })
        }
    }
}

@Composable
internal fun CalendarDialog(counts: Map<LocalDate, Int>, selectedDate: LocalDate?, zone: ZoneId,
                            onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now(zone)
    val initialMonth = YearMonth.from(selectedDate ?: today)
    var monthText by rememberSaveable(zone.id) { mutableStateOf(initialMonth.toString()) }
    val month = YearMonth.parse(monthText)
    val latest = remember(counts) { counts.keys.maxOrNull()?.let(YearMonth::from) }
    val scroll = rememberScrollState()
    LaunchedEffect(monthText) { scroll.scrollTo(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(4.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(8.dp)) {
                Text("カレンダー", Modifier.padding(8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                    CalendarMonth(month, counts, selectedDate,
                        onMonth = { monthText = it.toString() }, onSelect = onSelect)
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { monthText = YearMonth.now(zone).toString() }) { Text("今月") }
                    if (latest != null) TextButton(onClick = { monthText = latest.toString() }) { Text("最新の記録") }
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                }
            }
        }
    }
}

@Composable
internal fun CalendarMonth(month: YearMonth, counts: Map<LocalDate, Int>, selectedDate: LocalDate?,
                           onMonth: (YearMonth) -> Unit, onSelect: (LocalDate) -> Unit) {
    val cells = remember(month) { monthCells(month) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        val previous = shiftMonth(month, -1)
        IconButton(onClick = { previous?.let(onMonth) }, enabled = previous != null, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前の月")
        }
        Text("${month.year}年${month.monthValue}月", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        val next = shiftMonth(month, 1)
        IconButton(onClick = { next?.let(onMonth) }, enabled = next != null, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "次の月")
        }
    }
    Row(Modifier.fillMaxWidth()) {
        for (weekday in listOf("日", "月", "火", "水", "木", "金", "土"))
            Box(Modifier.weight(1f).height(24.dp), contentAlignment = Alignment.Center) { Text(weekday) }
    }
    for (week in cells.chunked(7)) {
        Row(Modifier.fillMaxWidth()) {
            for (date in week) {
                if (date == null) Spacer(Modifier.weight(1f).height(48.dp))
                else {
                    val count = counts.getOrDefault(date, 0)
                    val selected = selectedDate == date
                    Surface(selected = selected, onClick = { if (count > 0) onSelect(date) }, enabled = count > 0,
                        modifier = Modifier.weight(1f).height(48.dp).semantics {
                            contentDescription = "${date.year}年${date.monthValue}月${date.dayOfMonth}日、投稿${count}件"
                        }, shape = MaterialTheme.shapes.small,
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.fillMaxSize().clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Text(date.dayOfMonth.toString(), fontWeight = if (count > 0) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (count > 0) 1f else .38f))
                            if (count > 0) Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        }
                    }
                }
            }
        }
    }
    if (cells.none { it != null && counts.getOrDefault(it, 0) > 0 }) {
        Text("この月の投稿はありません。", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
    }
}
