package skillbill.engine.featuretask.slot.state

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.core.attemptPhaseExecution
import skillbill.engine.featuretask.phase.core.defaultPhaseExecution
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunStateReconstruction
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeStatelessAuditInputs
import skillbill.engine.featuretask.slot.PhaseReportedGate
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditResumeRules
import skillbill.engine.featuretask.slot.audit.auditCurrentExecution
import skillbill.engine.featuretask.slot.codereview.CodeReviewResumeRules
import skillbill.engine.featuretask.slot.codereview.history.CodeReviewHistory
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy.ImplementResumeRules
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy.PlanResumeRules
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateResumeRules
import skillbill.engine.featuretask.slot.qualitygate.gateCurrentExecution
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY

internal enum class PhaseHistoricalPolicy { REVISION_1 }

internal class PhaseHistoricalInterpreter(private val policy: PhaseHistoricalPolicy) {
  private val rules: Map<String, PhaseResumeRules> =
    when (policy) {
      PhaseHistoricalPolicy.REVISION_1 ->
        mapOf(
          PHASE_PREPLAN to PhaseResumeRules.None,
          PHASE_PLAN to PlanResumeRules,
          PHASE_IMPLEMENT to ImplementResumeRules,
          PHASE_SIMPLIFY to PhaseResumeRules.None,
          PHASE_AUDIT to AcceptanceAuditResumeRules,
          PHASE_AUDIT_PLAN_FIX to PhaseResumeRules.None,
          PHASE_AUDIT_IMPLEMENT_FIX to PhaseResumeRules.None,
          PHASE_REVIEW to CodeReviewResumeRules,
          PHASE_VERIFY_FINDINGS to PhaseResumeRules.None,
          PHASE_IMPLEMENT_FIX to PhaseResumeRules.None,
          PHASE_BUILD to PhaseResumeRules.None,
          PHASE_VALIDATE to AgentValidateResumeRules,
          PHASE_WRITE_HISTORY to PhaseResumeRules.None,
          PHASE_COMMIT_PUSH to PhaseResumeRules.None,
          PHASE_PR to PhaseResumeRules.None,
        )
    }

  fun resumeRules(stepId: String): PhaseResumeRules =
    rules[stepId]
      ?: throw InvalidPhaseStrategyCompositionError("no historical interpretation for $stepId under $policy")

  fun loopOnlyStepIds(gate: FeatureTaskRuntimeQualityGateSelection): Set<String> =
    if (gate == FeatureTaskRuntimeQualityGateSelection.BUILD) {
      setOf(PHASE_AUDIT_PLAN_FIX, PHASE_AUDIT_IMPLEMENT_FIX, PHASE_IMPLEMENT_FIX)
    } else {
      setOf(PHASE_AUDIT_PLAN_FIX, PHASE_AUDIT_IMPLEMENT_FIX, PHASE_IMPLEMENT_FIX, PHASE_BUILD)
    }

  fun gateReportedBy(stepId: String?): PhaseReportedGate? =
    when (stepId) {
      PHASE_BUILD -> PhaseReportedGate.BUILD
      PHASE_VALIDATE -> PhaseReportedGate.VALIDATION
      else -> null
    }

  fun stepReporting(gate: PhaseReportedGate): String =
    when (gate) {
      PhaseReportedGate.BUILD -> PHASE_BUILD
      PhaseReportedGate.VALIDATION -> PHASE_VALIDATE
    }

  fun normalize(
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): FeatureTaskRuntimeStatelessAuditInputs {
    val known =
      FeatureTaskRuntimeRunStateReconstruction.normalizeForStatelessAudit(
        records.filterKeys { it in rules },
        ledger.filter { it.phaseId in rules },
        ::resumeRules,
      )
    val retainedLedger = known.ledger.toSet()
    return FeatureTaskRuntimeStatelessAuditInputs(
      records.mapValues { (step, raw) -> known.records[step] ?: raw },
      ledger.filter { it.phaseId !in rules || it in retainedLedger },
    )
  }

  fun currentExecution(context: FeatureTaskRuntimeCurrentPhaseExecutionContext): IdeStatusCurrentPhaseExecution? {
    val stepId = context.currentPhaseId ?: return null
    if (context.phases.none { it.phaseId == stepId }) return null
    return when (stepId) {
      PHASE_REVIEW, PHASE_VERIFY_FINDINGS, PHASE_IMPLEMENT_FIX -> CodeReviewHistory.currentExecution(stepId, context)
      PHASE_AUDIT -> auditCurrentExecution(stepId, context)
      PHASE_BUILD, PHASE_VALIDATE -> gateCurrentExecution(stepId, context)
      else -> if (stepId in rules) defaultPhaseExecution(stepId, context) else attemptPhaseExecution(stepId, context)
    }
  }
}
