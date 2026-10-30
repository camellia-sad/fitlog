package dev.lzk.fitlog.training

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import dev.lzk.fitlog.FitLogApplication
import dev.lzk.fitlog.MainActivity
import dev.lzk.fitlog.R
import dev.lzk.fitlog.domain.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class WorkoutTimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "训练计时", NotificationManager.IMPORTANCE_LOW))
        if (monitor == null) {
            val initial = notification(null)
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, initial)
        }
        if (monitor == null) monitor = scope.launch {
            val container = (application as FitLogApplication).container
            container.sharing.trainingService.value = true
            container.ready.await()
            container.workouts.observeActiveWorkout().collectLatest { workout ->
                if (workout == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collectLatest }
                while (isActive) {
                    manager.notify(ID, notification(workout))
                    delay(1_000)
                }
            }
        }
        return START_STICKY
    }
    private fun notification(workout: Workout?): Notification {
        val intent = Intent(this, MainActivity::class.java).putExtra(OPEN_WORKOUT, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(this, 41, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val paused = workout?.session?.status == SessionStatus.PAUSED
        val elapsed = workout?.elapsedDuration(Instant.now())?.toMillis()?.coerceAtLeast(0) ?: 0
        val seconds = elapsed / 1000
        val time = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_workout_notification)
            .setContentTitle(if (workout == null) "恢复训练计时" else workout.session.trainingLabel + if (paused) " · 已暂停" else " · 训练中")
            .setContentText(if (workout == null) "点击返回训练" else "$time · ${workout.completedSetCount}/${workout.plannedSetCount} 组 · 点击返回")
            .setWhen(System.currentTimeMillis() - elapsed).setUsesChronometer(workout != null && !paused)
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(true)
            .setCategory(Notification.CATEGORY_SERVICE).build()
    }
    override fun onDestroy() { (application as FitLogApplication).container.sharing.trainingService.value = false; scope.cancel(); super.onDestroy() }
    companion object {
        const val OPEN_WORKOUT = "open_workout"
        const val CHANNEL = "workout_timer"
        const val ID = 41
        fun start(context: Context) { context.startForegroundService(Intent(context, WorkoutTimerService::class.java)) }
    }
}
