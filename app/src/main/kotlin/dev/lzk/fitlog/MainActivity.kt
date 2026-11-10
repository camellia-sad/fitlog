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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import java.time.LocalTime
import kotlinx.coroutines.delay
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
    private val socialOpen = mutableStateOf<String?>(null)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) Toast.makeText(this, "允许训练通知后，才能在通知栏查看计时", Toast.LENGTH_LONG).show()
    }
    // Asked once on opening; a refusal is left alone (the system stops asking after the second one).
    private val launchPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val notificationsAllowed get() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun readOpen(intent: Intent) {
        if (intent.getBooleanExtra(WorkoutTimerService.OPEN_WORKOUT, false)) {
            notificationOpens.intValue++
            intent.removeExtra(WorkoutTimerService.OPEN_WORKOUT)
        }
        intent.getStringExtra(dev.lzk.fitlog.features.SocialNotifications.OPEN_SOCIAL)?.let {
            socialOpen.value = it
            intent.removeExtra(dev.lzk.fitlog.features.SocialNotifications.OPEN_SOCIAL)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readOpen(intent)
        if (savedInstanceState == null && !notificationsAllowed) launchPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            val systemDark = isSystemInDarkTheme()
            val dark = when (preferences.theme) {
                ThemeMode.TIME, ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val clock by produceState(LocalTime.now()) { while (true) { value = LocalTime.now(); delay(30_000) } }
            val livePeriod = DayPeriod.at(clock)
            var period by remember { mutableStateOf(livePeriod) }
            // A new period waits until the workout page is closed, so colours never change mid-set.
            LaunchedEffect(livePeriod, ThemeClock.holdForWorkout) { if (!ThemeClock.holdForWorkout) period = livePeriod }
            val timeTheme = preferences.theme == ThemeMode.TIME
            val barsDark = isFitThemeDark(dark, period, timeTheme)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !barsDark
                    isAppearanceLightNavigationBars = !barsDark
                }
            }
            FitLogTheme(dark, period, timeTheme) {
                FitLogApp(container, notificationOpens.intValue, socialOpen = socialOpen.value, onSocialOpened = { socialOpen.value = null }) {
                    if (!notificationsAllowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        (application as FitLogApplication).container.sharing.foreground.value = true
    }
    override fun onStop() {
        AvatarImages.clear()
        (application as FitLogApplication).container.sharing.foreground.value = false
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpen(intent)
    }
}
