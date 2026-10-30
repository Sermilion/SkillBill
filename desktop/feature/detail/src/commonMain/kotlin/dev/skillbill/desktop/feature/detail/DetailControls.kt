package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DecisionOutcome
import dev.skillbill.desktop.core.domain.model.OperatorDecision
import dev.skillbill.desktop.core.domain.model.RecoveryOutcome
import dev.skillbill.desktop.core.domain.model.RecoveryPreviewOutcome
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.ui.feedback.ControlAction
import dev.skillbill.desktop.core.ui.feedback.FeedbackSubject
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.feedback.controlFeedback
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STALE_PREVIEW = "The incident changed. Refresh it and review a new recovery preview."
private const val RECOVERY_FINISHED = "Recovery request finished. Incident details were refreshed."
private const val DECISION_RECORDED_TITLE = "Decision recorded"
private const val DECISION_RECORDED_BODY = "Resume the goal to apply it."
private const val INSTRUCTIONS_REQUIRED = "Instructions are required for Retry fix."
private const val NO_DECISION_WAITING = "No current subtask is waiting for a decision. Refresh the detail."

/**
 * The detail's run controls, recovery and operator decision. One call runs at a time and none changes a row; the new state arrives
 * through the commit signal and the detail refresh. Each call and its toast run in [NonCancellable], so leaving the detail mid-write
 * neither abandons the repository's reservation settle nor drops the toast, which goes to the window's [toasts].
 */
internal class DetailControls(
  private val scope: CoroutineScope,
  private val controls: WorkControlRepository,
  private val toasts: ToastQueue,
  private val onLaunchStarted: (WorkItem) -> Unit,
  private val onLaunchFinished: (ControlOutcome) -> Unit,
  private val current: () -> DetailUiState,
) {
  private val mutableState = MutableStateFlow(ControlsUiState())
  val state: StateFlow<ControlsUiState> = mutableState.asStateFlow()

  init {
    controls.reservations.onEach { reserved -> mutableState.update { it.copy(reservations = reserved) } }.launchIn(scope)
  }

  /** Runs [action]; returns false when its control is absent or disabled, so a key goes unhandled. */
  fun onAction(action: ControlsAction): Boolean {
    val ui = current().copy(controls = mutableState.value)
    val view = runActionsView(ui)
    val item = ui.header
    return when (action) {
      ControlsAction.PauseAfterSubtask -> runControl(ui, view, view.pauseAfter, InFlightControl.PauseAfterSubtask, ControlAction.PauseAfterSubtask) {
        controls.pauseAfterSubtask(item)
      }
      ControlsAction.PauseNow -> runControl(ui, view, view.pauseNow, InFlightControl.PauseNow, ControlAction.PauseNow) { controls.pauseNow(item) }
      ControlsAction.Cancel -> runControl(ui, view, view.cancel, InFlightControl.Cancel, ControlAction.Cancel) { controls.cancel(item) }
      ControlsAction.Launch -> {
        val agentId = view.agentId
        if (agentId == null && view.launch?.enabled == true) {
          mutableState.update { it.copy(agentMenuOpen = true) }
          true
        } else {
          runControl(ui, view, view.launch, InFlightControl.Launch, ControlAction.Launch, agentId) {
            onLaunchStarted(item)
            controls.launch(item, agentId).also(onLaunchFinished)
          }
        }
      }
      ControlsAction.OpenAgentMenu -> update { it.copy(agentMenuOpen = view.agent?.enabled == true) }
      ControlsAction.CloseAgentMenu -> update { it.copy(agentMenuOpen = false) }
      is ControlsAction.ChooseAgent -> update { it.copy(chosenAgent = action.agentId, agentMenuOpen = false) }
      is ControlsAction.ConfirmPrerequisite -> confirmPrerequisite(ui, view, action)
      ControlsAction.Recover -> preview(ui, view)
      ControlsAction.ApplyRecovery -> applyRecovery(ui, view)
      ControlsAction.DismissRecovery ->
        update { if (it.inFlight == InFlightControl.Recover) it else it.copy(recoveryDialog = null) }
      is ControlsAction.ChooseDecision ->
        editDecision(ui, view) { it.copy(decision = action.decision, fieldError = null, inlineProblem = null) }
      is ControlsAction.EditInstructions ->
        editDecision(ui, view) { it.copy(instructions = action.text, fieldError = null, inlineProblem = null) }
      ControlsAction.RecordDecision -> recordDecision(ui, view)
    }
  }

  private fun update(change: (ControlsUiState) -> ControlsUiState): Boolean {
    mutableState.update(change)
    return true
  }

  /** Under rule D-A a control that would be usable after a refresh posts the refresh copy instead of acting. */
  private fun blocked(view: RunActionsView, button: ControlButton?): Boolean? = when {
    button == null -> false
    view.refreshNeeded -> { toasts.show(ToastMessage(ToastTone.Problem, REFRESH_BEFORE_CONTROLS)); true }
    !button.enabled -> false
    else -> null
  }

  private fun runControl(
    ui: DetailUiState,
    view: RunActionsView,
    button: ControlButton?,
    control: InFlightControl,
    action: ControlAction,
    agentId: String? = null,
    request: suspend () -> ControlOutcome,
  ): Boolean {
    blocked(view, button)?.let { return it }
    val item = ui.header
    val subject = FeedbackSubject(item.issueKey ?: item.id, (ui.sections as? SectionsUi.Loaded)?.runOptions?.launchLabel, agentId)
    return call(control) { toasts.show(controlFeedback(action, request(), subject)) }
  }

  private fun call(control: InFlightControl, block: suspend () -> Unit): Boolean {
    mutableState.update { it.copy(inFlight = control) }
    scope.launch {
      try {
        withContext(NonCancellable) { block() }
      } finally {
        mutableState.update { it.copy(inFlight = null) }
      }
    }
    return true
  }

  private fun confirmPrerequisite(ui: DetailUiState, view: RunActionsView, action: ControlsAction.ConfirmPrerequisite): Boolean {
    val incident = (ui.sections as? SectionsUi.Loaded)?.detail?.incident ?: return false
    val listed = incident.prerequisites.map { it.kind }.toSet()
    val prerequisite = prerequisiteOf(action.kind)
    if (action.kind !in listed || prerequisite == null || !view.checklistEnabled) return false
    val confirmed = if (action.checked) view.confirmed + prerequisite else view.confirmed - prerequisite
    return update { it.copy(prerequisites = PrerequisiteConfirmation(ui.header.id, incident.incidentToken, listed, confirmed)) }
  }

  private fun preview(ui: DetailUiState, view: RunActionsView): Boolean {
    blocked(view, view.recover)?.let { return it }
    val item = ui.header
    return call(InFlightControl.RecoveryPreview) {
      when (val outcome = controls.recoveryPreview(item, view.confirmed)) {
        is RecoveryPreviewOutcome.Ready -> mutableState.update { it.copy(recoveryDialog = outcome.preview) }
        is RecoveryPreviewOutcome.NeedsPrerequisites -> problem(
          "Recovery still needs prerequisites: ${outcome.instructions.joinToString(" ") { "${it.prerequisite.name.lowercase()}: ${it.instruction}" }} " +
            "Refresh the incident details.",
        )
        is RecoveryPreviewOutcome.ExistingAttempt -> problem("Recovery attempt ${outcome.attemptId} is ${outcome.status}. Refresh the incident details.")
        RecoveryPreviewOutcome.Unavailable -> problem("Recovery is no longer available. Refresh the incident details.")
        is RecoveryPreviewOutcome.Refused -> problem("Recovery preview was refused: ${outcome.summary}")
        is RecoveryPreviewOutcome.Failed -> problem(readProblemText(outcome.problem))
      }
    }
  }

  /** Applies the open preview; a preview whose incident token or revision no longer matches the loaded incident never reaches the runtime. */
  private fun applyRecovery(ui: DetailUiState, view: RunActionsView): Boolean {
    val preview = mutableState.value.recoveryDialog ?: return false
    if (mutableState.value.inFlight != null) return false
    if (!view.usable) {
      problem(REFRESH_BEFORE_CONTROLS)
      return true
    }
    val incident = (ui.sections as? SectionsUi.Loaded)?.detail?.incident
    if (incident?.incidentToken != preview.incidentToken || incident.revision != preview.incidentRevision) {
      mutableState.update { it.copy(recoveryDialog = null) }
      problem(STALE_PREVIEW)
      return true
    }
    val item = ui.header
    return call(InFlightControl.Recover) {
      val outcome = controls.recover(item, preview)
      mutableState.update { it.copy(recoveryDialog = null) }
      when (outcome) {
        is RecoveryOutcome.Applied -> toasts.show(ToastMessage(ToastTone.Confirmation, RECOVERY_FINISHED))
        RecoveryOutcome.PreviewStale -> problem(STALE_PREVIEW)
        is RecoveryOutcome.AttemptFailed -> problem("Recovery attempt ${outcome.attemptId} failed: ${outcome.summary}")
        is RecoveryOutcome.Refused -> problem(outcome.summary)
        is RecoveryOutcome.Failed -> problem(readProblemText(outcome.problem))
      }
    }
  }

  private fun editDecision(ui: DetailUiState, view: RunActionsView, change: (DecisionDraft) -> DecisionDraft): Boolean {
    val draft = view.decision ?: return false
    if (draft.recorded != null || !view.usable || mutableState.value.inFlight != null) return false
    return update { it.copy(decision = change(decisionDraft(ui.copy(controls = it)))) }
  }

  /** Records the decision for the goal's next resume; the app never resumes it. A blank Retry fix never reaches the runtime. */
  private fun recordDecision(ui: DetailUiState, view: RunActionsView): Boolean {
    val draft = view.decision ?: return false
    if (draft.decision == OperatorDecision.RETRY_FIX && draft.instructions.isBlank()) return false
    if (draft.recorded != null || mutableState.value.inFlight != null) return false
    if (!view.usable) {
      if (view.refreshNeeded) problem(REFRESH_BEFORE_CONTROLS)
      return view.refreshNeeded
    }
    val item = ui.header
    val instructions = draft.instructions.takeIf { draft.decision == OperatorDecision.RETRY_FIX }
    return call(InFlightControl.Decision) {
      val settled = when (val outcome = controls.recordOperatorDecision(item, draft.decision, instructions)) {
        is DecisionOutcome.Recorded -> {
          toasts.show(ToastData(DECISION_RECORDED_TITLE, DECISION_RECORDED_BODY))
          draft.copy(recorded = outcome, fieldError = null, inlineProblem = null)
        }
        DecisionOutcome.InstructionsRequired -> draft.copy(fieldError = INSTRUCTIONS_REQUIRED)
        DecisionOutcome.Unavailable -> draft.copy(inlineProblem = NO_DECISION_WAITING)
        is DecisionOutcome.Refused -> draft.copy(inlineProblem = outcome.reason)
        is DecisionOutcome.Failed -> draft.copy(inlineProblem = readProblemText(outcome.problem))
      }
      mutableState.update { it.copy(decision = settled) }
    }
  }

  private fun problem(text: String) = toasts.show(ToastMessage(ToastTone.Problem, text))
}
