package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairCommit
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction

internal object AcceptanceCriterionRepairStartLedger {
  fun sameCommittedStart(
    artifacts: Map<String, Any?>,
    request: AcceptanceCriterionRepairCommit,
  ): Boolean {
    val phase = request.phase
    if (phase.status != WorkflowStepStatus.RUNNING.wireValue) return false
    val record = decodePhaseRecords(artifacts)[phase.phaseId] ?: return false
    val latest = decodePhaseLedger(artifacts).maxByOrNull { it.sequenceNumber } ?: return false
    val recordMatches =
      record.status == WorkflowStepStatus.RUNNING && record.attemptCount == phase.attemptCount &&
        record.resolvedAgentId == phase.resolvedAgentId
    val loopMatches =
      record.loopId == phase.loopId && record.edgeIteration == phase.edgeIteration &&
        latest.loopId == phase.loopId && latest.edgeIteration == phase.edgeIteration
    val startMatches =
      latest.action == FeatureTaskRuntimePhaseLedgerAction.START && latest.phaseId == phase.phaseId &&
        latest.attemptCount == phase.attemptCount && latest.resolvedAgentId == phase.resolvedAgentId
    return recordMatches && loopMatches && startMatches
  }

  fun supersededStart(
    artifacts: Map<String, Any?>,
    request: AcceptanceCriterionRepairCommit,
  ): Boolean {
    val phase = request.phase
    if (phase.status != WorkflowStepStatus.RUNNING.wireValue) return false
    val latest = decodePhaseRecords(artifacts)[phase.phaseId] ?: return false
    return latest.attemptCount > phase.attemptCount ||
      latest.attemptCount == phase.attemptCount &&
      (latest.status != WorkflowStepStatus.RUNNING || !sameCommittedStart(artifacts, request))
  }

  fun freshCurrentAuditDiscovery(
    record: WorkflowStateSnapshot,
    request: AcceptanceCriterionRepairCommit,
  ): Boolean {
    val discovery = request.mutation is AcceptanceCriterionRepairMutation.Discover && request.phase.attemptCount == 1
    val currentStep = record.workflowStatus == WorkflowStatus.RUNNING && !record.currentStepId.isNullOrBlank()
    val current = decodePhaseRecords(record.artifacts)[request.phase.phaseId]
    val currentPhaseOpen = current == null || current.attemptCount == 1 && current.finishedAt == null
    val stepOpen =
      record.steps.none {
        it.stepId == request.phase.phaseId &&
          (it.attemptCount > 1 || it.status !in setOf(WorkflowStepStatus.PENDING, WorkflowStepStatus.RUNNING))
      }
    val ledger = decodePhaseLedger(record.artifacts)
    val auditLedger = ledger.filter { it.phaseId == request.phase.phaseId }
    val currentLedger =
      auditLedger.all {
        it.attemptCount == 1 && it.action == FeatureTaskRuntimePhaseLedgerAction.START
      }
    val ledgerIsFresh = auditLedger.isEmpty() || currentLedger && auditLedger.size == 1
    return discovery && currentStep && currentPhaseOpen && stepOpen && ledgerIsFresh
  }
}
