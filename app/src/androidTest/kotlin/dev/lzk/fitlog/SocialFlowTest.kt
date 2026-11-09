package dev.lzk.fitlog

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelStore
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel
import dev.lzk.fitlog.features.liveSnapshot
import dev.lzk.fitlog.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SocialFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val fake=FakeSocial()
    private val models=ViewModelStore()
    @After fun close(){compose.activityRule.scenario.close();models.clear()}
    private fun waitText(text:String)=compose.waitUntil(15_000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    @Test fun friendRowOpensChatAndAvatarOpensProfile() {
        val model=FriendsViewModel(fake);models.put("friends",model)
        compose.setContent {FitLogTheme {FriendsScreen(model,fake)}}
        waitText("我的好友 · 1")
        compose.onNodeWithTag("friends-list").performScrollToNode(hasText("训练搭子"))
        compose.onNodeWithText("训练搭子").performClick()
        waitText("发消息")
        compose.onNodeWithText("清空").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        waitText("我的好友 · 1")
        compose.onNodeWithTag("friends-list").performScrollToNode(hasText("训练搭子"))
        compose.onNodeWithContentDescription("训练搭子的头像").performClick()
        waitText("个人主页")
        // A friend's page is read only apart from the note.
        compose.onNodeWithTag("profile-nickname").assertHasNoClickAction()
        // Online but not training: no puppy on a friend's banner.
        compose.onNodeWithTag("profile-puppy").assertDoesNotExist()
        compose.onNodeWithTag("friend-note").assertExists()
        compose.onNodeWithTag("person-timeline").performScrollTo().performClick()
        waitText("未开启训练历史共享")
        compose.onNodeWithTag("timeline-tab-posts").performClick()
        compose.onNodeWithTag("compose-post").assertDoesNotExist()
    }
    @Test fun failedSendPersistsAndRetryDoesNotDuplicate() {
        fake.failSend=true
        compose.setContent {FitLogTheme {
            ChatScreen(fake,fake.me,Friend(fake.peer,"",true,false),emptyList(),{}, {})
        }}
        waitText("发消息")
        compose.onNodeWithText("发消息").performTextInput("今天练得不错")
        compose.onNodeWithText("发送").performClick()
        waitText("网络暂不可用")
        assertEquals(1,fake.queue.size)
        val id=fake.queue.single().id
        fake.failSend=false
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(15_000){fake.queue.isEmpty() && fake.rows.size==1}
        assertEquals(id,fake.rows.single().id)
        compose.onNodeWithText("今天练得不错").performTouchInput{longClick()}
        waitText("引用回复")
        compose.onNodeWithText("引用回复").performClick()
        compose.onNodeWithText("发消息").performTextInput("明天继续")
        compose.onNodeWithText("发送").performClick()
        compose.waitUntil(15_000){fake.rows.size==2}
        assertEquals(id,fake.lastReply)
    }
    @Test fun longPressCanForwardAndDeleteOnlyMyView() {
        val original=ChatMessage("original",fake.peer.id,fake.me.id,"原始消息",Instant.now())
        fake.rows=listOf(original)
        compose.setContent {FitLogTheme {
            ChatScreen(fake,fake.me,Friend(fake.peer,"",true,false),listOf(Friend(fake.peer,"",true,false)),{}, {})
        }}
        waitText("原始消息")
        compose.onNodeWithText("原始消息").performTouchInput{longClick()}
        compose.onNodeWithText("转发").performClick()
        waitText("转发给好友")
        compose.onAllNodesWithText("训练搭子").onLast().performClick()
        compose.waitUntil(15_000){fake.lastForward==listOf("original")}
        compose.waitUntil(15_000){compose.onAllNodesWithText("转发给好友").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("原始消息").performTouchInput{longClick()}
        compose.onNodeWithText("仅从本机删除").performClick()
        compose.waitUntil(15_000){fake.rows.none{it.id=="original"}}
    }
    @Test fun sharingRequiresExplicitBindingAndCanBeCancelled() {
        val model=FriendsViewModel(fake);models.put("friends",model)
        compose.setContent {FitLogTheme {androidx.compose.foundation.layout.Column {SocialPrivacyRows(model,fake){}}}}
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        waitText("绑定本机训练记录？")
        compose.onNodeWithText("取消").performClick()
        assertNull(fake.bound)
        assertFalse(fake.preference.history)
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("确认绑定并开启").performClick()
        compose.waitUntil(15_000){fake.preference.history}
        assertEquals(fake.me.id,fake.bound)
        assertFalse(fake.preference.live)
    }
    @Test fun snapshotUsesSelectedExerciseAndOnlyCompletedGroupsCount() {
        val session=WorkoutSession("session",Instant.now(),java.time.LocalDate.now(),SessionStatus.ACTIVE)
        val first=SessionExercise("one","session","first",0,"动作一",1,8,12,listOf(
            SetRecord("set1","one",1,40000,10,SetStatus.COMPLETED)))
        val second=SessionExercise("two","session","second",1,"动作二",1,8,12,listOf(
            SetRecord("set2","two",1,22500,12,SetStatus.PLANNED)))
        val snapshot=Workout(session,listOf(first,second)).liveSnapshot("two")
        assertEquals("动作二",snapshot.exercise)
        assertEquals(22500L,snapshot.weightGrams)
        assertEquals(1,snapshot.done)
        assertEquals(2,snapshot.total)
    }
    @Test fun ownPostsStayVisibleAndOlderLocalCopiesSitBelowADividerAndCanBeDeleted() {
        val now=Instant.now()
        fake.posts=listOf(SocialPost("recent",fake.me.id,"我",null,"本周的动态",emptyList(),now,1,false,0))
        fake.archive=listOf(SocialPost("old",fake.me.id,"我",null,"两个月前的动态",emptyList(),now.minus(java.time.Duration.ofDays(60)),0,false,0))
        compose.setContent {FitLogTheme {ProfileTimelineScreen(fake,fake.me,fake.me,null,ownHistory={},onBack={},onPerson={})}}
        compose.onNodeWithTag("timeline-tab-posts").performClick()
        // The list used to empty itself right after loading on a person's page.
        waitText("本周的动态")
        compose.waitUntil(5_000){compose.onAllNodesWithTag("local-archive").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("30 天前 · 仅本机").assertExists()
        compose.onNodeWithTag("post-window-note").assertDoesNotExist()
        compose.onNodeWithTag("delete-local-old").performScrollTo().performClick()
        waitText("删除这条动态？")
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitUntil(15_000){fake.localDeletes==listOf("old")}
        assertEquals(1,fake.posts.size)
        compose.waitUntil(5_000){compose.onAllNodesWithText("两个月前的动态").fetchSemanticsNodes().isEmpty()}
        waitText("本周的动态")
    }
    @Test fun othersPagesSayOnlyThirtyDaysShowAndOwnPageFallsBackToDeviceCopiesOffline() {
        fake.posts=listOf(SocialPost("peer-post",fake.peer.id,"训练搭子",null,"好友的动态",emptyList(),Instant.now(),0,false,0))
        var who by androidx.compose.runtime.mutableStateOf(fake.peer.id)
        compose.setContent {FitLogTheme {androidx.compose.runtime.key(who){SocialTimelineScreen(fake,fake.me,owner=who,onBack={},onPerson={})}}}
        waitText("好友的动态")
        compose.onNodeWithTag("post-window-note").assertExists()
        fake.offline=true
        fake.archive=listOf(SocialPost("mine",fake.me.id,"我",null,"只在本机的动态",emptyList(),Instant.now(),0,false,0))
        compose.runOnIdle {who=fake.me.id}
        waitText("只在本机的动态")
        compose.onNodeWithText("仅本机").assertExists()
    }
    @Test fun publishMomentAndDeleteOwnPost() {
        compose.setContent {FitLogTheme {SocialTimelineScreen(fake,fake.me,onBack={},onPerson={})}}
        waitText("发动态")
        compose.onNodeWithTag("compose-post").performClick()
        waitText("今天有什么新进步？")
        compose.onNodeWithText("今天有什么新进步？").performTextInput("完成了训练目标")
        compose.onAllNodesWithText("发表").onLast().performClick()
        waitText("完成了训练目标")
        compose.onNodeWithText("删除").performScrollTo().performClick()
        waitText("删除这条动态？")
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitUntil(15_000){fake.posts.isEmpty()}
    }
}
