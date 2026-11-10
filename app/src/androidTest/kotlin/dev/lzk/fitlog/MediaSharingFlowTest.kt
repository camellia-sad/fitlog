package dev.lzk.fitlog

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MediaSharingFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val fake=FakeSocial()
    @After fun close(){compose.activityRule.scenario.close()}
    private fun waitText(text:String)=compose.waitUntil(15_000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun picture() {
        val bitmap=Bitmap.createBitmap(40,120,Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap)
        val paint=android.graphics.Paint()
        paint.color=android.graphics.Color.RED;canvas.drawRect(0f,0f,40f,40f,paint)
        paint.color=android.graphics.Color.GREEN;canvas.drawRect(0f,40f,40f,80f,paint)
        paint.color=android.graphics.Color.BLUE;canvas.drawRect(0f,80f,40f,120f,paint)
        fake.imageBytes=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        bitmap.recycle()
    }
    private fun workout()=Workout(WorkoutSession("workout",Instant.now().minusSeconds(120),LocalDate.now(),
        SessionStatus.FINISHED,endedAtUtc=Instant.now(),feedback="坚持就是进步"),emptyList())
    @Test fun portraitIsNotCroppedAndOpensGalleryViewer() {
        picture()
        compose.setContent {FitLogTheme {SocialImage(fake,"one",Modifier.size(200.dp,100.dp),gallery=listOf("one","two"))}}
        compose.waitUntil(15_000){compose.onAllNodesWithContentDescription("图片").fetchSemanticsNodes().isNotEmpty()}
        val pixels=compose.onNodeWithContentDescription("图片").captureToImage().toPixelMap()
        val top=pixels[pixels.width/2,pixels.height/10]
        val bottom=pixels[pixels.width/2,pixels.height*9/10]
        assertTrue(top.red>0.9f && top.blue<0.1f)
        assertTrue(bottom.blue>0.9f && bottom.red<0.1f)
        compose.onNodeWithContentDescription("图片").performClick()
        waitText("关闭大图")
        compose.onNodeWithText("1 / 2").assertExists()
        compose.onNodeWithText("下一张").performClick()
        compose.onNodeWithText("2 / 2").assertExists()
        compose.onNodeWithText("还原").performClick()
        compose.onNodeWithText("关闭大图").performClick()
        compose.onNodeWithTag("photo-viewer").assertDoesNotExist()
    }
    @Test fun chatAndFeedPicturesOpenViewerAndChatStillSupportsLongPress() {
        picture()
        fake.rows=listOf(ChatMessage("image",fake.peer.id,fake.me.id,"",Instant.now(),mediaPath="one"))
        compose.setContent {FitLogTheme {
            var feed by remember{mutableStateOf(false)}
            if(!feed) ChatScreen(fake,fake.me,Friend(fake.peer,"",true,false),emptyList(),{feed=true},{})
            else SocialTimelineScreen(fake,fake.me,onBack={},onPerson={})
        }}
        compose.waitUntil(15_000){compose.onAllNodesWithContentDescription("图片").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("图片").performClick();waitText("关闭大图")
        compose.onNodeWithText("关闭大图").performClick()
        compose.onNodeWithContentDescription("图片").performTouchInput{longClick()}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("message-actions").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("引用").performClick()
        compose.waitUntil(5_000){compose.onAllNodesWithTag("message-actions").fetchSemanticsNodes().isEmpty()}
        fake.posts=listOf(SocialPost("post",fake.peer.id,"搭子",null,"照片",listOf("one"),Instant.now(),0,false,0))
        compose.onNodeWithContentDescription("返回").performClick()
        waitText("照片")
        compose.waitUntil(15_000){compose.onAllNodesWithContentDescription("图片").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("图片").performClick();waitText("关闭大图")
    }
    @Test fun workoutSharingRequiresExplicitPublishAndDoesNotEnableHistorySharing() {
        var visible by mutableStateOf(true)
        var published=false
        compose.setContent {FitLogTheme {
            if(visible)WorkoutShareDialog(workout(),fake,fake,{visible=false},{},{published=true;visible=false})
        }}
        waitText("训练动态内容")
        compose.onNodeWithTag("workout-post-body").assertTextContains("坚持就是进步",substring=true)
        assertTrue(fake.posts.isEmpty())
        compose.onNodeWithText("发表").performClick()
        compose.waitUntil(15_000){published}
        assertEquals(1,fake.posts.size)
        assertTrue(fake.posts.single().body.contains("坚持就是进步"))
        assertEquals(SharingPreferences(),fake.preference)
        assertNull(fake.bound)
    }
    @Test fun cancelSharingDoesNotPublish() {
        var visible by mutableStateOf(true)
        compose.setContent {FitLogTheme {if(visible)WorkoutShareDialog(workout(),fake,fake,{visible=false},{},{})}}
        waitText("训练动态内容")
        compose.onNodeWithText("取消").performClick()
        assertTrue(fake.posts.isEmpty())
    }
}
