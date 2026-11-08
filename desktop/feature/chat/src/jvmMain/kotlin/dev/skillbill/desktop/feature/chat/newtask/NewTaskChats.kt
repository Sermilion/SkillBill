package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.repository.ApprovalFeedRepository
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.designsystem.component.ToastAction
import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import dev.skillbill.desktop.feature.chat.UnavailableApprovalFeed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
private const val DISCARD_UNDO_WINDOW_MS = 5_000L

private sealed interface PendingDiscard {
  class Undoable(val job: Job) : PendingDiscard
  data object Deleting : PendingDiscard
}

internal class NewTaskSavedChats(
  private val chats: ExecutionChatRepository,
  private val scope: CoroutineScope,
  private val toasts: ToastQueue,
) {
  private val savedState = MutableStateFlow<List<ChatHistoryEntry>>(emptyList())
  private var savedRead: Job? = null
  private val pendingDiscards = mutableMapOf<String, PendingDiscard>()

  val saved: StateFlow<List<ChatHistoryEntry>> = savedState.asStateFlow()

  fun refresh() {
    scope.launch {
      savedRead?.cancel()
      savedRead = launch { savedState.value = chats.savedNewTaskDrafts().filterNot { it.sessionId in pendingDiscards } }
    }
  }

  fun discardSaved(entry: ChatHistoryEntry) {
    if (entry.sessionId in pendingDiscards) return
    val job = scope.launch(start = CoroutineStart.LAZY) {
      delay(DISCARD_UNDO_WINDOW_MS)
      pendingDiscards[entry.sessionId] = PendingDiscard.Deleting
      try {
        chats.deleteSavedChat(entry.sessionId)
      } finally {
        pendingDiscards.remove(entry.sessionId)
        refresh()
      }
    }
    pendingDiscards[entry.sessionId] = PendingDiscard.Undoable(job)
    savedState.value = savedState.value.filterNot { it.sessionId == entry.sessionId }
    job.start()
    toasts.show(ToastData(
      title = "Discarded ${chatTitle(entry.title).title}",
      actions = listOf(ToastAction("Undo") { undoDiscard(entry.sessionId) }),
    ))
  }

  private fun undoDiscard(sessionId: String) {
    val pending = pendingDiscards[sessionId] as? PendingDiscard.Undoable ?: return
    pendingDiscards.remove(sessionId)
    pending.job.cancel()
    refresh()
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
  private val approvals: ApprovalFeedRepository = UnavailableApprovalFeed,
  private val changesRepository: ChatChangesRepository = UnavailableChatChanges,
) {
  private val scope = windowScope.scope
  private val savedChats = NewTaskSavedChats(chats, scope, toasts)

  internal var chatDispatcher: CoroutineDispatcher = Dispatchers.IO
    set(value) {
      field = value
      openState.value.forEach { it.chatDispatcher = value }
    }

  private val newReplyState = MutableStateFlow<Set<NewTaskDraft>>(emptySet())
  private val closingDrafts = mutableSetOf<NewTaskDraft>()
  private val replyWatches = mutableMapOf<NewTaskDraft, Job>()
  private val openState = MutableStateFlow(listOf(create()))
  private val selectedState = MutableStateFlow(openState.value.first())
  private val pendingCloseState = MutableStateFlow<NewTaskDraft?>(null)
  private val resumedState = MutableSharedFlow<NewTaskDraft>(extraBufferCapacity = 1)

  val open: StateFlow<List<NewTaskDraft>> = openState.asStateFlow()
  val selected: StateFlow<NewTaskDraft> = selectedState.asStateFlow()
  val openCount: StateFlow<Int> = open.map { it.size }.stateIn(scope, SharingStarted.Eagerly, 1)

  /** True while any open chat has a conversation. */
  val anyChatOpen: StateFlow<Boolean> = open
    .flatMapLatest { drafts -> combine(drafts.map { it.chat }) { opened -> opened.any { it != null } } }
    .stateIn(scope, SharingStarted.Eagerly, false)

  /** Session ids of every open chat's conversation, for the "open" badge in Recent chats. */
  val openSessionIds: StateFlow<Set<String>> = open
    .flatMapLatest { drafts -> combine(drafts.map(::sessionIdOf)) { ids -> ids.filterNotNull().toSet() } }
    .stateIn(scope, SharingStarted.Eagerly, emptySet())

  /** A replying chat waiting for the operator to confirm its close. */
  val pendingClose: StateFlow<NewTaskDraft?> = pendingCloseState.asStateFlow()

  /** A draft that [resume] loaded a saved chat into, so the composer can re-read its agent and branches. */
  internal val resumed: SharedFlow<NewTaskDraft> = resumedState.asSharedFlow()

  internal val saved: StateFlow<List<ChatHistoryEntry>> = savedChats.saved

  init {
    // A summary written in the background changes its Recent chats row.
    scope.launch { chats.summaryUpdates.collect { refreshSaved() } }
  }

  /** Shows [draft]. It never ends, flushes, closes or stops any chat, so the others keep streaming. */
  fun select(draft: NewTaskDraft) {
    if (draft !in openState.value) return
    val outgoing = selectedState.value
    selectedState.value = draft
    // Leaving an idle chat summarizes it; a busy one is summarized when it is closed.
    if (outgoing !== draft) outgoing.chat.value?.takeUnless { it.thread.busy.value }?.let { chats.requestSummary(it.sessionId) }
    newReplyState.update { it - draft }
  }

  /** Opens an empty chat with the selected chat's repository, branch and agent, and selects it. */
  fun newChat(): NewTaskDraft {
    val draft = createLike(selectedState.value)
    openState.update { it + draft }
    select(draft)
    return draft
  }

  /** Selects the chat [step] places away from the selected one, wrapping around. */
  fun selectRelative(step: Int) {
    val drafts = openState.value
    select(drafts[Math.floorMod(drafts.indexOf(selectedState.value) + step, drafts.size)])
  }

  /** Selects the chat at [index]; false when there is none. */
  fun selectAt(index: Int): Boolean {
    val draft = openState.value.getOrNull(index) ?: return false
    select(draft)
    return true
  }

  /** Closes [draft], or asks first when its agent is still replying. */
  fun requestClose(draft: NewTaskDraft) {
    if (draft.chat.value?.thread?.busy?.value == true) pendingCloseState.value = draft else scope.launch { close(draft) }
  }

  /** Leaves the pending chat open and streaming. */
  fun keepOpen() {
    pendingCloseState.value = null
  }

  /** Stops the pending chat's reply and closes it. */
  fun confirmClose() {
    val draft = pendingCloseState.value ?: return
    pendingCloseState.value = null
    scope.launch { close(draft) }
  }

  /** Ends [draft]'s chat, deletes its save and removes it, with no toast. */
  fun discard(draft: NewTaskDraft) {
    if (draft in openState.value && draft.discard()) remove(draft)
  }

  /** Deletes [entry], removing the open chat that holds it like a tab's Discard does. */
  internal fun discardSaved(entry: ChatHistoryEntry) {
    val holder = openState.value.firstOrNull { it.chat.value?.sessionId == entry.sessionId }
    if (holder != null) {
      discard(holder)
      return
    }
    savedChats.discardSaved(entry)
  }

  /**
   * Shows [entry]: selects the chat that already holds it, loads it into an empty selected chat, or opens it in a new chat.
   * It never ends another chat. False when the entry cannot load; a chat added for it goes away again.
   */
  fun resume(entry: ChatHistoryEntry): Boolean {
    openState.value.firstOrNull { it.chat.value?.sessionId == entry.sessionId }?.let {
      select(it)
      return true
    }
    val previous = selectedState.value
    val target = if (previous.chat.value == null && previous.text.isBlank() && previous.images.isEmpty()) previous else newChat()
    if (!target.resume(entry)) {
      if (target !== previous) {
        openState.update { it - target }
        forget(target)
        target.dispose()
        select(previous)
      }
      return false
    }
    resumedState.tryEmit(target)
    return true
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
  internal suspend fun closeAll(): Boolean = openState.value.map { draft ->
    val idleChat = draft.chat.value?.takeUnless { it.thread.busy.value }
    draft.close().also { closed -> if (closed && idleChat != null) chats.requestSummary(idleChat.sessionId) }
  }.all { it }

  private suspend fun close(draft: NewTaskDraft) {
    if (draft !in openState.value || !closingDrafts.add(draft)) return
    try {
      val chat = draft.chat.value
      val title = newTaskChatTitle(chat?.thread?.transcript?.value.orEmpty())
      if (!draft.close()) return
      remove(draft)
      refreshSaved()
      if (chat != null) {
        // Read after close(): the session id can rotate while the last turn finishes.
        val sessionId = chat.sessionId
        chats.requestSummary(sessionId)
        toasts.show(ToastData("Closed \"$title\". It stays in Recent chats.", actions = listOf(ToastAction("Reopen") { reopen(sessionId) })))
      }
    } finally {
      closingDrafts -= draft
    }
  }

  // A fresh read, not the cached list, so a delete from Recent chats after the close is seen.
  private fun reopen(sessionId: String) = scope.launch {
    val entry = chats.savedNewTaskDrafts().firstOrNull { it.sessionId == sessionId }
    if (entry != null) resume(entry) else toasts.show(ToastMessage(ToastTone.Problem, "That chat is no longer in Recent chats."))
  }

  /** Removes [draft]; a removed selection moves to the chat now at its index, and the last chat is replaced by an empty one. */
  private fun remove(draft: NewTaskDraft) {
    val drafts = openState.value
    val index = drafts.indexOf(draft)
    if (index < 0) return
    if (pendingCloseState.value === draft) pendingCloseState.value = null
    forget(draft)
    newReplyState.update { it - draft }
    if (drafts.size == 1) {
      val replacement = createLike(draft)
      openState.value = listOf(replacement)
      select(replacement)
      return
    }
    val remaining = drafts - draft
    openState.value = remaining
    if (selectedState.value === draft) select(remaining[index.coerceAtMost(remaining.lastIndex)])
  }

  private fun busyOf(draft: NewTaskDraft): Flow<Boolean> = draft.chat.flatMapLatest { it?.thread?.busy ?: flowOf(false) }

  // Re-read on every busy change: a turn stopped before its session started rotates the id, then goes idle.
  private fun sessionIdOf(draft: NewTaskDraft): Flow<String?> = draft.chat.flatMapLatest { chat ->
    if (chat == null) flowOf(null) else chat.thread.busy.map { chat.sessionId }
  }

  /** Stops watching a draft that left the open set, so it does not outlive its tab. */
  private fun forget(draft: NewTaskDraft) {
    replyWatches.remove(draft)?.cancel()
  }

  private fun createLike(from: NewTaskDraft): NewTaskDraft = create().apply {
    repository = from.repository
    addedRepositories = from.addedRepositories
    baseBranch = from.baseBranch
    agentId = from.agentId
    selectedAgent = from.selectedAgent
  }

  private fun create(): NewTaskDraft {
    val draft = NewTaskDraft(chats, workControls, databaseSession, toasts, windowScope, clock, approvals, savedChats, changesRepository)
    draft.chatDispatcher = chatDispatcher
    replyWatches[draft] = watchReplies(draft)
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
