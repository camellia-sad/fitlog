package dev.lzk.fitlog.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.EquipmentType
import dev.lzk.fitlog.domain.Muscle
import java.time.Duration

/** Quiet container: soft corners and a hairline, never a shadow. Pixel treatment is reserved for controls. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = color, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun OneLineText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified) {
    val size = if (style.fontSize.isSp) style.fontSize else 16.sp
    BasicText(text, modifier, style.copy(color = if (color == Color.Unspecified) LocalContentColor.current else color),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = size * .75f, maxFontSize = size, stepSize = .5.sp))
}

/** Square progress cells; a newly lit cell pops in 2 frames. Long plans fold into at most 12 cells. */
@Composable
fun PixelProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val segments = total.coerceIn(1, 12)
    val filledCount = if (total <= 0) 0 else (0 until segments).count { it.toFloat() / segments < done.toFloat() / total }
    var shown by remember { mutableStateOf(filledCount) }
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    var popIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(filledCount) {
        if (filledCount > shown) {
            popIndex = filledCount - 1
            pop.snapTo(.3f); pop.animateTo(1f, tween(160, easing = StepEasing(2)))
        }
        shown = filledCount
    }
    Row(modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(segments) { index ->
            val color = if (index < filledCount) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
            Box(Modifier.weight(1f).fillMaxHeight()
                .graphicsLayer { if (index == popIndex) scaleY = pop.value }
                .background(color, RoundedCornerShape(1.dp)))
        }
    }
}

fun EquipmentType.label(): String = when (this) {
    EquipmentType.MACHINE -> "固定器械"
    EquipmentType.DUMBBELL -> "哑铃"
    EquipmentType.BARBELL -> "杠铃"
    EquipmentType.SMITH -> "史密斯机"
    EquipmentType.CABLE -> "绳索"
    EquipmentType.BODYWEIGHT -> "徒手"
}

fun Muscle.label(): String = when (this) {
    Muscle.CHEST -> "胸部"; Muscle.BACK -> "背部"; Muscle.SHOULDERS -> "肩部"
    Muscle.QUADRICEPS -> "股四头肌"; Muscle.HAMSTRINGS -> "腘绳肌"; Muscle.GLUTES -> "臀部"
    Muscle.CALVES -> "小腿"; Muscle.BICEPS -> "肱二头肌"; Muscle.TRICEPS -> "肱三头肌"; Muscle.CORE -> "核心"
}

fun Duration.timerText(): String {
    val value = seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(value / 3600, value / 60 % 60, value % 60)
}

fun Duration.display(): String {
    val minutes = seconds.coerceAtLeast(0) / 60
    return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
}
