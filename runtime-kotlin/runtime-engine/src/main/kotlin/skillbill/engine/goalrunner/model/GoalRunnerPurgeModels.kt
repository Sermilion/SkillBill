package skillbill.engine.goalrunner.model

import java.nio.file.Path

data class GoalRunnerPurgeRequest(
  val issueKey: String,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

data class GoalPurgeOwnership(
  val manifests: List<GoalRunnerManifestState>,
  val parentWorkflowIds: Set<String>,
  val unclassifiedWorkflows: List<String> = emptyList(),
  val planWorkflowIds: Set<String> = emptySet(),
)

enum class GoalRunnerPurgeSpecActionKind { DELETED, RESET, RESTORED }

data class GoalRunnerPurgeSpecAction(
  val path: String,
  val kind: GoalRunnerPurgeSpecActionKind,
)

data class GoalRunnerPurgeResult(
  val issueKey: String,
  val parentWorkflowIds: List<String> = emptyList(),
  val deletedChildWorkflowIds: List<String> = emptyList(),
  val removedRowCounts: Map<String, Int> = emptyMap(),
  val removedPaths: List<String> = emptyList(),
  val specBundleActions: List<GoalRunnerPurgeSpecAction> = emptyList(),
  val checkpointRefsPruned: Int = 0,
  val leftovers: List<String> = emptyList(),
  val refusalReason: String? = null,
) {
  val nothingToRemove: Boolean
    get() =
      refusalReason == null &&
        leftovers.isEmpty() &&
        removedRowCounts.values.all { it == 0 } &&
        removedPaths.isEmpty() &&
        specBundleActions.isEmpty() &&
        checkpointRefsPruned == 0
}
