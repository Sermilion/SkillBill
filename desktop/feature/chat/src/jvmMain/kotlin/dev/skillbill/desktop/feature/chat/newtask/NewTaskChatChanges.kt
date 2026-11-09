package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChanges
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatProjectWorktrees
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository
import java.nio.file.Path
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
  override suspend fun projectWorktrees(repositoryRoot: String) = ChatProjectWorktrees.Unavailable("Changes are unavailable.")
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
  private var usedRoots = setOf(repositoryRoot)
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
      usedRoots = setOf(repositoryRoot)
    }
    when (val result = repository.captureBaseline(repositoryRoot)) {
      is ChatBaselineCapture.Captured -> synchronized(lock) {
        if (!ended) {
          turnStart = result.baseline
          extendBaselines(result.baseline)
        }
      }
      is ChatBaselineCapture.Unavailable -> Unit
    }
  }

  suspend fun endTurn() {
    val start = synchronized(lock) {
      turnOpen = false
      turnStart?.let { start ->
        if (start.worktrees.isEmpty()) start else start.copy(worktrees = start.worktrees.filterKeys { it in usedRoots })
      }.also { turnStart = null }
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
        usedRoots = setOf(repositoryRoot)
        turnStart = result.baseline
        true
      }
      is ChatBaselineCapture.Unavailable -> false
    }
  }

  suspend fun recordTool(call: ChatToolCall) {
    if (synchronized(lock) { !turnOpen || turnStart == null || ended }) return
    val project = repository.projectWorktrees(repositoryRoot) as? ChatProjectWorktrees.Listed ?: return
    if (currentBaseline?.projectId != null && currentBaseline?.projectId != project.projectId) return
    val input = call.input.replace('\\', '/').replace("//", "/")
    val cwd = call.workingDirectory?.replace('\\', '/')?.trimEnd('/')
    val checkout = Path.of(repositoryRoot)
    val touched = project.roots.filter { path ->
      val root = path.replace('\\', '/').trimEnd('/')
      val sibling = Path.of(path)
      val relative = if (checkout.root == sibling.root) checkout.relativize(sibling).toString().replace('\\', '/') else ""
      cwd == root || cwd?.startsWith("$root/") == true || listOf(root, relative).filter(String::isNotEmpty).any { reference ->
        val at = input.indexOf(reference)
        val after = at + reference.length
        val delimitedBefore = at == 0 || at > 0 && (input[at - 1].isWhitespace() || input[at - 1] in "\"'=:([{;")
        val delimitedAfter = at >= 0 && (after == input.length || input[after].isWhitespace() || input[after] in "/\"';,)}]")
        delimitedBefore && delimitedAfter
      }
    }
    for (root in touched) {
      val known = synchronized(lock) {
        usedRoots = usedRoots + root
        turnStart?.worktrees?.containsKey(root) == true
      }
      if (known) continue
      val captured = (repository.captureBaseline(root) as? ChatBaselineCapture.Captured)?.baseline ?: continue
      val start = captured.worktrees[root] ?: continue
      synchronized(lock) {
        if (!ended && turnOpen && captured.projectId == project.projectId) {
          turnStart = turnStart?.let { it.copy(worktrees = it.worktrees + (root to start)) }
          extendBaselines(captured)
        }
      }
    }
  }

  private fun extendBaselines(captured: ChatChangesBaseline) {
    val previous = currentBaseline ?: return
    if (captured.worktrees.isEmpty() || previous.projectId != null && previous.projectId != captured.projectId) return
    val retained = previous.worktrees.ifEmpty { mapOf(repositoryRoot to previous.copy(projectId = null, worktrees = emptyMap())) }
    currentBaseline = previous.copy(projectId = captured.projectId, worktrees = captured.worktrees + retained)
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
