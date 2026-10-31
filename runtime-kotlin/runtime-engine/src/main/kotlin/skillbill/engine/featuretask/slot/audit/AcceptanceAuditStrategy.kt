package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.slot.AcceptanceRepairCapability
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseExecutionPlanMapping
import skillbill.engine.featuretask.slot.PhaseLoopCapability
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseRecordedPlanMappingCapability
import skillbill.engine.featuretask.slot.PhaseResumeCapability
import skillbill.engine.featuretask.slot.PhaseStatusCapability
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.PhaseVerdictCapability
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.audit.planning.AuditPlanFixPromptSections
import skillbill.engine.featuretask.slot.audit.planning.AuditPlanningExecutionPlanMapping
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

public class AcceptanceAuditStrategy :
  PhaseStrategy(),
  AcceptanceRepairCapability,
  PhaseLoopCapability by AcceptanceAuditCapabilities,
  PhaseRecordedPlanMappingCapability by AcceptanceAuditCapabilities,
  PhaseResumeCapability by AcceptanceAuditCapabilities,
  PhaseStatusCapability by AcceptanceAuditCapabilities,
  PhaseVerdictCapability {
  private val policies: Map<String, PhaseStepPolicy> = ACCEPTANCE_AUDIT_POLICIES

  override val acceptanceRepairSteps: AcceptanceCriterionRepairStepRoles =
    AcceptanceCriterionRepairStepRoles(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX,
      FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_REPAIR_LOOP_ID,
    )

  override val slot: PhaseSlot = PhaseSlot.AUDIT
  override val strategyId: String = ID
  override val semanticRevision: Int = AuditPlanningExecutionPlanMapping.SEMANTIC_REVISION
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT

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
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX ->
        AuditPlanFixPromptSections.sections(inputs.currentAcceptanceCriterion, inputs.priorAcceptanceRepairFailures)
      else -> AuditImplementFixPromptSections.sections(inputs)
    }
  }

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> {
    policies.policyOf(stepId)
    return when (stepId) {
      entryStep ->
        PhaseStepDescriptor(
          PhaseBindingFamily.QaFindings,
          AcceptanceAuditRound,
          AcceptanceAuditLoopRules,
        ) { run, state -> AcceptanceQaReaudit.settledWithoutScope(run, state) ?: runAgentStep(run, state) }
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX ->
        PhaseStepDescriptor(PhaseBindingFamily.Agent, PhaseStepHooks.None) { run, state -> runAgentStep(run, state) }
      else ->
        PhaseStepDescriptor(PhaseBindingFamily.Agent, AuditImplementFixStep) { run, state -> runAgentStep(run, state) }
    }
  }

  override fun verdictRule(
    stepId: String,
    diagnostics: RuntimeDiagnostics,
  ): FeatureTaskRuntimeStepVerdictRule? {
    policies.policyOf(stepId)
    return if (stepId == entryStep) AcceptanceAuditVerdictRule(diagnostics) else null
  }

  public companion object {
    public const val ID: String = "acceptance-audit"
  }
}

private val ACCEPTANCE_AUDIT_POLICIES: Map<String, PhaseStepPolicy> =
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

private object AcceptanceAuditCapabilities :
  PhaseLoopCapability,
  PhaseRecordedPlanMappingCapability,
  PhaseResumeCapability,
  PhaseStatusCapability {
  override val loopRules: PhaseLoopRules = AcceptanceAuditLoopRules

  override fun mapRecordedExecutionPlan(
    owner: PhaseStrategy,
    recorded: ResolvedPhaseExecutionPlan,
    current: ResolvedPhaseExecutionPlan,
  ): PhaseExecutionPlanMapping? = AuditPlanningExecutionPlanMapping.map(recorded, current, owner)

  override fun resumeRules(stepId: String): PhaseResumeRules {
    ACCEPTANCE_AUDIT_POLICIES.policyOf(stepId)
    return if (stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      AcceptanceAuditResumeRules
    } else {
      PhaseResumeRules.None
    }
  }

  override fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? = auditCurrentExecution(stepId, context)
}
