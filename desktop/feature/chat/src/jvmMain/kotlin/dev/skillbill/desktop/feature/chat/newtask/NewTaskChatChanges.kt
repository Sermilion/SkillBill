package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.ChatChangedPaths
import dev.skillbill.desktop.core.domain.model.ChatChanges
import dev.skillbill.desktop.core.domain.model.ChatWorktreeChanges
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Duration.Companion.seconds

internal sealed interface ChatChangesState {
  data object None : ChatChangesState
  data class Shown(val changes: ChatChanges, val baseline: ChatChangesBaseline, val resumed: Boolean, val paths: Set<String>?) : ChatChangesState
}

internal object UnavailableChatChanges : ChatChangesRepository {
  override suspend fun readSinceOpening(repositoryRoot: String, baseline: ChatChangesBaseline) = ChatChangesRead.Unavailable("Changes are unavailable.")
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
  private val captureLock = Mutex()
  private var readJob: Job? = null
  private var readRequested = false
  private var ended = false
  val captureAttempted: Boolean get() = attempted

  init { if (!repositoryMissing) refresh() }

  suspend fun beginTurn(started: Boolean) {
    if (started && currentBaseline == null && attempted) return
    val captured = ensureOpeningBaseline()
    synchronized(lock) {
      if (currentBaseline == null || attributed == null || turnOpen || repositoryMissing || ended) return
      turnOpen = true
      usedRoots = setOf(repositoryRoot)
      if (captured) {
        turnStart = currentBaseline
        return
      }
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

  private suspend fun ensureOpeningBaseline(): Boolean = captureLock.withLock {
    synchronized(lock) {
      if (currentBaseline != null || repositoryMissing || attempted || ended) return@withLock false
      attempted = true
    }
    when (val result = repository.captureBaseline(repositoryRoot)) {
      is ChatBaselineCapture.Captured -> synchronized(lock) {
        if (ended) return@withLock false
        currentBaseline = result.baseline
        attributed = emptySet()
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

  suspend fun observe() {
    while (synchronized(lock) { !ended }) {
      refresh()
      delay(1.seconds)
    }
  }

  fun refresh() {
    if (repositoryMissing) return
    synchronized(lock) {
      if (ended) return
      readRequested = true
      if (readJob?.isActive == true) return
      val job = scope.launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
        val owner = currentCoroutineContext()[Job]
        try {
          while (true) {
            val pending = synchronized(lock) {
              if (ended || !readRequested) {
                if (readJob === owner) readJob = null
                false
              } else {
                readRequested = false
                true
              }
            }
            if (!pending) break
            readCurrentChanges(owner)
          }
        } finally {
          synchronized(lock) { if (readJob === owner) readJob = null }
        }
      }
      readJob = job
      job.start()
    }
  }

  private suspend fun readCurrentChanges(owner: Job?) {
    ensureOpeningBaseline()
    val turn = synchronized(lock) { turnStart }
    val start = synchronized(lock) {
      turn?.let { if (it.worktrees.isEmpty()) it else it.copy(worktrees = it.worktrees.filterKeys { root -> root in usedRoots }) }
    }
    if (start != null) {
      when (val result = repository.changedPaths(repositoryRoot, start)) {
        is ChatChangedPaths.Listed -> synchronized(lock) {
          if (!ended && turnStart === turn) attributed = attributed?.plus(result.paths)
        }
        is ChatChangedPaths.Unavailable -> Unit
      }
    }
    val (saved, paths) = synchronized(lock) { (currentBaseline ?: return) to attributed }
    val baseline = (saved.worktrees[repositoryRoot] ?: saved).copy(untrackedPaths = emptyList(), stashSha = null, projectId = null, worktrees = emptyMap())
    val changes = (repository.readSinceOpening(repositoryRoot, baseline) as? ChatChangesRead.Listed)?.changes ?: return
    val primary = ChatWorktreeChanges(repositoryRoot, baseline, null, changes)
    val siblingPaths = paths?.filter { it.startsWith("/") || it.contains(":/") }?.toSet().orEmpty()
    val siblings = if (siblingPaths.isNotEmpty()) {
      (repository.read(repositoryRoot, saved, siblingPaths) as? ChatChangesRead.Listed)?.changes?.worktrees
        .orEmpty().filter { it.repositoryRoot != repositoryRoot }
    } else emptyList()
    val groups = (listOf(primary) + siblings).filter { it.changes.files.isNotEmpty() }
    val files = groups.flatMap { it.changes.files }
    val listed = changes.copy(files = files, insertions = groups.sumOf { it.changes.insertions },
      deletions = groups.sumOf { it.changes.deletions }, worktrees = if (siblings.isEmpty()) emptyList() else groups)
    val state = if (files.isEmpty()) ChatChangesState.None else ChatChangesState.Shown(listed, baseline, resumed, null)
    synchronized(lock) {
      if (!ended && readJob === owner && owner?.isActive != false) mutableState.value = state
    }
  }

  fun end() = synchronized(lock) {
    ended = true
    turnOpen = false
    turnStart = null
    readRequested = false
    readJob?.cancel()
    readJob = null
  }
}
