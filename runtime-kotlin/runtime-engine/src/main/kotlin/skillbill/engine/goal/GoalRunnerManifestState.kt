package skillbill.engine.goal

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

public data class GoalRunnerManifestState(
  public val parentWorkflowId: String,
  public val dbPath: String,
  public val manifest: DecompositionManifest,
  public val controlState: GoalRunnerControlState = GoalRunnerControlState(),
  public val repoRoot: Path? = null,
)
