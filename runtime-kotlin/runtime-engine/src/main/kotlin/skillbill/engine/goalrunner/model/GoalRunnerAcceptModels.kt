package skillbill.engine.goalrunner.model

import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import java.nio.file.Path

data class GoalRunnerAcceptRequest(
  val issueKey: String,
  val subtaskId: Int,
  val commitSha: String,
  val reason: String,
  val repoRoot: Path? = null,
  val restoreAfterHardReset: Boolean = false,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(commitSha.isNotBlank()) { "commitSha is required." }
    require(reason.isNotBlank()) { "reason is required." }
  }
}

sealed interface GoalRunnerAcceptResult {
  data class Accepted(
    val issueKey: String,
    val parentWorkflowId: String,
    val subtaskId: Int,
    val commitSha: String,
    val reason: String,
    val acceptedAt: String,
    val after: GoalRunnerResetSnapshot,
  ) : GoalRunnerAcceptResult

  data class Rejected(val issueKey: String, val reason: String) : GoalRunnerAcceptResult
}

data class GoalRunnerCompleteRequest(
  val issueKey: String,
  val commitSha: String,
  val reason: String,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(commitSha.isNotBlank()) { "commitSha is required." }
    require(reason.isNotBlank()) { "reason is required." }
  }
}

sealed interface GoalRunnerCompleteResult {
  data class Completed(
    val issueKey: String,
    val parentWorkflowId: String,
    val commitSha: String,
    val reason: String,
    val completedAt: String,
    val acceptedSubtaskIds: List<Int>,
    val before: GoalRunnerResetSnapshot,
    val after: GoalRunnerResetSnapshot,
  ) : GoalRunnerCompleteResult

  data class Rejected(val issueKey: String, val reason: String) : GoalRunnerCompleteResult
}

sealed interface GoalRunnerAcceptanceEvidence {
  data class Resolved(val commitSha: String) : GoalRunnerAcceptanceEvidence

  data class Rejected(val reason: String) : GoalRunnerAcceptanceEvidence
}

sealed interface GoalChildHistoryAcceptanceRestoreResult {
  data class Restored(
    val state: GoalRunnerManifestState,
    val acceptance: GoalRunnerOutOfBandAcceptance,
  ) : GoalChildHistoryAcceptanceRestoreResult

  data class Refused(val reason: String) : GoalChildHistoryAcceptanceRestoreResult
}

data class GoalRunnerRestoredChildReference(
  val childWorkflowId: String,
  val acceptance: GoalRunnerOutOfBandAcceptance,
)
