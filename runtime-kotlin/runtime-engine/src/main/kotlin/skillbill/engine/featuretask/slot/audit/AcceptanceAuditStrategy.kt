package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseExecutionPlanMapping
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategyStatusProjection
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.audit.planning.AuditPlanFixPromptSections
import skillbill.engine.featuretask.slot.audit.planning.AuditPlanningExecutionPlanMapping
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class AcceptanceAuditStrategy : PhaseStrategyStatusProjection() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = true,
          readOnlyIdle = true,
          fileMutating = false,
          generationScoped = false,
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX to
        PhaseStepPolicy(
          mutating = true,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
          extendsOwnedInventory = true,
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = true,
          readOnlyIdle = true,
          fileMutating = false,
          generationScoped = false,
        ),
    )

  override val slot: PhaseSlot = PhaseSlot.AUDIT
  override val strategyId: String = ID
  override val semanticRevision: Int = AuditPlanningExecutionPlanMapping.SEMANTIC_REVISION
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT

  override fun mapRecordedExecutionPlan(
    recorded: ResolvedPhaseExecutionPlan,
    current: ResolvedPhaseExecutionPlan,
  ): PhaseExecutionPlanMapping? = AuditPlanningExecutionPlanMapping.map(recorded, current, this)

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return when (stepId) {
      entryStep -> AcceptanceAuditPromptSections.DIRECTIVE
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX -> AuditPlanFixPromptSections.DIRECTIVE
      else -> AuditImplementFixPromptSections.DIRECTIVE
    }
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    policies.policyOf(stepId)
    return when (stepId) {
      entryStep -> AcceptanceAuditPromptSections.sections(inputs)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX -> AuditPlanFixPromptSections.sections()
      else -> AuditImplementFixPromptSections.sections(inputs)
    }
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome = runAgentStep(run, state)

  override fun stepHooks(stepId: String): PhaseStepHooks {
    policies.policyOf(stepId)
    return when (stepId) {
      entryStep -> AcceptanceAuditRound
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX -> PhaseStepHooks.None
      else -> AuditImplementFixStep
    }
  }

  override fun verdictRule(
    stepId: String,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeStepVerdictRule? {
    policies.policyOf(stepId)
    return if (stepId ==
      entryStep
    ) {
      AcceptanceAuditVerdictRule(diagnostics)
    } else {
      super.verdictRule(stepId, diagnostics)
    }
  }

  override fun resumeRules(stepId: String): PhaseResumeRules {
    policies.policyOf(stepId)
    return if (stepId == entryStep) AcceptanceAuditResumeRules else PhaseResumeRules.None
  }

  override val loopRules: PhaseLoopRules = AcceptanceAuditLoopRules

  override fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? {
    return auditCurrentExecution(stepId, context)
  }

  companion object {
    const val ID = "acceptance-audit"
  }
}
