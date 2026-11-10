package dev.lzk.fitlog.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName="cached_messages",primaryKeys=["owner","peer","id"])
internal data class CachedMessageEntity(val owner:String,val peer:String,val id:String,val micros:Long,val json:String,
    @ColumnInfo(defaultValue="0") val hidden:Boolean=false, @ColumnInfo(defaultValue="0") val unread:Boolean=false)
@Entity(tableName="message_outbox",primaryKeys=["owner","id"])
internal data class OutboxEntity(val owner:String,val peer:String,val id:String,val json:String,val queuedAt:Long)
@Entity(tableName="local_posts",primaryKeys=["owner","id"])
internal data class LocalPostEntity(val owner:String,val id:String,val json:String,@ColumnInfo(defaultValue="0") val hidden:Boolean=false)
@Dao
internal interface SocialCacheDao {
    @Query("SELECT * FROM cached_messages WHERE owner=:owner AND peer=:peer AND hidden=0 ORDER BY micros DESC,id DESC LIMIT 150")
    suspend fun messages(owner:String,peer:String):List<CachedMessageEntity>
    @Query("SELECT * FROM cached_messages WHERE owner=:owner AND peer=:peer AND hidden=0 AND (micros<:before OR (micros=:before AND id<:id)) ORDER BY micros DESC,id DESC LIMIT 50")
    suspend fun page(owner:String,peer:String,before:Long,id:String):List<CachedMessageEntity>
    @Query("SELECT * FROM cached_messages WHERE owner=:owner AND peer=:peer AND hidden=0 AND (micros>:at OR (micros=:at AND id>:id)) ORDER BY micros,id LIMIT 24")
    suspend fun newer(owner:String,peer:String,at:Long,id:String):List<CachedMessageEntity>
    @Query("SELECT * FROM cached_messages WHERE owner=:owner ORDER BY micros,id")
    suspend fun all(owner:String):List<CachedMessageEntity>
    @Query("SELECT * FROM cached_messages WHERE owner=:owner AND id=:id LIMIT 1")
    suspend fun find(owner:String,id:String):CachedMessageEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(rows:List<CachedMessageEntity>)
    @Query("UPDATE cached_messages SET hidden=1,json='{}',unread=0 WHERE owner=:owner AND peer=:peer")
    suspend fun clear(owner:String,peer:String)
    @Query("UPDATE cached_messages SET hidden=1,json='{}',unread=0 WHERE owner=:owner AND peer=:peer AND id=:id")
    suspend fun remove(owner:String,peer:String,id:String)
    @Query("UPDATE cached_messages SET unread=0 WHERE owner=:owner AND peer=:peer")
    suspend fun read(owner:String,peer:String)
    @Query("SELECT COUNT(*) FROM cached_messages WHERE owner=:owner AND peer=:peer AND unread=1 AND hidden=0")
    suspend fun unread(owner:String,peer:String):Int
    @Query("SELECT * FROM cached_messages WHERE owner=:owner AND unread=1 AND hidden=0 ORDER BY micros,id LIMIT 500")
    suspend fun unreadAll(owner:String):List<CachedMessageEntity>
    @Query("SELECT * FROM message_outbox WHERE owner=:owner AND peer=:peer ORDER BY queuedAt,id")
    suspend fun pending(owner:String,peer:String):List<OutboxEntity>
    @Query("SELECT * FROM message_outbox WHERE owner=:owner ORDER BY queuedAt,id")
    suspend fun allPending(owner:String):List<OutboxEntity>
    @Query("SELECT COUNT(*) FROM message_outbox WHERE owner=:owner") suspend fun pendingCount(owner:String):Int
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun enqueue(row:OutboxEntity)
    @Query("DELETE FROM message_outbox WHERE owner=:owner AND id=:id") suspend fun forget(owner:String,id:String)
    @Query("DELETE FROM message_outbox WHERE owner=:owner AND peer=:peer") suspend fun clearPending(owner:String,peer:String)
    @Query("DELETE FROM message_outbox WHERE owner=:owner") suspend fun clearOutboxOwner(owner:String)
    @Query("DELETE FROM cached_messages WHERE owner=:owner") suspend fun clearOwner(owner:String)
    @Query("UPDATE cached_messages SET hidden=1,json='{}',unread=0 WHERE owner=:owner") suspend fun hideOwner(owner:String)
    @Query("DELETE FROM local_posts WHERE owner=:owner") suspend fun clearPostsOwner(owner:String)
    @Query("SELECT * FROM local_posts WHERE owner=:owner ORDER BY id") suspend fun posts(owner:String):List<LocalPostEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putPosts(rows:List<LocalPostEntity>)
    @Query("UPDATE local_posts SET hidden=1,json='{}' WHERE owner=:owner AND id=:id") suspend fun hidePost(owner:String,id:String)
    @Query("SELECT (SELECT COUNT(*) FROM cached_messages WHERE owner=:owner AND hidden=0 AND instr(json,:path)>0)+(SELECT COUNT(*) FROM message_outbox WHERE owner=:owner AND instr(json,:path)>0)+(SELECT COUNT(*) FROM local_posts WHERE owner=:owner AND hidden=0 AND instr(json,:path)>0)")
    suspend fun mediaReferences(owner:String,path:String):Int
    @Query("SELECT COUNT(*) FROM local_posts WHERE owner=:owner AND hidden=0 AND instr(json,:path)>0")
    suspend fun ownsPostImage(owner:String,path:String):Int
}
@Database(entities=[CachedMessageEntity::class,OutboxEntity::class,LocalPostEntity::class],version=2,exportSchema=true)
internal abstract class SocialCacheDatabase:RoomDatabase() {
    abstract fun dao():SocialCacheDao
    companion object {
        val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN unread INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS local_posts (owner TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,hidden INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(owner,id))")
            }
        }
    }
}
