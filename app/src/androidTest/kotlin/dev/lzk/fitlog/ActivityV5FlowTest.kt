package dev.lzk.fitlog

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ActivityV5FlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val fake=FakeSocial()
    private val at=Instant.parse("2026-11-05T10:00:00Z")
    private val post=SocialPost("p",fake.peer.id,"搭子",null,"训练后的快乐",emptyList(),at,3,false,2,"fit-id")
    private val root=SocialComment("root",fake.me.id,"我","原评论",at,replyCount=70)
    private val reply=SocialComment("deep",fake.peer.id,"搭子","准确定位的旧回复",at.plusSeconds(70),parentId="root",replyToNickname="我")
    @After fun close(){compose.activityRule.scenario.close()}
    private fun waitText(text:String)=compose.waitUntil(15_000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun inbox(count:Int)=hasTestTag("reply-inbox-head") and hasText("$count")
    private fun waitInbox(count:Int)=compose.waitUntil(15_000){compose.onAllNodes(inbox(count)).fetchSemanticsNodes().isNotEmpty()}
    private fun seed(count:Int) {
        fake.posts=listOf(post)
        fake.notices=(0 until count).map{ReplyNotice(reply.copy(id=if(it==0)"deep" else "n$it"),post.id,post.body)}
        fake.contexts=fake.notices.associate{it.comment.id to CommentContext(post,root,it.comment)}
        // Neither the root nor target is in the first normal comment/reply page.
        fake.commentRows=listOf(SocialComment("other",fake.peer.id,"旁人","其他评论",at))
        fake.replyRows=mapOf("root" to (1..50).map{reply.copy(id="early$it",body="前面的回复 $it",createdAt=at.plusSeconds(it.toLong()))})
    }
    private fun show(){compose.setContent{FitLogTheme(darkTheme=true){SocialTimelineScreen(fake,fake.me,onBack={},onPerson={})}}}
    @Test fun inlineLocatesOldReplyAndMarksReadOnlyAfterVisible() {
        seed(1);show();waitInbox(1)
        assertEquals(1,fake.notices.size)
        compose.onNode(inbox(1)).performClick()
        compose.onNodeWithText("准确定位的旧回复").performClick()
        waitText("@我 准确定位的旧回复")
        compose.onNodeWithText("@我 准确定位的旧回复").assertIsDisplayed()
        compose.waitUntil(15_000){fake.notices.isEmpty()}
        compose.onNodeWithContentDescription("关闭评论").performClick()
        compose.onNodeWithText("动态详情").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("reply-inbox-head").assertDoesNotExist()
    }
    @Test fun fourUsesPageThenThreeUsesInline() {
        seed(4);show();waitInbox(4)
        compose.onNode(inbox(4)).performClick()
        waitText("回复消息")
        compose.onAllNodesWithText("准确定位的旧回复")[0].performClick()
        waitText("@我 准确定位的旧回复")
        compose.waitUntil(15_000){fake.notices.size==3}
        compose.onNodeWithContentDescription("关闭评论").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        waitInbox(3)
        compose.onNode(inbox(3)).performClick()
        compose.onNodeWithText("回复消息").assertDoesNotExist()
        compose.onAllNodesWithText("准确定位的旧回复").assertCountEquals(3)
    }
    @Test fun unavailableTargetStaysUnreadAndLikeUsesRepository() {
        seed(1);fake.contexts=emptyMap();show();waitInbox(1)
        compose.onNodeWithContentDescription("点赞").performClick()
        compose.waitUntil(15_000){fake.likedValue==true}
        compose.onNodeWithContentDescription("取消点赞").assertIsSelected()
        compose.onNode(inbox(1)).performClick()
        compose.onNodeWithText("准确定位的旧回复").performClick()
        waitText("原动态或回复已删除或不可访问")
        assertEquals(1,fake.notices.size)
    }
    @Test fun deletionRequiresConfirmationAndOtherUsersHaveNoDeleteButton() {
        seed(0);fake.commentRows=listOf(root.copy(replyCount=0),SocialComment("foreign",fake.peer.id,"搭子","别人留言",at))
        show();waitText(post.body)
        compose.onNodeWithContentDescription("查看评论").performClick()
        waitText("原评论")
        compose.onAllNodesWithText("删除").assertCountEquals(1)
        compose.onNodeWithText("删除").performClick()
        waitText("删除这条评论？")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("原评论").assertExists()
        compose.onNodeWithText("删除").performClick()
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitUntil(15_000){fake.commentRows.none{it.id==root.id}}
    }
    @Test fun previewAndKeyboardKeepComposerVisible() {
        seed(3);fake.commentRows=listOf(root.copy(replyCount=0))
        show();waitText(post.body)
        compose.onNode(inbox(3)).performClick()
        val image=compose.onRoot().captureToImage()
        val path=java.io.File(compose.activity.filesDir,"activity-v5-preview.png")
        java.io.FileOutputStream(path).use{image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithContentDescription("查看评论").performClick()
        waitText("原评论")
        compose.onNode(hasSetTextAction()).performClick().performTextInput("键盘下的评论")
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitUntil(15_000){fake.lastComment!=null}
        assertEquals("键盘下的评论",fake.lastComment!!.first)
    }

    @Test fun sendingIntoLongThreadShowsNewReplyWithoutLoadingEveryPage() {
        seed(1);show();waitInbox(1)
        compose.onNode(inbox(1)).performClick()
        compose.onNodeWithText("准确定位的旧回复").performClick()
        waitText("@我 准确定位的旧回复")
        compose.onAllNodesWithText("回复").onLast().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("追加回复")
        compose.onNodeWithContentDescription("发送").performClick()
        waitText("@搭子 追加回复")
        compose.onNodeWithText("@搭子 追加回复").assertIsDisplayed()
        assertEquals("追加回复" to "deep",fake.lastComment)
    }

}
