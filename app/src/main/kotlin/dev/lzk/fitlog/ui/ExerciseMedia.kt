package dev.lzk.fitlog.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.EquipmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun EquipmentType.symbol(): Symbol = when (this) {
    EquipmentType.MACHINE -> Symbol.MACHINE; EquipmentType.DUMBBELL -> Symbol.DUMBBELL; EquipmentType.BARBELL -> Symbol.BARBELL
    EquipmentType.SMITH -> Symbol.SMITH; EquipmentType.CABLE -> Symbol.CABLE; EquipmentType.BODYWEIGHT -> Symbol.BODYWEIGHT
}

/**
 * Photos the user picked for custom exercises, kept as square JPEGs in app-private storage and keyed by
 * exercise ID. They stay on this device only: JSON backups keep exercise records but not these files.
 */
object ExercisePhotos {
    private const val SIDE = 480
    private val versions = mutableStateMapOf<String, Int>()

    private fun file(context: Context, id: String) =
        File(File(context.filesDir, "exercise-photos"), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    fun version(id: String): Int = versions[id] ?: 0
    fun exists(context: Context, id: String) = file(context, id).isFile

    /** Centre-crops [jpeg] to a square, scales it to 480 px and replaces any earlier photo atomically. */
    suspend fun save(context: Context, id: String, jpeg: ByteArray) {
        withContext(Dispatchers.IO) {
            val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("无法读取这张图片")
            val side = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            val scaled = if (side > SIDE) square.scale(SIDE, SIDE) else square
            val target = file(context, id).also { it.parentFile?.mkdirs() }
            val temp = File(target.parentFile, target.name + ".tmp")
            try {
                temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (!temp.renameTo(target)) { target.delete(); check(temp.renameTo(target)) { "图片保存失败" } }
            } finally {
                temp.delete()
                listOf(source, square, scaled).distinct().forEach { it.recycle() }
            }
        }
        versions[id] = version(id) + 1
    }

    suspend fun remove(context: Context, id: String) {
        withContext(Dispatchers.IO) { file(context, id).delete() }
        versions[id] = version(id) + 1
    }

    @Composable
    fun rememberPhoto(id: String): ImageBitmap? {
        val context = LocalContext.current
        val version = version(id)
        val photo by produceState<ImageBitmap?>(null, id, version) {
            value = withContext(Dispatchers.IO) {
                file(context, id).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }
        }
        return photo
    }
}

/**
 * Picture for any exercise: the bundled GIF when there is one, otherwise the user's photo, otherwise a pixel
 * drawing of its equipment on the group tint. All three carry the group's corner tab.
 * With [fallback] off, nothing is drawn when the exercise has neither GIF nor photo.
 */
@Composable
fun ExerciseThumb(id: String, name: String, group: MuscleGroup?, equipment: EquipmentType?, modifier: Modifier = Modifier,
    corner: Dp = 14.dp, fallback: Boolean = true) {
    if (ExerciseAnimations.has(id)) { ExerciseStage(id, name, group, modifier, corner); return }
    val photo = ExercisePhotos.rememberPhoto(id)
    val shape = RoundedCornerShape(corner)
    when {
        photo != null -> Box(modifier.clip(shape).background(group?.tint() ?: androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)) {
            Image(photo, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            group?.let { GroupCornerTab(it.color(), corner / 3 + 2.dp) }
        }
        fallback -> DefaultExerciseArt(name, group, equipment, corner, modifier.clip(shape))
        else -> {}
    }
}

/** Default art for custom exercises: the equipment glyph, large and pixel-sharp, over a faint dot lattice. */
@Composable
private fun DefaultExerciseArt(name: String, group: MuscleGroup?, equipment: EquipmentType?, corner: Dp, modifier: Modifier) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val paper = group?.tint() ?: scheme.surfaceVariant
    val ink = group?.color() ?: scheme.outline
    Box(modifier.background(paper).semanticsLabel(name)) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 6.dp.toPx()
            val dot = 1.dp.toPx()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) { drawRect(ink.copy(alpha = .22f), Offset(x, y), androidx.compose.ui.geometry.Size(dot, dot)); x += step }
                y += step
            }
        }
        LineIcon(equipment?.symbol() ?: Symbol.DUMBBELL, Modifier.fillMaxSize(.42f).align(androidx.compose.ui.Alignment.Center), ink)
        group?.let { GroupCornerTab(it.color(), corner / 3 + 2.dp) }
    }
}
