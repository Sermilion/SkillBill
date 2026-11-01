package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.model.audit.AcceptanceAuditInteractionTraceScope
import skillbill.engine.featuretask.model.audit.UnknownInteractionScenarioContext
import skillbill.engine.featuretask.model.audit.ValidateProbeScope
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationTurn
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.engine.featuretask.phase.planning.PlanBundleTarget
import skillbill.goalrunner.model.history.GoalHistoryWriterInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.goalreview.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import java.nio.file.Path

public data class FeatureTaskRuntimePhasePromptComposeInputs(
  public val issueKey: String,
  public val briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  internal val specRewritable: Boolean = false,
  public val planBundleTarget: PlanBundleTarget? = null,
  internal val planSpecsRoot: Path? = null,
  public val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  public val reviewPassNumber: Int? = null,
  public val goalSubtaskReviewInput: GoalSubtaskReviewInput? = null,
  public val baselineUntrackedPaths: List<String> = emptyList(),
  public val resolvedReviewTier: CodeReviewExecutionMode? = null,
  public val reviewDecidingRule: String? = null,
  public val priorTerminalFailure: String? = null,
  internal val priorFindingCoverage: String? = null,
  internal val priorAcceptanceAudit: String? = null,
  public val currentAcceptanceCriterion: String? = null,
  public val priorAcceptanceRepairFailures: String? = null,
  internal val interactionTraceScope: AcceptanceAuditInteractionTraceScope? = null,
  public val unknownInteractionScenarios: List<UnknownInteractionScenarioContext> = emptyList(),
  internal val noChangeClaim: String? = null,
  public val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry? = null,
  public val implementationContinuation: FeatureTaskRuntimeImplementationContinuation? = null,
  internal val implementationTurn: FeatureTaskRuntimeImplementationTurn? = null,
  public val validationGateFindings: ValidationFindingSetProjection? = null,
  internal val validationGateTriagePlan: String? = null,
  public val validationGateRepair: Boolean = false,
  public val validationGateTriage: Boolean = false,
  public val validateProbeScopeSource: () -> ValidateProbeScope? = { null },
  internal val agentRunValidateFallback: Boolean = false,
  public val packConfirmationGateCommand: String? = null,
  public val packBuildCommand: String? = null,
  public val repairLedger: FeatureTaskRuntimeRepairLedger? = null,
  public val priorReviewContext: FeatureTaskRuntimePriorReviewContext? = null,
  public val phaseSettlement: FeatureTaskRuntimePhaseSettlementTarget? = null,
  public val mutating: Boolean = false,
  public val singleAgentSession: Boolean = false,
  public val repoRoot: Path? = null,
  public val stepProfile: PhaseModelProfile = PhaseModelProfile.CANONICAL,
  internal val implementationChecklistAddress: String? = null,
  public val projectInstructions: PhaseProjectInstructionsResolution.Injected? = null,
  public val goalHistoryWriterInput: GoalHistoryWriterInput? = null,
) {
  internal val validateProbeScope: ValidateProbeScope? get() = validateProbeScopeSource()
  internal val validateProbesActive: Boolean get() = validateProbeScope != null
}
