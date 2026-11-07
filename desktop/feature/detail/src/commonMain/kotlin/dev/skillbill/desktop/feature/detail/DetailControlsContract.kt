package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.RecoveryPreview
import dev.skillbill.desktop.core.domain.model.DecisionOutcome
import dev.skillbill.desktop.core.domain.model.OperatorDecision

/** The one control, preview or recovery call running; the others wait until its outcome returns. */
enum class InFlightControl { PauseAfterSubtask, PauseNow, Launch, Cancel, RecoveryPreview, Recover, RecordDecision }

/** The prerequisites the user confirmed for one incident; it applies only while the item, token and listed kinds still match. */
data class PrerequisiteConfirmation(
  val itemId: String,
  val incidentToken: String,
  val listedKinds: Set<String>,
  val confirmed: Set<IncidentPrerequisite>,
)

/** [reservations] is the app-wide launch reservations; [recoveryDialog] is the preview awaiting "Apply and resume". */
data class ControlsUiState(
  val inFlight: InFlightControl? = null,
  val chosenAgent: String? = null,
  val agentMenuOpen: Boolean = false,
  val prerequisites: PrerequisiteConfirmation? = null,
  val recoveryDialog: RecoveryPreview? = null,
  val reservations: Set<String> = emptySet(),
  val implementationStarted: Boolean = false,
  val decisionInstructions: String = "",
  val recordedDecision: DecisionOutcome.Recorded? = null,
)

sealed interface ControlsAction : DetailAction {
  data object ImplementPlan : ControlsAction
  data object PauseAfterSubtask : ControlsAction
  data object PauseNow : ControlsAction
  data object Launch : ControlsAction
  data object OpenAgentMenu : ControlsAction
  data object CloseAgentMenu : ControlsAction
  data class ChooseAgent(val agentId: String) : ControlsAction
  data object Cancel : ControlsAction
  data class ConfirmPrerequisite(val kind: String, val checked: Boolean) : ControlsAction
  data object Recover : ControlsAction
  data object ApplyRecovery : ControlsAction
  data object DismissRecovery : ControlsAction
  data class EditInstructions(val text: String) : ControlsAction
  data object RecordDecision : ControlsAction
}

data class DecisionDraft(val recorded: DecisionOutcome.Recorded? = null)

internal fun decisionDraft(state: DetailUiState): DecisionDraft = DecisionDraft(state.controls?.recordedDecision)

internal fun recordedText(recorded: DecisionOutcome.Recorded): String {
  val decision = recorded.decision.name.lowercase().replace('_', ' ')
  return "Decision recorded for subtask ${recorded.subtaskId}: $decision. Resume the goal to apply it."
}
