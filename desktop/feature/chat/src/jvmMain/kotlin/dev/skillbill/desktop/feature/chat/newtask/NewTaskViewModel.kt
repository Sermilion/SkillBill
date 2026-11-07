package dev.skillbill.desktop.feature.chat.newtask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.model.RepositoryBranches
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
import dev.skillbill.desktop.feature.chat.RESUME_COMMAND
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
  data object RetryBranches : NewTaskAction
  data class ChooseRepository(val repository: NewTaskRepository) : NewTaskAction
  data class SubmitRepositoryPath(val path: String) : NewTaskAction
  data class ChooseAgent(val agentId: String) : NewTaskAction
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
  private val mutableState = MutableStateFlow(NewTaskUiState(agents = chat.agents()))
  val state: StateFlow<NewTaskUiState> = mutableState.asStateFlow()

  /** The selected chat; every action acts on it. */
  val draft: NewTaskDraft get() = chats.selected.value

  private var pathCheck: Job? = null
  private val agentReads = mutableMapOf<NewTaskDraft, Job>()
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
    val first = draft
    readBranches(first)
    viewModelScope.launch { observeBoard(loadRepositories()) }
    readAgent(first) { resolveAgent(first, first.agentId ?: defaultAgentId()) }
    viewModelScope.launch { chats.selected.drop(1).collect(::showSelected) }
    viewModelScope.launch { chats.resumed.collect { if (isSelected(it)) showResumed(it) } }
  }

  fun now(): Instant = clock.now()

  fun onAction(action: NewTaskAction) {
    val target = draft
    when (action) {
      is NewTaskAction.TextChanged -> target.text = action.text
      is NewTaskAction.ChooseBaseBranch -> {
        if (!canChangeContext(target)) return
        pathCheck?.cancel()
        target.baseBranch = action.branch
        target.rebindChat()
        mutableState.update { it.copy(repositoryError = null, repositoryPickerOpen = false) }
      }
      NewTaskAction.RetryBranches -> target.repository?.root?.let { readBranches(target, it) }
      is NewTaskAction.ChooseRepository -> {
        selectRepository(target, action.repository)
      }
      is NewTaskAction.SubmitRepositoryPath -> submitPath(target, action.path)
      NewTaskAction.AddRepository -> addRepository(target)
      is NewTaskAction.ChooseAgent -> {
        target.agentId = action.agentId
        readAgent(target) { resolveAgent(target, action.agentId) }
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
        if (loadsIntoTarget && target.chat.value != null) agentReads.remove(target)?.cancel()
        mutableState.update { it.copy(resumePickerOpen = false) }
      }
      is NewTaskAction.RunSuggestedAction -> target.sendSuggestedAction(action.action)
    }
  }

  private fun isSelected(target: NewTaskDraft): Boolean = chats.selected.value === target

  /** Re-reads the composer for a saved chat loaded into the selected draft, which a selection change alone does not report. */
  private fun showResumed(target: NewTaskDraft) {
    agentReads.remove(target)?.cancel()
    mutableState.update { it.copy(agent = target.selectedAgent, resumePickerOpen = false) }
    readBranches(target)
  }

  private fun showSelected(selected: NewTaskDraft) {
    pathCheck?.cancel()
    mutableState.update {
      it.copy(agent = selected.selectedAgent, repositoryPickerOpen = false, resumePickerOpen = false, repositoryError = null)
    }
    readBranches(selected)
    val resolved = selected.selectedAgent != null && selected.selectedAgent?.agentId == selected.agentId
    if (!resolved && selected !in agentReads) readAgent(selected) { resolveAgent(selected, selected.agentId ?: defaultAgentId()) }
  }

  private fun send(target: NewTaskDraft) {
    if (!target.canSend()) return
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
      if (read !is BoardState.Loading && target.repository == null) {
        val row = (read as? BoardState.Loaded)?.items?.firstOrNull()
        target.repository = row?.let { NewTaskRepository(it.repositoryRoot, it.repositoryName) } ?: listed.firstOrNull()
        readBranches(target)
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
    val detected = (config.detectedAgentsRead() as? DetectedAgentsRead.Success)?.agents.orEmpty()
    return agents.firstOrNull { id -> detected.any { it.name.equals(id, ignoreCase = true) } } ?: agents.first()
  }

  /** Runs [read] as [target]'s only agent read, so a newer choice in one chat never cancels another chat's read. */
  private fun readAgent(target: NewTaskDraft, read: suspend () -> Unit) {
    agentReads.remove(target)?.cancel()
    val job = viewModelScope.launch { read() }
    agentReads[target] = job
    job.invokeOnCompletion { if (agentReads[target] === job) agentReads.remove(target) }
  }

  private suspend fun resolveAgent(target: NewTaskDraft, agentId: String?) {
    if (agentId == null) return
    target.agentId = agentId
    val agent = chat.agentFor(agentId)
    if (target.agentId != agentId) return
    target.selectedAgent = agent
    if (isSelected(target)) mutableState.update { it.copy(agent = agent) }
    target.rebindChat()
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

  private fun selectRepository(target: NewTaskDraft, repository: NewTaskRepository, listed: RepositoryBranches? = null) {
    if (!canChangeContext(target)) return
    pathCheck?.cancel()
    if (repository.root != target.repository?.root) target.baseBranch = ""
    target.repository = repository
    target.rebindChat()
    if (!isSelected(target)) return
    mutableState.update { it.copy(repositoryError = null, repositoryPicks = it.repositoryPicks + 1) }
    if (listed != null) {
      branchReadJob?.cancel()
      mutableState.update { it.copy(branchRead = BranchReadState.Loaded(repository.root, listed)) }
    } else if ((mutableState.value.branchRead as? BranchReadState.Loaded)?.root != repository.root) {
      readBranches(target)
    }
  }

  private fun addRepository(target: NewTaskDraft) {
    if (!canChangeContext(target) || mutableState.value.choosingFolder) return
    pathCheck?.cancel()
    mutableState.update { it.copy(choosingFolder = true) }
    // UNDISPATCHED enters the try at once, so a cancel before the first dispatch cannot leave the flag set.
    pathCheck = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val choice = try {
        folders.choose()
      } finally {
        mutableState.update { it.copy(choosingFolder = false) }
      }
      when (choice) {
        FolderChoice.Cancelled -> Unit
        is FolderChoice.Failed -> showRepositoryError(target, "Could not open the folder chooser: ${choice.message}")
        is FolderChoice.Chosen -> checkPath(target, choice.path)
      }
    }
  }

  private fun submitPath(target: NewTaskDraft, path: String) {
    if (!canChangeContext(target) || path.isBlank()) return
    pathCheck?.cancel()
    pathCheck = viewModelScope.launch { checkPath(target, path) }
  }

  private suspend fun checkPath(target: NewTaskDraft, path: String) {
    if (!canChangeContext(target)) return
    when (val read = branches.branches(path)) {
      is RepositoryBranchesRead.NotARepository -> showRepositoryError(target, "$path is not a git repository.")
      is RepositoryBranchesRead.Unavailable -> showRepositoryError(target, "Could not read $path: ${readProblemText(read.problem)}")
      is RepositoryBranchesRead.Listed -> addListedRepository(target, read.branches)
    }
  }

  private suspend fun addListedRepository(target: NewTaskDraft, listed: RepositoryBranches) {
    if (!canChangeContext(target)) return
    when (val project = specs.projectFor(listed.repositoryRoot)) {
      is Outcome.Failure -> showRepositoryError(target, readProblemText(project.error))
      is Outcome.Success -> {
        if (!canChangeContext(target)) return
        val repository = NewTaskRepository(project.value.repoRoot ?: listed.repositoryRoot, project.value.displayName)
        val known = mutableState.value.repositories + target.addedRepositories
        if (known.none { it.root == repository.root }) target.addedRepositories += repository
        selectRepository(target, repository, listed)
      }
    }
  }

  private fun showRepositoryError(target: NewTaskDraft, message: String) {
    if (isSelected(target)) mutableState.update { it.copy(repositoryError = message) }
  }

  private fun canChangeContext(target: NewTaskDraft): Boolean {
    val current = target.chat.value
    return current?.repositoryMissing != true && current?.thread?.busy?.value != true && !target.offer.value.starting
  }
}
