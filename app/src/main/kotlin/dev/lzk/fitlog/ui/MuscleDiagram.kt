package dev.lzk.fitlog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.Muscle

@Composable
fun MuscleDiagram(primary: Set<Muscle>, secondary: Set<Muscle>) {
    val neutral = MaterialTheme.colorScheme.outlineVariant
    val main = MaterialTheme.colorScheme.primary
    val supporting = Color(0xFFB0A4DB)
    Column {
        Canvas(Modifier.fillMaxWidth().height(200.dp).semanticsLabel("主要肌群：${primary.joinToString { it.label() }}；次要肌群：${secondary.joinToString { it.label() }}")) {
            val factor = minOf(size.width / 100, size.height / 70)
            scale(factor, factor, pivot = Offset.Zero) {
                for (back in listOf(false, true)) {
                    val x = if (back) 65f else 22f
                    fun patch(muscle: Muscle, px: Float, py: Float, w: Float, h: Float) {
                        val color = if (muscle in primary) main else if (muscle in secondary) supporting else neutral
                        drawRoundRect(color, Offset(x + px, py), Size(w, h), androidx.compose.ui.geometry.CornerRadius(1.5f))
                    }
                    drawCircle(neutral, 4.5f, Offset(x + 8, 7f))
                    drawRoundRect(neutral, Offset(x, 14f), Size(16f, 28f), androidx.compose.ui.geometry.CornerRadius(5f))
                    drawRoundRect(neutral, Offset(x - 7, 16f), Size(6f, 27f), androidx.compose.ui.geometry.CornerRadius(3f))
                    drawRoundRect(neutral, Offset(x + 17, 16f), Size(6f, 27f), androidx.compose.ui.geometry.CornerRadius(3f))
                    for (leg in listOf(0f, 9f)) drawRoundRect(neutral, Offset(x + leg, 40f), Size(7f, 27f), androidx.compose.ui.geometry.CornerRadius(3f))
                    patch(Muscle.SHOULDERS, -5f, 16f, 6f, 6f); patch(Muscle.SHOULDERS, 15f, 16f, 6f, 6f)
                    if (back) {
                        patch(Muscle.BACK, 1f, 18f, 14f, 16f)
                        patch(Muscle.GLUTES, 0f, 36f, 7f, 7f); patch(Muscle.GLUTES, 9f, 36f, 7f, 7f)
                        patch(Muscle.HAMSTRINGS, 1f, 44f, 5f, 11f); patch(Muscle.HAMSTRINGS, 10f, 44f, 5f, 11f)
                        patch(Muscle.TRICEPS, -6f, 23f, 4f, 9f); patch(Muscle.TRICEPS, 18f, 23f, 4f, 9f)
                    } else {
                        patch(Muscle.CHEST, 1f, 19f, 6f, 7f); patch(Muscle.CHEST, 9f, 19f, 6f, 7f)
                        patch(Muscle.CORE, 3f, 28f, 10f, 11f)
                        patch(Muscle.QUADRICEPS, 1f, 43f, 5f, 12f); patch(Muscle.QUADRICEPS, 10f, 43f, 5f, 12f)
                        patch(Muscle.BICEPS, -6f, 23f, 4f, 9f); patch(Muscle.BICEPS, 18f, 23f, 4f, 9f)
                    }
                    patch(Muscle.CALVES, 1f, 57f, 5f, 8f); patch(Muscle.CALVES, 10f, 57f, 5f, 8f)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("正面", style = MaterialTheme.typography.labelMedium)
            Text("背面", style = MaterialTheme.typography.labelMedium)
        }
        Text("青绿：主要肌群  ·  浅紫：次要肌群（位置示意）", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}
