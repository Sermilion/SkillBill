package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.chatScopeTarget
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import dev.skillbill.desktop.feature.chat.newtask.ChangesAnchor
import dev.skillbill.desktop.feature.chat.newtask.ChatChangesState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

internal fun runChatChanges(repository: ChatChangesRepository, root: String): Flow<ChatChangesState> = flow {
  while (currentCoroutineContext().isActive) {
    val changes = (repository.readCurrentChanges(root) as? ChatChangesRead.Listed)?.changes
    emit(if (changes == null || changes.files.isEmpty()) ChatChangesState.None else {
      val baseline = ChatChangesBaseline(changes.headSha, emptyList(), null, Clock.System.now())
      ChatChangesState.Shown(changes, baseline, false, null)
    })
    delay(1.seconds)
  }
}

@Composable
internal fun RunChatChangesAnchor(
  session: ChatSession,
  repository: ChatChangesRepository,
  composerFocus: FocusRequester,
  onOpenChanges: (ChangesSource.Chat, String?) -> Unit,
) {
  val item by session.header.collectAsState()
  val root = item.workingRoot()
  key(session, root) {
    val changes = remember(repository, root) { runChatChanges(repository, root).flowOn(Dispatchers.IO) }
    val state by changes.collectAsState(ChatChangesState.None)
    if (state is ChatChangesState.Shown) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        RunChatChangesPill(state, session.id, item.title, root, composerFocus, onOpenChanges)
      }
    }
  }
}

@Composable
internal fun RunChatChangesPill(
  state: ChatChangesState,
  chatId: String,
  title: String,
  root: String,
  composerFocus: FocusRequester,
  onOpenChanges: (ChangesSource.Chat, String?) -> Unit,
) {
  ChangesAnchor(state, composerFocus) { _, path ->
    val shown = state as? ChatChangesState.Shown ?: return@ChangesAnchor
    onOpenChanges(
      ChangesSource.Chat(chatId, title, chatScopeTarget(chatId, root, shown.baseline,
        shown.changes.timeline, shown.paths, shown.changes.comparisonBaseSha)),
      path,
    )
  }
}
