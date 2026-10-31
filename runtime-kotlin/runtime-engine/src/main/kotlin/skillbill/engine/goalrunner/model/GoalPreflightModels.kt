package skillbill.engine.goalrunner.model

import skillbill.application.continuation.model.GoalContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

internal data class GoalPreflightLookupInput(
  val lookup: FeatureTaskContinuationLookupResult,
  val normalizedIssueKey: String,
  val manifest: DecompositionManifest?,
  val manifestState: GoalRunnerManifestState?,
  val request: GoalPreflightRequest,
  val root: Path,
)

public data class GoalPreflightRequest(
  public val issueKey: String,
  public val repoRoot: Path,
  public val invokedAgentId: String,
  public val agentOverrideId: String? = null,
  public val requestedReviewMode: CodeReviewExecutionMode? = null,
  public val requestedAgentAddonSlugs: List<String> = emptyList(),
  public val userHome: Path = Path.of("."),
  public val environment: Map<String, String> = emptyMap(),
)

public data class GoalPreflightResult(
  public val verdict: String,
  public val issueKey: String,
  public val candidate: FeatureTaskContinuationCandidate? = null,
  public val candidates: List<FeatureTaskContinuationCandidate> = emptyList(),
  public val goal: GoalContinuationCandidate? = null,
  public val gateBlock: GoalPreflightGateBlock? = null,
  public val rehydrateTargets: List<GoalPreflightRehydrateTarget> = emptyList(),
  public val manifestMissing: Boolean = false,
)

public data class GoalPreflightGateBlock(
  public val issueKey: String,
  public val featureName: String,
  public val subtasks: List<GoalPreflightSubtask>,
  public val expectedFirstRunnableSubtask: Int?,
  public val childAgent: String,
  public val childAgentOverride: String?,
  public val reviewMode: String,
  public val agentAddons: List<GoalPreflightAgentAddon>,
)

public data class GoalPreflightSubtask(
  public val id: Int,
  public val name: String,
  public val status: String,
  public val dependencies: List<GoalPreflightDependency>,
)

public data class GoalPreflightDependency(
  public val subtaskId: Int,
  public val optional: Boolean,
  public val skipped: Boolean,
  public val note: String,
)

public data class GoalPreflightAgentAddon(
  public val slug: String,
  public val description: String,
)

public data class GoalPreflightRehydrateTarget(
  public val issueKey: String,
  public val linearIssueId: String?,
  public val targetPath: String,
)

public object GoalPreflightVerdict {
  public const val NEW_WORK: String = "new_work"
  public const val RESUMABLE: String = "resumable"
  public const val ALREADY_RUNNING: String = "already_running"
  public const val TERMINAL_ONLY: String = "terminal_only"
  public const val GOAL_CONTINUATION: String = "goal_continuation"
}
