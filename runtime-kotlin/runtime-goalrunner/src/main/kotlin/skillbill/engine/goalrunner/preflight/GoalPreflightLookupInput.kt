package skillbill.engine.goalrunner.preflight

import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

internal data class GoalPreflightLookupInput(
  val lookup: FeatureTaskContinuationLookupResult,
  val normalizedIssueKey: String,
  val manifest: DecompositionManifest?,
  val manifestState: GoalRunnerManifestState?,
  val request: GoalPreflightRequest,
  val root: Path,
  val omitLeftoverGateBlock: Boolean,
)
