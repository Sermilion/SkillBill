package skillbill.engine.work.control.model

import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.work.model.WorkStoreObservation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import java.nio.file.Path
import java.time.Instant

public data class WorkControlTarget(
  val workflowId: String,
  val repositoryIdentity: String,
  val repositoryRoot: Path,
  val issueKey: String,
  val executionId: String?,
  val statusRevision: String?,
  val statusStoreId: String?,
  val runSequence: String?,
  val updatedAt: Instant,
  val leaseOwnerToken: String?,
  val leaseGeneration: Long?,
  val observation: WorkStoreObservation? = null,
  val databaseFileObservation: DatabaseFileObservation? = null,
)

public enum class WorkControlAction { PAUSE, STOP, CANCEL, LAUNCH, RECOVER }

public enum class WorkControlRejection {
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

public sealed interface WorkControlResult {
  /** The request was admitted; it makes no claim about the lifecycle of the controlled process. */
  public data object Accepted : WorkControlResult

  /** A goal pause is durably recorded. The goal runner honours it at its next checkpoint; nothing has stopped yet. */
  public data object IntentRecorded : WorkControlResult

  /** A goal stop was recorded and the goal runner's process was confirmed to have exited. */
  public data object ExitConfirmed : WorkControlResult

  /**
   * A goal stop was recorded and a termination signal was sent, but the runner's exit could not be confirmed because
   * its process identity changed after the signal. The runner may still be running.
   */
  public data object TerminationAttempted : WorkControlResult

  public data class LaunchFailed(val reason: String) : WorkControlResult

  /** [detail] carries the typed recovery reason behind RECOVERY_UNAVAILABLE and PREREQUISITE_UNMET. */
  public data class Rejected(val reason: WorkControlRejection, val detail: String? = null) : WorkControlResult

  /**
   * A block incident recovery attempt reached [status]. [launch] is the resumed run's launch result, null when the
   * attempt failed or settled before this request could resume it.
   */
  public data class Recovered(
    val attemptId: String,
    val status: BlockIncidentRecoveryAttemptStatus,
    val failure: BlockIncidentRecoveryFailure?,
    val launch: WorkControlResult?,
  ) : WorkControlResult
}
