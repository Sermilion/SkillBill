package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/** Every repository's saved New task drafts, newest first, shared by all open chats and the home. */
internal class NewTaskSavedChats(private val chats: ExecutionChatRepository, private val scope: CoroutineScope) {
  private val savedState = MutableStateFlow<List<ChatHistoryEntry>>(emptyList())
  private var savedRead: Job? = null

  val saved: StateFlow<List<ChatHistoryEntry>> = savedState.asStateFlow()

  fun refresh() {
    savedRead?.cancel()
    savedRead = scope.launch { savedState.value = chats.savedNewTaskDrafts() }
  }
}

enum class NewTaskChatStatus { Idle, Replying, NewReply }

/** The window's open New task chats in tab order. It is held only in memory, so open chats are not restored after restart. */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@SingleIn(WindowScope::class)
class NewTaskChats(
  private val chats: ExecutionChatRepository,
  private val workControls: WorkControlRepository,
  private val databaseSession: DatabaseSessionController,
  private val toasts: ToastQueue,
  private val windowScope: WindowCoroutineScope,
  private val clock: Clock,
) {
  private val scope = windowScope.scope
  private val savedChats = NewTaskSavedChats(chats, scope)

  internal var chatDispatcher: CoroutineDispatcher = Dispatchers.IO
    set(value) {
      field = value
      openState.value.forEach { it.chatDispatcher = value }
    }

  private val newReplyState = MutableStateFlow<Set<NewTaskDraft>>(emptySet())
  private val openState = MutableStateFlow(listOf(create()))
  private val selectedState = MutableStateFlow(openState.value.first())

  val open: StateFlow<List<NewTaskDraft>> = openState.asStateFlow()
  val selected: StateFlow<NewTaskDraft> = selectedState.asStateFlow()
  val openCount: StateFlow<Int> = open.map { it.size }.stateIn(scope, SharingStarted.Eagerly, 1)

  /** True while any open chat has a conversation. */
  val anyChatOpen: StateFlow<Boolean> = open
    .flatMapLatest { drafts -> combine(drafts.map { it.chat }) { opened -> opened.any { it != null } } }
    .stateIn(scope, SharingStarted.Eagerly, false)

  internal val saved: StateFlow<List<ChatHistoryEntry>> = savedChats.saved

  /** Shows [draft]. It never ends, flushes, closes or stops any chat, so the others keep streaming. */
  fun select(draft: NewTaskDraft) {
    if (draft !in openState.value) return
    selectedState.value = draft
    newReplyState.update { it - draft }
  }

  /** Opens an empty chat with the selected chat's repository, branch and agent, and selects it. */
  fun newChat(): NewTaskDraft {
    val from = selectedState.value
    val draft = create().apply {
      repository = from.repository
      addedRepositories = from.addedRepositories
      baseBranch = from.baseBranch
      agentId = from.agentId
      selectedAgent = from.selectedAgent
    }
    openState.update { it + draft }
    select(draft)
    return draft
  }

  fun status(draft: NewTaskDraft): Flow<NewTaskChatStatus> = combine(busyOf(draft), newReplyState.map { draft in it }) { busy, flagged ->
    when {
      busy -> NewTaskChatStatus.Replying
      flagged -> NewTaskChatStatus.NewReply
      else -> NewTaskChatStatus.Idle
    }
  }.distinctUntilChanged()

  internal fun refreshSaved() = savedChats.refresh()

  internal fun disposeAll() = openState.value.forEach { it.dispose() }

  /** Closes every open chat. False when any one fails to save; that chat stays open, so calling again retries it. */
  internal suspend fun closeAll(): Boolean = openState.value.map { it.close() }.all { it }

  private fun busyOf(draft: NewTaskDraft): Flow<Boolean> = draft.chat.flatMapLatest { it?.thread?.busy ?: flowOf(false) }

  private fun create(): NewTaskDraft {
    val draft = NewTaskDraft(chats, workControls, databaseSession, toasts, windowScope, clock, savedChats)
    draft.chatDispatcher = chatDispatcher
    watchReplies(draft)
    return draft
  }

  /** Flags [draft] when its own chat finishes a reply while another chat is selected; a chat that ends or is replaced clears it. */
  private fun watchReplies(draft: NewTaskDraft) = scope.launch {
    var busyChat: NewTaskChat? = null
    draft.chat
      .flatMapLatest { chat -> chat?.thread?.busy?.map { busy -> chat to busy } ?: flowOf(null) }
      .collect { current ->
        when {
          current == null -> newReplyState.update { it - draft }
          current.first === busyChat && !current.second && selectedState.value !== draft -> newReplyState.update { it + draft }
        }
        busyChat = current?.takeIf { it.second }?.first
      }
  }
}
