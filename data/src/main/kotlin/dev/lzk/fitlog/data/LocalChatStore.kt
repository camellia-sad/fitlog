package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.withTransaction
import dev.lzk.fitlog.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class LocalChatStore(context:Context,private val db:SocialCacheDatabase,namespace:String) {
    private val root=File(context.filesDir,"$namespace.chat-media").apply {mkdirs()}
    fun file(owner:String,path:String):File {
        require(owner.matches(Regex("[a-fA-F0-9-]{36}"))) {"账号无效"}
        val name=MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") {"%02x".format(it)}
        return File(File(root,owner).apply {mkdirs()},name)
    }
    fun image(owner:String,path:String)=file(owner,path).takeIf {it.isFile}?.readBytes()
    fun saveImage(owner:String,path:String,bytes:ByteArray) {
        require(bytes.size in 1..2_097_152) {"聊天图片最大 2 MB"}
        val destination=file(owner,path)
        if(destination.isFile) return
        val temp=File.createTempFile("media-",".tmp",destination.parentFile)
        try {
            java.io.FileOutputStream(temp).use {it.write(bytes);it.fd.sync()}
            check(temp.renameTo(destination)) {"无法保存聊天图片"}
        } finally {temp.delete()}
    }
    suspend fun receive(owner:String,message:ChatMessage) = db.withTransaction {
        val old=db.dao().find(owner,message.id)
        if(old?.hidden==true || old!=null && JSONObject(old.json).optBoolean("withdrawn") && !message.withdrawn) return@withTransaction
        val peerId=if(message.sender==owner) message.recipient else message.sender
        val source=message.replyId?.let {db.dao().find(owner,it)}?.takeIf {it.peer==peerId}
        val original=source?.takeIf {!it.hidden}?.let {JSONObject(it.json).chatMessage()}
        val normalized=if(source?.hidden==true || original?.withdrawn==true) message.copy(replyPreview="[原消息不可用]")
            else if(original!=null) message.copy(replyPreview=original.body.ifBlank {"[图片或记录]"}.take(200)) else message
        val peer=if(message.sender==owner) message.recipient else message.sender
        db.dao().put(listOf(CachedMessageEntity(owner,peer,message.id,message.micros(),normalized.toJson().toString(),
            unread=old?.unread ?: (message.sender!=owner))))
        if(message.withdrawn) {hideQuotes(owner,message.id);old?.let {pruneImages(owner,JSONObject(it.json).chatMessage().mediaPaths())}}
    }
    suspend fun hideQuotes(owner:String,id:String)=hideQuotes(owner,setOf(id))
    suspend fun hideQuotes(owner:String,ids:Set<String>) {
        if(ids.isEmpty()) return
        for(row in db.dao().all(owner).filter {!it.hidden}) {
            val message=JSONObject(row.json).chatMessage()
            if(message.replyId in ids) db.dao().put(listOf(row.copy(json=message.copy(replyPreview="[原消息不可用]").toJson().toString())))
        }
    }
    suspend fun remove(owner:String,peer:String,id:String)=db.withTransaction {
        val paths=db.dao().find(owner,id)?.takeIf {!it.hidden}?.let {JSONObject(it.json).chatMessage().mediaPaths()}.orEmpty()
        db.dao().remove(owner,peer,id);hideQuotes(owner,id)
        pruneImages(owner,paths)
    }
    suspend fun pruneImages(owner:String,paths:Collection<String>) {paths.forEach {if(db.dao().mediaReferences(owner,JSONObject.quote(it))==0) file(owner,it).delete()}}
    suspend fun page(owner:String,peer:String,before:SocialCursor?)=db.dao()
        .page(owner,peer,before?.let {it.time.epochSecond*1_000_000+it.time.nano/1000} ?: Long.MAX_VALUE,
            before?.id ?: "~").map {JSONObject(it.json).chatMessage()}
    suspend fun export(owner:String):String {
        val messages=JSONArray()
        db.dao().all(owner).forEach {row->
            messages.put(JSONObject().put("peer",row.peer).put("id",row.id).put("micros",row.micros)
                .put("hidden",row.hidden).put("message",if(row.hidden) JSONObject() else JSONObject(row.json)))
        }
        val pending=JSONArray()
        db.dao().allPending(owner).forEach {row->pending.put(JSONObject().put("peer",row.peer).put("id",row.id)
            .put("queuedAt",row.queuedAt).put("draft",JSONObject(row.json)))}
        return JSONObject().put("format","FitLogChat").put("version",1).put("owner",owner)
            .put("messages",messages).put("pending",pending).toString()
    }
    fun validate(owner:String,text:String):Pair<List<CachedMessageEntity>,List<OutboxEntity>> {
        require(text.toByteArray().size<=32*1024*1024) {"聊天记录超过 32 MB"}
        val root=parseBackupObject(text)
        require(root.getString("format")=="FitLogChat" && root.getInt("version")==1 && root.getString("owner")==owner) {"聊天备份不属于当前账号"}
        val rows=root.getJSONArray("messages").objects().map {j->
            val peer=j.getString("peer");java.util.UUID.fromString(peer)
            val id=j.getString("id");java.util.UUID.fromString(id)
            val hidden=j.getBoolean("hidden");val message=j.getJSONObject("message")
            if(!hidden) {
                val m=message.chatMessage()
                require(m.id==id && setOf(m.sender,m.recipient)==setOf(owner,peer) && m.micros()==j.getLong("micros")) {"聊天归属无效"}
                require(m.body.length<=8000 && m.forwarded.size<=20 && m.forwarded.all {it.body.length<=8000 && it.sender.length<=100} && m.replyPreview.orEmpty().length<=200) {"聊天内容无效"}
                m.mediaPaths().forEach {requireMediaPath(it)}
            }
            CachedMessageEntity(owner,peer,id,j.getLong("micros"),message.toString(),hidden,false)
        }
        require(rows.distinctBy {it.id}.size==rows.size) {"聊天标识重复"}
        val pending=root.getJSONArray("pending").objects().map {j->
            val peer=j.getString("peer");java.util.UUID.fromString(peer)
            val id=j.getString("id");java.util.UUID.fromString(id)
            val d=j.getJSONObject("draft")
            SocialRules.message(MessageDraft(id,d.optString("body"),d.optional("media_path"),d.optional("reply_id")))
            d.optional("media_path")?.let {requireMediaPath(it)}
            OutboxEntity(owner,peer,id,d.toString(),j.getLong("queuedAt"))
        }
        require(pending.size<=200 && pending.distinctBy {it.id}.size==pending.size) {"待发送消息无效"}
        return rows to pending
    }
    suspend fun checkConflicts(owner:String,rows:List<CachedMessageEntity>,pending:List<OutboxEntity>) {
        rows.forEach {row->
            val old=db.dao().find(owner,row.id)
            if(old!=null && !old.hidden && !row.hidden) {
                val a=JSONObject(old.json).chatMessage();val b=JSONObject(row.json).chatMessage()
                require(a.withdrawn || b.withdrawn || a.copy(replyPreview=null)==b.copy(replyPreview=null)) {"同 ID 聊天内容冲突"}
            }
        }
        val old=db.dao().allPending(owner).associateBy {it.id}
        pending.forEach {require(old[it.id]==null || old[it.id]?.json==it.json) {"待发送消息冲突"}}
    }
    suspend fun restore(owner:String,rows:List<CachedMessageEntity>,pending:List<OutboxEntity>)=db.withTransaction {
        checkConflicts(owner,rows,pending)
        rows.forEach {row->
            val old=db.dao().find(owner,row.id)
            when {
                old?.hidden==true -> Unit
                row.hidden -> {db.dao().put(listOf(row));hideQuotes(owner,row.id)}
                old==null -> db.dao().put(listOf(row))
                JSONObject(row.json).optBoolean("withdrawn") -> {db.dao().put(listOf(row));hideQuotes(owner,row.id)}
            }
        }
        val unavailable=db.dao().all(owner).filter {it.hidden || JSONObject(it.json).optBoolean("withdrawn")}.map {it.id}.toSet()
        hideQuotes(owner,unavailable)
        val existing=db.dao().allPending(owner).map {it.id}.toSet()
        pending.filter {it.id !in existing && db.dao().find(owner,it.id)==null}.forEach {db.dao().enqueue(it)}
    }
}
internal fun requireMediaPath(path:String) {
    require(path.matches(Regex("[a-fA-F0-9-]{36}/(?:chat/)?[a-fA-F0-9-]{36}\\.jpg"))) {"图片路径无效"}
}
internal fun ChatMessage.mediaPaths()=(listOfNotNull(mediaPath)+forwarded.mapNotNull {it.mediaPath}).distinct()
internal fun ChatMessage.micros()=createdAt.epochSecond*1_000_000+createdAt.nano/1000
internal fun ChatMessage.toJson()=JSONObject().put("id",id).put("sender",sender).put("recipient",recipient)
    .put("body",body).put("created_at",createdAt.toString()).put("media_path",mediaPath)
    .put("reply_id",replyId).put("reply_preview",replyPreview).put("withdrawn",withdrawn)
    .put("forwarded",JSONArray(forwarded.map {JSONObject().put("sender",it.sender).put("body",it.body)
        .put("media_path",it.mediaPath).put("created_at",it.at?.toString())}))
