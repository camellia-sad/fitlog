package dev.lzk.fitlog

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel
import dev.lzk.fitlog.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.lifecycle.ViewModelStore

@RunWith(AndroidJUnit4::class)
class FriendsFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val models=ViewModelStore()
    private val repository=FakeFriends()
    private fun show() {
        val model=FriendsViewModel(repository)
        models.put("friends",model)
        compose.setContent { FitLogTheme { FriendsScreen(model) } }
    }
    @After fun close() { compose.activityRule.scenario.close(); models.clear() }
    @Test fun emailCodeLoginAndLogoutKeepOfflineTrainingIndependent() {
        show()
        waitText("邮箱")
        compose.onNodeWithText("邮箱").performTextInput("person@example.invalid")
        compose.onNodeWithText("发送验证码").performScrollTo().performClick()
        waitText("邮件验证码")
        compose.onNodeWithText("邮件验证码").performTextInput("123456")
        compose.onNodeWithText("验证并登录").performScrollTo().performClick()
        waitText("我的资料与 FitLog ID")
        assertEquals("person@example.invalid",repository.email)
        compose.onNodeWithText("我的资料与 FitLog ID").performClick()
        waitText("退出登录")
        compose.onNodeWithText("退出登录").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertNotNull(repository.profile.value)
        compose.onNodeWithText("退出登录").performClick()
        compose.onNodeWithText("确认").performClick()
        waitText("邮箱")
        assertNull(repository.profile.value)
    }
    @Test fun incomingRequestAcceptAndAvatarOpensPrivateRemarkEditor() {
        repository.profile.value=repository.me
        repository.rows=listOf(Friend(repository.peer,"",false,true))
        show()
        waitText("接受")
        compose.onNodeWithText("接受").performScrollTo().performClick()
        waitText("我的好友 · 1")
        compose.onNodeWithTag("friends-list").performScrollToNode(hasText("查看个人主页"))
        compose.onNodeWithText("查看个人主页").performClick()
        waitText("好友备注（仅自己可见）")
        compose.onNodeWithText("好友备注（仅自己可见）").performTextInput("一起练")
        compose.onNodeWithText("保存备注").performScrollTo().performClick()
        waitText("备注已保存")
        assertEquals("一起练",repository.rows.single().note)
        compose.onNodeWithContentDescription("返回好友列表").performClick()
        waitText("我的好友 · 1")
        compose.onNodeWithTag("friends-list").performScrollToNode(hasText("一起练"))
        compose.onNodeWithText("一起练").assertIsDisplayed()
    }
    @Test fun failedRequestKeepsSearchResultAndAllowsRetry() {
        repository.profile.value=repository.me
        show()
        waitText("查找好友")
        compose.onNodeWithTag("friend-search").performTextInput(repository.peer.fitlogId)
        compose.onNodeWithText("查找好友").performScrollTo().performClick()
        waitText("发送好友申请")
        repository.fail=true
        compose.onNodeWithText("发送好友申请").performScrollTo().performClick()
        waitText("网络暂不可用")
        compose.onNodeWithText("发送好友申请").assertExists()
        repository.fail=false
        compose.onNodeWithText("发送好友申请").performClick()
        waitText("等待对方接受")
        assertEquals(1,repository.rows.size)
    }
    @Test fun newLoginWithFailedFriendLoadCannotShowPreviousAccountFriends() {
        repository.profile.value=repository.me
        repository.rows=listOf(Friend(repository.peer,"旧账号好友",true,false))
        show()
        waitText("我的好友 · 1")
        compose.runOnIdle { repository.profile.value=null; repository.failFriends=true }
        waitText("邮箱")
        compose.onNodeWithText("邮箱").performTextInput("second@example.invalid")
        compose.onNodeWithText("已有验证码").performClick()
        compose.onNodeWithText("邮件验证码").performTextInput("123456")
        compose.onNodeWithText("验证并登录").performScrollTo().performClick()
        waitText("好友列表暂不可用")
        compose.onNodeWithText("旧账号好友").assertDoesNotExist()
    }

    private fun waitText(text:String)=compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text,substring=false).fetchSemanticsNodes().isNotEmpty()
    }
    private class FakeFriends:FriendsRepository {
        override val configured=true
        val me=SocialProfile("me","a".repeat(32),"我")
        val peer=SocialProfile("peer","b".repeat(32),"伙伴")
        override val profile=MutableStateFlow<SocialProfile?>(null)
        var email=""
        var rows=listOf<Friend>()
        var fail=false
        var failFriends=false
        override suspend fun requestCode(email:String) {this.email=email}
        override suspend fun verifyCode(email:String,code:String) {check(code=="123456");profile.value=me}
        override suspend fun refreshProfile() {}
        override suspend fun saveProfile(nickname:String,bio:String) {profile.value=me.copy(nickname=nickname,bio=bio)}
        override suspend fun friends():List<Friend> { check(!failFriends) {"好友列表暂不可用"}; return rows }
        override suspend fun find(fitlogId:String)=peer.takeIf{it.fitlogId==fitlogId}
        override suspend fun act(peerId:String,action:String) {
            check(!fail) {"网络暂不可用"}
            rows=when(action) {
                "request" -> listOf(Friend(peer,"",false,false))
                "accept" -> rows.map{it.copy(accepted=true)}
                else -> emptyList()
            }
        }
        override suspend fun saveNote(peerId:String,note:String) {rows=rows.map{it.copy(note=note)}}
        override suspend fun sharing()=SharingPreferences()
        override suspend fun saveSharing(value:SharingPreferences) {}
        override suspend fun signOut() {profile.value=null}
    }
}
