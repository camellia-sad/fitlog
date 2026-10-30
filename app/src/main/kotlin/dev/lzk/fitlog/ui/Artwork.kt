package dev.lzk.fitlog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale

enum class Symbol { HOME, DUMBBELL, BACK, PLUS, CHECK, SEARCH, ARROW, MINUS, CLOCK, LIST, SETTINGS, FRIENDS, PERSON }

@Composable
fun LineIcon(symbol: Symbol, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(x: Float, y: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x, y), Offset(x2, y2), strokeWidth = 1.9f, cap = StrokeCap.Round)
            when (symbol) {
                Symbol.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
                Symbol.MINUS -> line(5f, 12f, 19f, 12f)
                Symbol.CHECK -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 19f, 7f) }
                Symbol.BACK -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
                Symbol.ARROW -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
                Symbol.HOME -> {
                    val path = Path().apply { moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f); lineTo(21f, 21f); lineTo(15f, 21f); lineTo(15f, 14f); lineTo(9f, 14f); lineTo(9f, 21f); lineTo(3f, 21f); close() }
                    drawPath(path, color, style = Stroke(1.8f))
                }
                Symbol.DUMBBELL -> {
                    line(8f, 12f, 16f, 12f)
                    for (x in listOf(5f, 16f)) drawRoundRect(color, Offset(x, 5f), Size(3f, 14f), style = Stroke(1.8f))
                    line(2f, 9f, 2f, 15f); line(22f, 9f, 22f, 15f)
                }
                Symbol.SEARCH -> { drawCircle(color, 6.5f, Offset(10f, 10f), style = Stroke(1.8f)); line(15f, 15f, 21f, 21f) }
                Symbol.LIST -> {
                    drawRoundRect(color, Offset(5f, 3f), Size(14f, 18f), style = Stroke(1.8f))
                    for (y in listOf(8f, 12f, 16f)) line(9f, y, 15f, y)
                }
                Symbol.SETTINGS -> {
                    drawCircle(color, 6f, Offset(12f, 12f), style = Stroke(1.8f))
                    drawCircle(color, 2f, Offset(12f, 12f), style = Stroke(1.8f))
                    for (angle in 0 until 8) {
                        val a = angle * kotlin.math.PI / 4
                        line((12 + 6 * kotlin.math.cos(a)).toFloat(), (12 + 6 * kotlin.math.sin(a)).toFloat(),
                            (12 + 9 * kotlin.math.cos(a)).toFloat(), (12 + 9 * kotlin.math.sin(a)).toFloat())
                    }
                }
                Symbol.PERSON -> {
                    drawCircle(color,3.5f,Offset(12f,7f),style=Stroke(1.8f))
                    drawArc(color,180f,180f,false,Offset(4f,13f),Size(16f,14f),style=Stroke(1.8f))
                    line(4f,20f,20f,20f)
                }
                Symbol.FRIENDS -> {
                    drawCircle(color, 3f, Offset(9f, 7f), style = Stroke(1.8f))
                    drawArc(color, 180f, 180f, false, Offset(3f, 13f), Size(12f, 12f), style = Stroke(1.8f))
                    drawArc(color, -90f, 180f, false, Offset(15f, 4f), Size(5f, 6f), style = Stroke(1.8f))
                    drawArc(color, 210f, 120f, false, Offset(14f, 13f), Size(8f, 12f), style = Stroke(1.8f))
                }
                Symbol.CLOCK -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(1.8f))
                    line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f)
                }
            }
        }
    }
}
