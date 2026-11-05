package dev.lzk.fitlog.data

import dev.lzk.fitlog.domain.*
import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

internal fun String.toSocialInstant():Instant? = runCatching { Instant.parse(this) }.getOrNull() ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
internal fun JSONObject.optional(key:String)=optString(key).takeIf{it.isNotBlank() && it!="null"}
internal fun JSONArray.objects():List<JSONObject> = (0 until length()).map {getJSONObject(it)}
internal fun JSONArray.strings():List<String> = (0 until length()).map {getString(it)}
internal fun JSONObject.chatMessage()=ChatMessage(getString("id"),getString("sender"),getString("recipient"),
    optString("body"),requireNotNull(getString("created_at").toSocialInstant()),optional("media_path"),optional("reply_id"),optional("reply_preview"),
    (optJSONArray("forwarded")?:JSONArray()).objects().map {
        ForwardedMessage(it.optString("sender"),it.optString("body"),it.optional("media_path"),it.optString("created_at").toSocialInstant())
    },optBoolean("withdrawn"))
internal fun JSONObject.post()=SocialPost(getString("id"),getString("owner_id"),optString("nickname"),
    optional("avatar_path"),optString("body"),(optJSONArray("media_paths")?:JSONArray()).strings(),
    requireNotNull(getString("created_at").toSocialInstant()),optInt("like_count"),optBoolean("liked"),optInt("comment_count"))
internal fun JSONObject.socialProfile()=SocialProfile(getString("id"),getString("fitlog_id"),getString("nickname"),
    optString("bio"),optional("avatar_path"))
internal fun LiveTraining.toJson()=JSONObject().put("session_id",sessionId).put("label",label).put("exercise",exercise)
    .put("weight_grams",weightGrams).put("reps",reps).put("set_index",setIndex).put("done",done).put("total",total)
    .put("rest_ends_at",restEndsAt?.toString()).put("record_metric",recordMetric.name)
internal fun JSONObject.liveTraining()=LiveTraining(optString("session_id"),optString("label"),optString("exercise"),
    optLong("weight_grams"),optInt("reps"),optInt("set_index"),optInt("done"),optInt("total"),optString("rest_ends_at").toSocialInstant(),
    runCatching {RecordMetric.valueOf(optString("record_metric"))}.getOrDefault(RecordMetric.REPETITIONS))
internal fun JSONObject.comment()=SocialComment(getString("id"),getString("owner_id"),optString("nickname"),optString("body"),
    requireNotNull(getString("created_at").toSocialInstant()),optional("parent_id"),optional("reply_to_nickname"),
    optInt("reply_count"),optBoolean("deleted"))
