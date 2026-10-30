package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

class SupabaseSocialRepository(
    context:Context,url:String,key:String,namespace:String="fitlog",
) : SupabaseFriendsRepository(context,url,key,namespace),SocialRepository,AutoCloseable {
    private val appContext=context.applicationContext
    private val cacheStore=lazy { Room.databaseBuilder(appContext,SocialCacheDatabase::class.java,"$namespace.social-cache.db").build() }
    private val cache get()=cacheStore.value
    private val settings=appContext.getSharedPreferences("$namespace.social-ownership",Context.MODE_PRIVATE)
    private val mediaDir=File(appContext.cacheDir,"social-media").apply {mkdirs()}
    private val selected = kotlinx.coroutines.flow.MutableStateFlow(settings.getString("exercise",null))
    val selectedExercise = selected as kotlinx.coroutines.flow.StateFlow<String?>

    private suspend fun rpc(name:String,data:JSONObject,owner:String=requireProfileId()):String = authorized("/rest/v1/rpc/$name","POST",data,expectedOwner=owner)
    private fun JSONObject.cursor(before:SocialCursor?):JSONObject = apply {
        before?.let {put("p_before",it.time.toString());put("p_before_id",it.id)}
    }
    private fun chat(action:String,peer:String)=JSONObject().put("p_action",action).put("p_peer",peer)

    override suspend fun messages(peerId:String,before:SocialCursor?):List<ChatMessage> {
        val owner=requireProfileId()
        try {
            val raw=JSONArray(rpc("social_chat",chat("list",peerId).cursor(before))).objects()
            check(profile.value?.id==owner) {"账号已切换"}
            cache.withTransaction {
                if(before==null) cache.dao().clear(owner,peerId)
                cache.dao().put(raw.map {
                    val m=it.chatMessage()
                    CachedMessageEntity(owner,peerId,m.id,m.createdAt.epochSecond*1_000_000+m.createdAt.nano/1000,it.toString())
                })
                cache.dao().trim(owner)
            }
            return raw.map {it.chatMessage()}
        } catch(e:java.io.IOException) {
            if(before!=null || profile.value?.id!=owner) throw e
            val saved=cache.dao().messages(owner,peerId)
            if(saved.isEmpty()) throw e
            return saved.map {JSONObject(it.json).chatMessage()}
        }
    }
    override suspend fun messageContext(peerId:String,id:String):List<ChatMessage> =
        JSONArray(rpc("social_chat_context",JSONObject().put("p_peer",peerId).put("p_id",id))).objects().map {it.chatMessage()}
    override suspend fun send(peerId:String,draft:MessageDraft):ChatMessage {
        SocialRules.message(draft)
        val result=JSONObject(rpc("social_chat",chat("send",peerId).put("p_id",draft.id).put("p_body",draft.body)
            .put("p_media",draft.mediaPath).put("p_reply",draft.replyId))).chatMessage()
        forgetPending(peerId,draft.id)
        return result
    }
    override suspend fun deleteMessage(peerId:String,id:String) {
        rpc("social_chat",chat("delete",peerId).put("p_id",id))
        cache.dao().remove(requireProfileId(),peerId,id)
    }
    override suspend fun withdrawMessage(peerId:String,id:String) {
        rpc("social_chat",chat("withdraw",peerId).put("p_id",id))
        cache.dao().remove(requireProfileId(),peerId,id)
    }
    override suspend fun clearChat(peerId:String) {
        rpc("social_chat",chat("clear",peerId))
        val owner=requireProfileId()
        cache.withTransaction {cache.dao().clear(owner,peerId);cache.dao().clearPending(owner,peerId)}
    }
    override suspend fun markRead(peerId:String) {rpc("social_chat",chat("mark_read",peerId))}
    override suspend fun forward(peerId:String,ids:List<String>,requestId:String):ChatMessage {
        require(ids.size in 1..SocialRules.MAX_FORWARD) {"一次转发 1～20 条"}
        return JSONObject(rpc("social_chat",chat("forward",peerId).put("p_id",requestId).put("p_ids",JSONArray(ids)))).chatMessage()
    }
    override suspend fun pending(peerId:String):List<MessageDraft> {
        val owner=requireProfileId()
        return cache.dao().pending(owner,peerId).map {
            val j=JSONObject(it.json)
            MessageDraft(it.id,j.optString("body"),j.optional("media_path"),j.optional("reply_id"))
        }
    }
    override suspend fun queue(peerId:String,draft:MessageDraft) {
        SocialRules.message(draft)
        val owner=requireProfileId()
        cache.withTransaction {
            val existing=cache.dao().pending(owner,peerId).any {it.id==draft.id}
            require(existing || cache.dao().pendingCount(owner)<200) {"待发送消息过多，请先重试或删除"}
            val j=JSONObject().put("body",draft.body).put("media_path",draft.mediaPath).put("reply_id",draft.replyId)
            cache.dao().enqueue(OutboxEntity(owner,peerId,draft.id,j.toString(),System.currentTimeMillis()))
        }
    }
    override suspend fun forgetPending(peerId:String,id:String) {cache.dao().forget(requireProfileId(),id)}

    override suspend fun feed(ownerId:String?,before:SocialCursor?):List<SocialPost> =
        JSONArray(rpc("social_feed",JSONObject().put("p_action","list").put("p_peer",ownerId).cursor(before))).objects().map {it.post()}
    override suspend fun publish(draft:PostDraft) {
        SocialRules.post(draft)
        rpc("social_feed",JSONObject().put("p_action","publish").put("p_id",draft.id).put("p_body",draft.body).put("p_media",JSONArray(draft.mediaPaths)))
    }
    override suspend fun deletePost(id:String) {rpc("social_feed",JSONObject().put("p_action","delete").put("p_id",id))}
    override suspend fun like(id:String,liked:Boolean) {rpc("social_feed",JSONObject().put("p_action",if(liked)"like" else "unlike").put("p_id",id))}
    override suspend fun comments(postId:String,before:SocialCursor?):List<SocialComment> =
        JSONArray(rpc("social_feed",JSONObject().put("p_action","comments").put("p_id",postId).cursor(before))).objects().map {
            SocialComment(it.getString("id"),it.getString("owner_id"),it.optString("nickname"),it.getString("body"),
                requireNotNull(it.getString("created_at").toSocialInstant()))
        }
    override suspend fun comment(postId:String,text:String,id:String) {
        require(text.trim().length in 1..1000) {"评论为 1～1000 字"}
        rpc("social_feed",JSONObject().put("p_action","comment").put("p_id",postId).put("p_body",text.trim()).put("p_comment_id",id))
    }
    override suspend fun deleteComment(postId:String,id:String) {
        rpc("social_feed",JSONObject().put("p_action","delete_comment").put("p_id",postId).put("p_comment_id",id))
    }
    override suspend fun person(id:String):SocialPerson {
        val j=JSONObject(rpc("social_person",JSONObject().put("p_peer",id)))
        return SocialPerson(j.socialProfile(),SharingPreferences(j.optBoolean("share_history"),j.optBoolean("share_live")),
            PresenceStatus.from(j.optString("status")),j.optJSONObject("live")?.takeIf{it.optString("session_id").isNotBlank()}?.liveTraining(),
            j.optString("updated_at").toSocialInstant())
    }
    override suspend fun sharedHistory(id:String,before:SocialCursor?):List<SharedWorkout> =
        JSONArray(rpc("social_training",JSONObject().put("p_action","history").put("p_peer",id).cursor(before))).objects().map {
            val j=it.getJSONObject("snapshot")
            SharedWorkout(it.getString("id"),it.getString("local_id"),requireNotNull(it.getString("started_at").toSocialInstant()),
                j.optString("label"),j.optString("status"),j.optInt("completed_sets"),j.optInt("total_sets"),
                j.optLong("duration_seconds"),(j.optJSONArray("exercises")?:JSONArray()).strings())
        }
    override suspend fun blocked():List<SocialProfile> =
        JSONArray(rpc("social_blocks",JSONObject())).objects().map {it.socialProfile()}

    override suspend fun uploadImage(jpeg:ByteArray):String {
        require(jpeg.size in 1..2_097_152) {"图片最大支持 2 MB"}
        val path=requireProfileId()+"/"+UUID.randomUUID()+".jpg"
        authorizedBinary("/storage/v1/object/fitlog-media/$path","POST",jpeg)
        return path
    }
    override suspend fun image(path:String):ByteArray = withContext(Dispatchers.IO) {
        require(path.matches(Regex("[a-fA-F0-9-]{36}/[a-fA-F0-9-]{36}\\.jpg"))) {"图片路径无效"}
        val owner=requireProfileId()
        val digest=MessageDigest.getInstance("SHA-256").digest("$owner|$path".toByteArray())
            .joinToString("") {"%02x".format(it)}
        val file=File(mediaDir,digest)
        if(file.isFile) {file.setLastModified(System.currentTimeMillis());return@withContext file.readBytes()}
        val bytes=authorizedBinary("/storage/v1/object/authenticated/fitlog-media/$path")
        check(profile.value?.id==owner) {"账号已切换"}
        val temp=File.createTempFile("image-",".tmp",mediaDir)
        try {temp.writeBytes(bytes);temp.renameTo(file)} finally {temp.delete()}
        var total=mediaDir.listFiles().orEmpty().sumOf{it.length()}
        for(old in mediaDir.listFiles().orEmpty().sortedBy{it.lastModified()}) {
            if(total<=32L*1024*1024) break
            val size=old.length()
            if(old.delete()) total-=size
        }
        bytes
    }
    override suspend fun saveAvatar(path:String) {
        require(path.startsWith(requireProfileId()+"/")) {"头像图片无效"}
        authorized("/rest/v1/social_profiles?id=eq.${requireProfileId()}","PATCH",JSONObject().put("avatar_path",path))
        refreshProfile()
    }
    override suspend fun heartbeat(deviceId:String,status:PresenceStatus,training:LiveTraining?) =
        heartbeatAs(requireProfileId(),deviceId,status,training)
    suspend fun heartbeatAs(owner:String,deviceId:String,status:PresenceStatus,training:LiveTraining?) {
        rpc("social_training",JSONObject().put("p_action","heartbeat").put("p_device",deviceId).put("p_status",status.name.lowercase())
            .put("p_snapshot",training?.toJson()?:JSONObject()),owner)
    }
    override suspend fun uploadWorkout(workout:Workout) = uploadWorkoutAs(requireProfileId(),workout)
    suspend fun uploadWorkoutAs(owner:String,workout:Workout) {
        check(boundTrainingOwner()==owner && owner==requireProfileId()) {"需要先确认本机训练记录的共享归属"}
        val now=Instant.now()
        val j=JSONObject().put("started_at",workout.session.startedAtUtc.toString()).put("label",workout.session.trainingLabel)
            .put("status",workout.historyStatus()).put("completed_sets",workout.completedSetCount)
            .put("total_sets",workout.plannedSetCount).put("duration_seconds",workout.elapsedDuration(now).seconds)
            .put("exercises",JSONArray(workout.exercises.map {e->
                e.exerciseNameSnapshot+" · "+e.sets.filter{it.status==SetStatus.COMPLETED}
                    .joinToString(" / "){"${WeightUnits.format(it.weightGrams,WeightUnit.KG)} kg × ${it.reps}"}
            }))
        rpc("social_training",JSONObject().put("p_action","upload").put("p_local_id",workout.session.id).put("p_snapshot",j),owner)
    }
    override fun boundTrainingOwner():String?=settings.getString("training_owner",null)
    override fun bindTrainingOwner() {check(settings.edit().putString("training_owner",requireProfileId()).commit()) {"无法保存共享归属"}}
    override fun selectTrainingExercise(id:String?) {selected.value=id;settings.edit().putString("exercise",id).apply()}
    fun deviceId():String = settings.getString("device_id",null) ?: UUID.randomUUID().toString().also {
        check(settings.edit().putString("device_id",it).commit()) {"无法保存设备标识"}
    }
    override suspend fun signOut() {
        val owner=profile.value?.id
        super.signOut()
        if(owner!=null) {
            cache.withTransaction {
                cache.dao().clearOwner(owner)
                cache.dao().clearOutboxOwner(owner)
            }
        }
        withContext(Dispatchers.IO) {mediaDir.listFiles().orEmpty().forEach {it.delete()}}
    }
    fun selectedTrainingExercise():String?=settings.getString("exercise",null)
    override fun close() {if(cacheStore.isInitialized()) cache.close()}
}
