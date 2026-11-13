package dev.skillbill.desktop.feature.chat.newtask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.model.RepositoryBranches
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.newWorkBranchProblem
import dev.skillbill.desktop.core.domain.model.RepositoryBranchesRead
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.FolderChoice
import dev.skillbill.desktop.core.domain.repository.FolderChooser
import dev.skillbill.desktop.core.domain.repository.RepositoryBranchesRepository
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.NewTaskChatLayout
import dev.skillbill.desktop.core.ui.board.ShellBoard
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.feature.chat.RESUME_COMMAND
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import me.tatarka.inject.annotations.Inject
import kotlin.time.Clock
import kotlin.time.Instant

sealed interface BranchReadState {
  data object Idle : BranchReadState
  data class Reading(val root: String) : BranchReadState
  data class Loaded(val root: String, val branches: RepositoryBranches) : BranchReadState
  data class Failed(val root: String, val message: String) : BranchReadState
}

data class NewTaskUiState(
  val repositories: List<NewTaskRepository> = emptyList(),
  val repositoryError: String? = null,
  val repositoryPickerOpen: Boolean = false,
  val choosingFolder: Boolean = false,
  val agents: List<String> = emptyList(),
  val agent: ChatAgent? = null,
  val home: NewTaskHome = NewTaskHome.Pending,
  val detectedAgents: DetectedAgentsRead? = null,
  val resumePickerOpen: Boolean = false,
  val branchRead: BranchReadState = BranchReadState.Idle,
  val repositoryPicks: Int = 0,
  val newTaskChatLayout: NewTaskChatLayout = NewTaskChatLayout.DEFAULT,
  val qaDeviceName: String? = null,
)

sealed interface NewTaskAction {
  data class TextChanged(val text: String) : NewTaskAction
  data class ChooseBaseBranch(val branch: String) : NewTaskAction
  data object SwitchToDefaultBranch : NewTaskAction
  data object UseCurrentBranchAsBase : NewTaskAction
  data object RetryBranches : NewTaskAction
  data class ChooseRepository(val repository: NewTaskRepository) : NewTaskAction
  data class SubmitRepositoryPath(val path: String) : NewTaskAction
  data class ChooseAgent(val agentId: String) : NewTaskAction
  data class ChooseModel(val model: ChatModel?) : NewTaskAction
  data object AddRepository : NewTaskAction
  data object OpenRepositoryPicker : NewTaskAction
  data object CloseRepositoryPicker : NewTaskAction
  data object Send : NewTaskAction
  data object Stop : NewTaskAction
  data object SendNow : NewTaskAction
  data object NewChat : NewTaskAction
  data object DismissResume : NewTaskAction
  data class ResumeDraft(val entry: ChatHistoryEntry) : NewTaskAction
  data class DiscardDraft(val entry: ChatHistoryEntry) : NewTaskAction
  data object ShowAllDrafts : NewTaskAction
  data class RunSuggestedAction(val action: ChatSuggestedAction) : NewTaskAction
}

/** Reads the choices the composer offers and keeps the operator's values in the selected chat of [chats]. */
@Inject
class NewTaskViewModel(
  val chats: NewTaskChats,
  private val board: WorkBoardRepository,
  private val specs: SpecRepository,
  private val branches: RepositoryBranchesRepository,
  private val folders: FolderChooser,
  private val chat: ExecutionChatRepository,
  private val config: RuntimeConfigRepository,
  private val clock: Clock,
  private val settings: AppSettingsRepository,
  private val diagnostics: Diagnostics,
) : ViewModel() {
  private val mutableState = MutableStateFlow(NewTaskUiState(agents = chat.agents(), agent = chats.selected.value.selectedAgent))
  val state: StateFlow<NewTaskUiState> = mutableState.asStateFlow()

  /** The selected chat; every action acts on it. */
  val draft: NewTaskDraft get() = chats.selected.value

  private var pathCheck: Job? = null
  internal var repositoryDispatcher: CoroutineDispatcher = Dispatchers.IO
  private var pathCheckGeneration = 0L
  private val agentReads = mutableMapOf<NewTaskDraft, Job>()
  private class AgentReadOwner(val requestedAgentId: String?)
  private sealed interface RepositoryValidation {
    data object Stale : RepositoryValidation
    data class Rejected(val message: String) : RepositoryValidation
    data class Listed(val repository: NewTaskRepository, val branches: RepositoryBranches) : RepositoryValidation
  }

  private val agentReadOwners = mutableMapOf<NewTaskDraft, AgentReadOwner>()
  private val pendingRepositorySelection = mutableMapOf<NewTaskDraft, Pair<String, RepositoryBranches?>>()
  private var branchReadJob: Job? = null
  private var readinessRequested = false

  init {
    settings.settings.map { it.newTaskChatLayout }.distinctUntilChanged()
      .onEach { layout -> mutableState.update { it.copy(newTaskChatLayout = layout) } }
      .catch { diagnostics.record("desktop_new_task_chat_layout_unavailable") }
      .launchIn(viewModelScope)
    settings.settings.map { it.qaDevice?.displayName }.distinctUntilChanged()
      .onEach { name -> mutableState.update { it.copy(qaDeviceName = name) } }
      .catch { diagnostics.record("desktop_qa_device_pick_unavailable") }
      .launchIn(viewModelScope)
    settings.settings.map { it.defaultAgentId }.distinctUntilChanged().drop(1)
      .onEach { followDefaultAgent(chats.selected.value) }
      .catch { diagnostics.record("desktop_default_agent_unavailable") }
      .launchIn(viewModelScope)
    val first = draft
    observeContextCommit(first)
    readBranches(first)
    viewModelScope.launch { observeBoard(loadRepositories()) }
    readAgent(first) { owner -> resolveAgent(first, first.agentId ?: defaultAgentId(), owner) }
    viewModelScope.launch { chats.selected.drop(1).collect(::showSelected) }
    viewModelScope.launch { chats.open.collect { drafts -> drafts.forEach(::observeContextCommit) } }
    viewModelScope.launch { chats.resumed.collect { if (isSelected(it)) showResumed(it) } }
  }

  fun now(): Instant = clock.now()

  fun onAction(action: NewTaskAction) {
    val target = draft
    when (action) {
      is NewTaskAction.TextChanged -> {
        if (target.text != action.text) target.cursorMcpDependency = CursorMcpDependency.ProjectUserOnly
        target.text = action.text
      }
      is NewTaskAction.ChooseBaseBranch -> chooseBaseBranch(target, action.branch)
      NewTaskAction.SwitchToDefaultBranch -> switchToDefaultBranch(target)
      NewTaskAction.UseCurrentBranchAsBase -> {
        if (!canChangeContext(target)) return
        val loaded = mutableState.value.branchRead as? BranchReadState.Loaded ?: return
        if (loaded.root != target.repository?.root) return
        if (target.rebindChat(requestedBranch = loaded.branches.currentBranch.orEmpty())) mutableState.update { it.copy(repositoryError = null) }
      }
      NewTaskAction.RetryBranches -> target.repository?.root?.let { readBranches(target, it) }
      is NewTaskAction.ChooseRepository -> {
        selectRepository(target, action.repository)
      }
      is NewTaskAction.SubmitRepositoryPath -> submitPath(target, action.path)
      NewTaskAction.AddRepository -> addRepository(target)
      is NewTaskAction.ChooseModel -> {
        if (!canChangeContext(target)) return
        val agent = target.selectedAgent ?: return
        if (agent.agentId != target.agentId) return
        if (agentReadOwners[target]?.requestedAgentId?.let { it != agent.agentId } == true) return
        agentReads.remove(target)?.cancel()
        target.agentChosen = true
        val selected = agent.copy(model = action.model?.id, effort = null)
        target.rebindChat(requestedAgent = selected)
      }
      is NewTaskAction.ChooseAgent -> {
        if (!canChangeContext(target)) return
        target.cursorMcpDependency = CursorMcpDependency.ProjectUserOnly
        target.agentChosen = true
        readAgent(target, action.agentId) { owner -> resolveAgent(target, action.agentId, owner) }
      }
      NewTaskAction.OpenRepositoryPicker -> {
        if (!canChangeContext(target)) return
        mutableState.update { it.copy(repositoryError = null, repositoryPickerOpen = true) }
        readBranches(target)
      }
      NewTaskAction.CloseRepositoryPicker -> {
        if (mutableState.value.choosingFolder) return
        pathCheck?.cancel()
        mutableState.update { it.copy(repositoryError = null, repositoryPickerOpen = false) }
      }
      NewTaskAction.Send -> send(target)
      NewTaskAction.Stop -> target.stop()
      NewTaskAction.SendNow -> target.sendNow()
      NewTaskAction.NewChat -> chats.newChat()
      NewTaskAction.DismissResume -> mutableState.update { it.copy(resumePickerOpen = false) }
      is NewTaskAction.DiscardDraft -> chats.discardSaved(action.entry)
      NewTaskAction.ShowAllDrafts -> {
        chats.refreshSaved()
        mutableState.update { it.copy(resumePickerOpen = true) }
      }
      is NewTaskAction.ResumeDraft -> {
        val loadsIntoTarget = target.chat.value == null
        if (!chats.resume(action.entry)) return
        // A read still running for the draft that took the load would rebind its chat to the old agent; other drafts keep theirs.
        if (loadsIntoTarget || chats.selected.value !== target) {
          val resumedTarget = chats.open.value.firstOrNull {
            it.chat.value?.sessionId == action.entry.sessionId || action.entry.sessionId in it.resumeSourceSessions.value
          }
          agentReads.remove(resumedTarget ?: target)?.cancel()
        }
        mutableState.update { it.copy(resumePickerOpen = false) }
      }
      is NewTaskAction.RunSuggestedAction -> {
        if (action.action.mode == ChatPermissionMode.Act && requestBranchChoice(target)) return
        target.branchChoiceRequested = false
        target.sendSuggestedAction(action.action)
      }
    }
  }

  internal fun start(origin: NewTaskStartOrigin, kind: NewTaskKind, target: String?, action: ChatSuggestedAction?) {
    val current = origin.draft
    if (current !in chats.open.value || chats.selected.value !== current || current.chat.value !== origin.chat ||
      current.contextRevision != origin.contextRevision || current.repository?.root != origin.selectedRoot ||
      current.contextTransitioning || current.resuming
    ) return
    if (action?.branch.isNullOrBlank() && requestBranchChoice(current)) return
    current.start(kind, target = target, action = action)
  }

  private fun requestBranchChoice(target: NewTaskDraft): Boolean {
    val loaded = mutableState.value.branchRead as? BranchReadState.Loaded ?: return false
    if (loaded.root != target.repository?.root || loaded.branches.newWorkBranchProblem(target.baseBranch) == null) return false
    target.branchChoiceRequested = true
    return true
  }

  private fun isSelected(target: NewTaskDraft): Boolean = chats.selected.value === target

  /** Re-reads the composer for a saved chat loaded into the selected draft, which a selection change alone does not report. */
  private fun showResumed(target: NewTaskDraft) {
    agentReads.remove(target)?.cancel()
    mutableState.update { it.copy(agent = target.selectedAgent, resumePickerOpen = false) }
    readBranches(target)
  }

  private fun showSelected(selected: NewTaskDraft) {
    observeContextCommit(selected)
    pathCheck?.cancel()
    mutableState.update {
      it.copy(agent = selected.selectedAgent, repositoryPickerOpen = false, resumePickerOpen = false, repositoryError = null)
    }
    readBranches(selected)
    val resolved = selected.selectedAgent != null && selected.selectedAgent?.agentId == selected.agentId
    if (!resolved && selected !in agentReads) readAgent(selected) { owner -> resolveAgent(selected, selected.agentId ?: defaultAgentId(), owner) }
    else if (resolved) followDefaultAgent(selected)
  }

  /** Moves a draft that has no conversation and no operator pick onto the current default agent. */
  private fun followDefaultAgent(target: NewTaskDraft) {
    if (target.resuming || target.agentChosen || target.chat.value != null) return
    readAgent(target) { owner ->
      val next = defaultAgentId() ?: return@readAgent
      if (next != target.agentId && !target.agentChosen && target.chat.value == null) resolveAgent(target, next, owner)
    }
  }

  private fun send(target: NewTaskDraft) {
    if (!target.canSend()) return
    target.branchChoiceRequested = false
    if (target.text.trim() != RESUME_COMMAND) return target.send()
    target.text = ""
    chats.refreshSaved()
    mutableState.update { it.copy(resumePickerOpen = true) }
  }

  private suspend fun loadRepositories(): List<NewTaskRepository> {
    val listed = when (val projects = specs.projects()) {
      is Outcome.Success -> projects.value.mapNotNull { project -> project.repoRoot?.let { NewTaskRepository(it, project.displayName) } }
      is Outcome.Failure -> emptyList()
    }
    mutableState.update { it.copy(repositories = listed) }
    return listed
  }

  private suspend fun observeBoard(listed: List<NewTaskRepository>) {
    board.observe(ShellBoard.query).collect { read ->
      val target = draft
      if (read !is BoardState.Loading && !target.resuming && target.initialRepositoryDefaultPending && target.repository == null && target.chat.value == null) {
        val row = (read as? BoardState.Loaded)?.items?.firstOrNull()
        val repository = row?.let { NewTaskRepository(it.repositoryRoot, it.repositoryName) } ?: listed.firstOrNull()
        if (repository != null) {
          target.repository = repository
          readBranches(target)
        }
      }
      val home = newTaskHome(read)
      mutableState.update { it.copy(home = home) }
      if (home == NewTaskHome.FirstRun) readReadinessOnce()
    }
  }

  private fun readReadinessOnce() {
    if (readinessRequested) return
    readinessRequested = true
    viewModelScope.launch {
      val read = config.detectedAgentsRead()
      mutableState.update { it.copy(detectedAgents = read) }
    }
  }

  private suspend fun defaultAgentId(): String? {
    val agents = mutableState.value.agents
    if (agents.isEmpty()) return null
    val stored = try {
      settings.settings.first().defaultAgentId
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null // The settings observer already records the failure.
    }
    stored?.let { id -> agents.firstOrNull { it.equals(id, ignoreCase = true) } }?.let { return it }
    val detected = (config.detectedAgentsRead() as? DetectedAgentsRead.Success)?.agents.orEmpty()
    return agents.firstOrNull { id -> detected.any { it.name.equals(id, ignoreCase = true) } } ?: agents.first()
  }

  /** Runs [read] as [target]'s only agent read, so a newer choice in one chat never cancels another chat's read. */
  private fun readAgent(target: NewTaskDraft, requestedAgentId: String? = null, read: suspend (AgentReadOwner) -> Unit) {
    if (target.resuming) return
    agentReads.remove(target)?.cancel()
    val owner = AgentReadOwner(requestedAgentId)
    agentReadOwners[target] = owner
    val job = viewModelScope.launch { read(owner) }
    agentReads[target] = job
    job.invokeOnCompletion {
      if (agentReads[target] === job) agentReads.remove(target)
      if (agentReadOwners[target] === owner) agentReadOwners.remove(target)
    }
  }

  private suspend fun resolveAgent(target: NewTaskDraft, agentId: String?, owner: AgentReadOwner) {
    if (agentId == null || target.resuming) return
    val agent = chat.agentFor(agentId)
    if (target.resuming || agentReadOwners[target] !== owner) return
    target.rebindChat(requestedAgent = agent)
  }

  private fun readBranches(target: NewTaskDraft, rootOverride: String? = null) {
    if (!isSelected(target)) return
    branchReadJob?.cancel()
    val root = rootOverride ?: target.repository?.root
    if (root == null) {
      mutableState.update { it.copy(branchRead = BranchReadState.Idle) }
      return
    }
    mutableState.update { current ->
      val loaded = (current.branchRead as? BranchReadState.Loaded)?.root == root
      if (loaded) current else current.copy(branchRead = BranchReadState.Reading(root))
    }
    branchReadJob = viewModelScope.launch {
      val read = when (val result = branches.branches(root)) {
        is RepositoryBranchesRead.Listed -> BranchReadState.Loaded(root, result.branches)
        is RepositoryBranchesRead.NotARepository -> BranchReadState.Failed(root, "${result.path} is not a git repository.")
        is RepositoryBranchesRead.Unavailable -> BranchReadState.Failed(root, readProblemText(result.problem))
      }
      if (isSelected(target) && target.repository?.root == root) mutableState.update { it.copy(branchRead = read) }
    }
  }

  private fun selectRepository(target: NewTaskDraft, repository: NewTaskRepository) {
    if (!canChangeContext(target)) return
    submitPath(target, repository.root)
  }

  private fun addRepository(target: NewTaskDraft) {
    if (!canChangeContext(target) || mutableState.value.choosingFolder) return
    pathCheck?.cancel()
    val generation = ++pathCheckGeneration
    val revision = target.contextRevision
    val request = target.beginRepositorySelectionRequest()
    mutableState.update { it.copy(choosingFolder = true) }
    // UNDISPATCHED enters the try at once, so a cancel before the first dispatch cannot leave the flag set.
    pathCheck = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val choice = try {
        folders.choose()
      } finally {
        mutableState.update { it.copy(choosingFolder = false) }
      }
      if (!isSelected(target) || generation != pathCheckGeneration || target.contextRevision != revision ||
        target.repositoryRequestGeneration != request || !canChangeContext(target)
      ) return@launch
      when (choice) {
        FolderChoice.Cancelled -> Unit
        is FolderChoice.Failed -> showRepositoryError(target, "Could not open the folder chooser: ${choice.message}")
        is FolderChoice.Chosen -> checkPath(target, choice.path, generation, revision, request)
      }
    }
  }

  private fun submitPath(target: NewTaskDraft, path: String) {
    if (!canChangeContext(target) || path.isBlank()) return
    pathCheck?.cancel()
    val generation = ++pathCheckGeneration
    val revision = target.contextRevision
    val request = target.beginRepositorySelectionRequest()
    pathCheck = viewModelScope.launch { checkPath(target, path, generation, revision, request) }
  }

  private suspend fun checkPath(
    target: NewTaskDraft,
    path: String,
    generation: Long,
    revision: Long,
    request: Long,
  ) {
    if (!canChangeContext(target)) return
    val validation = validateRepository(path) {
      isSelected(target) && generation == pathCheckGeneration && target.contextRevision == revision &&
        target.repositoryRequestGeneration == request && canChangeContext(target)
    }
    when (validation) {
      RepositoryValidation.Stale -> Unit
      is RepositoryValidation.Rejected -> showRepositoryError(target, validation.message)
      is RepositoryValidation.Listed -> addListedRepository(target, validation, generation, revision, request)
    }
  }

  private suspend fun validateRepository(path: String, stillCurrent: () -> Boolean): RepositoryValidation {
    val canonical = try { withContext(repositoryDispatcher) { Path.of(path).toRealPath().toString() } }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { return if (stillCurrent()) RepositoryValidation.Rejected("Could not access $path.") else RepositoryValidation.Stale }
    if (!stillCurrent()) return RepositoryValidation.Stale
    when (val read = branches.branches(canonical)) {
      is RepositoryBranchesRead.NotARepository -> return if (stillCurrent()) {
        RepositoryValidation.Rejected("$canonical is not a git repository.")
      } else RepositoryValidation.Stale
      is RepositoryBranchesRead.Unavailable -> return if (stillCurrent()) {
        RepositoryValidation.Rejected("Could not read $canonical: ${readProblemText(read.problem)}")
      } else RepositoryValidation.Stale
      is RepositoryBranchesRead.Listed -> {
        val root = try { withContext(repositoryDispatcher) { Path.of(read.branches.repositoryRoot).toRealPath().toString() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
          return if (stillCurrent()) RepositoryValidation.Rejected("Could not resolve the repository root for $canonical.") else RepositoryValidation.Stale
        }
        if (!stillCurrent()) return RepositoryValidation.Stale
        if (root != canonical) return RepositoryValidation.Rejected("Choose the repository root itself. $canonical is inside $root.")
        return when (val project = specs.projectFor(root)) {
          is Outcome.Failure -> if (stillCurrent()) RepositoryValidation.Rejected(readProblemText(project.error)) else RepositoryValidation.Stale
          is Outcome.Success -> if (stillCurrent()) {
            RepositoryValidation.Listed(NewTaskRepository(root, project.value.displayName), read.branches.copy(repositoryRoot = root))
          } else RepositoryValidation.Stale
        }
      }
    }
  }

  private fun addListedRepository(target: NewTaskDraft, validation: RepositoryValidation.Listed, generation: Long, revision: Long, request: Long) {
    if (!isSelected(target) || generation != pathCheckGeneration || target.contextRevision != revision ||
      target.repositoryRequestGeneration != request || !canChangeContext(target)
    ) return
    val repository = validation.repository
    val root = repository.root
    if (target.repository?.root == root) return
    pendingRepositorySelection[target] = repository.root to validation.branches
    if (!target.rebindChat(requestedRepository = repository, requestedBranch = "")) {
      pendingRepositorySelection.remove(target)
      showRepositoryError(target, "Finish the current chat or operation decision before changing repositories.")
    } else {
      viewModelScope.launch {
        target.awaitContextTransition()
        if (target.repository?.root != root && pendingRepositorySelection.remove(target) != null) {
          showRepositoryError(target, "The repository could not be saved. The previous selection remains active.")
        }
      }
    }
  }

  private fun showRepositoryError(target: NewTaskDraft, message: String) {
    if (isSelected(target)) mutableState.update { it.copy(repositoryError = message) }
  }

  /** Moves the chat onto a checkout of the chosen base [branch], so the base and the working directory never differ; blank is the default branch. */
  private fun chooseBaseBranch(target: NewTaskDraft, branch: String) {
    if (!canChangeContext(target)) return
    pathCheck?.cancel()
    mutableState.update { it.copy(repositoryError = null, repositoryPickerOpen = false) }
    val root = target.repository?.root ?: return
    val loaded = (mutableState.value.branchRead as? BranchReadState.Loaded)?.takeIf { it.root == root }?.branches
    val checkout = branch.ifBlank { loaded?.defaultBranch.orEmpty() }
    if (loaded == null || checkout.isBlank() || checkout == loaded.currentBranch) {
      target.rebindChat(requestedBranch = branch)
      return
    }
    val remote = loaded.remote.any { it.name == checkout }
    switchCheckout(target, root, checkout, loaded.currentBranch, if (remote) checkout.substringAfter('/') else branch)
  }

  private fun switchToDefaultBranch(target: NewTaskDraft) {
    if (!canChangeContext(target)) return
    val loaded = mutableState.value.branchRead as? BranchReadState.Loaded ?: return
    val root = target.repository?.root ?: return
    val defaultBranch = loaded.branches.defaultBranch
    if (loaded.root != root || defaultBranch == null) return
    switchCheckout(target, root, defaultBranch, loaded.branches.currentBranch, "")
  }

  /** Re-points [target] at the checkout of [branch] and adopts [baseBranch]; a failure keeps the old root and base and shows why. */
  private fun switchCheckout(target: NewTaskDraft, root: String, branch: String, expectedCurrentBranch: String?, baseBranch: String) {
    target.branchSwitching = true
    viewModelScope.launch {
      try {
        when (val result = branches.switchToBranch(root, branch, expectedCurrentBranch)) {
          is Outcome.Failure -> showRepositoryError(target, readProblemText(result.error))
          is Outcome.Success -> {
            if (!isSelected(target) || !canChangeContext(target) || target.repository?.root != root) return@launch
            val repository = NewTaskRepository(result.value, checkNotNull(target.repository).name)
            if ((mutableState.value.repositories + target.addedRepositories).none { it.root == repository.root }) {
              target.addedRepositories += repository
            }
            target.rebindChat(requestedRepository = repository, requestedBranch = baseBranch)
            if (isSelected(target)) mutableState.update { it.copy(repositoryError = null) }
          }
        }
      } finally {
        target.branchSwitching = false
        readBranches(target)
      }
    }
  }

  private fun canChangeContext(target: NewTaskDraft): Boolean {
    if (target.resuming || target.contextTransitioning) return false
    if (target.chat.value?.thread?.deferDestructiveAction("change the draft context") == true) return false
    val current = target.chat.value
    return current?.repositoryMissing != true && current?.thread?.busy?.value != true && !target.offer.value.starting
  }

  private fun observeContextCommit(target: NewTaskDraft) {
    target.onRepositoryCandidate = { owner, current, path, sessionId, revision, request ->
      selectRepositoryCandidate(owner, current, path, sessionId, revision, request)
    }
    target.onContextCommitted = { committed ->
      if (isSelected(committed)) {
        val selectedRoot = committed.repository?.root
        val pending = pendingRepositorySelection.remove(committed)
        committed.repository?.takeIf { it.root == pending?.first }?.let { selected ->
          if ((mutableState.value.repositories + committed.addedRepositories).none { it.root == selected.root }) {
            committed.addedRepositories = committed.addedRepositories + selected
          }
        }
        val requestedRoot = pending?.first
        mutableState.update { state ->
          state.copy(agent = committed.selectedAgent, repositoryError = null,
            repositoryPicks = state.repositoryPicks + if (requestedRoot != null && requestedRoot == selectedRoot) 1 else 0)
        }
        val listed = pending?.second
        if (listed != null && listed.repositoryRoot == selectedRoot) {
          branchReadJob?.cancel()
          mutableState.update { it.copy(branchRead = BranchReadState.Loaded(listed.repositoryRoot, listed)) }
        } else {
          val branchRoot = when (val read = mutableState.value.branchRead) {
            is BranchReadState.Reading -> read.root
            is BranchReadState.Loaded -> read.root
            is BranchReadState.Failed -> read.root
            BranchReadState.Idle -> null
          }
          if (pending != null || branchRoot != selectedRoot) readBranches(committed)
        }
      }
    }
  }

  private suspend fun selectRepositoryCandidate(target: NewTaskDraft, current: NewTaskChat, path: String, sessionId: String, revision: Long, request: Long) {
    if (!target.ownsCandidateOrigin(current, sessionId, revision, request)) return
    if (!current.awaitRepositoryContextReady()) {
      if (target.ownsCandidateOrigin(current, sessionId, revision, request)) {
        showCandidateProblem(target, current, "A background reply could not be saved. The current repository remains selected.")
      }
      return
    }
    if (!target.ownsCandidateOrigin(current, sessionId, revision, request)) return
    if (current.thread.hasDeliveryClaim()) {
      showCandidateProblem(target, current, "Finish the current follow-up before changing repositories.")
      return
    }
    val validation = validateRepository(path) { target.ownsCandidateOrigin(current, sessionId, revision, request) }
    when (validation) {
      RepositoryValidation.Stale -> Unit
      is RepositoryValidation.Rejected -> showCandidateProblem(target, current, validation.message)
      is RepositoryValidation.Listed -> {
        if (!current.awaitRepositoryContextReady()) {
          if (target.ownsCandidateOrigin(current, sessionId, revision, request)) {
            showCandidateProblem(target, current, "A background reply could not be saved. The current repository remains selected.")
          }
          return
        }
        if (!target.ownsCandidateOrigin(current, sessionId, revision, request)) return
        if (current.thread.hasDeliveryClaim()) {
          showCandidateProblem(target, current, "Finish the current follow-up before changing repositories.")
          return
        }
        val repository = validation.repository
        if (target.repository?.root == repository.root) return
        pendingRepositorySelection[target] = repository.root to validation.branches
        if (!target.rebindChat(requestedRepository = repository, requestedBranch = "")) {
          pendingRepositorySelection.remove(target)
          showCandidateProblem(target, current, "The repository could not be selected while this draft was busy.")
          return
        }
        target.awaitContextTransition()
        if (target.repository?.root != repository.root) {
          showCandidateProblem(target, current, "The repository could not be saved. The previous selection remains active.")
        } else {
          val known = mutableState.value.repositories + target.addedRepositories
          if (known.none { it.root == repository.root }) target.addedRepositories += repository
        }
      }
    }
  }

  private fun showCandidateProblem(target: NewTaskDraft, current: NewTaskChat, message: String) {
    showRepositoryError(target, message)
    current.thread.append(ChatMessage.Notice("Repository selection was not applied: $message"))
    target.launch { current.persist() }
  }
}
