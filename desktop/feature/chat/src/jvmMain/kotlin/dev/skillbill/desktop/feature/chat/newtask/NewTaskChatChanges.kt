package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatChanges
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext

internal sealed interface ChatChangesState {
  data object None : ChatChangesState
  data class Shown(val changes: ChatChanges, val baseline: ChatChangesBaseline, val resumed: Boolean) : ChatChangesState
}

internal object UnavailableChatChanges : ChatChangesRepository {
  override suspend fun captureBaseline(repositoryRoot: String) = ChatBaselineCapture.Unavailable("Changes are unavailable.")
  override suspend fun read(repositoryRoot: String, baseline: ChatChangesBaseline) = ChatChangesRead.Unavailable("Changes are unavailable.")
}

internal class NewTaskChatChanges(
  private val repository: ChatChangesRepository,
  private val scope: CoroutineScope,
  private val dispatcher: CoroutineDispatcher,
  private val repositoryRoot: String,
  initialBaseline: ChatChangesBaseline?,
  val resumed: Boolean,
  private val repositoryMissing: Boolean,
  captureAttempted: Boolean,
) {
  @Volatile private var currentBaseline = initialBaseline
  @Volatile private var attempted = captureAttempted || initialBaseline != null
  val baseline: ChatChangesBaseline? get() = currentBaseline
  private val mutableState = MutableStateFlow<ChatChangesState>(ChatChangesState.None)
  val state: StateFlow<ChatChangesState> = mutableState.asStateFlow()
  private val lock = Any()
  private var readJob: Job? = null
  private var ended = false
  val captureAttempted: Boolean get() = attempted

  init { if (initialBaseline != null && !repositoryMissing) refresh() }

  suspend fun captureBeforeFirstTurn(started: Boolean) {
    synchronized(lock) {
      if (currentBaseline != null || started || repositoryMissing || attempted || ended) return
      attempted = true
    }
    when (val result = repository.captureBaseline(repositoryRoot)) {
      is ChatBaselineCapture.Captured -> currentBaseline = result.baseline
      is ChatBaselineCapture.Unavailable -> Unit
    }
  }

  fun refresh() {
    val baseline = currentBaseline ?: return
    if (repositoryMissing) return
    synchronized(lock) {
      if (ended) return
      readJob?.cancel()
      val job = scope.launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
        val state = try {
          when (val result = repository.read(repositoryRoot, baseline)) {
            is ChatChangesRead.Listed -> if (result.changes.files.isNotEmpty()) ChatChangesState.Shown(result.changes, baseline, resumed) else ChatChangesState.None
            is ChatChangesRead.Unavailable -> ChatChangesState.None
          }
        } catch (cancelled: CancellationException) { throw cancelled }
        val completedJob = currentCoroutineContext()[Job]
        synchronized(lock) { if (!ended && readJob === completedJob && completedJob?.isActive != false) mutableState.value = state }
      }
      readJob = job
      job.start()
    }
  }

  fun end() = synchronized(lock) {
    ended = true
    readJob?.cancel()
    readJob = null
  }
}
