package dev.lzk.fitlog

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.ui.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ImageSendPreviewFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fake = FakeSocial()
    private val repository = MediaRepository(fake)
    private val picker = ResultPicker()
    private val files = mutableListOf<File>()

    @After fun close() {
        compose.activityRule.scenario.close()
        files.forEach { it.delete() }
    }

    private fun jpeg(color: Int, width: Int = 90, height: Int = 160): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    private fun picked(count: Int): List<Uri> = (1..count).map { i ->
        File.createTempFile("preview-$i-", ".jpg", compose.activity.cacheDir).also {
            it.writeBytes(jpeg(if (i % 2 == 0) android.graphics.Color.BLUE else android.graphics.Color.GREEN))
            files += it
        }.let(Uri::fromFile)
    }

    private fun showChat() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                FitLogTheme { ChatScreen(repository, fake.me, Friend(fake.peer, "", true, false), emptyList(), {}, {}) }
            }
        }
        waitTag("chat-input")
    }

    private fun waitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitNoTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }
    private fun select(uris: List<Uri>, add: Boolean = false) {
        picker.next = uris
        if (add) compose.onNodeWithTag("preview-add").performClick()
        else compose.onNodeWithContentDescription("发送图片").performClick()
        waitTag("image-preview")
    }

    @Test fun selectionWaitsForConfirmationAndKeepsTypedTextAndQuote() {
        fake.rows = listOf(ChatMessage("original", fake.peer.id, fake.me.id, "今天练什么", Instant.now()))
        showChat()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("今天练什么").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("今天练什么").performTouchInput { longClick() }
        compose.onNodeWithText("引用").performClick()
        compose.onNodeWithTag("chat-input").performTextInput("先保留这句话")
        select(picked(3))
        compose.onNodeWithText("发送 3 张").assertIsDisplayed()
        assertTrue(repository.uploads.isEmpty())
        assertTrue(repository.sent.isEmpty())
        compose.onNodeWithTag("preview-thumb-1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("preview-index") and hasText("2")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("移除第 2 张图片").performClick()
        compose.onNodeWithText("发送 2 张").assertExists()
        compose.onNodeWithTag("send-images").performClick()
        waitNoTag("image-preview")
        compose.waitUntil(15_000) { repository.sent.size == 2 && fake.queue.isEmpty() }
        assertEquals(listOf("chat/1", "chat/2"), repository.sent.map { it.mediaPath })
        assertEquals(listOf("original", null), repository.sent.map { it.replyId })
        assertTrue(repository.sent.all { it.body.isEmpty() })
        compose.onNodeWithTag("chat-input").assertTextContains("先保留这句话")
        assertEquals(listOf("upload", "upload", "queue", "queue", "send", "send"), repository.events)
    }

    @Test fun addMoreHonorsNineImageLimitAndRemovingLastClosesPreview() {
        showChat()
        select(picked(8))
        compose.onNodeWithTag("preview-strip").performScrollToNode(hasTestTag("preview-add"))
        select(picked(3), add = true)
        compose.onNodeWithText("发送 9 张").assertExists()
        compose.onNode(hasText("最多 9 张图片") and hasAnyAncestor(hasTestTag("image-preview"))).assertIsDisplayed()
        compose.onNodeWithTag("preview-add").assertDoesNotExist()
        compose.onNodeWithTag("preview-strip").performScrollToNode(hasTestTag("preview-thumb-8"))
        compose.onNodeWithTag("preview-thumb-8").performClick()
        compose.onNodeWithContentDescription("移除第 9 张图片").performClick()
        compose.onNodeWithText("发送 8 张").assertExists()
        compose.onNodeWithTag("preview-add").assertExists()
        compose.onNodeWithTag("cancel-images").performClick()
        waitNoTag("image-preview")
        assertTrue(repository.uploads.isEmpty())
        select(picked(1))
        compose.onNodeWithContentDescription("移除第 1 张图片").performClick()
        waitNoTag("image-preview")
        assertTrue(fake.queue.isEmpty())
    }

    @Test fun uploadFailureRetainsPreviewAndSendingFailureLeavesRetryableDrafts() {
        showChat()
        select(picked(2))
        repository.failUpload = true
        compose.onNodeWithTag("send-images").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("上传失败").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image-preview").assertExists()
        compose.onNodeWithText("发送 2 张").assertExists()
        assertTrue(fake.queue.isEmpty())
        assertTrue(repository.sent.isEmpty())
        repository.failUpload = false
        fake.failSend = true
        compose.onNodeWithTag("send-images").performClick()
        waitNoTag("image-preview")
        compose.waitUntil(15_000) { fake.queue.size == 2 && compose.onAllNodesWithText("重试").fetchSemanticsNodes().size == 2 }
        val ids = fake.queue.map { it.id }.toSet()
        val uploadCount = repository.uploads.size
        fake.failSend = false
        repeat(2) {
            compose.onAllNodesWithText("重试").onFirst().performClick()
            compose.waitUntil(15_000) { fake.queue.size == 1 - it }
        }
        assertEquals(ids, fake.rows.map { it.id }.toSet())
        assertEquals(uploadCount, repository.uploads.size)
    }

    @Test fun busyPreviewCannotBeDismissedOrEditedAndPickerCancellationDoesNothing() {
        showChat()
        picker.next = emptyList()
        compose.onNodeWithContentDescription("发送图片").performClick()
        compose.onNodeWithTag("image-preview").assertDoesNotExist()
        select(picked(2))
        val gate = CompletableDeferred<Unit>()
        repository.uploadGate = gate
        compose.onNodeWithTag("send-images").performClick()
        compose.waitUntil(5_000) { repository.uploadStarted }
        compose.onNodeWithTag("send-images").assertIsNotEnabled()
        compose.onNodeWithTag("cancel-images").assertIsNotEnabled()
        compose.onNodeWithTag("preview-add").assertIsNotEnabled()
        compose.onNodeWithContentDescription("移除第 1 张图片").assertIsNotEnabled()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("image-preview").assertExists()
        gate.complete(Unit)
        waitNoTag("image-preview")
        compose.waitUntil(15_000) { repository.sent.size == 2 }
    }

    @Test fun unreadablePictureDoesNotDiscardReadableOnes() {
        val bad = File.createTempFile("preview-bad-", ".jpg", compose.activity.cacheDir).also {
            it.writeText("not an image"); files += it
        }
        showChat()
        select(listOf(Uri.fromFile(bad)) + picked(1))
        compose.onNodeWithText("发送 1 张").assertExists()
        compose.onNode(hasText("有 1 张图片无法读取或过大") and hasAnyAncestor(hasTestTag("image-preview"))).assertIsDisplayed()
        compose.onNodeWithTag("cancel-images").performClick()
        assertTrue(repository.uploads.isEmpty())
    }

    @Test fun ninePicturesInPostKeepTheirOrderAndCanBeRemovedBeforePublishing() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                FitLogTheme { SocialTimelineScreen(repository, fake.me, onBack = {}, onPerson = {}) }
            }
        }
        waitTag("compose-post")
        compose.onNodeWithTag("compose-post").performClick()
        picker.next = picked(9)
        compose.onNodeWithTag("add-post-image").performClick()
        compose.waitUntil(15_000) { repository.uploads.size == 9 }
        assertTrue(fake.posts.isEmpty())
        compose.onNodeWithText("今天有什么新进步？").performClick().performTextInput("九张图片的动态")
        compose.onNodeWithContentDescription("移除第 9 张图片").assertIsDisplayed().performClick()
        compose.onNodeWithTag("publish-post").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { fake.posts.size == 1 }
        assertEquals((1..8).map { "post/$it" }, fake.posts.single().mediaPaths)
    }

    @Test fun previewFitsPortraitAndLandscapePicturesAndCanSwipe() {
        val images = listOf(PickedImage(jpeg(android.graphics.Color.GREEN)),
            PickedImage(jpeg(android.graphics.Color.BLUE, 160, 90)))
        var open by mutableStateOf(true)
        compose.setContent { FitLogTheme { if (open) ImageSendPreview(images, false, null, null, {}, {}, { open = false }, {}) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("第 1 张图片").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("第 1 张图片").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("第 2 张图片").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("send-images").assertIsDisplayed()
        val image = compose.onNodeWithTag("image-preview").captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "image-preview-device.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("cancel-images").performClick()
        waitNoTag("image-preview")
    }

    /** Drives the real activity-result contract and compression without relying on external gallery contents. */
    private class ResultPicker : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var next = emptyList<Uri>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            check(contract is androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia)
            dispatchResult(requestCode, next)
        }
    }

    private class MediaRepository(val fake: FakeSocial) : SocialRepository by fake {
        val uploads = mutableListOf<ByteArray>()
        val sent = mutableListOf<MessageDraft>()
        val events = mutableListOf<String>()
        var failUpload = false
        var uploadGate: CompletableDeferred<Unit>? = null
        var uploadStarted = false
        override suspend fun uploadChatImage(jpeg: ByteArray): String = upload(jpeg, "chat")
        override suspend fun uploadImage(jpeg: ByteArray): String = upload(jpeg, "post")
        private suspend fun upload(jpeg: ByteArray, prefix: String): String {
            uploadStarted = true
            uploadGate?.await()
            check(!failUpload) { "上传失败" }
            uploads += jpeg
            events += "upload"
            return "$prefix/${uploads.size}"
        }
        override suspend fun queue(peerId: String, draft: MessageDraft) {
            events += "queue"; fake.queue(peerId, draft)
        }
        override suspend fun send(peerId: String, draft: MessageDraft): ChatMessage {
            events += "send"
            val message = fake.send(peerId, draft).copy(mediaPath = draft.mediaPath)
            fake.rows = fake.rows.map { if (it.id == message.id) message else it }
            sent += draft
            return message
        }
        override suspend fun image(path: String): ByteArray = uploads.lastOrNull() ?: fake.imageBytes
    }
}
