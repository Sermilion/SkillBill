package skillbill.ports.workflow.gitops.model

import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunks

data class WorkflowSelectedDiffHunksRequest(
  val paths: List<String>,
  val includeStaged: Boolean = true,
  val includeUnstaged: Boolean = true,
  val maxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  val maxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  val maxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
) {
  init {
    require(paths.all { it.isNotBlank() }) { "selected diff paths must not be blank." }
    require(maxHunks > 0) { "maxHunks must be positive." }
    require(maxLines > 0) { "maxLines must be positive." }
    require(maxBytes > 0) { "maxBytes must be positive." }
  }
}

data class WorkflowSelectedDiffHunksResult(
  val status: WorkflowGitOperationStatus,
  val selectedDiffHunks: GoalObservabilitySelectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  val error: String = "",
)

const val DEFAULT_SELECTED_DIFF_MAX_HUNKS: Int = 6
const val DEFAULT_SELECTED_DIFF_MAX_LINES: Int = 120
const val DEFAULT_SELECTED_DIFF_MAX_BYTES: Int = 12_000
