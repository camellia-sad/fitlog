package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.CancellationException
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * A friend's shared training laid out like one's own history page: year switch, the year grid coloured by completed
 * sets, four year figures, the chosen date and a card per session. Shared records carry no per-set detail, so cards
 * list exercise names instead of weight × reps.
 */
@Composable
internal fun SharedHistoryView(repository: SocialRepository, owner: String, sharing: Boolean?) {
    val scheme = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var history by remember { mutableStateOf<List<SharedWorkout>>(emptyList()) }
    var exhausted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Pages arrive newest first; keep reading until the year in view is covered (bounded so a long history stays cheap).
    LaunchedEffect(owner, year) {
        loading = true
        try {
            var pages = 0
            while (!exhausted && pages < 30 && (history.lastOrNull()?.startedAt?.atZone(zone)?.year ?: Int.MAX_VALUE) >= year) {
                val last = history.lastOrNull()
                val page = repository.sharedHistory(owner, last?.let { SocialCursor(it.startedAt, it.id) })
                history = (history + page).distinctBy { it.id }
                exhausted = page.size < 20; pages++
            }
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "加载失败，请重试" }
        finally { loading = false }
    }
    val inYear = remember(history, year) { history.filter { it.startedAt.atZone(zone).year == year } }
    val days = remember(inYear) {
        inYear.filter { it.completedSets > 0 }.groupBy { it.startedAt.atZone(zone).toLocalDate() }
            .mapValues { (date, list) -> TrainingDay(date, list.sumOf { it.completedSets }, list.size) }
    }
    val statistics = remember(inYear) {
        val done = inYear.filter { it.completedSets > 0 }
        TrainingStatistics(days.size, done.size, done.sumOf { it.completedSets }, done.sumOf { it.durationSeconds }, BigInteger.ZERO)
    }
    val today = remember(selected, history) { history.filter { it.startedAt.atZone(zone).toLocalDate() == selected } }
    LazyColumn(Modifier.fillMaxSize().testTag("shared-history"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (sharing == false) item {
            Text("未开启训练历史共享", Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center, color = scheme.onSurfaceVariant)
        } else {
            error?.let { item { Text(it, color = scheme.error) } }
            item {
                SoftCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { year -= 1 }, modifier = Modifier.semanticsLabel("上一年")) { LineIcon(Symbol.BACK, Modifier.size(16.dp), scheme.primary) }
                        Text("$year", Modifier.weight(1f), style = pixelNumber(22.sp, FontWeight.Bold), textAlign = TextAlign.Center)
                        val canNext = year < LocalDate.now().year
                        IconButton(onClick = { year += 1 }, enabled = canNext, modifier = Modifier.semanticsLabel("下一年")) {
                            LineIcon(Symbol.ARROW, Modifier.size(16.dp), if (canNext) scheme.primary else scheme.outline)
                        }
                    }
                    TrainingCalendar(year, days, selected) { selected = it }
                    TrainingColorLegend()
                }
            }
            item { YearFigures(statistics, WeightUnit.KG, showVolume = false) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${selected.monthValue.toString().padStart(2, '0')}·${selected.dayOfMonth.toString().padStart(2, '0')}", style = pixelNumber(44.sp),
                        color = scheme.onSurface, modifier = Modifier.semantics { contentDescription = selected.toString() })
                    Text(selected.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE), Modifier.padding(bottom = 6.dp),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = scheme.onSurfaceVariant)
                }
            }
            if (loading && history.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else if (today.isEmpty()) item { Text("这一天没有共享训练", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
            items(today, key = { it.id }) { SharedSessionCard(it) }
        }
    }
}

@Composable
private fun SharedSessionCard(workout: SharedWorkout) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape).padding(12.dp).testTag("shared-session"),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(46.dp)) {
            Text(workout.startedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), style = pixelNumber(20.sp), color = scheme.onSurface)
            Text("开始", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(workout.label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val done = workout.status == "已完成"
                Text(workout.status, Modifier.background(if (done) scheme.primaryContainer else scheme.surfaceVariant, PixelShapeSmall).padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = if (done) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HistoryFigure("${workout.exercises.size}", "动作")
                HistoryFigure("${workout.completedSets}/${workout.totalSets}", "组")
                HistoryFigure("${workout.durationSeconds / 60}", "分钟")
            }
            if (workout.exercises.isNotEmpty()) Text(workout.exercises.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
