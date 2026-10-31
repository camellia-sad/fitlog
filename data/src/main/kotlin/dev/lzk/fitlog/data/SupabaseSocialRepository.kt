package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
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
    private val cacheStore=lazy { Room.databaseBuilder(appContext,SocialCacheDatabase::class.java,"$namespace.social-cache.db").addMigrations(SocialCacheDatabase.MIGRATION_1_2).build() }
    internal val cache get()=cacheStore.value
    internal val localChat by lazy {LocalChatStore(appContext,cache,namespace)}
    internal val localGate=Mutex()
    private val settings=appContext.getSharedPreferences("$namespace.social-ownership",Context.MODE_PRIVATE)
    private val mediaDir=File(appContext.cacheDir,"social-media").apply {mkdirs()}
    private val selected = kotlinx.coroutines.flow.MutableStateFlow(settings.getString("exercise",null))
    val selectedExercise = selected as kotlinx.coroutines.flow.StateFlow<String?>

    private suspend fun rpc(name:String,data:JSONObject,owner:String=requireProfileId()):String = authorized("/rest/v1/rpc/$name","POST",data,expectedOwner=owner)
    private fun JSONObject.cursor(before:SocialCursor?):JSONObject = apply {
        before?.let {put("p_before",it.time.toString());put("p_before_id",it.id)}
    }
    private fun chat(action:String,peer:String)=JSONObject().put("p_action",action).put("p_peer",peer)

    private suspend fun relay(action:String,peer:String?=null,id:String?=null,payload:JSONObject?=null,owner:String=requireProfileId()):String =
        rpc("social_chat_relay",JSONObject().put("p_action",action).put("p_peer",peer).put("p_id",id).put("p_payload",payload),owner)

    /** ACK only after the complete message and every attachment are durably stored. */
    private suspend fun receiveInbox(owner:String) {
        relay("enable",owner=owner)
        var after:SocialCursor?=null
        repeat(4) {
            val payload=JSONObject().apply {after?.let {put("after_time",it.time.toString());put("after_id",it.id)}}
            val incoming=JSONArray(relay("pull",payload=payload,owner=owner)).objects().map {it.chatMessage()}
            if(incoming.isEmpty()) return
            for(message in incoming) {
                check(profile.value?.id==owner) {"账号已切换"}
                try {
                    if(cache.dao().find(owner,message.id)?.hidden!=true) {
                        localChat.receive(owner,message)
                        for(path in message.mediaPaths()) {
                            if(localChat.image(owner,path)==null) localChat.saveImage(owner,path,image(path))
                        }
                    }
                    relay("ack",id=message.id,owner=owner)
                } catch(e:CancellationException) {throw e}
                catch(e:java.io.IOException) {
                    check(profile.value?.id==owner) {"登录已失效"}
                    // Keep this envelope unacknowledged. Other text messages can still be delivered.
                } catch(_:IllegalArgumentException) {
                    // Unsupported attachment paths must not block other inbox entries.
                }
            }
            after=incoming.last().cursor
            if(incoming.size<50) return
        }
    }

    override suspend fun cachedFriends():List<Friend> {
        val owner=profile.value?.id ?: return emptyList()
        val rows=JSONArray(settings.getString("contacts_$owner","[]")).objects().map {j->
            Friend(j.socialProfile(),j.optString("note"),j.getBoolean("accepted"),j.getBoolean("incoming"),PresenceStatus.OFFLINE)
        }
        return localFriends(owner,rows)
    }
    private suspend fun localFriends(owner:String,rows:List<Friend>)=rows.map {friend->
        val last=localChat.page(owner,friend.profile.id,null).firstOrNull()
        friend.copy(lastMessage=last?.let {if(it.withdrawn) "[已撤回]" else it.body.ifBlank {"[图片]"}},
            lastMessageAt=last?.createdAt,unread=cache.dao().unread(owner,friend.profile.id))
    }.sortedByDescending {it.lastMessageAt}
    override suspend fun friends():List<Friend> {
        val owner=requireProfileId()
        val remote=super.friends()
        check(profile.value?.id==owner) {"账号已切换"}
        val contacts=JSONArray(remote.map {f->JSONObject().put("id",f.profile.id).put("fitlog_id",f.profile.fitlogId)
            .put("nickname",f.profile.nickname).put("bio",f.profile.bio).put("avatar_path",f.profile.avatarPath)
            .put("note",f.note).put("accepted",f.accepted).put("incoming",f.incoming)})
        check(settings.edit().putString("contacts_$owner",contacts.toString()).commit()) {"无法保存本机通讯录"}
        return localGate.withLock {
            receiveInbox(owner)
            remote.map {friend->
                val last=localChat.page(owner,friend.profile.id,null).firstOrNull()
                friend.copy(lastMessage=last?.let {if(it.withdrawn) "[已撤回]" else it.body.ifBlank {"[图片]"}},
                    lastMessageAt=last?.createdAt,unread=cache.dao().unread(owner,friend.profile.id))
            }.sortedByDescending {it.lastMessageAt}
        }
    }
    override suspend fun localMessages(peerId:String)=localChat.page(requireProfileId(),peerId,null)
    override suspend fun messages(peerId:String,before:SocialCursor?):List<ChatMessage> = localGate.withLock {
        val owner=requireProfileId()
        if(before==null) {
            try {receiveInbox(owner)}
            catch(e:CancellationException) {throw e}
            catch(e:java.io.IOException) {if(localChat.page(owner,peerId,null).isEmpty()) throw e}
        }
        localChat.page(owner,peerId,before)
    }
    override suspend fun messageContext(peerId:String,id:String):List<ChatMessage> {
        val owner=requireProfileId()
        val source=cache.dao().find(owner,id)?.takeIf {!it.hidden && it.peer==peerId} ?: error("原消息已删除或不在本机")
        val before=cache.dao().page(owner,peerId,source.micros,source.id).take(25)
        val after=cache.dao().newer(owner,peerId,source.micros,source.id).reversed()
        return (after+source+before).map {JSONObject(it.json).chatMessage()}
    }
    private suspend fun sendPayload(owner:String,peer:String,id:String,payload:JSONObject):ChatMessage {
        val response=JSONObject(relay("send",peer,id,payload,owner))
        val value=JSONObject(payload.toString()).put("id",id).put("sender",owner).put("recipient",peer)
            .put("created_at",response.getString("created_at")).chatMessage()
        localChat.receive(owner,value)
        relay("ack",id=id,owner=owner)
        cache.dao().forget(owner,id)
        return value
    }
    override suspend fun send(peerId:String,draft:MessageDraft):ChatMessage = localGate.withLock {
        SocialRules.message(draft)
        val owner=requireProfileId()
        relay("enable",owner=owner)
        val queued=cache.dao().pending(owner,peerId).find {it.id==draft.id}
        val saved=queued?.let {JSONObject(it.json)}
        val payload=if(saved?.has("forwarded")==true) saved else JSONObject()
            .put("body",draft.body).put("media_path",draft.mediaPath).put("reply_id",draft.replyId)
            .put("reply_preview",draft.replyId?.let {id->
                cache.dao().find(owner,id)?.takeIf {!it.hidden}?.let {JSONObject(it.json).chatMessage()}
                    ?.takeIf {!it.withdrawn}?.body?.ifBlank {"[图片或记录]"}?.take(200) ?: "[原消息不可用]"
            })
        sendPayload(owner,peerId,draft.id,payload)
    }
    override suspend fun deleteMessage(peerId:String,id:String) = localGate.withLock {
        localChat.remove(requireProfileId(),peerId,id)
    }
    override suspend fun withdrawMessage(peerId:String,id:String) = localGate.withLock {
        val owner=requireProfileId()
        val source=cache.dao().find(owner,id)?.takeIf {!it.hidden && it.peer==peerId} ?: error("消息不存在")
        val message=JSONObject(source.json).chatMessage()
        check(message.sender==owner) {"只能撤回自己的消息"}
        relay("withdraw",peerId,id,owner=owner)
        localChat.receive(owner,message.copy(body="",mediaPath=null,forwarded=emptyList(),replyPreview=null,withdrawn=true))
    }
    override suspend fun clearChat(peerId:String) = localGate.withLock {
        val owner=requireProfileId()
        val rows=cache.dao().all(owner).filter {it.peer==peerId}
        val ids=rows.map {it.id}
        val paths=rows.filter {!it.hidden}.flatMap {JSONObject(it.json).chatMessage().mediaPaths()}
        cache.withTransaction {
            cache.dao().clear(owner,peerId);cache.dao().clearPending(owner,peerId)
            localChat.hideQuotes(owner,ids.toSet())
            localChat.pruneImages(owner,paths)
        }
    }
    override suspend fun markRead(peerId:String) {cache.dao().read(requireProfileId(),peerId)}
    override suspend fun forward(peerId:String,ids:List<String>,requestId:String):ChatMessage = localGate.withLock {
        val owner=requireProfileId()
        require(ids.size in 1..SocialRules.MAX_FORWARD) {"一次转发 1～20 条"}
        val items=ids.map {id->
            val row=cache.dao().find(owner,id)?.takeIf {!it.hidden} ?: error("转发消息不在本机")
            JSONObject(row.json).chatMessage().also {check(!it.withdrawn) {"消息已撤回"}}
        }
        val payload=JSONObject()
        if(items.size==1) {
            val message=items.single()
            payload.put("body",message.body).put("media_path",message.mediaPath?.let {copyChatImage(it)})
                .put("forwarded",JSONArray(message.forwarded.map {part->
                    JSONObject().put("sender",part.sender).put("body",part.body)
                        .put("media_path",part.mediaPath?.let {copyChatImage(it)}).put("created_at",part.at?.toString())
                }))
        } else {
            require(items.all {it.forwarded.isEmpty()}) {"聊天记录请单独转发"}
            payload.put("body","聊天记录").put("forwarded",JSONArray(items.map {message->
                val name=if(message.sender==owner) profile.value?.nickname ?: "我"
                    else runCatching {person(message.sender).profile.nickname}.getOrDefault("好友")
                JSONObject().put("sender",name).put("body",message.body).put("created_at",message.createdAt.toString())
                    .put("media_path",message.mediaPath?.let {copyChatImage(it)})
            }))
        }
        require(payload.toString().toByteArray().size<=250_000) {"转发内容过长，请减少条数"}
        cache.dao().enqueue(OutboxEntity(owner,peerId,requestId,payload.toString(),System.currentTimeMillis()))
        relay("enable",owner=owner)
        sendPayload(owner,peerId,requestId,payload)
    }
    private suspend fun copyChatImage(path:String)=uploadChatImage(image(path))

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
            if(existing) return@withTransaction
            val j=JSONObject().put("body",draft.body).put("media_path",draft.mediaPath).put("reply_id",draft.replyId)
            cache.dao().enqueue(OutboxEntity(owner,peerId,draft.id,j.toString(),System.currentTimeMillis()))
        }
    }
    override suspend fun forgetPending(peerId:String,id:String) {cache.dao().forget(requireProfileId(),id)}

    override suspend fun feed(ownerId:String?,before:SocialCursor?):List<SocialPost> {
        val owner=requireProfileId()
        val posts=JSONArray(rpc("social_feed",JSONObject().put("p_action","list").put("p_peer",ownerId).cursor(before),owner)).objects().map {it.post()}
        val old=cache.dao().posts(owner).associateBy {it.id}
        cache.dao().putPosts(posts.filter {it.ownerId==owner && old[it.id]?.hidden!=true}.map {p->
            LocalPostEntity(owner,p.id,JSONObject().put("id",p.id).put("body",p.body).put("created_at",p.createdAt.toString())
                .put("media_paths",JSONArray(p.mediaPaths)).toString())
        })
        return posts
    }
    override suspend fun publish(draft:PostDraft) {
        SocialRules.post(draft)
        val owner=requireProfileId()
        rpc("social_feed",JSONObject().put("p_action","publish").put("p_id",draft.id).put("p_body",draft.body).put("p_media",JSONArray(draft.mediaPaths)),owner)
        cache.dao().putPosts(listOf(LocalPostEntity(owner,draft.id,JSONObject().put("id",draft.id).put("body",draft.body)
            .put("created_at",Instant.now().toString()).put("media_paths",JSONArray(draft.mediaPaths)).toString())))
    }
    override suspend fun deletePost(id:String) {
        val owner=requireProfileId()
        rpc("social_feed",JSONObject().put("p_action","delete").put("p_id",id),owner)
        cache.dao().hidePost(owner,id)
    }
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

    override suspend fun uploadChatImage(jpeg:ByteArray):String {
        val owner=requireProfileId()
        require(jpeg.size in 1..2_097_152) {"图片最大支持 2 MB"}
        val path=owner+"/chat/"+UUID.randomUUID()+".jpg"
        localChat.saveImage(owner,path,jpeg)
        authorizedBinary("/storage/v1/object/fitlog-media/$path","POST",jpeg,expectedOwner=owner)
        return path
    }
    override suspend fun uploadImage(jpeg:ByteArray):String {
        require(jpeg.size in 1..2_097_152) {"图片最大支持 2 MB"}
        val owner=requireProfileId()
        val path=owner+"/"+UUID.randomUUID()+".jpg"
        authorizedBinary("/storage/v1/object/fitlog-media/$path","POST",jpeg,expectedOwner=owner)
        localChat.saveImage(owner,path,jpeg)
        return path
    }
    override suspend fun image(path:String):ByteArray = withContext(Dispatchers.IO) {
        requireMediaPath(path)
        val owner=requireProfileId()
        localChat.image(owner,path)?.let {return@withContext it}
        val digest=MessageDigest.getInstance("SHA-256").digest("$owner|$path".toByteArray())
            .joinToString("") {"%02x".format(it)}
        val file=File(mediaDir,digest)
        if(file.isFile) {file.setLastModified(System.currentTimeMillis());return@withContext file.readBytes()}
        val bytes=authorizedBinary("/storage/v1/object/authenticated/fitlog-media/$path")
        check(profile.value?.id==owner) {"账号已切换"}
        if(cache.dao().ownsPostImage(owner,JSONObject.quote(path))>0) localChat.saveImage(owner,path,bytes)
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
        super.signOut()
        // Local history and queued drafts survive logout, isolated by owner ID.
        withContext(Dispatchers.IO) {mediaDir.listFiles().orEmpty().forEach {it.delete()}}
    }
    fun selectedTrainingExercise():String?=settings.getString("exercise",null)
    suspend fun prepareOwnMedia(owner:String) {
        var cursor:SocialCursor?=null
        do {
            check(profile.value?.id==owner) {"账号已切换"}
            val page=feed(owner,cursor)
            if(page.isEmpty()) break
            for(post in page) for(path in post.mediaPaths) {
                if(localChat.image(owner,path)==null) localChat.saveImage(owner,path,image(path))
            }
            cursor=page.last().cursor
        } while(page.size==SocialRules.FEED_PAGE)
    }
    internal suspend fun exportOwnPosts(owner:String):JSONObject = JSONObject().put("owner",owner)
        .put("posts",JSONArray(cache.dao().posts(owner).map {row->
            JSONObject().put("id",row.id).put("hidden",row.hidden).put("post",JSONObject(row.json))
        }))
    internal fun validateOwnPosts(owner:String,root:JSONObject):List<LocalPostEntity> {
        require(root.getString("owner")==owner) {"动态备份不属于当前账号"}
        val rows=root.getJSONArray("posts").objects().map {j->
            val id=j.getString("id");UUID.fromString(id)
            val hidden=j.getBoolean("hidden");val post=j.getJSONObject("post")
            if(!hidden) {
                require(post.getString("id")==id && post.getString("body").length<=4000) {"动态内容无效"}
                Instant.parse(post.getString("created_at"))
                val paths=post.getJSONArray("media_paths").strings()
                require(paths.size<=9) {"动态图片过多"}
                paths.forEach {requireMediaPath(it);require(it.startsWith("$owner/")) {"动态图片归属无效"}}
            }
            LocalPostEntity(owner,id,post.toString(),hidden)
        }
        require(rows.distinctBy {it.id}.size==rows.size) {"动态标识重复"}
        return rows
    }
    internal suspend fun restoreOwnPosts(owner:String,rows:List<LocalPostEntity>) {
        val old=cache.dao().posts(owner).associateBy {it.id}
        cache.dao().putPosts(rows.filter {old[it.id]==null || it.hidden})
    }
    data class CloudBackup(val name:String,val createdAt:Instant?,val size:Long)
    suspend fun cloudBackups(owner:String=requireProfileId()):List<CloudBackup> =
        JSONArray(authorized("/storage/v1/object/list/fitlog-backups","POST",JSONObject().put("prefix",owner+"/")
            .put("limit",100).put("sortBy",JSONObject().put("column","created_at").put("order","desc")),expectedOwner=owner))
            .objects().filter {it.optString("name").matches(Regex("[a-fA-F0-9-]{36}\\.zip"))}
            .map {CloudBackup(it.getString("name"),it.optString("created_at").toSocialInstant(),it.optJSONObject("metadata")?.optLong("size") ?: 0)}
    suspend fun uploadBackup(bytes:ByteArray,owner:String):CloudBackup {
        check(profile.value?.id==owner) {"账号已切换"}
        require(bytes.size<=50*1024*1024) {"云端备份最大 50 MB，请导出本地备份"}
        val name=UUID.randomUUID().toString()+".zip"
        authorizedBinary("/storage/v1/object/fitlog-backups/$owner/$name","POST",bytes,"application/zip",owner)
        // Only remove old snapshots after the new upload succeeds; leave backups intact on a failed list.
        val current=cloudBackups(owner)
        val old=current.drop(3).filter {it.name!=name}
        if(old.isNotEmpty()) deleteBackups(old.map {it.name},owner)
        return current.find {it.name==name} ?: CloudBackup(name,Instant.now(),bytes.size.toLong())
    }
    suspend fun downloadBackup(name:String,owner:String):ByteArray {
        require(name.matches(Regex("[a-fA-F0-9-]{36}\\.zip"))) {"备份名称无效"}
        return authorizedBinary("/storage/v1/object/authenticated/fitlog-backups/$owner/$name",expectedOwner=owner,maxBytes=50*1024*1024)
    }
    suspend fun deleteBackups(names:List<String>,owner:String) {
        require(names.all {it.matches(Regex("[a-fA-F0-9-]{36}\\.zip"))}) {"备份名称无效"}
        authorized("/storage/v1/object/fitlog-backups","DELETE",JSONObject().put("prefixes",JSONArray(names.map {"$owner/$it"})),expectedOwner=owner)
    }
    override fun close() {if(cacheStore.isInitialized()) cache.close()}
}
