package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
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
  data class Shown(val changes: ChatChanges, val baseline: ChatChangesBaseline, val resumed: Boolean, val paths: Set<String>?) : ChatChangesState
}

internal object UnavailableChatChanges : ChatChangesRepository {
  override suspend fun captureBaseline(repositoryRoot: String) = ChatBaselineCapture.Unavailable("Changes are unavailable.")
  override suspend fun read(repositoryRoot: String, baseline: ChatChangesBaseline, paths: Set<String>?) = ChatChangesRead.Unavailable("Changes are unavailable.")
  override suspend fun changedPaths(repositoryRoot: String, since: ChatChangesBaseline) = ChatChangedPaths.Unavailable("Changes are unavailable.")
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
  initialAttributedPaths: Set<String>?,
) {
  @Volatile private var currentBaseline = initialBaseline
  @Volatile private var attempted = captureAttempted || initialBaseline != null
  @Volatile private var attributed: Set<String>? = if (initialBaseline == null) emptySet() else initialAttributedPaths
  @Volatile private var turnOpen = false
  @Volatile private var turnStart: ChatChangesBaseline? = null
  val baseline: ChatChangesBaseline? get() = currentBaseline
  val attributedPaths: Set<String>? get() = if (currentBaseline == null) null else attributed
  private val mutableState = MutableStateFlow<ChatChangesState>(ChatChangesState.None)
  val state: StateFlow<ChatChangesState> = mutableState.asStateFlow()
  private val lock = Any()
  private var readJob: Job? = null
  private var ended = false
  val captureAttempted: Boolean get() = attempted

  init { if (initialBaseline != null && !repositoryMissing) refresh() }

  suspend fun beginTurn(started: Boolean) {
    if (captureBeforeFirstTurn(started)) return
    synchronized(lock) {
      if (currentBaseline == null || attributed == null || turnOpen || repositoryMissing || ended) return
      turnOpen = true
    }
    when (val result = repository.captureBaseline(repositoryRoot)) {
      is ChatBaselineCapture.Captured -> synchronized(lock) { if (!ended) turnStart = result.baseline }
      is ChatBaselineCapture.Unavailable -> Unit
    }
  }

  suspend fun endTurn() {
    val start = synchronized(lock) {
      turnOpen = false
      turnStart.also { turnStart = null }
    } ?: return
    when (val result = repository.changedPaths(repositoryRoot, start)) {
      is ChatChangedPaths.Listed -> synchronized(lock) { attributed = attributed?.plus(result.paths) }
      is ChatChangedPaths.Unavailable -> Unit
    }
  }

  private suspend fun captureBeforeFirstTurn(started: Boolean): Boolean {
    synchronized(lock) {
      if (currentBaseline != null || started || repositoryMissing || attempted || ended) return false
      attempted = true
    }
    return when (val result = repository.captureBaseline(repositoryRoot)) {
      is ChatBaselineCapture.Captured -> synchronized(lock) {
        currentBaseline = result.baseline
        attributed = emptySet()
        turnOpen = true
        turnStart = result.baseline
        true
      }
      is ChatBaselineCapture.Unavailable -> false
    }
  }

  fun refresh() {
    val baseline = currentBaseline ?: return
    if (repositoryMissing) return
    synchronized(lock) {
      if (ended) return
      readJob?.cancel()
      val paths = attributed
      val job = scope.launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
        val state = try {
          when (val result = repository.read(repositoryRoot, baseline, paths)) {
            is ChatChangesRead.Listed -> if (result.changes.files.isNotEmpty()) ChatChangesState.Shown(result.changes, baseline, resumed, paths) else ChatChangesState.None
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
    turnOpen = false
    turnStart = null
    readJob?.cancel()
    readJob = null
  }
}
