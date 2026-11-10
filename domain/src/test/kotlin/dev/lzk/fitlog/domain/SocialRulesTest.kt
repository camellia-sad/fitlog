package dev.lzk.fitlog.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SocialRulesTest {
    @org.junit.Test fun chatTimesKeepMinutesAndAddTheDateAfterYesterday() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.ZonedDateTime.of(2026, 11, 9, 10, 0, 0, 0, zone).toInstant()
        fun at(d: Int, h: Int, m: Int) = java.time.ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toInstant()
        org.junit.Assert.assertEquals("09:05", ChatTimes.label(at(9, 9, 5), now, zone))
        org.junit.Assert.assertEquals("昨天 21:05", ChatTimes.label(at(8, 21, 5), now, zone))
        org.junit.Assert.assertEquals("2026/11/07 14:32", ChatTimes.label(at(7, 14, 32), now, zone))
        org.junit.Assert.assertTrue(ChatTimes.separated(at(9, 9, 0), at(9, 9, 5)))
        org.junit.Assert.assertFalse(ChatTimes.separated(at(9, 9, 0), at(9, 9, 4)))
    }
    @org.junit.Test fun statusLineNamesTheExerciseOnlyWhileTraining() {
        val profile = SocialProfile("p", "f", "阿杰")
        val live = LiveTraining(exercise = "杠铃卧推", weightGrams = 60_000, reps = 8, done = 2, total = 4)
        org.junit.Assert.assertEquals("锻炼中 · 杠铃卧推 · 60 kg × 8 · 已完成 2/4 组",
            SocialPerson(profile, SharingPreferences(), PresenceStatus.TRAINING, live, null).statusLine())
        org.junit.Assert.assertEquals("在线", SocialPerson(profile, SharingPreferences(), PresenceStatus.ONLINE, live, null).statusLine())
        org.junit.Assert.assertEquals("锻炼中", SocialPerson(profile, SharingPreferences(), PresenceStatus.TRAINING, null, null).statusLine())
    }
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
