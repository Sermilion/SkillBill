package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.core.attemptPhaseExecution
import skillbill.engine.featuretask.phase.core.defaultPhaseExecution
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptScope
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.promptSource
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.stepFacts
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecution
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.persistence.task.runtime.run.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

/** The one review-step variation between code_review strategies: how a review pass produces its findings. */
internal interface CodeReviewPass {
  /** The review step's policy. */
  val policy: PhaseStepPolicy

  /** The review step's task directive. */
  val directive: String

  /** The tier this pass executes for the review mode the pass sequence [resolved]. */
  fun executedTier(resolved: CodeReviewExecutionMode): CodeReviewExecutionMode

  /** Reviews [input] for [run] through [runner] and returns the merged review result. */
  fun review(
    run: PhaseRun,
    input: GoalSubtaskReviewInput,
    reviewRunId: String,
    runner: PhaseRunner,
    state: PhaseStepState,
  ): ParallelCodeReviewResult
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
        InlineReviewPromptSections.review(stepId, inputs, pass.directive)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        InlineReviewPromptSections.verifyFindings(stepId)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> InlineReviewPromptSections.implementFix(stepId)
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
    state: PhaseStepState,
  ): PhaseOutcome =
    when (run.phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        review.run(
          run,
          PhaseAttemptScope(run.request, state),
          state,
          strategy.promptSource(run.phaseId),
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      -> strategy.runAgentStep(run, state)
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
  ): IdeStatusCurrentPhaseExecution? =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        activeReviewPassNumber(context.records[stepId], context.ledger)?.let { pass ->
          IdeStatusCurrentPhaseExecution(
            phaseId = stepId,
            kind = IdeStatusCurrentPhaseExecutionKind.PASS,
            count = pass,
          )
        } ?: attemptPhaseExecution(stepId, context)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> attemptPhaseExecution(stepId, context)
      else -> defaultPhaseExecution(stepId, context)
    }

  private fun activeReviewPassNumber(
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): Int? {
    val pass = record?.reviewPassNumber ?: return null
    if (record.status.workflowStepStatus() != WorkflowStepStatus.COMPLETED) return pass
    val latestReviewFixEdge =
      ledger
        .filter {
          it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
            it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID &&
            it.edgeIteration != null
        }
        .maxByOrNull { it.sequenceNumber }
    val ledgerEdge = latestReviewFixEdge?.edgeIteration
    val reenteredReview =
      record.takeIf {
        it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      }?.edgeIteration
    return ledgerEdge?.let { edge ->
      reenteredReview?.takeIf { it >= edge }
    }?.let { pass }
  }
}

internal fun reviewStepInput(
  run: PhaseRun,
  directive: String,
): PhaseStepInput =
  PhaseStepInput(
    stepName = run.phaseId,
    directive = directive,
    priorValues = emptyMap(),
    operatorInstructions = null,
    facts = run.stepFacts(run.request.workflowId.takeIf(String::isNotBlank) ?: REVIEW_ISSUE_KEY, attempt = null),
    policy = run.policy,
  )

private const val REVIEW_ISSUE_KEY = "code-review"
