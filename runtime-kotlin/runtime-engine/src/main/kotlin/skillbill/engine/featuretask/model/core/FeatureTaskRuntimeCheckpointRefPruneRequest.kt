package skillbill.engine.featuretask.model.core

public data class FeatureTaskRuntimeCheckpointRefPruneRequest(
  public val issueKey: String,
  public val subtaskId: String,
  public val manifestCommitSha: String?,
  public val bypassEligibilityGate: Boolean = false,
  public val featureBranch: String? = null,
)
