package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.goalreview.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import java.nio.file.Path

data class FeatureTaskRuntimePhasePromptComposeInputs(
  val issueKey: String,
  val briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  val suppressDecomposition: Boolean = false,
  val specBundleRequired: Boolean = false,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val reviewPassNumber: Int? = null,
  val goalSubtaskReviewInput: GoalSubtaskReviewInput? = null,
  val baselineUntrackedPaths: List<String> = emptyList(),
  val resolvedReviewTier: CodeReviewExecutionMode? = null,
  val reviewDecidingRule: String? = null,
  val priorTerminalFailure: String? = null,
  val priorFindingCoverage: String? = null,
  val priorAcceptanceAudit: String? = null,
  val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = null,
  val implementationContinuation: FeatureTaskRuntimeImplementationContinuation? = null,
  val validationGateFindings: ValidationFindingSetProjection? = null,
  val validationGateTriagePlan: String? = null,
  val validationGateRepair: Boolean = false,
  val validationGateTriage: Boolean = false,
  val agentRunValidateFallback: Boolean = false,
  val packCollectAllCommand: String? = null,
  val packConfirmationGateCommand: String? = null,
  val packBuildCommand: String? = null,
  val repairLedger: FeatureTaskRuntimeRepairLedger? = null,
  val priorReviewContext: FeatureTaskRuntimePriorReviewContext? = null,
  val phaseSettlement: FeatureTaskRuntimePhaseSettlementTarget? = null,
  val mutating: Boolean = false,
  val singleAgentSession: Boolean = false,
  val repoRoot: Path? = null,
)
