package dev.skillbill.tray.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.tray.core.domain.model.ControlConfirmation
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.model.eligibleForControl
import dev.skillbill.tray.core.domain.model.hasStartedOrFinished
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.ui.AppendState
import dev.skillbill.tray.core.ui.Feedback
import dev.skillbill.tray.core.ui.FeedbackIds
import dev.skillbill.tray.core.ui.FeedbackTone
import dev.skillbill.tray.core.ui.NavigationEffects
import dev.skillbill.tray.core.ui.acknowledged
import dev.skillbill.tray.core.ui.withFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pages kept in memory per detail list. */
private const val MAX_DETAIL_PAGES = 5
private const val READ_FAILURE_GRACE_MS = 500L

private val LAUNCH_REGISTRATION_TIMEOUT = 120.seconds

/** How long a launch waits for the board's current attempts before it starts without them. */
private val KNOWN_ATTEMPTS_TIMEOUT = 5.seconds

/** A finished item keeps its run controls for this long. */
private val CONTROL_WINDOW = 24.hours

/**
 * One work item's detail. Selecting another item creates another instance and clears this one, which cancels anything still
 * in flight: a control request, an append or a refresh can never land on the item that replaced this one.
 */
class DetailViewModel(
  private val identity: WorkIdentity,
  private val details: WorkDetailRepository,
  private val controls: WorkControlRepository,
  private val boards: WorkBoardRepository,
  private val clock: Clock,
) : ViewModel() {
  private val mutable = MutableStateFlow(DetailUiState(identity))
  private val feedbackIds = FeedbackIds()
  private val navigation = NavigationEffects<DetailEffect>()

  // The pages read for each list, oldest first. Only touched while holding [mutex].
  private val windows = mutableMapOf<DetailList, List<Detail>>()
  private val mutex = Mutex()
  private var appendJob: Job? = null
  private var controlJob: Job? = null
  private var readFailureJob: Job? = null

  val state: StateFlow<DetailUiState> = mutable.asStateFlow()
  val effects: Flow<DetailEffect> = navigation.flow

  init {
    viewModelScope.launch {
      details.observeDetail(identity).collect { observed -> mutex.withLock { applyObserved(observed) } }
    }
  }

  fun onAction(action: DetailAction) {
    when (action) {
      is DetailAction.LoadMore -> loadMore(action.list)
      DetailAction.Pause -> perform(ControlKind.PAUSE)
      DetailAction.Stop -> perform(ControlKind.STOP)
      DetailAction.Cancel -> perform(ControlKind.CANCEL)
      is DetailAction.Launch -> perform(ControlKind.LAUNCH, action.agentId)
      DetailAction.Refresh -> viewModelScope.launch { boards.refresh() }
      is DetailAction.AcknowledgeFeedback -> mutable.update { state ->
        val control = state.control
        state.copy(
          control = if (control is ControlState.Refused && control.feedbackId == action.id) ControlState.Idle else control,
          pendingFeedback = state.pendingFeedback.acknowledged(action.id),
        )
      }
    }
  }

  private fun loaded(): DetailLoad.Loaded? = mutable.value.load as? DetailLoad.Loaded

  private suspend fun applyObserved(observed: Outcome<Detail>, retryReadFailure: Boolean = true) {
    when (observed) {
      is Outcome.Failure -> readFailed(observed.problem, retryReadFailure)
      is Outcome.Value -> {
        val retained = windows.toMap()
        val rereads = DetailList.entries.any { list ->
          val kept = retained[list].orEmpty()
          kept.size > 1 || kept.firstOrNull()?.anchors?.get(list) != null
        }
        if (rereads) loaded()?.let { current -> mutable.update { it.copy(load = current.copy(refreshing = true)) } }
        when (val refreshed = reread(observed.value, retained)) {
          is Reread.Failed -> readFailed(refreshed.problem, retryReadFailure)
          is Reread.Refreshed -> {
            readFailureJob?.cancel()
            readFailureJob = null
            windows.clear()
            windows.putAll(refreshed.windows)
            val item = refreshed.detail.item
            val controllable = item.liveSection != null || item.finishedAt?.let { it >= clock.now() - CONTROL_WINDOW } == true
            // An append still waiting for this lock keeps its Loading entry; it re-reads the fresh detail when it runs.
            val pending = appendJob?.isActive == true
            mutable.update {
              it.copy(
                load = DetailLoad.Loaded(refreshed.detail),
                readHealth = DetailReadHealth.Healthy,
                appends = if (pending) it.appends.filterValues { append -> append == AppendState.Loading } else emptyMap(),
                controllable = controllable,
              )
            }
          }
        }
      }
    }
  }

  /**
   * Reads the pages kept for each list again. The first kept page is re-read from the identity it started after, so rows
   * inserted or removed ahead of it never shift the window; a head page has no anchor and is the observed page itself. A
   * list that has become shorter simply ends sooner.
   */
  private suspend fun reread(observed: Detail, retained: Map<DetailList, List<Detail>>): Reread {
    var combined = observed
    val refreshed = mutableMapOf<DetailList, List<Detail>>()
    for (list in DetailList.entries) {
      val kept = retained[list].orEmpty()
      val anchor = kept.firstOrNull()?.anchors?.get(list)
      var page: Outcome<Detail> = if (anchor == null) Outcome.Value(observed) else details.detail(identity, list = list, anchor = anchor)
      val replacement = mutableListOf<Detail>()
      val cursors = mutableSetOf<String>()
      while (replacement.size < kept.size.coerceAtLeast(1)) {
        when (val value = page) {
          is Outcome.Failure -> return Reread.Failed(value.problem)
          is Outcome.Value -> {
            if (!sameStoreRevision(observed, value.value)) return Reread.Failed(Problem(ProblemCode.STALE_TARGET, "Detail changed during refresh. Retry."))
            replacement += value.value
            val cursor = value.value.cursors[list]
            if (replacement.size < kept.size && cursor != null && cursors.add(cursor)) page = details.detail(identity, cursor, list) else break
          }
        }
      }
      refreshed[list] = replacement
      combined = replaceDetailList(combined, list, replacement)
    }
    return Reread.Refreshed(combined, refreshed)
  }

  private fun readFailed(problem: Problem, retry: Boolean) {
    val current = loaded()
    val health = mutable.value.readHealth
    if (retry && current != null && health is DetailReadHealth.GracePeriod) return
    if (retry && current != null && health == DetailReadHealth.Healthy) {
      mutable.update { it.copy(readHealth = DetailReadHealth.GracePeriod(problem)) }
      readFailureJob = viewModelScope.launch {
        delay(READ_FAILURE_GRACE_MS)
        mutex.withLock {
          readFailureJob = null
          applyObserved(details.detail(identity), retryReadFailure = false)
        }
      }
      return
    }
    mutable.update { state ->
      if (current == null) {
        state.copy(load = DetailLoad.Failed(problem))
      } else {
        state.copy(load = current.copy(refreshing = false), readHealth = DetailReadHealth.Failed(problem))
      }
    }
  }

  private fun loadMore(list: DetailList) {
    val current = loaded() ?: return
    if (current.detail.cursors[list] == null || appendJob?.isActive == true) return
    mutable.update { it.copy(appends = it.appends + (list to AppendState.Loading)) }
    appendJob = viewModelScope.launch {
      mutex.withLock {
        val previous = loaded()?.detail ?: return@withLock
        val cursor = previous.cursors[list] ?: return@withLock
        val page = details.detail(identity, cursor, list)
        val result: Outcome<Detail> = when (page) {
          is Outcome.Failure -> page
          is Outcome.Value -> if (sameStoreRevision(previous, page.value)) {
            val window = (windows[list].orEmpty() + page.value).takeLast(MAX_DETAIL_PAGES)
            windows[list] = window
            Outcome.Value(replaceDetailList(previous, list, window))
          } else {
            Outcome.Failure(Problem(ProblemCode.STALE_TARGET, "Detail changed during append. Refresh before retrying."))
          }
        }
        when (result) {
          is Outcome.Failure -> mutable.update { it.copy(appends = it.appends + (list to AppendState.Failed(result.problem))) }
          is Outcome.Value -> mutable.update { it.copy(load = DetailLoad.Loaded(result.value), appends = it.appends - list) }
        }
      }
    }
  }

  private fun perform(kind: ControlKind, agentId: String? = null) {
    val current = loaded() ?: return
    if (mutable.value.control is ControlState.InProgress) return
    if (mutable.value.readHealth.blocksControls) {
      refuse(kind, Problem(ProblemCode.STALE_TARGET, "Refresh the detail before using run controls."))
      return
    }
    val item = current.detail.item
    val options = current.detail.runOptions
    val eligible = when (kind) {
      ControlKind.CANCEL -> options?.canCancel == true
      ControlKind.LAUNCH -> options?.canLaunch == true
      ControlKind.PAUSE -> item.pauseEligible
      ControlKind.STOP -> item.eligibleForControl()
    }
    if (!eligible) {
      refuse(kind, Problem(ProblemCode.UNKNOWN_WORK_ITEM, "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} is not available for this item right now."))
      return
    }
    val generation = item.databaseGeneration
    val requestedAt = clock.now()
    mutable.update { it.copy(control = ControlState.InProgress(kind)) }
    controlJob = viewModelScope.launch {
      try {
        // Attempts that exist before the request, so the one it registers can be told apart; nothing arriving in time means none.
        val knownAttempts = if (kind == ControlKind.LAUNCH) {
          withTimeoutOrNull(KNOWN_ATTEMPTS_TIMEOUT) { launchCandidates().first() }.orEmpty().map { it.identity }.toSet()
        } else {
          emptySet()
        }
        val result: Outcome<*> = when (kind) {
          ControlKind.PAUSE -> controls.pause(item)
          ControlKind.STOP -> controls.stop(item)
          ControlKind.CANCEL -> controls.cancel(item)
          ControlKind.LAUNCH -> controls.launch(item, agentId)
        }
        // The store was replaced while the request ran: its answer belongs to a work item that no longer exists.
        if (loaded()?.detail?.item?.databaseGeneration != generation) return@launch
        when (result) {
          is Outcome.Failure -> refuse(kind, result.problem)
          is Outcome.Value<*> -> (result.value as? ControlConfirmation)?.let { addFeedback(FeedbackTone.CONFIRMATION, confirmationNotice(it)) }
        }
        boards.refresh()
        if (result is Outcome.Value<*> && kind == ControlKind.LAUNCH) followLaunch(item, knownAttempts, requestedAt)
      } finally {
        mutable.update { if (it.control is ControlState.InProgress) it.copy(control = ControlState.Idle) else it }
      }
    }
  }

  /** The live board's items, which is where a launched attempt registers; finished attempts are included so a fast one is not missed. */
  private fun launchCandidates(): Flow<List<WorkItem>> = combine(
    boardItems(WorkSection.NEEDS_YOU),
    boardItems(WorkSection.GOALS),
    boardItems(WorkSection.PHASES),
    boardItems(WorkSection.RECENT),
  ) { needsYou, goals, phases, recent ->
    ((needsYou + goals + phases).filter { it.liveSection != null } + recent).distinctBy { it.identity }
  }

  private fun boardItems(section: WorkSection): Flow<List<WorkItem>> =
    boards.observe(section, Retention.DAY).map { (it as? Outcome.Value)?.value?.items.orEmpty() }

  /** Records a control that did not run, or failed, in the control state and as the notice that explains it. */
  private fun refuse(kind: ControlKind, problem: Problem) {
    val feedback = Feedback(feedbackIds.next(), FeedbackTone.PROBLEM, problem.summary)
    mutable.update {
      it.copy(control = ControlState.Refused(kind, problem, feedback.id), pendingFeedback = it.pendingFeedback.withFeedback(feedback))
    }
  }

  private suspend fun followLaunch(item: WorkItem, knownAttempts: Set<WorkIdentity>, requestedAt: Instant) {
    val successor = withTimeoutOrNull(LAUNCH_REGISTRATION_TIMEOUT) {
      launchCandidates().map { items -> items.firstOrNull { candidate -> isSuccessor(candidate, item, knownAttempts, requestedAt) } }.filterNotNull().first()
    }
    if (successor != null) {
      navigation.send(DetailEffect.SelectSuccessor(item.identity, successor.identity))
    } else {
      addFeedback(FeedbackTone.PROBLEM, "The harness started but has not registered a new attempt yet. Check its output before trying again.")
    }
  }

  private fun addFeedback(tone: FeedbackTone, message: String) {
    val feedback = Feedback(feedbackIds.next(), tone, message)
    mutable.update { it.copy(pendingFeedback = it.pendingFeedback.withFeedback(feedback)) }
  }

  private sealed interface Reread {
    data class Refreshed(val detail: Detail, val windows: Map<DetailList, List<Detail>>) : Reread
    data class Failed(val problem: Problem) : Reread
  }
}

/** A launch's successor is a new attempt of the same standalone phase, or the same goal once it has started again. */
private fun isSuccessor(candidate: WorkItem, item: WorkItem, knownAttempts: Set<WorkIdentity>, requestedAt: Instant): Boolean {
  if (candidate.identity.repositoryIdentity != item.identity.repositoryIdentity || candidate.branch != item.branch ||
    candidate.phaseId != item.phaseId || candidate.issueKey != item.issueKey
  ) {
    return false
  }
  return if (item.scope == ExecutionScope.STANDALONE_PHASE) {
    candidate.identity !in knownAttempts && candidate.startedAt?.let { started ->
      started >= requestedAt && (item.startedAt?.let { started > it } ?: (candidate.updatedAt > item.updatedAt))
    } == true
  } else {
    candidate.identity == item.identity && candidate.updatedAt > item.updatedAt &&
      (candidate.executionId != item.executionId || candidate.hasStartedOrFinished())
  }
}

private fun sameStoreRevision(left: Detail, right: Detail): Boolean =
  left.item.databaseStoreId == right.item.databaseStoreId && left.item.databaseRevision == right.item.databaseRevision

/** Replaces one list of [base] with the rows of [window], which are consecutive pages of that list. */
private fun replaceDetailList(base: Detail, list: DetailList, window: List<Detail>): Detail {
  val cursors = base.cursors - list + window.lastOrNull()?.cursors.orEmpty().filterKeys { it == list }
  return when (list) {
    DetailList.SUBTASKS -> base.copy(subtasks = window.flatMap { it.subtasks }.distinctBy { it.id }, cursors = cursors)
    DetailList.PHASES -> base.copy(phases = window.flatMap { it.phases }.distinctBy { it.id }, cursors = cursors)
    DetailList.VALIDATION -> base.copy(validation = window.flatMap { it.validation }.distinctBy { it.subtaskId }, cursors = cursors)
    DetailList.DEPENDENCIES -> base.copy(dependencies = window.flatMap { it.dependencies }.distinctBy { it.subtaskId to it.dependency.subtaskId }, cursors = cursors)
    DetailList.EXECUTIONS -> base.copy(executionHistory = window.flatMap { it.executionHistory }.distinctBy { it.invocationId }, cursors = cursors)
  }
}

/** Feedback for a goal control that succeeded; each wording claims only what the runtime confirmed. */
internal fun confirmationNotice(confirmation: ControlConfirmation): String = when (confirmation) {
  ControlConfirmation.INTENT_RECORDED -> "Pause recorded. The goal pauses at its next checkpoint."
  ControlConfirmation.REQUEST_ACCEPTED -> "Request accepted. No running goal process needed stopping."
  ControlConfirmation.EXIT_CONFIRMED -> "Stop confirmed. The goal runner has exited."
  ControlConfirmation.TERMINATION_ATTEMPTED ->
    "Stop signal sent. The goal runner's exit was not confirmed; refresh and check it before retrying."
}
