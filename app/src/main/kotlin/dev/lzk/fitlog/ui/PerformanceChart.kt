package dev.lzk.fitlog.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

@Composable
fun PerformanceChart(points: List<PerformancePoint>, unit: WeightUnit,
    metric: RecordMetric = points.lastOrNull()?.recordMetric ?: RecordMetric.REPETITIONS) {
    val timed = metric == RecordMetric.SECONDS
    var repetitions by remember(metric) { mutableStateOf(timed) }
    val daily = remember(points, metric, repetitions) { dailyPerformance(points, metric, repetitions) }
    val bounds = remember(daily.map { it.date }) { HistoryViewport.forDates(daily.map { it.date }, LocalDate.now()) }
    var viewport by remember(bounds) { mutableStateOf(bounds) }
    val values = remember(daily, unit, repetitions) { daily.map {
        if(repetitions) it.reps.toDouble() else it.weightGrams.toDouble() / if(unit == WeightUnit.KG) 1000.0 else 453.59237
    } }
    SoftCard {
        if(!timed) ChoiceChips(listOf(false, true), { it == repetitions }, { if(it) "次数" else "重量" }) { repetitions = it }
        if(daily.isEmpty()) Text("还没有这个动作的已结束训练记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Text(if(timed) "每日最长保持时间（秒）" else if(repetitions) "每日最高次数" else "每日最高重量（${unit.label}）",
                style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全部" to 0, "近 3 月" to 90, "近 1 月" to 30).forEach { (label, days) ->
                    OutlinedButton(onClick = { viewport = if(days == 0) bounds else bounds.recent(days) },
                        modifier = Modifier.weight(1f).testTag("trend-range-$days"), contentPadding = PaddingValues(6.dp)) { OneLineText(label) }
                }
            }
            val description = "${viewport.fromDate} 至 ${viewport.toDate}"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewport = viewport.transform(1 / 1.5, 0.0) }, enabled = viewport.span < bounds.span,
                    modifier = Modifier.semanticsLabel("缩小日期曲线").testTag("trend-zoom-out")) { Text("−", style = MaterialTheme.typography.titleLarge) }
                OneLineText(description, Modifier.weight(1f).testTag("trend-visible-dates"), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { viewport = viewport.transform(1.5, 0.0) }, enabled = viewport.span > minOf(7.0, bounds.span),
                    modifier = Modifier.semanticsLabel("放大日期曲线").testTag("trend-zoom-in")) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
            val density = LocalDensity.current
            val foreground = MaterialTheme.colorScheme.onSurfaceVariant
            val line = MaterialTheme.colorScheme.primary
            val grid = MaterialTheme.colorScheme.outlineVariant
            val paint = remember(density, foreground) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 11.sp.toPx() }; color = foreground.toArgb()
            } }
            val maximum = maxOf(values.maxOrNull() ?: 0.0, 1.0)
            val step = 10.0.pow(floor(log10(maximum)) - 1)
            val yMax = ceil(maximum * 1.1 / step) * step
            val left = maxOf(with(density) { 36.dp.toPx() }, (0..4).maxOf { paint.measureText(chartNumber(yMax * it / 4)) } + with(density) { 8.dp.toPx() })
            val right = with(density) { 12.dp.toPx() }
            Canvas(Modifier.fillMaxWidth().height(240.dp).testTag("performance-chart")
                .semantics { stateDescription = description }
                .semanticsLabel("${daily.size} 天的真实日期曲线；双指缩放，左右拖动")
                .pointerInput(bounds, left, right) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var movement = Offset.Zero
                        var transforming = false
                        do {
                            val event = awaitPointerEvent()
                            if(event.changes.any { it.isConsumed }) break
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            movement += pan
                            if((event.changes.count { it.pressed } > 1 && abs(zoom - 1f) > .001f) ||
                                (abs(movement.x) > viewConfiguration.touchSlop && abs(movement.x) > abs(movement.y))) transforming = true
                            if(transforming) {
                                val width = (size.width - left - right).coerceAtLeast(1f)
                                val anchor = (event.calculateCentroid(useCurrent = false).x - left) / width
                                viewport = viewport.transform(zoom.toDouble(), (pan.x / width).toDouble(), anchor.toDouble())
                                event.changes.forEach { if(it.positionChanged()) it.consume() }
                            }
                        } while(event.changes.any { it.pressed })
                    }
                }) {
                val plotLeft = left.coerceAtMost(size.width * .4f)
                val plotRight = size.width - right
                val top = 12.dp.toPx()
                val bottom = size.height - maxOf(28.dp.toPx(), paint.textSize + 12.dp.toPx())
                val width = (plotRight - plotLeft).coerceAtLeast(1f)
                val height = (bottom - top).coerceAtLeast(1f)
                fun x(day: Double) = (plotLeft + (day - viewport.start) / viewport.span * width).toFloat()
                fun y(value: Double) = (bottom - value / yMax * height).toFloat()
                repeat(5) { tick ->
                    val value = yMax * tick / 4
                    val at = y(value)
                    drawLine(grid, Offset(plotLeft, at), Offset(plotRight, at), 1.dp.toPx())
                    drawIntoCanvas { it.nativeCanvas.drawText(chartNumber(value), plotLeft - 6.dp.toPx() - paint.measureText(chartNumber(value)),
                        at + paint.textSize / 3, paint) }
                }
                clipRect(plotLeft, top, plotRight, bottom + 4.dp.toPx()) {
                    val path = Path()
                    daily.forEachIndexed { index, point ->
                        val at = Offset(x(point.date.toEpochDay().toDouble()), y(values[index]))
                        if(index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                        if(at.x in plotLeft..plotRight) drawCircle(line, 3.dp.toPx(), at)
                    }
                    drawPath(path, line, style = Stroke(2.dp.toPx()))
                }
                val formatter = DateTimeFormatter.ofPattern(if(viewport.span > 180) "yy/M" else "M/d")
                val ticks = if(width < 220.dp.toPx()) 2 else 3
                repeat(ticks + 1) { tick ->
                    val day = viewport.start + viewport.span * tick / ticks
                    val label = LocalDate.ofEpochDay(day.roundToLong()).format(formatter)
                    val labelX = (x(day) - paint.measureText(label) / 2).coerceIn(plotLeft, maxOf(plotLeft, plotRight - paint.measureText(label)))
                    drawIntoCanvas { it.nativeCanvas.drawText(label, labelX, bottom + paint.textSize + 8.dp.toPx(), paint) }
                }
            }
            val visible = points.filter { it.recordMetric == metric && it.date >= viewport.fromDate && it.date <= viewport.toDate }
            if(visible.isEmpty()) Text("这个时间范围没有完成记录。", style = MaterialTheme.typography.bodySmall)
            visible.takeLast(15).reversed().forEach { point ->
                Text("${point.date} · " + if(timed) "${point.reps} 秒" else "${WeightUnits.format(point.weightGrams, unit)} ${unit.label} × ${point.reps}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if(visible.size > 15) Text("仅列出最近 15 组", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun chartNumber(value: Double): String =
    if(value >= 1_000_000) String.format(Locale.ROOT, "%.1e", value)
    else String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
