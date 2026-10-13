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
  data object Accepted : WorkControlResult

  data class LaunchFailed(val reason: String) : WorkControlResult

  data class Rejected(val reason: WorkControlRejection) : WorkControlResult
}
