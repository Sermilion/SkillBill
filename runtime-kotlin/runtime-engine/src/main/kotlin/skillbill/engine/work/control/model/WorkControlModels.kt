package skillbill.engine.work.control.model

import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.work.model.WorkStoreObservation
import java.nio.file.Path
import java.time.Instant

data class WorkControlTarget(
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

enum class WorkControlAction { PAUSE, STOP, CANCEL, LAUNCH }

enum class WorkControlRejection {
  UNKNOWN_TARGET,
  CHANGED_TARGET,
  INELIGIBLE_TARGET,
  NOT_FOUND,
  OWNERSHIP_MISMATCH,
  TERMINATION_INCOMPLETE,
  AGENT_REQUIRED,
  BRANCH_CHANGED,
}

sealed interface WorkControlResult {
  /** The request was admitted; it makes no claim about the lifecycle of the controlled process. */
  data object Accepted : WorkControlResult

  /** A goal pause is durably recorded. The goal runner honours it at its next checkpoint, so nothing has stopped yet. */
  data object IntentRecorded : WorkControlResult

  /** A goal stop was recorded and the goal runner's process was confirmed to have exited. */
  data object ExitConfirmed : WorkControlResult

  /**
   * A goal stop was recorded and a termination signal was sent, but the runner's exit could not be confirmed because
   * its process identity changed after the signal. The runner may still be running.
   */
  data object TerminationAttempted : WorkControlResult

  data class LaunchFailed(val reason: String) : WorkControlResult

  data class Rejected(val reason: WorkControlRejection) : WorkControlResult
}
