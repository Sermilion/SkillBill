package skillbill.engine.featuretask.persist.acceptance

import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal fun FeatureTaskPhaseSettlementService.restoreAcceptanceRepairResumeEvidence(
  request: FeatureTaskRuntimeRunRequest,
  steps: AcceptanceCriterionRepairStepRoles?,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimePhaseOutput? {
  val state = request.acceptanceCriterionRepair?.snapshot
  val phaseId =
    steps?.audit?.takeIf {
      state?.mode == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED &&
        state.stage == AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED
    } ?: return null
  val record = records[phaseId]?.takeIf { it.outputArtifact.isNullOrBlank() } ?: return null
  val completion =
    ledger.filter { it.phaseId == phaseId && it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
      .maxByOrNull { it.sequenceNumber }
      ?.takeIf { it.attemptCount == record.attemptCount }
      ?: return null
  val envelope = findEnvelope(request.workflowId, phaseId, completion.attemptCount)?.envelope
  val normalized =
    envelope?.let {
      NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(JsonCodec.mapToJsonString(it), phaseId)
    }
  return if (normalized == null || normalized.phaseId != phaseId ||
    normalized.status != WorkflowStepStatus.COMPLETED.wireValue
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Acceptance repair could not restore the completed $phaseId receipt for attempt ${completion.attemptCount}.",
    )
    null
  } else {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Acceptance repair restored $phaseId attempt ${completion.attemptCount} from settlement history " +
        "after its current output was cleared on resume.",
    )
    FeatureTaskRuntimePhaseOutput(
      phaseId = phaseId,
      iteration = completion.attemptCount,
      payload = normalized.canonicalJson,
      normalizedOutput = normalized,
      repairEvidence = record.repairEvidence,
    )
  }
}
