package dev.lzk.fitlog.domain

import kotlinx.coroutines.flow.StateFlow

/** [banner] is the profile card's backdrop: null for the time-of-day sky, a [ProfileBanners] scene, or an own image path. */
data class SocialProfile(val id: String, val fitlogId: String, val nickname: String, val bio: String = "", val avatarPath: String? = null,
    val banner: String? = null)
object ProfileBanners {
    val SCENES = listOf("gym", "track", "hills", "sea")
    fun isImage(banner: String?) = banner != null && '/' in banner
    /** Unknown values (a scene added by a newer version) fall back to the sky. */
    fun scene(banner: String?) = banner?.takeIf { it in SCENES }
}
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
    suspend fun cachedFriends():List<Friend> = emptyList()
    suspend fun friends(): List<Friend>
    suspend fun find(fitlogId: String): SocialProfile?
    suspend fun act(peerId: String, action: String)
    suspend fun saveNote(peerId: String, note: String)
    suspend fun sharing(): SharingPreferences
    suspend fun saveSharing(value: SharingPreferences)
    suspend fun signOut()
}
