package dev.lzk.fitlog.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SocialRulesTest {
    @Test fun retryIdentityIsExplicitAndEmptyOrOversizedContentIsRejected() {
        val id=UUID.randomUUID().toString()
        SocialRules.message(MessageDraft(id,"hello"))
        SocialRules.message(MessageDraft(id,mediaPath="image"))
        assertThrows(IllegalArgumentException::class.java) {SocialRules.message(MessageDraft(id," "))}
        assertThrows(IllegalArgumentException::class.java) {SocialRules.message(MessageDraft(id,"x".repeat(8001)))}
        assertThrows(IllegalArgumentException::class.java) {SocialRules.post(PostDraft(id,"",List(10){"image"}))}
        SocialRules.post(PostDraft(id,"",List(9){"image"}))
    }
    @Test fun mergingPagesDeduplicatesBoundaryAndEnforcesResidenceLimit() {
        data class Row(val id:String,val body:String)
        val result=SocialRules.boundedMerge(listOf(Row("a","new"),Row("b","b")),
            listOf(Row("a","old"),Row("c","c"),Row("d","d")),{it.id},3)
        assertEquals(listOf("a","b","c"),result.map{it.id})
        assertEquals("new",result.first().body)
    }
    @Test fun unknownOrExpiredStatusNeverBecomesTraining() {
        assertEquals(PresenceStatus.OFFLINE,PresenceStatus.from("unknown"))
        assertEquals(PresenceStatus.REST,PresenceStatus.from("rest"))
    }
}
