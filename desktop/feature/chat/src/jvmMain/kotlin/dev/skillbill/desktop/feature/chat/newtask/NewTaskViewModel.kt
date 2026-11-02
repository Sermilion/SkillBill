package dev.skillbill.desktop.feature.chat.newtask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.domain.repository.SpecRepository
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.ui.board.ShellBoard
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.feature.chat.RESUME_COMMAND
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import kotlin.time.Clock
import kotlin.time.Instant

data class NewTaskUiState(
  val repositories: List<NewTaskRepository> = emptyList(),
  val repositoryError: String? = null,
  val repositoryPickerOpen: Boolean = false,
  val agents: List<String> = emptyList(),
  val agent: ChatAgent? = null,
  val home: NewTaskHome = NewTaskHome.Pending,
  val detectedAgents: DetectedAgentsRead? = null,
  val resumePickerOpen: Boolean = false,
)

sealed interface NewTaskAction {
  data class TextChanged(val text: String) : NewTaskAction
  data class BaseBranchChanged(val branch: String) : NewTaskAction
  data class ChooseRepository(val repository: NewTaskRepository) : NewTaskAction
  data class SubmitRepositoryPath(val path: String) : NewTaskAction
  data class ChooseAgent(val agentId: String) : NewTaskAction
  data object OpenRepositoryPicker : NewTaskAction
  data object CloseRepositoryPicker : NewTaskAction
  data object Send : NewTaskAction
  data object Discard : NewTaskAction
  data object NewChat : NewTaskAction
  data object DismissResume : NewTaskAction
  data class ResumeDraft(val entry: ChatHistoryEntry) : NewTaskAction
  data class RunSuggestedAction(val action: ChatSuggestedAction) : NewTaskAction
}

/** Reads the choices the composer offers and keeps the operator's values in [draft]. */
@Inject
class NewTaskViewModel(
  val draft: NewTaskDraft,
  private val board: WorkBoardRepository,
  private val specs: SpecRepository,
  private val chat: ExecutionChatRepository,
  private val config: RuntimeConfigRepository,
  private val clock: Clock,
) : ViewModel() {
  private val mutableState = MutableStateFlow(NewTaskUiState(agents = chat.agents()))
  val state: StateFlow<NewTaskUiState> = mutableState.asStateFlow()

  private var pathCheck: Job? = null
  private var agentRead: Job? = null
  private var readinessRequested = false

  init {
    viewModelScope.launch { observeBoard(loadRepositories()) }
    agentRead = viewModelScope.launch { resolveAgent(draft.agentId ?: defaultAgentId()) }
  }

  fun now(): Instant = clock.now()

  fun onAction(action: NewTaskAction) {
    when (action) {
      is NewTaskAction.TextChanged -> draft.text = action.text
      is NewTaskAction.BaseBranchChanged -> draft.baseBranch = action.branch
      is NewTaskAction.ChooseRepository -> {
        pathCheck?.cancel()
        draft.repository = action.repository
        draft.rebindChat()
        mutableState.update { it.copy(repositoryError = null, repositoryPickerOpen = false) }
      }
      is NewTaskAction.SubmitRepositoryPath -> submitPath(action.path)
      is NewTaskAction.ChooseAgent -> {
        agentRead?.cancel()
        draft.agentId = action.agentId
        agentRead = viewModelScope.launch { resolveAgent(action.agentId) }
      }
      NewTaskAction.OpenRepositoryPicker -> mutableState.update { it.copy(repositoryError = null, repositoryPickerOpen = true) }
      NewTaskAction.CloseRepositoryPicker -> {
        pathCheck?.cancel()
        draft.rebindChat()
        mutableState.update { it.copy(repositoryError = null, repositoryPickerOpen = false) }
      }
      NewTaskAction.Send -> send()
      NewTaskAction.Discard -> draft.discard()
      NewTaskAction.NewChat -> draft.launch { draft.close() }
      NewTaskAction.DismissResume -> mutableState.update { it.copy(resumePickerOpen = false) }
      is NewTaskAction.ResumeDraft -> {
        agentRead?.cancel()
        draft.launch {
          if (draft.resume(action.entry)) mutableState.update { it.copy(agent = action.entry.agent, resumePickerOpen = false) }
        }
      }
      is NewTaskAction.RunSuggestedAction -> draft.sendSuggestedAction(action.action)
    }
  }

  private fun send() {
    if (!draft.canSend()) return
    if (draft.text.trim() != RESUME_COMMAND) return draft.send()
    draft.text = ""
    draft.refreshSaved()
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
      if (read !is BoardState.Loading && draft.repository == null) {
        val row = (read as? BoardState.Loaded)?.items?.firstOrNull()
        draft.repository = row?.let { NewTaskRepository(it.repositoryRoot, it.repositoryName) } ?: listed.firstOrNull()
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

  private suspend fun resolveAgent(agentId: String?) {
    if (agentId == null) return
    draft.agentId = agentId
    val agent = chat.agentFor(agentId)
    if (draft.agentId != agentId) return
    draft.selectedAgent = agent
    mutableState.update { it.copy(agent = agent) }
    draft.rebindChat()
  }

  private fun submitPath(path: String) {
    if (path.isBlank()) return
    pathCheck?.cancel()
    pathCheck = viewModelScope.launch {
      when (val project = specs.projectFor(path)) {
        is Outcome.Success -> {
          draft.repository = NewTaskRepository(project.value.repoRoot ?: path, project.value.displayName)
          draft.rebindChat()
          mutableState.update { it.copy(repositoryError = null, repositoryPickerOpen = false) }
        }
        is Outcome.Failure -> mutableState.update { it.copy(repositoryError = readProblemText(project.error)) }
      }
    }
  }
}
