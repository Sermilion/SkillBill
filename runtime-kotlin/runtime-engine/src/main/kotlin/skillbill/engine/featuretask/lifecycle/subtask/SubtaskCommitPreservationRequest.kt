package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import java.nio.file.Path

public data class SubtaskCommitPreservationRequest(
  public val repoRoot: Path,
  public val decision: FeatureTaskRuntimeSubtaskCommitDecision,
  public val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  public val message: String,
  public val allowUnchangedIndex: Boolean,
  public val record: (String) -> Unit,
)
