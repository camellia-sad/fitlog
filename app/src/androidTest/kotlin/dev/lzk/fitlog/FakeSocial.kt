package dev.lzk.fitlog

import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

internal class FakeSocial:FriendsRepository,SocialRepository {
    val me=SocialProfile("00000000-0000-0000-0000-000000000001","a".repeat(32),"我")
    val peer=SocialProfile("00000000-0000-0000-0000-000000000002","b".repeat(32),"训练搭子")
    override val configured=true
    override val profile=MutableStateFlow<SocialProfile?>(me)
    var failSend=false
    var rows=listOf<ChatMessage>()
    var queue=listOf<MessageDraft>()
    var posts=listOf<SocialPost>()
    var commentRows=listOf<SocialComment>()
    var replyRows=mapOf<String,List<SocialComment>>()
    var notices=listOf<ReplyNotice>()
    var contexts=mapOf<String,CommentContext>()
    var likedValue:Boolean?=null
    var lastComment:Pair<String,String?>?=null
    var imageBytes=ByteArray(0)
    var imageReads=0
    var preference=SharingPreferences()
    var bound:String?=null
    var lastReply:String?=null
    var lastForward:List<String> = emptyList()
    override suspend fun refreshProfile(){}
    override suspend fun friends()=listOf(Friend(peer,"",true,false,PresenceStatus.ONLINE))
    override suspend fun requestCode(email:String){}
    override suspend fun verifyCode(email:String,code:String){}
    override suspend fun saveProfile(nickname:String,bio:String){}
    override suspend fun find(fitlogId:String)=peer
    override suspend fun act(peerId:String,action:String){}
    override suspend fun saveNote(peerId:String,note:String){}
    override suspend fun sharing()=preference
    override suspend fun saveSharing(value:SharingPreferences){preference=value}
    override suspend fun signOut(){profile.value=null}
    override suspend fun messages(peerId:String,before:SocialCursor?)=rows
    override suspend fun messageContext(peerId:String,id:String)=rows
    override suspend fun send(peerId:String,draft:MessageDraft):ChatMessage {
        if(failSend)error("网络暂不可用")
        val message=ChatMessage(draft.id,me.id,peerId,draft.body,Instant.now(),replyId=draft.replyId)
        lastReply=draft.replyId
        rows=(listOf(message)+rows).distinctBy{it.id}
        forgetPending(peerId,draft.id)
        return message
    }
    override suspend fun deleteMessage(peerId:String,id:String){rows=rows.filterNot{it.id==id}}
    override suspend fun withdrawMessage(peerId:String,id:String){rows=rows.map{if(it.id==id)it.copy(body="",withdrawn=true)else it}}
    override suspend fun clearChat(peerId:String){rows=emptyList();queue=emptyList()}
    override suspend fun markRead(peerId:String){}
    override suspend fun forward(peerId:String,ids:List<String>,requestId:String):ChatMessage {lastForward=ids;return send(peerId,MessageDraft(requestId,"转发"))}
    override suspend fun feed(ownerId:String?,before:SocialCursor?):List<SocialPost> {check(!offline){"网络暂不可用"};return posts}
    override suspend fun publish(draft:PostDraft){posts=listOf(SocialPost(draft.id,me.id,me.nickname,null,draft.body,draft.mediaPaths,Instant.now(),0,false,0,workout=draft.workout))+posts}
    override suspend fun deletePost(id:String){posts=posts.filterNot{it.id==id}}
    var archive=listOf<SocialPost>()
    var offline=false
    val localDeletes=mutableListOf<String>()
    override suspend fun ownArchive()=archive
    override suspend fun deleteOwnPost(id:String,inCloud:Boolean){if(inCloud) deletePost(id) else localDeletes+=id;archive=archive.filterNot{it.id==id}}
    override suspend fun like(id:String,liked:Boolean){likedValue=liked}
    override suspend fun comments(postId:String,before:SocialCursor?)=commentRows
    override suspend fun replies(postId:String,parentId:String,after:SocialCursor?)=replyRows[parentId].orEmpty()
    override suspend fun comment(postId:String,text:String,id:String,replyTo:String?){lastComment=text to replyTo}
    override suspend fun deleteComment(postId:String,id:String){
        commentRows=commentRows.filterNot{it.id==id};replyRows=replyRows.mapValues{(_,rows)->rows.filterNot{it.id==id}}
    }
    override suspend fun replyNotices(before:SocialCursor?)=notices
    override suspend fun unreadReplies()=notices.size
    override suspend fun readReply(id:String){notices=notices.filterNot{it.comment.id==id}}
    override suspend fun readAllReplies(){notices=emptyList()}
    override suspend fun commentContext(id:String)=contexts[id] ?: error("原动态或回复已删除或不可访问")
    override suspend fun person(id:String)=SocialPerson(if(id==me.id)me else peer,SharingPreferences(),PresenceStatus.ONLINE,null,null)
    override suspend fun sharedHistory(id:String,before:SocialCursor?)=emptyList<SharedWorkout>()
    override suspend fun blocked()=emptyList<SocialProfile>()
    override suspend fun uploadImage(jpeg:ByteArray)="image"
    override suspend fun image(path:String):ByteArray {imageReads++;return imageBytes}
    override suspend fun saveAvatar(path:String){}
    override suspend fun saveBanner(banner:String?){profile.value=profile.value?.copy(banner=banner)}
    override suspend fun heartbeat(deviceId:String,status:PresenceStatus,training:LiveTraining?){}
    override suspend fun uploadWorkout(workout:Workout){}
    override suspend fun pending(peerId:String)=queue
    override suspend fun queue(peerId:String,draft:MessageDraft){queue=(queue+draft).distinctBy{it.id}}
    override suspend fun forgetPending(peerId:String,id:String){queue=queue.filterNot{it.id==id}}
    override fun boundTrainingOwner()=bound
    override fun bindTrainingOwner(){bound=me.id}
    override fun selectTrainingExercise(id:String?){}
}
