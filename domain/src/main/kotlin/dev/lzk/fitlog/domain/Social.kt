package dev.lzk.fitlog.domain

import java.time.Instant
import java.util.UUID

data class SocialCursor(val time: Instant,val id: String)
data class ForwardedMessage(val sender:String,val body:String,val mediaPath:String?=null,val at:Instant?=null)
data class ChatMessage(
    val id:String,val sender:String,val recipient:String,val body:String,val createdAt:Instant,
    val mediaPath:String?=null,val replyId:String?=null,val replyPreview:String?=null,
    val forwarded:List<ForwardedMessage> = emptyList(),val withdrawn:Boolean=false,
) {
    val cursor get()=SocialCursor(createdAt,id)
}
data class MessageDraft(val id:String=UUID.randomUUID().toString(),val body:String="",val mediaPath:String?=null,val replyId:String?=null)
data class SocialPost(
    val id:String,val ownerId:String,val nickname:String,val avatarPath:String?,val body:String,
    val mediaPaths:List<String>,val createdAt:Instant,val likeCount:Int,val liked:Boolean,val commentCount:Int,
) { val cursor get()=SocialCursor(createdAt,id) }
data class SocialComment(val id:String,val ownerId:String,val nickname:String,val body:String,val createdAt:Instant) {
    val cursor get()=SocialCursor(createdAt,id)
}
data class PostDraft(val id:String=UUID.randomUUID().toString(),val body:String="",val mediaPaths:List<String> = emptyList())
enum class PresenceStatus(val label:String) {
    OFFLINE("离线"),ONLINE("在线"),TRAINING("锻炼中"),REST("组间休息"),PAUSED("训练已暂停");
    companion object {
        fun from(value:String)=entries.find {it.name.equals(value,true)} ?: OFFLINE
    }
}
data class LiveTraining(
    val sessionId:String="",val label:String="",val exercise:String="",val weightGrams:Long=0,val reps:Int=0,
    val setIndex:Int=0,val done:Int=0,val total:Int=0,val restEndsAt:Instant?=null,
    val recordMetric:RecordMetric=RecordMetric.REPETITIONS,
)
data class SocialPerson(val profile:SocialProfile,val sharing:SharingPreferences,val status:PresenceStatus,val live:LiveTraining?,val updatedAt:Instant?)
data class SharedWorkout(val id:String,val localId:String,val startedAt:Instant,val label:String,
    val status:String,val completedSets:Int,val totalSets:Int,val durationSeconds:Long,val exercises:List<String>)

interface SocialRepository {
    suspend fun localMessages(peerId:String):List<ChatMessage> = emptyList()
    suspend fun messages(peerId:String,before:SocialCursor?=null):List<ChatMessage>
    suspend fun messageContext(peerId:String,id:String):List<ChatMessage>
    suspend fun send(peerId:String,draft:MessageDraft):ChatMessage
    suspend fun deleteMessage(peerId:String,id:String)
    suspend fun withdrawMessage(peerId:String,id:String)
    suspend fun clearChat(peerId:String)
    suspend fun markRead(peerId:String)
    suspend fun forward(peerId:String,ids:List<String>,requestId:String):ChatMessage
    suspend fun feed(ownerId:String?=null,before:SocialCursor?=null):List<SocialPost>
    suspend fun publish(draft:PostDraft)
    suspend fun deletePost(id:String)
    suspend fun like(id:String,liked:Boolean)
    suspend fun comments(postId:String,before:SocialCursor?=null):List<SocialComment>
    suspend fun comment(postId:String,text:String,id:String)
    suspend fun deleteComment(postId:String,id:String)
    suspend fun person(id:String):SocialPerson
    suspend fun sharedHistory(id:String,before:SocialCursor?=null):List<SharedWorkout>
    suspend fun blocked():List<SocialProfile>
    suspend fun uploadChatImage(jpeg:ByteArray):String = uploadImage(jpeg)
    suspend fun uploadImage(jpeg:ByteArray):String
    suspend fun image(path:String):ByteArray
    suspend fun saveAvatar(path:String)
    suspend fun heartbeat(deviceId:String,status:PresenceStatus,training:LiveTraining?)
    suspend fun uploadWorkout(workout:Workout)
    suspend fun pending(peerId:String):List<MessageDraft>
    suspend fun queue(peerId:String,draft:MessageDraft)
    suspend fun forgetPending(peerId:String,id:String)
    fun boundTrainingOwner():String?
    fun bindTrainingOwner()
    fun selectTrainingExercise(id:String?)
}

object SocialRules {
    const val MESSAGE_PAGE=50
    const val FEED_PAGE=20
    const val MAX_FORWARD=20
    fun message(draft:MessageDraft) {
        require(draft.body.length<=8000 && (draft.body.isNotBlank() || draft.mediaPath!=null)) {"消息须有文字或图片，最多 8000 字"}
        UUID.fromString(draft.id)
    }
    fun post(draft:PostDraft) {
        require(draft.body.length<=4000 && draft.mediaPaths.size<=9 && (draft.body.isNotBlank() || draft.mediaPaths.isNotEmpty())) {"动态须有文字或图片，最多 4000 字、9 张图"}
        UUID.fromString(draft.id)
    }
    fun <T> boundedMerge(newest:List<T>,older:List<T>,id:(T)->String,maximum:Int):List<T> =
        (newest+older).distinctBy(id).take(maximum)
}
