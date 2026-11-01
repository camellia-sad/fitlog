package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataRemovalTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    @Test fun deletedSessionCleanupNeverSignsOutADifferentAccount()=runBlocking {
        val namespace="session-erase-"+id()
        val owner=id();val previous=id()
        val store=SocialSessionStore(context,namespace)
        store.write(JSONObject().put("profile",JSONObject().put("id",owner).put("fitlog_id","a".repeat(32)).put("nickname","保留账号")).toString())
        val repository=object:SupabaseFriendsRepository(context,"","",namespace) {
            suspend fun forget(owner:String)=forgetDeletedSession(owner)
        }
        try {
            repository.forget(previous)
            assertEquals(owner,repository.profile.value?.id);assertNotNull(store.read())
            repository.forget(owner)
            assertNull(repository.profile.value);assertNull(store.read())
        } finally {store.clear()}
    }

    @Test fun trainingClearPreservesTemplatesPlansCustomExercisesAndDrafts()=runBlocking {
        val name="clear-training-"+id()
        val db=FitLogDatabase.create(context,name)
        try {
            ExerciseCatalog(context,db).initialize()
            val training=RoomTrainingRepository(db);val workouts=RoomWorkoutRepository(db)
            val custom=id()
            db.trainingDao().importExercises(listOf(db.workoutDao().exercise("barbell-bench-press")!!.copy(id=custom,isCustom=true,isFavorite=true)))
            val template=training.saveTemplate(TemplateDraft(name="保留模板",exercises=listOf(TemplateExercise(id(),custom,0))))
            training.saveTemplateDraft(TemplateDraft(name="保留草稿"))
            val plan=TrainingPlan(LocalDate.now(),templateIds=listOf(template)+List(6){null})
            training.savePlan(plan)
            val finished=workouts.startWorkout(template)
            val e=finished.exercises.single();val set=e.sets.first()
            workouts.completeSet(e.id,set.id,40_000,8)
            workouts.finishWorkout(finished.session.id,"感想")
            workouts.startWorkout(template)
            training.clearTrainingHistory()
            assertNull(workouts.observeActiveWorkout().first())
            assertTrue(db.trainingDao().allSessions().isEmpty())
            assertTrue(db.trainingDao().allSessionExercises().isEmpty())
            assertTrue(db.trainingDao().allSets().isEmpty())
            assertEquals(template,training.observeTemplates().first().single().id)
            assertEquals(plan,training.observePlan().first())
            assertEquals("保留草稿",training.observeTemplateDrafts().first().single().name)
            assertTrue(db.workoutDao().exercise(custom)!!.isFavorite)
            assertTrue(db.workoutDao().exercise(custom)!!.isCustom)
            assertEquals(custom,workouts.startWorkout(template).exercises.single().exerciseId)
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun chatClearDeletesChatAndPendingPicturesButKeepsPostImagesAndOtherAccounts()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,SocialCacheDatabase::class.java).build()
        val store=LocalChatStore(context,db,"clear-chat-"+id())
        val owner=id();val peer=id()
        try {
            val image=owner+"/chat/"+id()+".jpg"
            val shared=owner+"/"+id()+".jpg"
            val pending=owner+"/chat/"+id()+".jpg"
            val other=peer+"/chat/"+id()+".jpg"
            listOf(image,shared,pending).forEach {store.saveImage(owner,it,byteArrayOf(1,2))}
            store.saveImage(peer,other,byteArrayOf(3,4))
            val message=ChatMessage(id(),owner,peer,"清空",Instant.now(),mediaPath=image)
            store.receive(owner,message)
            store.receive(owner,ChatMessage(id(),owner,peer,"动态图片也出现在聊天",Instant.now(),mediaPath=shared))
            store.receive(peer,ChatMessage(id(),peer,owner,"其他账号",Instant.now(),mediaPath=other))
            db.dao().enqueue(OutboxEntity(owner,peer,id(),JSONObject().put("body","待发送").put("media_path",pending).toString(),0))
            val post=JSONObject().put("media_paths",JSONArray().put(shared))
            db.dao().putPosts(listOf(LocalPostEntity(owner,id(),post.toString())))
            store.clearChats(owner)
            assertTrue(store.page(owner,peer,null).isEmpty())
            assertTrue(db.dao().allPending(owner).isEmpty())
            assertNull(store.image(owner,image));assertNull(store.image(owner,pending))
            assertNotNull(store.image(owner,shared));assertEquals(1,db.dao().posts(owner).size)
            assertEquals(1,store.page(peer,owner,null).size);assertNotNull(store.image(peer,other))
            store.receive(owner,message)
            assertTrue(store.page(owner,peer,null).isEmpty())
        } finally {store.eraseAccount(owner);store.eraseAccount(peer);db.close()}
    }
    @Test fun accountLocalEraseIncludesPostsAndPicturesButIsIsolatedByOwner()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,SocialCacheDatabase::class.java).build()
        val store=LocalChatStore(context,db,"erase-social-"+id())
        val owner=id();val peer=id()
        try {
            val image=owner+"/chat/"+id()+".jpg";val other=peer+"/chat/"+id()+".jpg"
            store.saveImage(owner,image,byteArrayOf(1));store.saveImage(peer,other,byteArrayOf(2))
            store.receive(owner,ChatMessage(id(),owner,peer,"删除",Instant.now(),mediaPath=image))
            store.receive(peer,ChatMessage(id(),peer,owner,"保留",Instant.now(),mediaPath=other))
            db.dao().putPosts(listOf(LocalPostEntity(owner,id(),"{}")))
            db.dao().enqueue(OutboxEntity(owner,peer,id(),"{}",0))
            store.eraseAccount(owner)
            assertTrue(db.dao().all(owner).isEmpty());assertTrue(db.dao().posts(owner).isEmpty())
            assertTrue(db.dao().allPending(owner).isEmpty());assertNull(store.image(owner,image))
            assertEquals(1,db.dao().all(peer).size);assertNotNull(store.image(peer,other))
        } finally {store.eraseAccount(owner);store.eraseAccount(peer);db.close()}
    }
}
