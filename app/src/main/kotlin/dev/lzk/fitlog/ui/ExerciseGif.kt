@file:Suppress("DEPRECATION")
package dev.lzk.fitlog.ui

import android.graphics.Movie
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*

internal object ExerciseAnimations {
    // The packaged filenames are stable exercise IDs; custom exercises never inherit an unrelated GIF.
    fun has(id:String)=id in packaged
    val packaged=setOf(
        "barbell-bench-press",
        "barbell-row",
        "chest-supported-row",
        "crunch",
        "dumbbell-bench-press",
        "dumbbell-curl",
        "dumbbell-lateral-raise",
        "dumbbell-lunge",
        "dumbbell-row",
        "dumbbell-shoulder-press",
        "hammer-curl",
        "incline-dumbbell-press",
        "leg-curl",
        "leg-extension",
        "machine-chest-press",
        "machine-row",
        "machine-shoulder-press",
        "pec-deck",
        "pull-up",
        "push-up",
        "rear-delt-fly",
        "reverse-crunch",
        "seated-cable-row",
        "smith-bench-press",
        "smith-squat",
        "split-squat", "seated-calf-raise"
    )
}

@Composable internal fun ExerciseGif(id:String,name:String,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val movie by produceState<Movie?>(null,id) {
        value=withContext(Dispatchers.IO) {
            context.assets.open("exercise-gifs/"+id+".gif").use {Movie.decodeStream(it)}
        }
    }
    var time by remember(id){mutableIntStateOf(0)}
    LaunchedEffect(movie,lifecycle) {
        val animation=movie ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            time=0
            val scale=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
            if(scale<=0f)return@repeatOnLifecycle
            val start=SystemClock.uptimeMillis()
            while(isActive) {
                time=(((SystemClock.uptimeMillis()-start)/scale).toLong()%animation.duration().coerceAtLeast(1)).toInt()
                delay(33)
            }
        }
    }
    Canvas(modifier.semantics {contentDescription=name+"动作演示"}) {
        movie?.let {animation->
            val factor=minOf(size.width/animation.width(),size.height/animation.height())
            drawIntoCanvas {canvas->
                val native=canvas.nativeCanvas
                native.save()
                native.translate((size.width-animation.width()*factor)/2,(size.height-animation.height()*factor)/2)
                native.scale(factor,factor)
                animation.setTime(time)
                animation.draw(native,0f,0f)
                native.restore()
            }
        }
    }
}
