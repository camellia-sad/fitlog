package dev.lzk.fitlog.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SocialAlertsTest {
    private val t0=Instant.parse("2026-11-10T08:00:00Z")
    private fun at(minutes:Long)=t0.plusSeconds(minutes*60)
    private fun item(id:String,name:String,minutes:Long,comment:Boolean=false)=AlertItem(id,name,at(minutes),comment)

    @Test fun messageTextNamesTheFirstSenderAndCountsEverything() {
        assertEquals("小王给你发了一条消息",SocialAlerts.messageText(listOf(item("a","小王",1))))
        assertEquals("小王给你发了 3 条消息",SocialAlerts.messageText(listOf(item("a","小王",1),item("a","小王",2),item("a","小王",3))))
        // 小李 wrote later, so 小王 leads even though the list starts with 小李.
        assertEquals("小王等 2 位好友给你发了 5 条消息",SocialAlerts.messageText(listOf(item("b","小李",4),item("a","小王",1),
            item("b","小李",5),item("a","小王",2),item("a","小王",3))))
    }

    @Test fun postTextListsUpToTwoNames() {
        assertEquals("小王发了新动态，快去看看吧",SocialAlerts.postText(listOf(item("a","小王",1),item("a","小王",2))))
        assertEquals("小王、小李发了新动态，快去看看吧",SocialAlerts.postText(listOf(item("b","小李",3),item("a","小王",1))))
        assertEquals("小王、小李等 3 位好友发了新动态，快去看看吧",
            SocialAlerts.postText(listOf(item("a","小王",1),item("b","小李",2),item("c","阿杰",3))))
    }

    @Test fun replyTextSaysWhetherItIsACommentOrAReply() {
        assertEquals("小王回复了你",SocialAlerts.replyText(listOf(item("a","小王",1))))
        assertEquals("小王评论了你的动态",SocialAlerts.replyText(listOf(item("a","小王",1,comment=true))))
        assertEquals("小王评论了你的动态 2 次",SocialAlerts.replyText(listOf(item("a","小王",1,true),item("a","小王",2,true))))
        assertEquals("小王等 2 人评论和回复了你 3 次",
            SocialAlerts.replyText(listOf(item("a","小王",1,true),item("b","小李",2),item("a","小王",3))))
        // The unread count can reach past the loaded page.
        assertEquals("小王回复了你 25 次",SocialAlerts.replyText(listOf(item("a","小王",1)),25))
    }

    @Test fun onlyWhatIsNewerThanTheMarksPopsUp() {
        val batch=AlertBatch(listOf(item("a","小王",1)),listOf(item("c","阿杰",2)),listOf(item("b","小李",3)))
        val start=SocialAlerts.baseline(batch,t0)
        // Waiting before notifications began: nothing pops up, and the old posts count as seen.
        val quiet=SocialAlerts.plan(batch,start,AlertView())
        assertFalse(quiet.messages!!.alert);assertFalse(quiet.replies!!.alert);assertNull(quiet.posts)

        val later=batch.copy(messages=batch.messages+item("b","小李",10),posts=batch.posts+item("a","小王",11),
            replies=batch.replies+item("a","小王",12,true))
        val plan=SocialAlerts.plan(later,quiet.marks,AlertView())
        assertEquals(AlertNotice("小王等 2 位好友给你发了 2 条消息",true),plan.messages)
        assertEquals(AlertNotice("小王发了新动态，快去看看吧",true),plan.posts)
        assertEquals(AlertNotice("小李等 2 人评论和回复了你 2 次",true),plan.replies)
        // The same batch again only updates quietly.
        val again=SocialAlerts.plan(later,plan.marks,AlertView())
        assertFalse(again.messages!!.alert);assertFalse(again.posts!!.alert);assertFalse(again.replies!!.alert)
        assertEquals(plan.marks,again.marks)
    }

    @Test fun whatIsOnScreenIsNotAlerted() {
        val marks=AlertMarks(t0,t0,t0,t0)
        val batch=AlertBatch(listOf(item("a","小王",1),item("a","小王",2)),listOf(item("c","阿杰",3)),listOf(item("b","小李",4)))
        // Chatting with 小王: no message notification at all.
        assertNull(SocialAlerts.plan(batch,marks,AlertView(chatPeer="a")).messages)
        // On the feed: its posts are seen, and replies are shown there instead of popping up.
        val feed=SocialAlerts.plan(batch,marks,AlertView(feed=true))
        assertNull(feed.posts);assertEquals(at(3),feed.marks.feedSeen)
        assertFalse(feed.replies!!.alert)
        // Leaving the feed does not bring them back.
        val after=SocialAlerts.plan(batch,feed.marks,AlertView())
        assertNull(after.posts);assertFalse(after.replies!!.alert)
    }

    @Test fun oneSenderOpensTheirChat() {
        val marks=AlertMarks(t0,t0,t0,t0)
        assertEquals("a",SocialAlerts.plan(AlertBatch(listOf(item("a","小王",1),item("a","小王",2)),emptyList(),emptyList()),marks,AlertView()).messages?.chatPeer)
        assertNull(SocialAlerts.plan(AlertBatch(listOf(item("a","小王",1),item("b","小李",2)),emptyList(),emptyList()),marks,AlertView()).messages?.chatPeer)
    }
}
