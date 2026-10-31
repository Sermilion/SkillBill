package skillbill.engine.goalrunner.model

import java.nio.file.Path

public data class GoalRunnerPurgeRequest(
  public val issueKey: String,
  public val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

public data class GoalPurgeOwnership(
  public val manifests: List<GoalRunnerManifestState>,
  public val parentWorkflowIds: Set<String>,
  internal val unclassifiedWorkflows: List<String> = emptyList(),
  public val planWorkflowIds: Set<String> = emptySet(),
  internal val siblingWorktreeWorkflows: List<String> = emptyList(),
)

public enum class GoalRunnerPurgeSpecActionKind { DELETED, RESET, RESTORED }

public data class GoalRunnerPurgeSpecAction(
  public val path: String,
  public val kind: GoalRunnerPurgeSpecActionKind,
)

public data class GoalRunnerPurgeResult(
  public val issueKey: String,
  public val parentWorkflowIds: List<String> = emptyList(),
  public val deletedChildWorkflowIds: List<String> = emptyList(),
  public val removedRowCounts: Map<String, Int> = emptyMap(),
  public val removedPaths: List<String> = emptyList(),
  public val specBundleActions: List<GoalRunnerPurgeSpecAction> = emptyList(),
  public val checkpointRefsPruned: Int = 0,
  public val leftovers: List<String> = emptyList(),
  public val refusalReason: String? = null,
) {
  public val nothingToRemove: Boolean
    get() =
      refusalReason == null &&
        leftovers.isEmpty() &&
        removedRowCounts.values.all { it == 0 } &&
        removedPaths.isEmpty() &&
        specBundleActions.isEmpty() &&
        checkpointRefsPruned == 0
}
