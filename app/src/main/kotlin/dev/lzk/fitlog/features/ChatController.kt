package dev.lzk.fitlog.features

import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class ChatController(
    private val repository: SocialRepository, private val peer: String, private val scope: CoroutineScope,
) {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val pending = MutableStateFlow<List<MessageDraft>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val browsingHistory = MutableStateFlow(false)
    val hasOlder = MutableStateFlow(true)
    suspend fun refresh() {
        if (browsingHistory.value || busy.value) return
        try {
            if(messages.value.isEmpty()) messages.value=repository.localMessages(peer)
            pending.value = repository.pending(peer)
            messages.value = repository.messages(peer)
            repository.markRead(peer)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error.value = e.message ?: "暂时无法刷新消息" }
    }
    fun action(block: suspend () -> Unit) {
        if (busy.value) return
        scope.launch {
            busy.value = true; error.value = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "操作失败，请重试" }
            finally { busy.value = false }
        }
    }
    fun locate(id:String) = action {
        messages.value=repository.messageContext(peer,id)
        browsingHistory.value=true;hasOlder.value=true
    }
    fun older() = action {
        val cursor = messages.value.lastOrNull()?.cursor
        val page = repository.messages(peer, cursor)
        browsingHistory.value = true
        hasOlder.value = page.size == SocialRules.MESSAGE_PAGE
        // Keep the oldest end when paging further back, so an entire history never resides in memory.
        messages.value = (messages.value + page).distinctBy { it.id }.takeLast(150)
    }
    fun latest() = action {
        browsingHistory.value = false; hasOlder.value = true
        messages.value = repository.messages(peer); repository.markRead(peer)
    }
    fun send(draft: MessageDraft,onQueued:()->Unit={}) = action {
        repository.queue(peer, draft)
        onQueued()
        pending.value = repository.pending(peer)
        try {
            repository.send(peer, draft)
            browsingHistory.value = false; messages.value = repository.messages(peer)
        } finally { pending.value = repository.pending(peer) }
    }
    fun retry(draft: MessageDraft) = send(draft)
    fun discard(draft: MessageDraft) = action { repository.forgetPending(peer, draft.id); pending.value = repository.pending(peer) }
    fun delete(message: ChatMessage) = action {
        repository.deleteMessage(peer, message.id); messages.value = messages.value.filterNot { it.id == message.id }
    }
    fun withdraw(message: ChatMessage) = action {
        repository.withdrawMessage(peer, message.id)
        messages.value = messages.value.map { if (it.id == message.id) it.copy(body="",mediaPath=null,forwarded=emptyList(),withdrawn=true) else it }
    }
    fun clear() = action { repository.clearChat(peer); messages.value=emptyList(); pending.value=emptyList() }
    fun release() { messages.value=emptyList(); pending.value=emptyList(); browsingHistory.value=false }
}
