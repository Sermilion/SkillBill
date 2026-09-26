package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.core.attemptPhaseExecution
import skillbill.engine.featuretask.phase.core.defaultPhaseExecution
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategyStatusProjection
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.promptSource
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecution
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.persistence.task.runtime.run.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class InlineReviewStrategy(
  override val runner: PhaseRunner,
) : PhaseStrategyStatusProjection() {
  private val review = InlineReviewStep(runner)
  private val verifyFindings = VerifyFindingsStep()
  private val implementFix = ImplementFixStep()
  private val policies: Map<String, PhaseStepPolicy> =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to review.policy,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to verifyFindings.policy,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to implementFix.policy,
    )

  override val slot: PhaseSlot = PhaseSlot.CODE_REVIEW
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> InlineReviewPromptSections.REVIEW_DIRECTIVE
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        InlineReviewPromptSections.VERIFY_FINDINGS_DIRECTIVE
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX ->
        InlineReviewPromptSections.IMPLEMENT_FIX_DIRECTIVE
      else -> throw UnknownPhaseStepError(stepId)
    }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> InlineReviewPromptSections.review(stepId, inputs)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        InlineReviewPromptSections.verifyFindings(
          stepId,
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> InlineReviewPromptSections.implementFix(stepId)
      else -> throw UnknownPhaseStepError(stepId)
    }

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    policies.policyOf(stepId)
    return if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      FeatureTaskRuntimeRunInvariantPromptAllowlist.IDENTITY_CEREMONY_AND_POLICY
    } else {
      super.briefingInvariantFields(stepId)
    }
  }

  override fun runStep(
    run: PhaseRun,
    context: FeatureTaskRuntimeRunLoopContext,
    state: PhaseRunState,
  ): PhaseOutcome =
    when (run.phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        review.run(
          run,
          context,
          state,
          promptSource(run.phaseId),
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      -> runAgentStep(run, context, state)
      else -> throw UnknownPhaseStepError(run.phaseId)
    }

  override fun stepHooks(stepId: String): PhaseStepHooks =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> review
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> verifyFindings
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> implementFix
      else -> PhaseStepHooks.None
    }

  override val loopRules: PhaseLoopRules = InlineReviewLoopRules

  override fun currentExecution(
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

  companion object {
    const val ID = "inline"
  }
}
