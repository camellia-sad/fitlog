package dev.lzk.fitlog.features

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.work.*
import dev.lzk.fitlog.FitLogApplication
import dev.lzk.fitlog.MainActivity
import dev.lzk.fitlog.R
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The chat or feed currently on screen; set by those pages while they are shown, so what is in view never pops up. */
object SocialViewing {
    val chat = MutableStateFlow<String?>(null)
    val feed = MutableStateFlow(false)
}

/**
 * Top-of-screen notifications for friends' messages, new posts, and comments or replies, one per kind, updated in place.
 * There is no push service: the app checks every 15 s while open, every 30 s while the training timer keeps it running,
 * and about every 15 minutes otherwise (WorkManager). Only what is newer than the last check pops up.
 */
class SocialNotifications(context: Context, private val account: StateFlow<SocialProfile?>,
    private val source: suspend () -> AlertBatch, namespace: String) {
    private val context = context.applicationContext
    private val store = this.context.getSharedPreferences("$namespace.social-alerts", Context.MODE_PRIVATE)
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val gate = Mutex()
    private val pokes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val shown = mutableMapOf<Int, String>()

    init {
        manager.createNotificationChannels(listOf(
            NotificationChannel(Kind.MESSAGES.channel, "聊天消息", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(Kind.POSTS.channel, "好友动态", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(Kind.REPLIES.channel, "评论和回复", NotificationManager.IMPORTANCE_HIGH)))
    }

    /** Checks while the app is open or training, re-checks at once when the page in view changes, and keeps the background job in step with sign-in. */
    fun start(scope: CoroutineScope, foreground: StateFlow<Boolean>, training: StateFlow<Boolean>) {
        val work = WorkManager.getInstance(context)
        scope.launch {
            account.map { it?.id }.distinctUntilChanged().collect { id ->
                if (id == null) { cancelAll(); work.cancelUniqueWork(WORK) }
                else work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<SocialAlertWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            }
        }
        scope.launch {
            combine(SocialViewing.chat, SocialViewing.feed) { chat, feed -> chat to feed }.drop(1).collect { pokes.tryEmit(Unit) }
        }
        scope.launch {
            combine(account.map { it?.id }, foreground, training) { id, front, service ->
                when { id == null -> null; front -> 15_000L; service -> 30_000L; else -> null }
            }.distinctUntilChanged().collectLatest { period ->
                if (period == null) return@collectLatest
                while (isActive) {
                    try { check() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Next round retries. */ }
                    withTimeoutOrNull(period) { pokes.first() }
                }
            }
        }
    }

    /** One round: fetch, compare with what was already alerted, and show, update or clear each notification. */
    suspend fun check(view: AlertView = AlertView(SocialViewing.chat.value, SocialViewing.feed.value)) = gate.withLock {
        val owner = account.value?.id ?: return@withLock
        val batch = source()
        if (account.value?.id != owner) return@withLock
        val marks = read(owner)
        if (marks == null) { write(owner, SocialAlerts.baseline(batch, Instant.EPOCH)); return@withLock }
        val plan = SocialAlerts.plan(batch, marks, view)
        write(owner, plan.marks)
        show(Kind.MESSAGES, plan.messages, plan.messages?.chatPeer?.let { "chat:$it" } ?: "friends")
        show(Kind.POSTS, plan.posts, "feed")
        show(Kind.REPLIES, plan.replies, "feed")
    }

    private fun show(kind: Kind, notice: AlertNotice?, target: String) {
        if (notice == null) { cancel(kind); return }
        val permitted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permitted) return
        // A quiet update only rewrites a notification that is still there; one swiped away stays away until something new arrives.
        val present = manager.activeNotifications.any { it.id == kind.id }
        if (!notice.alert && (!present || shown[kind.id] == notice.text + target)) return
        val open = Intent(context, MainActivity::class.java).putExtra(OPEN_SOCIAL, target)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(context, kind.id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(kind.id, Notification.Builder(context, kind.channel)
            .setSmallIcon(R.drawable.ic_social_notification).setContentTitle(notice.text)
            .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(!notice.alert).setShowWhen(true)
            .setCategory(if (kind == Kind.MESSAGES) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_SOCIAL).build())
        shown[kind.id] = notice.text + target
    }

    private fun cancel(kind: Kind) { manager.cancel(kind.id); shown.remove(kind.id) }
    private fun cancelAll() = Kind.entries.forEach(::cancel)

    private fun read(owner: String): AlertMarks? {
        fun at(key: String) = store.getString("$owner.$key", null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return AlertMarks(at("message") ?: return null, at("post") ?: return null, at("reply") ?: return null, at("feed") ?: return null)
    }
    private fun write(owner: String, marks: AlertMarks) {
        store.edit {
            putString("$owner.message", marks.message.toString()); putString("$owner.post", marks.post.toString())
            putString("$owner.reply", marks.reply.toString()); putString("$owner.feed", marks.feedSeen.toString())
        }
    }

    private enum class Kind(val id: Int, val channel: String) {
        MESSAGES(51, "social_messages"), POSTS(52, "social_posts"), REPLIES(53, "social_replies")
    }

    companion object {
        /** "chat:<friend id>", "friends" or "feed": where a tapped notification leads. */
        const val OPEN_SOCIAL = "open_social"
        private const val WORK = "social-alerts"
    }
}

/** The background round, about every 15 minutes while signed in. */
class SocialAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try { (applicationContext as FitLogApplication).container.alerts.check() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* The next period tries again. */ }
        return Result.success()
    }
}
