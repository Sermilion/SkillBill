package skillbill.engine.goalrunner.model

import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.gitops.model.DEFAULT_SELECTED_DIFF_MAX_LINES
import java.nio.file.Path

data class GoalRunnerStatusRequest(
  val issueKey: String,
  val invokedAgentId: String? = null,
  val configuredAgentOverrideId: String? = null,
  val repoRoot: Path? = null,
  val includeDiffStat: Boolean = false,
  val pruneCheckpointRefs: Boolean = true,
  val includeCompletedSubtaskValidation: Boolean = true,
  val completedSubtaskValidationIds: Set<Int>? = null,
  val selectedDiffHunkPaths: List<String> = emptyList(),
  val selectedDiffMaxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  val selectedDiffMaxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  val selectedDiffMaxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
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
