package dev.lzk.fitlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PostWorkoutJsonTest {
    private val card = PostWorkout("推日", Instant.parse("2026-11-09T10:40:00Z"), 52, 14, 14, 4_860_000, WeightUnit.LB, listOf(
        PostWorkoutExercise("杠铃卧推", Muscle.CHEST, RecordMetric.REPETITIONS, listOf(PostSet(62_500, 6), PostSet(60_000, 8)), 60_000),
        PostWorkoutExercise("平板支撑", null, RecordMetric.SECONDS, listOf(PostSet(0, 60)))), "最后一组力竭", "冲")

    @Test fun roundTripsAndReadsFromAFeedRow() {
        val json = PostWorkoutJson.encode(card)
        assertEquals(card, PostWorkoutJson.decode(JSONObject(json.toString())))
        val row = JSONObject().put("id", "p").put("owner_id", "o").put("body", "文字版").put("created_at", "2026-11-09T11:00:00Z").put("workout", json)
        assertEquals(card, row.post().workout)
        assertNull(row.put("workout", JSONObject.NULL).post().workout)
    }

    @Test fun unknownVersionsOrBrokenCardsFallBackToText() {
        assertNull(PostWorkoutJson.decode(PostWorkoutJson.encode(card).put("v", 2)))
        assertNull(PostWorkoutJson.decode(JSONObject().put("v", 1).put("label", "x")))
    }
}
