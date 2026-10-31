package skillbill.engine.featuretask.model.subtask

public data class FeatureTaskRuntimeCommitPushHandoff(
  public val outcomeMessage: String,
  public val changedPaths: List<String>,
)

public data class FeatureTaskRuntimeCommitPushReceipt(
  public val commitSha: String?,
  public val branch: String? = null,
  public val baseBranch: String? = null,
  public val pushed: Boolean = false,
  public val committed: Boolean = true,
)

public sealed interface FeatureTaskRuntimeCommitPushHandoffResult

public data class FeatureTaskRuntimeCommitPushHandoffValid(
  public val handoff: FeatureTaskRuntimeCommitPushHandoff,
) : FeatureTaskRuntimeCommitPushHandoffResult

public data class FeatureTaskRuntimeCommitPushHandoffInvalid(
  public val reason: String,
) : FeatureTaskRuntimeCommitPushHandoffResult

public sealed interface FeatureTaskRuntimeSubtaskFinalisationResult

public data class FeatureTaskRuntimeSubtaskFinalised(
  public val commitSha: String,
  public val stagedPaths: List<String>,
  public val forcedWithLease: Boolean,
) : FeatureTaskRuntimeSubtaskFinalisationResult

public data class FeatureTaskRuntimeSubtaskFinalisationBlocked(
  public val reason: String,
) : FeatureTaskRuntimeSubtaskFinalisationResult
