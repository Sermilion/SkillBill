package dev.skillbill.desktop.core.domain.model

/** The runtime's control refusals, one to one by name. */
enum class ControlRejection {
  UNKNOWN_TARGET,
  CHANGED_TARGET,
  INELIGIBLE_TARGET,
  NOT_FOUND,
  OWNERSHIP_MISMATCH,
  FOREIGN_HOST,
  PID_REUSED,
  UNINSPECTABLE,
  IDENTITY_MISMATCH,
  TERMINATION_INCOMPLETE,
  AGENT_REQUIRED,
  BRANCH_CHANGED,
  BRANCH_UNAVAILABLE,
  STALE_INCIDENT,
  RECOVERY_UNAVAILABLE,
  PREREQUISITE_UNMET,
  RECOVERY_IN_PROGRESS,
}

/** What a run control achieved. Each member claims only what the runtime reported; rows change when the next read shows it. */
sealed interface ControlOutcome {
  /** A pause is recorded; the runner honours it at its next checkpoint and nothing has stopped yet. */
  data object IntentRecorded : ControlOutcome

  /** The runtime admitted the request; no claim about the process lifecycle. */
  data object RequestAccepted : ControlOutcome

  /** The stop was recorded and the runner's exit was confirmed. */
  data object ExitConfirmed : ControlOutcome

  /** The stop was recorded and a termination signal sent, but the exit was not confirmed; the runner may still run. */
  data object TerminationAttempted : ControlOutcome

  /** The stop was accepted and no running goal process needed stopping. */
  data object NoProcessNeeded : ControlOutcome

  /** A launch for the same work is already in flight or waiting for the board; no request was sent. */
  data object AlreadyLaunching : ControlOutcome

  /** The runtime refused; nothing changed. */
  data class Refused(val rejection: ControlRejection, val detail: String?) : ControlOutcome

  data class Failed(val problem: ReadProblem) : ControlOutcome
}

enum class IncidentPrerequisite { CREDENTIALS_CONFIGURED, CONFIGURATION_FIXED, PROJECT_EDIT_COMPLETED, EXTERNAL_SERVICE_AVAILABLE }

enum class RecoveryAttemptStatus { REQUESTED, APPLYING, APPLIED, RESUMING, RESUMED, FAILED }

data class PrerequisiteInstruction(val prerequisite: IncidentPrerequisite, val instruction: String)

/** The runtime's recovery preview for one incident revision, with the prerequisites the user confirmed for it. */
data class RecoveryPreview(
  val action: String,
  val label: String,
  val effects: String?,
  val targetWorkflowId: String?,
  val phaseId: String?,
  val incidentToken: String,
  val incidentRevision: Long,
  val confirmedPrerequisites: Set<IncidentPrerequisite>,
)

sealed interface RecoveryPreviewOutcome {
  data class Ready(val preview: RecoveryPreview) : RecoveryPreviewOutcome

  data class NeedsPrerequisites(val instructions: List<PrerequisiteInstruction>, val planned: RecoveryPreview?) :
    RecoveryPreviewOutcome

  data class ExistingAttempt(val attemptId: String, val status: RecoveryAttemptStatus) : RecoveryPreviewOutcome

  /** The item has no open incident. */
  data object Unavailable : RecoveryPreviewOutcome

  data class Refused(val summary: String) : RecoveryPreviewOutcome

  data class Failed(val problem: ReadProblem) : RecoveryPreviewOutcome
}

sealed interface RecoveryOutcome {
  /** [launch] is the resumed run's launch outcome, null when the attempt settled before it resumed. */
  data class Applied(val attemptId: String, val status: RecoveryAttemptStatus, val launch: ControlOutcome?) : RecoveryOutcome

  data class AttemptFailed(val attemptId: String, val summary: String) : RecoveryOutcome

  /** The incident changed after the preview; request a new one. */
  data object PreviewStale : RecoveryOutcome

  data class Refused(val rejection: ControlRejection, val summary: String) : RecoveryOutcome

  data class Failed(val problem: ReadProblem) : RecoveryOutcome
}

enum class OperatorDecision { RETRY_FIX, ACCEPT_AND_ADVANCE, ABANDON_SUBTASK }

sealed interface DecisionOutcome {
  data class Recorded(val subtaskId: Int, val decision: OperatorDecision) : DecisionOutcome

  /** A retry needs instructions; nothing was sent. */
  data object InstructionsRequired : DecisionOutcome

  /** The item has no goal issue key or current subtask to decide on; nothing was sent. */
  data object Unavailable : DecisionOutcome

  data class Refused(val reason: String) : DecisionOutcome

  data class Failed(val problem: ReadProblem) : DecisionOutcome
}

sealed interface PurgeOutcome {
  data class Purged(val removedRows: Int, val removedPaths: Int, val specActions: Int) : PurgeOutcome

  data object NothingToRemove : PurgeOutcome

  data class Incomplete(val leftovers: List<String>) : PurgeOutcome

  data class Refused(val reason: String) : PurgeOutcome

  data class Failed(val problem: ReadProblem) : PurgeOutcome
}
