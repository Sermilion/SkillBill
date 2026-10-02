package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.promptSource
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.codereview.history.CodeReviewHistory
import skillbill.engine.featuretask.slot.codereview.verify.VerifyFindingsStep
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.engine.featuretask.slot.state.PhaseVerifyFindingsStepBinding
import skillbill.engine.featuretask.slot.stepFacts
import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

/** The one review-step variation between code_review strategies: how a review pass produces its findings. */
internal interface CodeReviewPass {
  /** The review step's policy. */
  val policy: PhaseStepPolicy

  /** The review step's task directive. */
  val directive: String

  /** Whether [review] already records the pass's lane and stage telemetry, so the run state must not repeat it. */
  val recordsLaneTelemetry: Boolean

  /** The tier this pass executes for the review mode the pass sequence [resolved]. */
  fun executedTier(resolved: CodeReviewExecutionMode): CodeReviewExecutionMode

  /** Reviews [input] for [run] through [runner] and returns the merged review result. */
  fun review(
    run: PhaseRun,
    input: GoalSubtaskReviewInput,
    reviewRunId: String,
    runner: PhaseRunner,
    state: PhaseReviewStepBinding,
  ): ParallelCodeReviewRunOutcome
}

internal class CodeReviewSlot(
  runner: PhaseRunner,
  private val pass: CodeReviewPass,
) {
  private val review = CodeReviewStep(runner, pass)
  private val verifyFindings = VerifyFindingsStep()
  private val implementFix = ImplementFixStep()
  private val policies: Map<String, PhaseStepPolicy> =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to pass.policy,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to verifyFindings.policy,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to implementFix.policy,
    )

  val steps: List<String> = policies.keys.toList()
  val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
  val loopRules: PhaseLoopRules = InlineReviewLoopRules

  fun executionBindingKind(stepId: String): PhaseExecutionBindingKind =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> PhaseExecutionBindingKind.REVIEW
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> PhaseExecutionBindingKind.FINDING_VERIFICATION
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> PhaseExecutionBindingKind.REPAIR_RECEIPT
      else -> throw UnknownPhaseStepError(stepId)
    }

  fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  fun directiveFor(stepId: String): String =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> pass.directive
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        InlineReviewPromptSections.VERIFY_FINDINGS_DIRECTIVE
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX ->
        InlineReviewPromptSections.IMPLEMENT_FIX_DIRECTIVE
      else -> throw UnknownPhaseStepError(stepId)
    }

  fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        InlineReviewPromptSections.review(inputs, pass.directive)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> InlineReviewPromptSections.verifyFindings()
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> InlineReviewPromptSections.implementFix()
      else -> throw UnknownPhaseStepError(stepId)
    }

  fun briefingInvariantFields(
    stepId: String,
    otherwise: Set<FeatureTaskRuntimeRunInvariantPromptField>,
  ): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    policies.policyOf(stepId)
    return if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      FeatureTaskRuntimeRunInvariantPromptAllowlist.IDENTITY_CEREMONY_AND_POLICY
    } else {
      otherwise
    }
  }

  fun runStep(
    strategy: PhaseStrategy,
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome =
    when (run.phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> {
        val reviewBinding =
          state as? PhaseReviewStepBinding
            ?: error("Review step requires a review execution binding.")
        review.run(
          run,
          reviewBinding.reviewExecutionContext(),
          reviewBinding,
          strategy.promptSource(run.phaseId),
        )
      }
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> {
        check(state is PhaseVerifyFindingsStepBinding) {
          "Verify findings step requires a finding-verification binding."
        }
        strategy.runAgentStep(run, state)
      }
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> {
        check(state is PhaseImplementFixStepBinding) {
          "Implement fix step requires an implement-fix binding."
        }
        strategy.runAgentStep(run, state)
      }
      else -> throw UnknownPhaseStepError(run.phaseId)
    }

  fun stepHooks(stepId: String): PhaseStepHooks =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> review
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> verifyFindings
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> implementFix
      else -> PhaseStepHooks.None
    }

  fun verdictRule(stepId: String): FeatureTaskRuntimeStepVerdictRule? =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> FeatureTaskRuntimeOutputVerification.reviewVerdictRule
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        FeatureTaskRuntimeOutputVerification.findingVerificationVerdictRule
      else -> null
    }

  fun resumeRules(stepId: String): PhaseResumeRules =
    if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      CodeReviewResumeRules
    } else {
      PhaseResumeRules.None
    }

  fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? = CodeReviewHistory.currentExecution(stepId, context)
}

internal fun reviewStepInput(
  run: PhaseRun,
  directive: String,
): PhaseStepInput =
  PhaseStepInput(
    stepName = run.phaseId,
    directive = directive,
    priorValues = emptyMap(),
    operatorInstructions = run.request.phaseInstructions?.forStep(run.phaseId),
    facts = run.stepFacts(run.request.workflowId.takeIf(String::isNotBlank) ?: REVIEW_ISSUE_KEY, attempt = null),
    policy = run.policy,
  )

private const val REVIEW_ISSUE_KEY = "code-review"
