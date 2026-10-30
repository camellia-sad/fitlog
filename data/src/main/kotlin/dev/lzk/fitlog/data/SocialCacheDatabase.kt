package dev.lzk.fitlog.data

import androidx.room.*

@Entity(tableName="cached_messages",primaryKeys=["owner","peer","id"])
internal data class CachedMessageEntity(val owner:String,val peer:String,val id:String,val micros:Long,val json:String)
@Entity(tableName="message_outbox",primaryKeys=["owner","id"])
internal data class OutboxEntity(val owner:String,val peer:String,val id:String,val json:String,val queuedAt:Long)
@Dao
internal interface SocialCacheDao {
    @Query("SELECT * FROM cached_messages WHERE owner=:owner AND peer=:peer ORDER BY micros DESC,id DESC LIMIT 150")
    suspend fun messages(owner:String,peer:String):List<CachedMessageEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(rows:List<CachedMessageEntity>)
    @Query("DELETE FROM cached_messages WHERE owner=:owner AND peer=:peer") suspend fun clear(owner:String,peer:String)
    @Query("DELETE FROM cached_messages WHERE owner=:owner AND peer=:peer AND id=:id") suspend fun remove(owner:String,peer:String,id:String)
    @Query("DELETE FROM cached_messages WHERE owner=:owner AND rowid NOT IN (SELECT rowid FROM cached_messages WHERE owner=:owner ORDER BY micros DESC,id DESC LIMIT 1500)")
    suspend fun trim(owner:String)
    @Query("SELECT * FROM message_outbox WHERE owner=:owner AND peer=:peer ORDER BY queuedAt,id")
    suspend fun pending(owner:String,peer:String):List<OutboxEntity>
    @Query("SELECT COUNT(*) FROM message_outbox WHERE owner=:owner") suspend fun pendingCount(owner:String):Int
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun enqueue(row:OutboxEntity)
    @Query("DELETE FROM message_outbox WHERE owner=:owner AND id=:id") suspend fun forget(owner:String,id:String)
    @Query("DELETE FROM message_outbox WHERE owner=:owner AND peer=:peer") suspend fun clearPending(owner:String,peer:String)
    @Query("DELETE FROM message_outbox WHERE owner=:owner") suspend fun clearOutboxOwner(owner:String)
    @Query("DELETE FROM cached_messages WHERE owner=:owner") suspend fun clearOwner(owner:String)
}
@Database(entities=[CachedMessageEntity::class,OutboxEntity::class],version=1,exportSchema=true)
internal abstract class SocialCacheDatabase:RoomDatabase() {
    abstract fun dao():SocialCacheDao
}
