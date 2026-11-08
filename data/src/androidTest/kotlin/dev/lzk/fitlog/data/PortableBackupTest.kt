package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class PortableBackupTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun session(namespace:String,owner:String) {
        SocialSessionStore(context,namespace).write(JSONObject().put("profile",JSONObject().put("id",owner)
            .put("fitlog_id",owner.replace("-","")).put("nickname","测试用户")).toString())
    }
    @Test fun jsonV3RoundTripsPreferencesPreparationDraftsAndPreviewNeverWrites()=runBlocking {
        val names=listOf("extended-"+id()+".db","extended-"+id()+".db")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val a=FitLogDatabase.create(context,names[0]);val b=FitLogDatabase.create(context,names[1])
        val pa=UserPreferencesStore(context,scope,names[0]+".preferences_pb")
        val pb=UserPreferencesStore(context,scope,names[1]+".preferences_pb")
        try {
            ExerciseCatalog(context,a).initialize();ExerciseCatalog(context,b).initialize()
            val prefs=UserPreferences(equipment=setOf(EquipmentType.DUMBBELL),weightUnit=WeightUnit.LB,theme=ThemeMode.DARK,onboardingCompleted=true)
            pa.save(prefs);pa.savePreparedExercises(listOf("barbell-bench-press"))
            RoomTrainingRepository(a).saveTemplateDraft(TemplateDraft(name="尚未保存"))
            val exporter=JsonBackupRepository(a,pa,pa);val importer=JsonBackupRepository(b,pb,pb)
            val json=exporter.exportJson()
            assertEquals(9, JSONObject(json).getInt("schemaVersion"))
            importer.validateJson(json)
            assertTrue(b.trainingDao().allDrafts().isEmpty());assertNotEquals(prefs,pb.preferences.first())
            importer.importJson(json)
            assertEquals(prefs,pb.preferences.first())
            assertEquals(listOf("barbell-bench-press"),pb.preparedExercises.first())
            assertEquals("尚未保存",b.trainingDao().allDrafts().single().toDraft().name)
            val bad=JSONObject(json).put("preparedExercises",org.json.JSONArray().put("missing"))
            try {importer.importJson(bad.toString());fail("invalid prepared exercise accepted")} catch(_:IllegalArgumentException) {}
            assertEquals(prefs,pb.preferences.first())
        } finally {scope.cancel();a.close();b.close();names.forEach {context.deleteDatabase(it)}}
    }
    @Test fun timeThemeBackupStaysReadableForOlderVersions() {
        val prefs=UserPreferences(theme=ThemeMode.TIME,onboardingCompleted=true,hapticsEnabled=false)
        val json=prefs.backupJson()
        // Older versions only read "theme", which must stay a value they know.
        assertEquals("SYSTEM",json.getString("theme"))
        assertEquals(prefs,json.backupPreferences())
        // A backup written before themeMode/hapticsEnabled existed still imports with their defaults.
        json.remove("themeMode");json.remove("hapticsEnabled")
        assertEquals(prefs.copy(theme=ThemeMode.SYSTEM,hapticsEnabled=true),json.backupPreferences())
    }
    @Test fun archiveRestoresChatAndImagesWithoutCloudAndDeletedMessagesStayDeleted()=runBlocking {
        val owner=id();val peer=id();val namespace="portable-"+id();val second="portable-"+id()
        session(namespace,owner);session(second,owner)
        val a=FitLogDatabase.create(context,namespace);val b=FitLogDatabase.create(context,second)
        val sa=SupabaseSocialRepository(context,"","",namespace);val sb=SupabaseSocialRepository(context,"","",second)
        try {
            ExerciseCatalog(context,a).initialize();ExerciseCatalog(context,b).initialize()
            val path=owner+"/chat/"+id()+".jpg";val bytes=byteArrayOf(1,2,3,4)
            val m=ChatMessage(id(),owner,peer,"本地消息",Instant.now(),mediaPath=path)
            sa.localChat.saveImage(owner,path,bytes);sa.localChat.receive(owner,m)
            val source=PortableBackupRepository(JsonBackupRepository(a),sa)
            val target=PortableBackupRepository(JsonBackupRepository(b),sb)
            val archive=source.export(true)
            val entries=mutableSetOf<String>()
            ZipInputStream(ByteArrayInputStream(archive)).use {zip->while(true) {val e=zip.nextEntry ?: break;entries+=e.name}}
            assertTrue("chat/messages.json" in entries);assertTrue(entries.any {it.startsWith("media/")})
            assertFalse(entries.any {it.contains("token")})
            val preview=target.preview(archive)
            assertEquals(1,preview.messageCount);assertEquals(1,preview.mediaCount)
            assertTrue(sb.localChat.page(owner,peer,null).isEmpty())
            target.restore(preview)
            assertEquals(m,sb.localChat.page(owner,peer,null).single())
            assertArrayEquals(bytes,sb.localChat.image(owner,path))
            sb.localChat.remove(owner,peer,m.id)
            target.restore(target.preview(archive))
            assertTrue(sb.localChat.page(owner,peer,null).isEmpty())
            assertTrue(source.preview(source.export(false)).includesChat.not())
        } finally {a.close();b.close();sa.close();sb.close();listOf(namespace,second).forEach {context.deleteDatabase(it);context.deleteDatabase("$it.social-cache.db");SocialSessionStore(context,it).clear()}}
    }
    @Test fun mainJsonEmbedsChatButCloudArchiveAlsoRestoresOwnPostPictures()=runBlocking {
        val owner=id();val peer=id();val namespace="main-json-"+id();session(namespace,owner)
        val db=FitLogDatabase.create(context,namespace);val social=SupabaseSocialRepository(context,"","",namespace)
        try {
            ExerciseCatalog(context,db).initialize()
            val path=owner+"/"+id()+".jpg";val bytes=byteArrayOf(7,8,9)
            social.localChat.saveImage(owner,path,bytes)
            val post=JSONObject().put("id",id()).put("body","我的动态").put("created_at",Instant.now().toString())
                .put("media_paths",org.json.JSONArray().put(path))
            social.cache.dao().putPosts(listOf(LocalPostEntity(owner,post.getString("id"),post.toString())))
            val message=ChatMessage(id(),owner,peer,"聊天独立分区",Instant.now())
            social.localChat.receive(owner,message)
            val backup=PortableBackupRepository(JsonBackupRepository(db),social)
            val json=JSONObject(backup.exportLocalJson())
            assertEquals("FitLogChat",json.getJSONObject("chat").getString("format"))
            assertEquals("聊天独立分区",json.getJSONObject("chat").getJSONArray("messages").getJSONObject(0).getJSONObject("message").getString("body"))
            assertEquals(path,json.getJSONObject("publishedPosts").getJSONArray("posts").getJSONObject(0).getJSONObject("post").getJSONArray("media_paths").getString(0))
            assertFalse(json.has("media"))
            val snapshot=backup.export(false)
            social.localChat.file(owner,path).delete()
            assertNull(social.localChat.image(owner,path))
            val preview=backup.preview(snapshot)
            assertFalse(preview.includesChat);assertEquals(1,preview.mediaCount)
            backup.restore(preview)
            assertArrayEquals(bytes,social.localChat.image(owner,path))
            // Local JSON restoration restores text without requiring image files.
            social.cache.dao().clearOwner(owner)
            backup.importLocalJson(json.toString())
            assertEquals(message,social.localChat.page(owner,peer,null).single())
        } finally {social.close();db.close();context.deleteDatabase(namespace);context.deleteDatabase("$namespace.social-cache.db");SocialSessionStore(context,namespace).clear()}
    }
    @Test fun crossAccountAndPathTraversalArchivesAreRejected()=runBlocking {
        val namespace="portable-invalid-"+id();val owner=id();session(namespace,owner)
        val db=FitLogDatabase.create(context,namespace);val social=SupabaseSocialRepository(context,"","",namespace)
        try {
            ExerciseCatalog(context,db).initialize()
            val backup=PortableBackupRepository(JsonBackupRepository(db),social)
            try {backup.importLocalJson("[".repeat(40)+"]".repeat(40));fail("deep JSON accepted")}
            catch(e:IllegalArgumentException) {assertEquals("备份结构嵌套过深",e.message)}
            val other=JSONObject().put("format","FitLogChat").put("version",1).put("owner",id())
                .put("messages",org.json.JSONArray()).put("pending",org.json.JSONArray()).toString()
            try {social.localChat.validate(owner,other);fail("wrong owner accepted")} catch(_:IllegalArgumentException) {}
            val output=java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(output).use {it.putNextEntry(java.util.zip.ZipEntry("../escape"));it.write(byteArrayOf(1));it.closeEntry()}
            try {backup.preview(output.toByteArray());fail("unsafe path accepted")} catch(_:IllegalArgumentException) {} catch(_:java.util.zip.ZipException) {}
        } finally {social.close();db.close();context.deleteDatabase(namespace);SocialSessionStore(context,namespace).clear()}
    }
    @Test fun localWithdrawAndDeletionScrubQuotePreviews()=runBlocking {
        val owner=id();val peer=id()
        val db=Room.inMemoryDatabaseBuilder(context,SocialCacheDatabase::class.java).build()
        val store=LocalChatStore(context,db,"quote-test")
        try {
            val source=ChatMessage(id(),peer,owner,"秘密",Instant.now())
            val reply=ChatMessage(id(),owner,peer,"回复",Instant.now(),replyId=source.id,replyPreview="秘密")
            store.receive(owner,source);store.receive(owner,reply)
            store.receive(owner,source.copy(body="",withdrawn=true))
            store.receive(owner,source) // A stale response must not undo withdrawal.
            assertTrue(store.page(owner,peer,null).first {it.id==source.id}.withdrawn)
            assertEquals("[原消息不可用]",store.page(owner,peer,null).first {it.id==reply.id}.replyPreview)
            store.remove(owner,peer,source.id)
            store.receive(owner,source)
            assertFalse(store.page(owner,peer,null).any {it.id==source.id})
        } finally {db.close()}
    }
}
