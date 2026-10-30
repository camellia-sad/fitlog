package dev.lzk.fitlog.features

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class FriendsViewModel(private val repository: FriendsRepository) : ViewModel() {
    val configured get() = repository.configured
    val profile = repository.profile
    val friends = MutableStateFlow<List<Friend>>(emptyList())
    val found = MutableStateFlow<SocialProfile?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val codeSent = MutableStateFlow(false)
    init { refresh() }
    private fun execute(action: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value=true; message.value=null
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if(profile.value==null) { friends.value=emptyList(); found.value=null }
                message.value=e.message ?: "操作失败，请重试"
            }
            finally { busy.value=false }
        }
    }
    fun refresh() = execute {
        if (!configured) return@execute
        repository.refreshProfile()
        friends.value=if(profile.value==null) emptyList() else repository.friends()
    }
    fun requestCode(email:String)=execute { repository.requestCode(email); codeSent.value=true; message.value="验证码已发送，请查收邮箱" }
    fun verify(email:String,code:String)=execute {
        friends.value=emptyList(); found.value=null
        repository.verifyCode(email,code)
        friends.value=repository.friends(); codeSent.value=false
    }
    fun search(id:String)=execute {
        found.value=null; found.value=repository.find(id)
        if(found.value==null) message.value="未找到这个 FitLog ID"
    }
    fun act(id:String,action:String)=execute {
        repository.act(id,action); found.value=null; friends.value=repository.friends(); message.value="操作已完成"
    }
    fun saveProfile(name:String,bio:String)=execute { repository.saveProfile(name,bio); message.value="资料已保存" }
    fun saveNote(id:String,note:String)=execute { repository.saveNote(id,note); friends.value=repository.friends(); message.value="备注已保存" }
    suspend fun repositorySharing() = repository.sharing()
    suspend fun updateSharing(value:SharingPreferences) = repository.saveSharing(value)
    suspend fun unblock(id:String) = repository.act(id,"unblock")
    fun signOut()=execute { repository.signOut(); friends.value=emptyList(); found.value=null; codeSent.value=false }
    class Factory(private val repository:FriendsRepository):ViewModelProvider.Factory {
        override fun <T:ViewModel> create(modelClass:Class<T>):T {
            require(modelClass==FriendsViewModel::class.java)
            @Suppress("UNCHECKED_CAST") return FriendsViewModel(repository) as T
        }
    }
}
