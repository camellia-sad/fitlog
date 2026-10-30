package dev.lzk.fitlog.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialCacheTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun messageCacheIsOwnerScopedBoundedAndDeletionDoesNotAffectOtherAccount()=runBlocking {
        val database=Room.inMemoryDatabaseBuilder(context,SocialCacheDatabase::class.java).build()
        try {
            val dao=database.dao()
            dao.put((0..1600).map {CachedMessageEntity("a","peer","m$it",it.toLong(),"{}")})
            dao.put(listOf(CachedMessageEntity("b","peer","m1600",1600,"{}")))
            dao.trim("a")
            assertEquals(150,dao.messages("a","peer").size)
            assertEquals("m1600",dao.messages("a","peer").first().id)
            dao.remove("a","peer","m1600")
            assertFalse(dao.messages("a","peer").any{it.id=="m1600"})
            assertEquals(1,dao.messages("b","peer").size)
            dao.clearOwner("a")
            assertTrue(dao.messages("a","peer").isEmpty())
            assertEquals(1,dao.messages("b","peer").size)
        }finally{database.close()}
    }
    @Test fun outboxSurvivesReopenAndRetryUsesSameId()=runBlocking {
        val name="social-outbox-test.db"
        context.deleteDatabase(name)
        fun open()=Room.databaseBuilder(context,SocialCacheDatabase::class.java,name).build()
        var db=open()
        try {
            val draft=OutboxEntity("a","peer","same-id","payload",1)
            db.dao().enqueue(draft);db.dao().enqueue(draft)
            db.dao().enqueue(draft.copy(owner="b"))
            db.close();db=open()
            assertEquals(1,db.dao().pending("a","peer").size)
            assertEquals("same-id",db.dao().pending("a","peer").single().id)
            db.dao().clearOutboxOwner("a")
            assertTrue(db.dao().pending("a","peer").isEmpty())
            assertEquals(1,db.dao().pending("b","peer").size)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun postgresMicrosecondCursorAndNullFieldsDecodeWithoutPrecisionLoss() {
        val raw=JSONObject("""{"id":"m","sender":"a","recipient":"b","body":"text","created_at":"2026-10-30T10:20:30.123456+00:00","media_path":null,"reply_preview":null}""")
        val message=raw.chatMessage()
        assertEquals(123456000,message.createdAt.nano)
        assertNull(message.mediaPath)
        assertNull(message.replyPreview)
        assertFalse(message.withdrawn)
    }
}
