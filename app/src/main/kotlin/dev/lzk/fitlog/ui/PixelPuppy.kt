package dev.lzk.fitlog.ui

import android.content.res.Resources
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lzk.fitlog.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

enum class PuppyPose(val description: String, internal val cycles: Int, internal val frameIds: IntArray) {
    IDLE("待机的小狗", 2, intArrayOf(R.drawable.puppy_idle_0, R.drawable.puppy_idle_1, R.drawable.puppy_idle_2, R.drawable.puppy_idle_3)),
    LIFT("举哑铃的小狗", Int.MAX_VALUE, intArrayOf(R.drawable.puppy_lift_0, R.drawable.puppy_lift_1, R.drawable.puppy_lift_2, R.drawable.puppy_lift_3)),
    REST("喝水休息的小狗", 2, intArrayOf(R.drawable.puppy_rest_0, R.drawable.puppy_rest_1, R.drawable.puppy_rest_2, R.drawable.puppy_rest_3)),
    CELEBRATE("庆祝完成的小狗", 2, intArrayOf(R.drawable.puppy_celebrate_0, R.drawable.puppy_celebrate_1, R.drawable.puppy_celebrate_2, R.drawable.puppy_celebrate_3)),
    SWEAT("努力训练的小狗", 1, intArrayOf(R.drawable.puppy_sweat_0, R.drawable.puppy_sweat_1, R.drawable.puppy_sweat_2, R.drawable.puppy_sweat_3)),
    SLEEP("安心休息的小狗", 2, intArrayOf(R.drawable.puppy_sleep_0, R.drawable.puppy_sleep_1, R.drawable.puppy_sleep_2, R.drawable.puppy_sleep_3));
}

private object PuppyFrames {
    private val cache = mutableMapOf<Int, ImageBitmap>()
    fun load(resources: Resources, id: Int): ImageBitmap =
        cache.getOrPut(id) { ImageBitmap.imageResource(resources, id) }
}

// Original package animation order: 0, 1, 2, 3, 2, 1, with 140 ms per frame.
private val puppySequence = intArrayOf(0, 1, 2, 3, 2, 1)

@Composable
fun PixelPuppy(modifier: Modifier = Modifier, pose: PuppyPose = PuppyPose.IDLE) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val images = remember(context.resources, pose) { pose.frameIds.map { PuppyFrames.load(context.resources, it) } }
    var frame by remember(pose) { mutableIntStateOf(0) }
    LaunchedEffect(pose, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            frame = 0
            val motion = currentCoroutineContext()[MotionDurationScale]
            val fallbackScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            repeat(pose.cycles) {
                for (index in puppySequence) {
                    val scale = motion?.scaleFactor ?: fallbackScale
                    if (scale <= 0f) { frame = 0; return@repeatOnLifecycle }
                    frame = index
                    delay((140L * scale).toLong().coerceAtLeast(1))
                }
            }
            frame = 0
        }
    }
    val image = images[frame]
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = pose.description }) {
        // The shared viewport removes transparent canvas padding while keeping every pose aligned.
        drawImage(image, srcOffset = IntOffset(64, 64), srcSize = IntSize(192, 192),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.None)
    }
}
