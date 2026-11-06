package dev.lzk.fitlog.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

/** Whether the user allows FitLog's own vibration feedback (Settings → 操作震动). */
val LocalHapticsEnabled = compositionLocalOf { true }

/**
 * Feedback vocabulary shared by every screen. Uses View haptics, so the system touch-feedback
 * setting still applies and no VIBRATE permission is needed.
 */
class FitHaptics(private val view: View, private val enabled: Boolean) {
    fun tap() = perform(HapticFeedbackConstants.KEYBOARD_TAP)
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun reject() { if (Build.VERSION.SDK_INT >= 30) perform(HapticFeedbackConstants.REJECT) }

    /** Two pulses: rest finished or a new personal record. */
    suspend fun double() { confirm(); delay(110); confirm() }

    private fun perform(constant: Int) { if (enabled) view.performHapticFeedback(constant) }
}

@Composable
fun fitHaptics(): FitHaptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { FitHaptics(view, enabled) }
}
