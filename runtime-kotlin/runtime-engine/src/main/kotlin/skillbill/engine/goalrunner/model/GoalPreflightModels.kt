package skillbill.engine.goalrunner.model

import skillbill.application.continuation.model.GoalContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

data class GoalPreflightLookupInput(
  val lookup: FeatureTaskContinuationLookupResult,
  val normalizedIssueKey: String,
  val manifest: DecompositionManifest?,
  val manifestState: GoalRunnerManifestState?,
  val request: GoalPreflightRequest,
  val root: Path,
)

data class GoalPreflightRequest(
  val issueKey: String,
  val repoRoot: Path,
  val invokedAgentId: String,
  val agentOverrideId: String? = null,
  val requestedReviewMode: CodeReviewExecutionMode? = null,
  val requestedAgentAddonSlugs: List<String> = emptyList(),
  val userHome: Path = Path.of("."),
  val environment: Map<String, String> = emptyMap(),
)

data class GoalPreflightResult(
  val verdict: String,
  val issueKey: String,
  val candidate: FeatureTaskContinuationCandidate? = null,
  val candidates: List<FeatureTaskContinuationCandidate> = emptyList(),
  val goal: GoalContinuationCandidate? = null,
  val gateBlock: GoalPreflightGateBlock? = null,
  val rehydrateTargets: List<GoalPreflightRehydrateTarget> = emptyList(),
  val manifestMissing: Boolean = false,
)

data class GoalPreflightGateBlock(
  val issueKey: String,
  val featureName: String,
  val subtasks: List<GoalPreflightSubtask>,
  val expectedFirstRunnableSubtask: Int?,
  val childAgent: String,
  val childAgentOverride: String?,
  val reviewMode: String,
  val agentAddons: List<GoalPreflightAgentAddon>,
)

data class GoalPreflightSubtask(
  val id: Int,
  val name: String,
  val status: String,
  val dependencies: List<GoalPreflightDependency>,
)

data class GoalPreflightDependency(
  val subtaskId: Int,
  val optional: Boolean,
  val skipped: Boolean,
  val note: String,
)

data class GoalPreflightAgentAddon(
  val slug: String,
  val description: String,
)

data class GoalPreflightRehydrateTarget(
  val issueKey: String,
  val linearIssueId: String?,
  val targetPath: String,
)
