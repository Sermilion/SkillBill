package dev.skillbill.tray.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.skillbill.tray.core.domain.model.ControlConfirmation
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.IncidentRecoveryPreviewResult
import dev.skillbill.tray.core.domain.model.IncidentRecoveryPreview
import dev.skillbill.tray.core.domain.model.IncidentProgress
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
  private enum class OrdinaryControl(val kind: ControlKind) {
    PAUSE(ControlKind.PAUSE),
    STOP(ControlKind.STOP),
    CANCEL(ControlKind.CANCEL),
    LAUNCH(ControlKind.LAUNCH),
  }

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
      DetailAction.Pause -> perform(OrdinaryControl.PAUSE)
      DetailAction.Stop -> perform(OrdinaryControl.STOP)
      DetailAction.Cancel -> perform(OrdinaryControl.CANCEL)
      is DetailAction.Launch -> perform(OrdinaryControl.LAUNCH, action.agentId)
      DetailAction.Recover -> loadRecoveryPreview()
      is DetailAction.ConfirmPrerequisite -> confirmPrerequisite(action)
      DetailAction.ConfirmRecovery -> confirmRecovery()
      DetailAction.CancelRecovery -> cancelRecovery()
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
              reconcileRecovery(it, refreshed.detail).copy(
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
      if (current == null) state.copy(load = DetailLoad.Failed(problem))
      else state.copy(
        load = current.copy(refreshing = false),
        readHealth = DetailReadHealth.Failed(problem),
        recovery = state.recovery.takeIf { it is RecoveryState.Applying } ?: RecoveryState.Idle,
        prerequisiteIdentity = null,
        confirmedPrerequisites = emptySet(),
      )
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
          is Outcome.Value -> mutable.update {
            reconcileRecovery(it, result.value).copy(load = DetailLoad.Loaded(result.value), appends = it.appends - list)
          }
        }
      }
    }
  }

  private fun perform(control: OrdinaryControl, agentId: String? = null) {
    val kind = control.kind
    val current = loaded() ?: return
    if (mutable.value.control is ControlState.InProgress || mutable.value.recovery !is RecoveryState.Idle || controlJob?.isActive == true) return
    if (mutable.value.readHealth.blocksControls) {
      refuse(kind, Problem(ProblemCode.STALE_TARGET, "Refresh the detail before using run controls."))
      return
    }
    val item = current.detail.item
    val options = current.detail.runOptions
    val eligible = when (control) {
      OrdinaryControl.CANCEL -> options?.canCancel == true
      OrdinaryControl.LAUNCH -> options?.canLaunch == true
      OrdinaryControl.PAUSE -> item.pauseEligible
      OrdinaryControl.STOP -> item.eligibleForControl()
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
        val knownAttempts = if (control == OrdinaryControl.LAUNCH) {
          withTimeoutOrNull(KNOWN_ATTEMPTS_TIMEOUT) { launchCandidates().first() }.orEmpty().map { it.identity }.toSet()
        } else {
          emptySet()
        }
        val result: Outcome<*> = when (control) {
          OrdinaryControl.PAUSE -> controls.pause(item)
          OrdinaryControl.STOP -> controls.stop(item)
          OrdinaryControl.CANCEL -> controls.cancel(item)
          OrdinaryControl.LAUNCH -> controls.launch(item, agentId)
        }
        // The store was replaced while the request ran: its answer belongs to a work item that no longer exists.
        if (loaded()?.detail?.item?.databaseGeneration != generation) return@launch
        when (result) {
          is Outcome.Failure -> refuse(kind, result.problem)
          is Outcome.Value<*> -> (result.value as? ControlConfirmation)?.let { addFeedback(FeedbackTone.CONFIRMATION, confirmationNotice(it)) }
        }
        boards.refresh()
        if (result is Outcome.Value<*> && control == OrdinaryControl.LAUNCH) followLaunch(item, knownAttempts, requestedAt)
      } finally {
        mutable.update { if (it.control is ControlState.InProgress) it.copy(control = ControlState.Idle) else it }
      }
    }
  }

  private fun confirmPrerequisite(action: DetailAction.ConfirmPrerequisite) {
    val current = loaded() ?: return
    val detail = current.detail
    val incident = detail.incident ?: return
    if (mutable.value.readHealth.blocksControls || mutable.value.recovery !is RecoveryState.Idle || controlJob?.isActive == true ||
      incident.progress != IncidentProgress.NEEDS_PREREQUISITES || incident.prerequisites.none { it.kind == action.kind }
    ) return
    val identity = recoveryIdentity(detail)
    mutable.update { state ->
      val checked = if (state.prerequisiteIdentity == identity) state.confirmedPrerequisites else emptySet()
      state.copy(prerequisiteIdentity = identity,
        confirmedPrerequisites = if (action.checked) checked + action.kind else checked - action.kind)
    }
  }

  private fun cancelRecovery() {
    mutable.update { state ->
      if (state.recovery !is RecoveryState.Confirming) state else state.copy(recovery = RecoveryState.Idle)
    }
  }

  private fun loadRecoveryPreview() {
    val current = loaded() ?: return
    val detail = current.detail
    val incident = detail.incident ?: return
    val state = mutable.value
    val item = detail.item
    if (state.readHealth.blocksControls || state.control is ControlState.InProgress || state.recovery !is RecoveryState.Idle ||
      controlJob?.isActive == true || incident.progress !in setOf(IncidentProgress.RECOVERY_AVAILABLE, IncidentProgress.NEEDS_PREREQUISITES)
    ) return
    val identity = recoveryIdentity(detail)
    val checked = state.confirmedPrerequisites.takeIf { state.prerequisiteIdentity == identity }.orEmpty()
    val required = incident.prerequisites.map { it.kind }.toSet()
    if (incident.progress == IncidentProgress.NEEDS_PREREQUISITES && !checked.containsAll(required)) return
    val request = RecoveryRequest(item, identity, checked.toSet())
    val loading = RecoveryState.Loading(request)
    mutable.update { it.copy(recovery = loading, prerequisiteIdentity = identity, confirmedPrerequisites = checked) }
    controlJob = viewModelScope.launch {
      try {
        val result = controls.recoveryPreview(request.item, identity.incidentToken, request.confirmedPrerequisites)
        if (!ownsPreview(loading)) return@launch
        val refresh = when (result) {
          is Outcome.Failure -> { refuse(ControlKind.RECOVER, result.problem); true }
          is Outcome.Value -> when (val previewResult = result.value) {
            is IncidentRecoveryPreviewResult.Available -> {
              if (previewMatches(previewResult.preview, request)) {
                mutable.update { it.copy(recovery = RecoveryState.Confirming(RecoverySnapshot(request, previewResult.preview))) }
                controlJob = null
                false
              } else { refuse(ControlKind.RECOVER, stalePreviewProblem()); true }
            }
            is IncidentRecoveryPreviewResult.NeedsPrerequisites -> {
              val planned = previewResult.plannedPreview
              val instructions = previewResult.instructions.joinToString(" ") { "${it.kind}: ${it.instruction}" }
              val message = when {
                planned == null -> "Recovery needs prerequisites, but the runtime did not provide a preview: $instructions Refresh the incident details."
                !previewMatches(planned, request) -> "Recovery preview belongs to a different incident revision. Remaining prerequisites: $instructions Refresh the incident details."
                else -> "Recovery still needs prerequisites: $instructions Refresh the incident details."
              }
              refuse(ControlKind.RECOVER, Problem(ProblemCode.STALE_TARGET, message)); true
            }
            is IncidentRecoveryPreviewResult.ExistingAttempt -> {
              val failure = previewResult.failure?.let { " It ended with $it." }.orEmpty()
              refuse(ControlKind.RECOVER, Problem(ProblemCode.STALE_TARGET,
                "Recovery attempt ${previewResult.attemptId} is ${previewResult.status}.$failure Refresh the incident details.")); true
            }
            is IncidentRecoveryPreviewResult.Rejected -> {
              refuse(ControlKind.RECOVER, Problem(ProblemCode.STALE_TARGET,
                "Recovery preview was refused: ${previewResult.reason}${previewResult.detail?.let { ": $it" }.orEmpty()}")); true
            }
            is IncidentRecoveryPreviewResult.Unrecognized -> {
              refuse(ControlKind.RECOVER, Problem(ProblemCode.STALE_TARGET,
                "Recovery preview returned an unrecognized result. Refresh the incident details.")); true
            }
          }
        }
        if (refresh) boards.refresh()
      } finally {
        mutable.update { if (it.recovery === loading) it.copy(recovery = RecoveryState.Idle) else it }
      }
    }
  }

  private fun confirmRecovery() {
    val confirmation = mutable.value.recovery as? RecoveryState.Confirming ?: return
    val snapshot = confirmation.snapshot
    val current = loaded()
    val detail = current?.detail
    val incident = detail?.incident
    val valid = current != null && mutable.value.readHealth == DetailReadHealth.Healthy && detail != null && incident != null &&
      snapshot.request.identity.matches(detail.item, incident) && previewMatches(snapshot.preview, snapshot.request) &&
      snapshot.request.confirmedPrerequisites == mutable.value.confirmedPrerequisites &&
      mutable.value.prerequisiteIdentity == snapshot.request.identity &&
      incident.progress in setOf(IncidentProgress.RECOVERY_AVAILABLE, IncidentProgress.NEEDS_PREREQUISITES) &&
      (incident.progress != IncidentProgress.NEEDS_PREREQUISITES ||
        snapshot.request.confirmedPrerequisites.containsAll(incident.prerequisites.map { it.kind }))
    if (!valid || mutable.value.control is ControlState.InProgress || controlJob?.isActive == true) {
      mutable.update { it.copy(recovery = RecoveryState.Idle) }
      refuse(ControlKind.RECOVER, stalePreviewProblem())
      return
    }
    val applying = RecoveryState.Applying(snapshot)
    mutable.update { it.copy(recovery = applying, control = ControlState.InProgress(ControlKind.RECOVER)) }
    controlJob = viewModelScope.launch {
      try {
        val result = controls.recover(snapshot.request.item, snapshot.preview.incidentToken,
          snapshot.preview.incidentRevision, snapshot.request.confirmedPrerequisites)
        val ownerStillSelected = loaded()?.detail?.let { value ->
          value.incident?.let { snapshot.request.identity.matches(value.item, it) } == true
        } == true
        if (mutable.value.recovery === applying && ownerStillSelected) when (result) {
          is Outcome.Failure -> refuse(ControlKind.RECOVER, result.problem)
          is Outcome.Value -> addFeedback(FeedbackTone.CONFIRMATION, "Recovery request finished. Incident details were refreshed.")
        }
        boards.refresh()
      } finally {
        mutable.update { state ->
          val ownsRequest = state.recovery === applying
          state.copy(
            recovery = if (ownsRequest) RecoveryState.Idle else state.recovery,
            prerequisiteIdentity = null,
            confirmedPrerequisites = emptySet(),
            control = if (ownsRequest && (state.control as? ControlState.InProgress)?.kind == ControlKind.RECOVER) {
              ControlState.Idle
            } else state.control,
          )
        }
      }
    }
  }

  private fun ownsPreview(loading: RecoveryState.Loading): Boolean {
    if (mutable.value.recovery !== loading) return false
    val current = loaded() ?: return false
    val incident = current.detail.incident ?: return false
    return mutable.value.readHealth == DetailReadHealth.Healthy && loading.request.identity.matches(current.detail.item, incident) &&
      mutable.value.prerequisiteIdentity == loading.request.identity &&
      mutable.value.confirmedPrerequisites == loading.request.confirmedPrerequisites
  }

  private fun recoveryIdentity(detail: Detail): RecoveryIdentity {
    val incident = requireNotNull(detail.incident)
    val item = detail.item
    return RecoveryIdentity(item.identity, item.workflowId, item.invocationId, item.databaseGeneration, item.databaseFile, item.databaseStoreId,
      incident.incidentToken, incident.revision, incident.prerequisites.toList())
  }

  private fun previewMatches(preview: IncidentRecoveryPreview, request: RecoveryRequest): Boolean =
    preview.incidentToken == request.identity.incidentToken && preview.incidentRevision == request.identity.incidentRevision

  private fun stalePreviewProblem() = Problem(ProblemCode.STALE_TARGET,
    "The incident changed. Refresh it and review a new recovery preview.")

  private fun reconcileRecovery(state: DetailUiState, detail: Detail): DetailUiState {
    val incident = detail.incident
    val identity = incident?.let { recoveryIdentity(detail) }
    val actionable = incident != null && incident.progress in setOf(IncidentProgress.RECOVERY_AVAILABLE, IncidentProgress.NEEDS_PREREQUISITES)
    val keepChecks = actionable && identity != null && state.prerequisiteIdentity == identity
    val recovery = when (val current = state.recovery) {
      is RecoveryState.Applying -> current
      is RecoveryState.Confirming -> current.takeIf { actionable && identity == it.snapshot.request.identity } ?: RecoveryState.Idle
      is RecoveryState.Loading -> current.takeIf { actionable && identity == it.request.identity } ?: RecoveryState.Idle
      RecoveryState.Idle -> RecoveryState.Idle
    }
    return state.copy(recovery = recovery,
      prerequisiteIdentity = if (keepChecks) state.prerequisiteIdentity else null,
      confirmedPrerequisites = if (keepChecks) state.confirmedPrerequisites else emptySet())
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
