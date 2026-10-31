package skillbill.engine.goalrunner.model

import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import java.nio.file.Path

public data class GoalRunnerAcceptRequest(
  public val issueKey: String,
  public val subtaskId: Int,
  public val commitSha: String,
  public val reason: String,
  public val repoRoot: Path? = null,
  public val restoreAfterHardReset: Boolean = false,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(commitSha.isNotBlank()) { "commitSha is required." }
    require(reason.isNotBlank()) { "reason is required." }
  }
}

public sealed interface GoalRunnerAcceptResult {
  public data class Accepted(
    public val issueKey: String,
    public val parentWorkflowId: String,
    public val subtaskId: Int,
    public val commitSha: String,
    public val reason: String,
    public val acceptedAt: String,
    public val after: GoalRunnerResetSnapshot,
  ) : GoalRunnerAcceptResult

  public data class Rejected(public val issueKey: String, public val reason: String) : GoalRunnerAcceptResult
}

public data class GoalRunnerCompleteRequest(
  public val issueKey: String,
  public val commitSha: String,
  public val reason: String,
  public val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(commitSha.isNotBlank()) { "commitSha is required." }
    require(reason.isNotBlank()) { "reason is required." }
  }
}

public sealed interface GoalRunnerCompleteResult {
  public data class Completed(
    public val issueKey: String,
    public val parentWorkflowId: String,
    public val commitSha: String,
    public val reason: String,
    public val completedAt: String,
    public val acceptedSubtaskIds: List<Int>,
    public val before: GoalRunnerResetSnapshot,
    public val after: GoalRunnerResetSnapshot,
  ) : GoalRunnerCompleteResult

  public data class Rejected(public val issueKey: String, public val reason: String) : GoalRunnerCompleteResult
}

public sealed interface GoalRunnerAcceptanceEvidence {
  public data class Resolved(public val commitSha: String) : GoalRunnerAcceptanceEvidence

  public data class Rejected(public val reason: String) : GoalRunnerAcceptanceEvidence
}

public sealed interface GoalChildHistoryAcceptanceRestoreResult {
  public data class Restored(
    public val state: GoalRunnerManifestState,
    public val acceptance: GoalRunnerOutOfBandAcceptance,
  ) : GoalChildHistoryAcceptanceRestoreResult

  public data class Refused(public val reason: String) : GoalChildHistoryAcceptanceRestoreResult
}

public data class GoalRunnerRestoredChildReference(
  public val childWorkflowId: String,
  public val acceptance: GoalRunnerOutOfBandAcceptance,
)
