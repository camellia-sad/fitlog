@file:Suppress("DEPRECATION")
package dev.lzk.fitlog

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Movie
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel
import dev.lzk.fitlog.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PolishFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val fake=FakeSocial()
    private val models=ViewModelStore()
    @After fun close(){compose.activityRule.scenario.close();models.clear();AvatarImages.clear()}
    private fun waitText(text:String)=compose.waitUntil(15_000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    @Test fun mySummaryHidesIdAndDetailCopiesFullId() {
        val model=FriendsViewModel(fake);models.put("me",model)
        compose.setContent {FitLogTheme {MeScreen(model)}}
        waitText("我的资料与 FitLog ID")
        compose.onNodeWithText("FitLog ID："+fake.me.fitlogId).assertDoesNotExist()
        compose.onNodeWithText("我的资料与 FitLog ID").performClick()
        compose.onNodeWithContentDescription("复制 FitLog ID").performClick()
        compose.runOnIdle {
            val clipboard=compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(fake.me.fitlogId,clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun postAuthorCannotDeletePeersCommentButCanDeleteOwnComment() {
        fake.posts=listOf(SocialPost("post",fake.me.id,"我",null,"今日动态",emptyList(),Instant.now(),0,false,2))
        fake.commentRows=listOf(
            SocialComment("other",fake.peer.id,"训练搭子","好友评论",Instant.now()),
            SocialComment("mine",fake.me.id,"我","本人评论",Instant.now()))
        compose.setContent {FitLogTheme {SocialTimelineScreen(fake,fake.me,onBack={},onPerson={})}}
        waitText("今日动态")
        compose.onNodeWithContentDescription("查看评论").performClick()
        waitText("好友评论")
        // One delete belongs to the post underneath; only one belongs to a comment.
        compose.onAllNodesWithText("删除").assertCountEquals(2)
        compose.onAllNodesWithText("删除").onLast().performClick()
        waitText("删除这条评论？")
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitUntil(15_000){fake.commentRows.size==1}
        assertEquals("other",fake.commentRows.single().id)
    }
    @Test fun repliesExpandUnderCommentAndReplyKeepsThread() {
        fake.posts=listOf(SocialPost("post",fake.me.id,"我",null,"今日动态",emptyList(),Instant.now(),0,false,3))
        fake.commentRows=listOf(SocialComment("root",fake.peer.id,"训练搭子","顶层评论",Instant.now(),replyCount=2))
        fake.replyRows=mapOf("root" to listOf(
            SocialComment("r1",fake.me.id,"我","第一条回复",Instant.now(),parentId="root"),
            SocialComment("r2",fake.peer.id,"训练搭子","继续回复",Instant.now(),parentId="root",replyToNickname="我")))
        compose.setContent {FitLogTheme {SocialTimelineScreen(fake,fake.me,onBack={},onPerson={})}}
        waitText("今日动态")
        compose.onNodeWithContentDescription("查看评论").performClick()
        waitText("展开 2 条回复")
        compose.onNodeWithText("第一条回复").assertDoesNotExist()
        compose.onNodeWithText("展开 2 条回复").performClick()
        waitText("第一条回复")
        compose.onNodeWithText("@我 继续回复").assertExists()
        // Reply buttons follow list order: root, r1, r2.
        compose.onAllNodesWithText("回复").onLast().performClick()
        waitText("正在回复 @训练搭子")
        compose.onNode(hasSetTextAction()).performTextInput("收到")
        compose.onNodeWithText("发送").performClick()
        compose.waitUntil(15_000){fake.lastComment!=null}
        assertEquals("收到" to "r2",fake.lastComment)
        compose.onNodeWithTag("activity-comments").performScrollToNode(hasText("收起回复"))
        compose.onNodeWithText("收起回复").performClick()
        compose.onNodeWithText("第一条回复").assertDoesNotExist()
    }
    @Test fun avatarSurvivesOutgoingPageLifecycleStop() {
        val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.GREEN)
        fake.imageBytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        image.recycle()
        val owner=object:androidx.lifecycle.LifecycleOwner {
            val registry=androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle:androidx.lifecycle.Lifecycle get()=registry
        }
        compose.runOnUiThread{owner.registry.currentState=androidx.lifecycle.Lifecycle.State.STARTED}
        compose.setContent {FitLogTheme {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                SocialAvatar(fake,fake.me.copy(nickname="陈先生",avatarPath="avatar")){}
            }
        }}
        compose.waitUntil(15_000){compose.onAllNodesWithContentDescription("图片").fetchSemanticsNodes().isNotEmpty()}
        // Navigation can stop the old destination while its exit transition is still visible.
        compose.runOnUiThread{owner.registry.currentState=androidx.lifecycle.Lifecycle.State.CREATED}
        compose.waitForIdle()
        compose.onNodeWithContentDescription("图片").assertExists()
        compose.onNodeWithText("陈").assertDoesNotExist()
        compose.runOnUiThread{owner.registry.currentState=androidx.lifecycle.Lifecycle.State.STARTED}
        compose.waitForIdle()
        compose.onNodeWithContentDescription("图片").assertExists()
        compose.runOnIdle{assertEquals(1,fake.imageReads)}
    }
    @Test fun customAvatarLoadingDoesNotFlashNicknameButFailureHasFallback() {
        val requested=java.util.concurrent.atomic.AtomicBoolean(false)
        val gate=kotlinx.coroutines.CompletableDeferred<Unit>()
        val source=object:SocialRepository by fake,FriendsRepository by fake {
            override suspend fun image(path:String):ByteArray {
                requested.set(true);gate.await()
                return fake.image(path)
            }
        }
        compose.setContent {FitLogTheme {SocialAvatar(source,fake.me.copy(nickname="陈先生",avatarPath="delayed-avatar")){}}}
        compose.waitUntil(15_000){requested.get()}
        compose.onNodeWithText("陈").assertDoesNotExist()
        compose.onNodeWithContentDescription("图片").assertDoesNotExist()
        // Invalid bytes produce the actual error fallback, rather than a loading fallback.
        gate.complete(Unit)
        compose.waitUntil(15_000){compose.onAllNodesWithText("陈").fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun avatarIsReusedAcrossPageChangesWithoutEllipsis() {
        val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.GREEN)
        fake.imageBytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        image.recycle()
        compose.setContent {FitLogTheme {
            var page by remember{mutableStateOf(false)}
            Column {
                Button(onClick={page=!page}){Text("切页")}
                key(page){SocialAvatar(fake,fake.me.copy(avatarPath="avatar")){}}
            }
        }}
        compose.waitUntil(15_000){fake.imageReads==1 && compose.onAllNodesWithContentDescription("图片").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("切页").performClick()
        compose.onNodeWithText("…").assertDoesNotExist()
        compose.onNodeWithContentDescription("图片").assertExists()
        compose.runOnIdle{assertEquals(1,fake.imageReads)}
    }
    @Test fun gifAssetsMatchRegistryAndContainDrawableAnimation() {
        val assets=compose.activity.assets
        assertEquals(ExerciseAnimations.packaged,assets.list("exercise-gifs")!!.map{it.removeSuffix(".gif")}.toSet())
        for(id in ExerciseAnimations.packaged) {
            val movie=assets.open("exercise-gifs/"+id+".gif").use{Movie.decodeStream(it)}
            assertNotNull(id,movie);requireNotNull(movie)
            assertTrue(id,movie.duration()>0)
            val bitmap=Bitmap.createBitmap(movie.width(),movie.height(),Bitmap.Config.ARGB_8888)
            val canvas=android.graphics.Canvas(bitmap)
            movie.setTime(0);movie.draw(canvas,0f,0f)
            val first=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(first,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            bitmap.eraseColor(0)
            movie.setTime(movie.duration()/2);movie.draw(canvas,0f,0f)
            val second=IntArray(first.size);bitmap.getPixels(second,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            assertFalse(id,first.contentEquals(second));bitmap.recycle()
        }
        assertTrue(ExerciseAnimations.has("standing-calf-raise"))
    }
    @Test fun keyboardLeavesComposerAboveImeAndLatestMessageVisible() {
        var imePixels=0
        var density=1f
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        fake.rows=(0..25).map{ChatMessage("m"+it,fake.peer.id,fake.me.id,if(it==0)"最新消息" else "旧消息 "+it,Instant.now().minusSeconds(it.toLong()))}
        compose.setContent {FitLogTheme {
            val local=LocalDensity.current
            val bottom=WindowInsets.ime.getBottom(local)
            SideEffect{imePixels=bottom;density=local.density}
            Scaffold(contentWindowInsets=WindowInsets.safeDrawing) {padding->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    ChatScreen(fake,fake.me,Friend(fake.peer,"",true,false),emptyList(),{}, {})
                }
            }
        }}
        waitText("最新消息")
        compose.onNodeWithTag("chat-input").performClick()
        compose.waitUntil(15_000){imePixels>0}
        compose.onNodeWithText("最新消息").assertIsDisplayed()
        val root=compose.onRoot().getUnclippedBoundsInRoot()
        val composer=compose.onNodeWithTag("chat-composer").getUnclippedBoundsInRoot()
        assertTrue(composer.bottom.value<=root.bottom.value-imePixels/density+3)
        assertTrue(composer.top.value>root.top.value+48)
    }
}
