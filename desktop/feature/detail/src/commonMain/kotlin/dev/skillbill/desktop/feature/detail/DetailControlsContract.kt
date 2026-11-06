package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.RecoveryPreview

/** The one control, preview or recovery call running; the others wait until its outcome returns. */
enum class InFlightControl { PauseAfterSubtask, PauseNow, Launch, Cancel, RecoveryPreview, Recover }

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
}
