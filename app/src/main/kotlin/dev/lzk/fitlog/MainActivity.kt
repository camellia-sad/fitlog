package dev.lzk.fitlog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.training.WorkoutTimerService
import dev.lzk.fitlog.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationOpens = mutableIntStateOf(0)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) Toast.makeText(this, "允许训练通知后，才能在通知栏查看计时", Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(WorkoutTimerService.OPEN_WORKOUT, false)) {
            notificationOpens.intValue++
            intent.removeExtra(WorkoutTimerService.OPEN_WORKOUT)
        }
        enableEdgeToEdge()
        val container = (application as FitLogApplication).container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.ready.await()
                var displayedId: String? = null
                container.workouts.observeActiveWorkout().collect { workout ->
                    if (workout != null && displayedId != workout.session.id) WorkoutTimerService.start(this@MainActivity)
                    displayedId = workout?.session?.id
                }
            }
        }
        setContent {
            val preferences by container.preferences.preferences.collectAsStateWithLifecycle(initialValue = UserPreferences())
            val dark = when (preferences.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FitLogTheme(dark) {
                FitLogApp(container, notificationOpens.intValue) {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        (application as FitLogApplication).container.sharing.foreground.value = true
    }
    override fun onStop() {
        (application as FitLogApplication).container.sharing.foreground.value = false
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(WorkoutTimerService.OPEN_WORKOUT, false)) {
            notificationOpens.intValue++
            intent.removeExtra(WorkoutTimerService.OPEN_WORKOUT)
        }
    }
}
