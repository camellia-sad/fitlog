package dev.lzk.fitlog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

/**
 * Month grid in a bottom sheet, replacing the system date dialog. History fills each day by its completed sets
 * ([sets], same scale as the year grid) and greys out the future past [latest]; the plan editor writes the short
 * name a day would get ([labels]). Today is outlined orange; the chosen day becomes a hard-shadow key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(title: String, initial: LocalDate, onDismiss: () -> Unit, latest: LocalDate? = null,
    sets: Map<LocalDate, Int> = emptyMap(), labels: (LocalDate, LocalDate) -> String? = { _, _ -> null }, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(initial).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(initial.toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)
    val scheme = MaterialTheme.colorScheme
    val haptics = fitHaptics()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = scheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 18.dp).testTag("date-sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${month.year}·${month.monthValue.toString().padStart(2, '0')}", style = pixelNumber(34.sp), color = scheme.onSurface,
                        modifier = Modifier.testTag("date-month").semantics { contentDescription = "${month.year}年${month.monthValue}月" })
                    Text(title, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = scheme.onSurfaceVariant)
                }
                PixelOutlineKey(onClick = { monthText = month.minusMonths(1).toString() }, minHeight = 34.dp, minWidth = 34.dp, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.semantics { contentDescription = "上个月" }) { LineIcon(Symbol.BACK, Modifier.size(12.dp), scheme.onSurface) }
                PixelOutlineKey(onClick = { monthText = month.plusMonths(1).toString() }, enabled = latest == null || month < YearMonth.from(latest),
                    minHeight = 34.dp, minWidth = 34.dp, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.semantics { contentDescription = "下个月" }) { LineIcon(Symbol.ARROW, Modifier.size(12.dp), scheme.onSurface) }
            }
            Row(Modifier.fillMaxWidth()) {
                "一二三四五六日".forEach { Text(it.toString(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
            val lead = month.atDay(1).dayOfWeek.value - 1
            val days = month.lengthOfMonth()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (0 until (lead + days + 6) / 7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(7) { column ->
                            val day = week * 7 + column - lead + 1
                            Box(Modifier.weight(1f).aspectRatio(1f)) {
                                if (day in 1..days) {
                                    val date = month.atDay(day)
                                    DayCell(date, date == selected, date == today, latest != null && date > latest, sets[date] ?: 0, labels(date, selected)) {
                                        haptics.tick(); selectedText = date.toString()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelOutlineKey(onClick = { selectedText = today.toString(); monthText = YearMonth.from(today).toString() }, minHeight = 36.dp) { Text("今天", maxLines = 1) }
                if (latest != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("少", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    listOf(1, 4, 9, 16).forEach { Box(Modifier.size(10.dp).background(trainingColor(it), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))) }
                    Text("多", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                PixelKey(onClick = { onPick(selected) }, minHeight = 40.dp, depth = 4.dp, modifier = Modifier.testTag("date-confirm")) {
                    LineIcon(Symbol.CHECK, Modifier.size(13.dp), LocalContentColor.current); Text("确定", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, today: Boolean, out: Boolean, sets: Int, label: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val orange = fitColors.orange
    val stamp = remember { Animatable(1f) }
    LaunchedEffect(selected) { if (selected) { stamp.snapTo(1.3f); stamp.animateTo(1f, tween(150, easing = StepEasing(3))) } }
    val fill = if (out || sets == 0) Color.Transparent else trainingColor(sets)
    val ink = when { out -> scheme.onSurfaceVariant.copy(alpha = .4f); selected -> scheme.primary; sets > 8 -> scheme.onPrimary; else -> scheme.onSurface }
    val primary = scheme.primary
    Box(Modifier.fillMaxSize().graphicsLayer { scaleX = stamp.value; scaleY = stamp.value }
        .then(if (selected) Modifier.drawBehind { drawRect(primary, Offset(0f, size.height - 4.dp.toPx()), Size(size.width, 4.dp.toPx())) }
            .padding(bottom = 4.dp).background(scheme.surface, PixelShapeSmall).border(2.dp, primary, PixelShapeSmall)
            else Modifier.background(fill, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
        .then(if (today && !selected) Modifier.border(2.dp, orange, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)) else Modifier)
        .clickable(enabled = !out, onClick = onClick)
        .testTag("date-$date")
        .semantics { this.selected = selected; contentDescription = "${date.monthValue}月${date.dayOfMonth}日" + if (today) "（今天）" else "" },
        contentAlignment = Alignment.Center) {
        Text("${date.dayOfMonth}", style = pixelNumber(19.sp), color = ink)
        label?.let {
            Text(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp), style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp,
                fontWeight = FontWeight.Black), color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
