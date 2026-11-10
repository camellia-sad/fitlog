package dev.lzk.fitlog.domain

import java.time.Instant

/** One thing worth a notification: who did it, the name to show for them, and when the cloud recorded it. */
data class AlertItem(val personId:String,val name:String,val at:Instant,val comment:Boolean=false)

/** Everything waiting right now: unread chat messages, friends' posts on the first feed page, unread comments and replies. */
data class AlertBatch(val messages:List<AlertItem>,val posts:List<AlertItem>,val replies:List<AlertItem>,val replyCount:Int=replies.size)

/** What is on screen: an open chat, or the friends' feed. Nothing is alerted for what is already in view. */
data class AlertView(val chatPeer:String?=null,val feed:Boolean=false)

/** The newest moment already alerted for each kind, and the newest friend post seen on the feed. All in cloud time. */
data class AlertMarks(val message:Instant,val post:Instant,val reply:Instant,val feedSeen:Instant)

/** A notification to show (or keep showing). [alert] pops it at the top of the screen; otherwise it is updated quietly. */
data class AlertNotice(val text:String,val alert:Boolean,val chatPeer:String?=null)
data class AlertPlan(val messages:AlertNotice?,val posts:AlertNotice?,val replies:AlertNotice?,val marks:AlertMarks)

object SocialAlerts {
    /** Names in the order they first appear, earliest first. */
    private fun people(items:List<AlertItem>)=items.sortedBy {it.at}.distinctBy {it.personId}.map {it.name}

    fun messageText(items:List<AlertItem>):String {
        val names=people(items)
        val count=if(items.size==1) "一条" else " ${items.size} 条"
        return if(names.size==1) "${names[0]}给你发了${count}消息" else "${names[0]}等 ${names.size} 位好友给你发了${count}消息"
    }

    fun postText(items:List<AlertItem>):String {
        val names=people(items)
        val who=when(names.size) {
            1 -> names[0]
            2 -> "${names[0]}、${names[1]}"
            else -> "${names[0]}、${names[1]}等 ${names.size} 位好友"
        }
        return "${who}发了新动态，快去看看吧"
    }

    /** Comments on one's posts and replies to one's comments; [count] may exceed the page of [items] that was loaded. */
    fun replyText(items:List<AlertItem>,count:Int=items.size):String {
        val names=people(items)
        val total=maxOf(count,items.size)
        val verb=when {
            items.all {it.comment} -> "评论了你的动态"
            items.none {it.comment} -> "回复了你"
            else -> "评论和回复了你"
        }
        val who=if(names.size==1) names[0] else "${names[0]}等 ${names.size} 人"
        return if(total==1) "$who$verb" else "$who$verb $total 次"
    }

    /** The marks a new account starts from, so what was already waiting before notifications began is never alerted. */
    fun baseline(batch:AlertBatch,fallback:Instant):AlertMarks {
        fun newest(items:List<AlertItem>)=items.maxOfOrNull {it.at} ?: fallback
        val post=newest(batch.posts)
        return AlertMarks(newest(batch.messages),post,newest(batch.replies),post)
    }

    fun plan(batch:AlertBatch,marks:AlertMarks,view:AlertView):AlertPlan {
        val newestPost=batch.posts.maxOfOrNull {it.at}
        // Posts that were on screen in the feed count as seen.
        val feedSeen=if(view.feed && newestPost!=null && newestPost>marks.feedSeen) newestPost else marks.feedSeen

        val messages=batch.messages.filter {it.personId!=view.chatPeer}.sortedBy {it.at}
        val messageNotice=messages.takeIf {it.isNotEmpty()}?.let {items->
            AlertNotice(messageText(items),items.any {it.at>marks.message},items.map {it.personId}.distinct().singleOrNull())
        }
        val posts=batch.posts.filter {it.at>feedSeen}
        val postNotice=posts.takeIf {it.isNotEmpty()}?.let {AlertNotice(postText(it),it.any {p->p.at>marks.post})}
        val replyNotice=batch.replies.takeIf {it.isNotEmpty()}?.let {items->
            AlertNotice(replyText(items,batch.replyCount),!view.feed && items.any {it.at>marks.reply})
        }
        fun later(a:Instant,items:List<AlertItem>)=maxOf(a,items.maxOfOrNull {it.at} ?: a)
        return AlertPlan(messageNotice,postNotice,replyNotice,
            AlertMarks(later(marks.message,batch.messages),later(marks.post,batch.posts),later(marks.reply,batch.replies),feedSeen))
    }
}
