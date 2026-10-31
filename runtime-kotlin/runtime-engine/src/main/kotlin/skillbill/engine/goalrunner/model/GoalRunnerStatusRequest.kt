package skillbill.engine.goalrunner.model

import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_LINES
import java.nio.file.Path

public data class GoalRunnerStatusRequest(
  public val issueKey: String,
  public val invokedAgentId: String? = null,
  public val configuredAgentOverrideId: String? = null,
  public val repoRoot: Path? = null,
  public val includeDiffStat: Boolean = false,
  public val pruneCheckpointRefs: Boolean = false,
  public val includeCompletedSubtaskValidation: Boolean = true,
  public val completedSubtaskValidationIds: Set<Int>? = null,
  public val selectedDiffHunkPaths: List<String> = emptyList(),
  public val selectedDiffMaxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  public val selectedDiffMaxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  public val selectedDiffMaxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    invokedAgentId?.let { require(it.isNotBlank()) { "invokedAgentId must not be blank." } }
    configuredAgentOverrideId?.let { require(it.isNotBlank()) { "configuredAgentOverrideId must not be blank." } }
    require(selectedDiffHunkPaths.all { it.isNotBlank() }) { "selectedDiffHunkPaths must not contain blanks." }
    require(selectedDiffMaxHunks > 0) { "selectedDiffMaxHunks must be positive." }
    require(selectedDiffMaxLines > 0) { "selectedDiffMaxLines must be positive." }
    require(selectedDiffMaxBytes > 0) { "selectedDiffMaxBytes must be positive." }
  }
}
