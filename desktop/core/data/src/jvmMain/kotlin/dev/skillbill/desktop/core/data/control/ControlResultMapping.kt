package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ControlRejection
import dev.skillbill.desktop.core.domain.model.ReadProblem
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult

internal enum class ControlKind { PAUSE_AFTER_SUBTASK, PAUSE_NOW, CANCEL, LAUNCH, START_GOAL }

/**
 * desktop-tray's `ControlConfirmation` mapping. A stop the runtime only accepted means no live runner needed stopping (its stop
 * path returns `Accepted` only for already stopped and no live lease), so it never reads as a confirmed exit.
 */
internal fun WorkControlResult.toControlOutcome(kind: ControlKind, diagnostics: Diagnostics): ControlOutcome = when (this) {
  WorkControlResult.Accepted -> if (kind == ControlKind.PAUSE_NOW) ControlOutcome.NoProcessNeeded else ControlOutcome.RequestAccepted
  WorkControlResult.IntentRecorded -> ControlOutcome.IntentRecorded
  WorkControlResult.ExitConfirmed -> ControlOutcome.ExitConfirmed
  WorkControlResult.TerminationAttempted -> ControlOutcome.TerminationAttempted
  is WorkControlResult.LaunchFailed -> ControlOutcome.Failed(ReadProblem.Runtime(reason))
  is WorkControlResult.Rejected -> ControlOutcome.Refused(reason.toDomain(), detail)
  // Only recovery returns Recovered; the repository maps that path itself.
  is WorkControlResult.Recovered -> {
    diagnostics.record("desktop_control_unexpected_recovered")
    ControlOutcome.Failed(ReadProblem.Runtime("The runtime returned a recovery result for a run control."))
  }
}

// Exhaustive on purpose: a runtime refusal without a desktop counterpart must fail the build, not a control.
internal fun WorkControlRejection.toDomain(): ControlRejection = when (this) {
  WorkControlRejection.UNKNOWN_TARGET -> ControlRejection.UNKNOWN_TARGET
  WorkControlRejection.CHANGED_TARGET -> ControlRejection.CHANGED_TARGET
  WorkControlRejection.INELIGIBLE_TARGET -> ControlRejection.INELIGIBLE_TARGET
  WorkControlRejection.NOT_FOUND -> ControlRejection.NOT_FOUND
  WorkControlRejection.OWNERSHIP_MISMATCH -> ControlRejection.OWNERSHIP_MISMATCH
  WorkControlRejection.FOREIGN_HOST -> ControlRejection.FOREIGN_HOST
  WorkControlRejection.PID_REUSED -> ControlRejection.PID_REUSED
  WorkControlRejection.UNINSPECTABLE -> ControlRejection.UNINSPECTABLE
  WorkControlRejection.IDENTITY_MISMATCH -> ControlRejection.IDENTITY_MISMATCH
  WorkControlRejection.TERMINATION_INCOMPLETE -> ControlRejection.TERMINATION_INCOMPLETE
  WorkControlRejection.AGENT_REQUIRED -> ControlRejection.AGENT_REQUIRED
  WorkControlRejection.BRANCH_CHANGED -> ControlRejection.BRANCH_CHANGED
  WorkControlRejection.BRANCH_UNAVAILABLE -> ControlRejection.BRANCH_UNAVAILABLE
  WorkControlRejection.STALE_INCIDENT -> ControlRejection.STALE_INCIDENT
  WorkControlRejection.RECOVERY_UNAVAILABLE -> ControlRejection.RECOVERY_UNAVAILABLE
  WorkControlRejection.PREREQUISITE_UNMET -> ControlRejection.PREREQUISITE_UNMET
  WorkControlRejection.RECOVERY_IN_PROGRESS -> ControlRejection.RECOVERY_IN_PROGRESS
}
