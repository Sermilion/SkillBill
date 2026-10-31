package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMetadata
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushHandoff
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity

public data class FeatureTaskRuntimeSubtaskFinaliseRequest(
  public val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  public val durableCommitSha: String?,
  public val sequenceNumber: Int,
  public val handoff: FeatureTaskRuntimeCommitPushHandoff,
  public val metadata: FeatureTaskRuntimeCheckpointMetadata,
  public val manifestCommitSha: String? = null,
)
