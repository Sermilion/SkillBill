package skillbill.engine.featuretask.lifecycle.checkpoint

internal data class CheckpointScopePreparation(
  internal val worktreeDelta: List<String>,
  public val stagedPaths: List<String>,
  internal val phaseWritten: List<String>,
  internal val writingIntroduced: List<String>,
  internal val seedOwned: List<String>,
  public val deletedPaths: List<String>,
)
