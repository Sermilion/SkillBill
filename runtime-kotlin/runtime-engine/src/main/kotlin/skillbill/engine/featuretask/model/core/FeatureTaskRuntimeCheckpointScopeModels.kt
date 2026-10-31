package skillbill.engine.featuretask.model.core

public sealed interface FeatureTaskRuntimeCheckpointDecision {
  /**
   * Stage [ownedPaths] and commit. [adoptedPaths] is the subset that was already staged or modified
   * outside the remembered inventory; it is reported, never refused.
   */
  public data class Stage(
    public val ownedPaths: List<String>,
    public val adoptedPaths: List<String> = emptyList(),
  ) : FeatureTaskRuntimeCheckpointDecision

  /** The dirty tree is empty, so there is nothing to checkpoint. */
  public data object Skip : FeatureTaskRuntimeCheckpointDecision

  /** Refuse: a git read or preservation step failed before staging could proceed safely. */
  public data class Block(val reason: String) : FeatureTaskRuntimeCheckpointDecision
}

public data class FeatureTaskRuntimeCheckpointScopeInput(
  public val issueKey: String,
  public val ownedPaths: List<String>,
  public val phaseIntroducedPaths: List<String>,
  public val worktreeDeltaPaths: List<String>,
  public val foreignStagedPaths: List<String> = emptyList(),
  public val concurrentlyModifiedOwnedPaths: List<String> = emptyList(),
  public val deletedPaths: List<String> = emptyList(),
  public val workflowId: String? = null,
)
