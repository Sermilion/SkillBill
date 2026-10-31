package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import java.nio.file.Path

public data class SubtaskCommitPreservationRequest(
  val repoRoot: Path,
  val decision: FeatureTaskRuntimeSubtaskCommitDecision,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val message: String,
  val allowUnchangedIndex: Boolean,
  val record: (String) -> Unit,
)
