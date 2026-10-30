package dev.lzk.fitlog.domain

import kotlinx.coroutines.flow.StateFlow

data class SocialProfile(val id: String, val fitlogId: String, val nickname: String, val bio: String = "", val avatarPath: String? = null)
data class Friend(val profile: SocialProfile, val note: String, val accepted: Boolean, val incoming: Boolean,
    val presence: PresenceStatus = PresenceStatus.OFFLINE, val presenceUpdatedAt: java.time.Instant? = null,
    val lastMessage: String? = null, val lastMessageAt: java.time.Instant? = null, val unread: Int = 0) {
    val displayName get() = note.ifBlank { profile.nickname }
}
data class SharingPreferences(val history: Boolean = false, val live: Boolean = false)
interface FriendsRepository {
    val configured: Boolean
    val profile: StateFlow<SocialProfile?>
    suspend fun requestCode(email: String)
    suspend fun verifyCode(email: String, code: String)
    suspend fun refreshProfile()
    suspend fun saveProfile(nickname: String, bio: String)
    suspend fun friends(): List<Friend>
    suspend fun find(fitlogId: String): SocialProfile?
    suspend fun act(peerId: String, action: String)
    suspend fun saveNote(peerId: String, note: String)
    suspend fun sharing(): SharingPreferences
    suspend fun saveSharing(value: SharingPreferences)
    suspend fun signOut()
}
