package dev.lzk.fitlog.data

import dev.lzk.fitlog.domain.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire form of a post's training card (`social_posts.workout`). Sets are `[grams, reps]` pairs to keep it small.
 * Reading is lenient: a card it cannot understand is dropped and the post falls back to its text.
 */
internal object PostWorkoutJson {
    fun encode(card: PostWorkout): JSONObject = JSONObject()
        .put("v", 1).put("label", card.label).put("started_at", card.startedAt.toString()).put("minutes", card.minutes)
        .put("done", card.completedSets).put("total", card.totalSets).put("volume", card.volumeGrams).put("unit", card.unit.name)
        .put("feeling", card.feeling).put("caption", card.caption)
        .put("exercises", JSONArray(card.exercises.map { e ->
            JSONObject().put("name", e.name).put("muscle", e.muscle?.name ?: JSONObject.NULL).put("metric", e.metric.name)
                .put("sets", JSONArray(e.sets.map { JSONArray().put(it.weightGrams).put(it.reps) }))
                .put("prev", e.previousBest ?: JSONObject.NULL)
        }))

    fun decode(json: JSONObject?): PostWorkout? = try {
        json?.takeIf { it.optInt("v") == 1 }?.let { j ->
            PostWorkout(j.getString("label"), requireNotNull(j.getString("started_at").toSocialInstant()), j.optInt("minutes"),
                j.optInt("done"), j.optInt("total"), j.optLong("volume"),
                WeightUnit.entries.find { it.name == j.optString("unit") } ?: WeightUnit.KG,
                (j.optJSONArray("exercises") ?: JSONArray()).objects().take(PostWorkouts.MAX_EXERCISES).map { e ->
                    PostWorkoutExercise(e.getString("name"), Muscle.entries.find { it.name == e.optString("muscle") },
                        RecordMetric.entries.find { it.name == e.optString("metric") } ?: RecordMetric.REPETITIONS,
                        (e.optJSONArray("sets") ?: JSONArray()).let { sets ->
                            (0 until minOf(sets.length(), PostWorkouts.MAX_SETS)).map { i -> sets.getJSONArray(i).let { PostSet(it.getLong(0), it.getInt(1)) } }
                        },
                        if (e.isNull("prev")) null else e.optLong("prev"))
                }, j.optString("feeling"), j.optString("caption"))
        }
    } catch (_: Exception) { null }
}
