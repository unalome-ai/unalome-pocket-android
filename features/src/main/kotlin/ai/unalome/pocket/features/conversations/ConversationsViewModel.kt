package ai.unalome.pocket.features.conversations

import ai.unalome.pocket.chatcore.Conversation
import ai.unalome.pocket.chatcore.ConversationStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The list of chats, filtered by a search query. `null` means not loaded yet. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModel(
    private val store: ConversationStore,
    private val writeScope: CoroutineScope,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val conversations: StateFlow<List<Conversation>?> = _query
        .flatMapLatest { store.conversations(it) }
        .map<List<Conversation>, List<Conversation>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun delete(conversationId: String) {
        writeScope.launch { store.delete(conversationId) }
    }
}
