package skillbill.engine.featuretask.model.subtask

data class FeatureTaskRuntimeCommitPushHandoff(
  val outcomeMessage: String,
  val changedPaths: List<String>,
)

data class FeatureTaskRuntimeCommitPushReceipt(
  val commitSha: String?,
  val branch: String? = null,
  val baseBranch: String? = null,
  val pushed: Boolean = false,
  val committed: Boolean = true,
)

sealed interface FeatureTaskRuntimeCommitPushHandoffResult

data class FeatureTaskRuntimeCommitPushHandoffValid(
  val handoff: FeatureTaskRuntimeCommitPushHandoff,
) : FeatureTaskRuntimeCommitPushHandoffResult

data class FeatureTaskRuntimeCommitPushHandoffInvalid(
  val reason: String,
) : FeatureTaskRuntimeCommitPushHandoffResult

sealed interface FeatureTaskRuntimeSubtaskFinalisationResult

data class FeatureTaskRuntimeSubtaskFinalised(
  val commitSha: String,
  val stagedPaths: List<String>,
  val forcedWithLease: Boolean,
) : FeatureTaskRuntimeSubtaskFinalisationResult

data class FeatureTaskRuntimeSubtaskFinalisationBlocked(
  val reason: String,
) : FeatureTaskRuntimeSubtaskFinalisationResult
