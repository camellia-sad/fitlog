package dev.lzk.fitlog

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel
import dev.lzk.fitlog.features.SocialNotifications
import dev.lzk.fitlog.features.SocialViewing
import dev.lzk.fitlog.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SocialNotificationsFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val manager=context.getSystemService(NotificationManager::class.java)
    private val models=ViewModelStore()
    private val t0=Instant.parse("2026-11-10T08:00:00Z")
    private fun at(minutes:Long)=t0.plusSeconds(minutes*60)
    private fun titles()=manager.activeNotifications.filter {it.id in 51..53}
        .associate {it.id to it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()}
    /** Posting and cancelling reach the system a moment later. */
    private fun shows(expected:Map<Int,String>) {
        try {compose.waitUntil(5_000){titles()==expected}} catch(_:Throwable) {}
        assertEquals(expected,titles())
    }

    @Before fun grant() {
        if(Build.VERSION.SDK_INT>=33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        context.deleteSharedPreferences("alerts-test.social-alerts")
        (51..53).forEach(manager::cancel)
    }
    @After fun close() {
        (51..53).forEach(manager::cancel)
        context.deleteSharedPreferences("alerts-test.social-alerts")
        compose.activityRule.scenario.close();models.clear()
        SocialViewing.chat.value=null;SocialViewing.feed.value=false
    }

    @Test fun newMessagesPostsAndCommentsShowOnceAndClearWhenSeen() {
        var batch=AlertBatch(listOf(AlertItem("a","小王",at(1))),emptyList(),emptyList())
        val alerts=SocialNotifications(context,MutableStateFlow(SocialProfile("me","10000001","我")),{batch},"alerts-test")
        // What was waiting before notifications began never pops up.
        runBlocking {alerts.check(AlertView())}
        assertTrue(titles().isEmpty())

        batch=AlertBatch(batch.messages+AlertItem("b","小李",at(2))+AlertItem("a","小王",at(3)),
            listOf(AlertItem("c","阿杰",at(4))),listOf(AlertItem("a","小王",at(5),comment=true)))
        runBlocking {alerts.check(AlertView())}
        shows(mapOf(51 to "小王等 2 位好友给你发了 3 条消息",52 to "阿杰发了新动态，快去看看吧",53 to "小王评论了你的动态"))

        // Chats read and the feed opened: those notifications go; the unread comment stays until it is read.
        batch=batch.copy(messages=emptyList())
        runBlocking {alerts.check(AlertView(feed=true))}
        shows(mapOf(53 to "小王评论了你的动态"))
        batch=batch.copy(replies=emptyList())
        runBlocking {alerts.check(AlertView())}
        shows(emptyMap())
    }

    @Test fun aTappedNotificationOpensTheChatOrTheFeed() {
        val fake=FakeSocial()
        val model=FriendsViewModel(fake);models.put("friends",model)
        var open by mutableStateOf<String?>(null)
        compose.setContent {FitLogTheme {FriendsScreen(model,fake,open=open,onOpened={open=null})}}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("friend-row-"+fake.peer.id).fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {open="chat:"+fake.peer.id}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("chat-status").fetchSemanticsNodes().isNotEmpty()}
        // The open chat is what notifications leave out.
        compose.waitUntil(5_000){SocialViewing.chat.value==fake.peer.id}
        assertNull(open)
        compose.runOnIdle {open="feed"}
        compose.waitUntil(15_000){compose.onAllNodesWithText("好友动态").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(5_000){SocialViewing.feed.value && SocialViewing.chat.value==null}
    }
}
