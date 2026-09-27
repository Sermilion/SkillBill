package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.lifecycle.continuation.agentAttributionFromPhaseState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeDecomposeTerminalStatus
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeDegradedDiagnosticStatus
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusProjection
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionDeriver
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunStateReconstruction
import skillbill.engine.featuretask.runloop.durable.LEGACY_QUALITY_GATE_SELECTION
import skillbill.engine.featuretask.slot.PhaseReportedGate
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodeValidationGateExecutionEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.phase.task.SkeletonDefinition

@Inject
class FeatureTaskRuntimeStatusService(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  private val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  internal val strategies: PhaseStrategyLookup,
) {
  val currentPhaseExecutionDeriver = FeatureTaskRuntimeCurrentPhaseExecutionDeriver(strategies)

  fun status(request: FeatureTaskRuntimeStatusRequest): FeatureTaskRuntimeStatusProjection? {
    val records = recorder.loadPhaseRecords(request.workflowId) ?: return null
    val decomposeTerminal = decomposeTerminalRecorder.loadDecomposeTerminal(request.workflowId)
    val ledger = recorder.loadPhaseLedger(request.workflowId).orEmpty()
    return buildStatusProjection(request, records, decomposeTerminal, ledger)
  }

  fun ledgerBlockedPhaseIds(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    durableBlockedPhaseIds: Set<String>,
  ): Set<String> =
    ledger
      .groupBy { it.phaseId }
      .filterKeys { it !in durableBlockedPhaseIds }
      .filterValues { entries ->
        entries.maxByOrNull { it.sequenceNumber }?.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
      }
      .keys
}

fun FeatureTaskRuntimeStatusService.buildStatusProjection(
  request: FeatureTaskRuntimeStatusRequest,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  decomposeTerminal: FeatureTaskRuntimeDecomposeTerminal?,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): FeatureTaskRuntimeStatusProjection {
  val selectionFacts = statusSelectionFacts(request)
  val statelessAuditInputs =
    FeatureTaskRuntimeRunStateReconstruction.normalizeForStatelessAudit(
      records,
      ledger,
      strategies.resumeRules(selectionFacts),
    )
  val normalizedRecords = statelessAuditInputs.records
  val normalizedLedger = statelessAuditInputs.ledger
  val durableBlockedPhaseIds =
    normalizedRecords
      .filterValues { record ->
        record.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED
      }
      .keys
  val blockedPhaseIds =
    (
      durableBlockedPhaseIds +
        ledgerBlockedPhaseIds(normalizedLedger, durableBlockedPhaseIds)
    ).toSet()
  val phases = phaseStatuses(normalizedRecords, blockedPhaseIds, normalizedLedger)
  val terminalDecomposeRecorded = decomposeTerminal != null
  val currentPhaseId =
    resolveCurrentPhaseId(
      terminalDecomposeRecorded,
      normalizedRecords,
      phases,
      normalizedLedger,
      currentPhaseExecutionDeriver.loopOnlyStepIds(selectionFacts),
    )
  val gateRunCount = gateRunCountFor(request, currentPhaseId)
  return statusProjectionFrom(
    StatusProjectionParts(
      request = request,
      phases = phases,
      terminalDecomposeRecorded = terminalDecomposeRecorded,
      currentPhaseId = currentPhaseId,
      gateRunCount = gateRunCount,
      records = normalizedRecords,
      ledger = normalizedLedger,
      decomposeTerminal = decomposeTerminal,
      selectionFacts = selectionFacts,
    ),
  )
}

private fun FeatureTaskRuntimeStatusService.statusSelectionFacts(
  request: FeatureTaskRuntimeStatusRequest,
): PhaseStrategySelectionFacts {
  val continuation = recorder.loadGoalContinuation(request.workflowId)
  return PhaseStrategySelectionFacts(
    SkeletonDefinition.forRun(goalContinuation = continuation != null),
    setOfNotNull(
      runInvariantsStore.resolve(request.workflowId)?.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT,
      continuation?.let { it.qualityGateSelection ?: LEGACY_QUALITY_GATE_SELECTION },
    ),
  )
}

private data class StatusProjectionParts(
  val request: FeatureTaskRuntimeStatusRequest,
  val phases: List<FeatureTaskRuntimePhaseStatus>,
  val terminalDecomposeRecorded: Boolean,
  val currentPhaseId: String?,
  val gateRunCount: Int?,
  val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val decomposeTerminal: FeatureTaskRuntimeDecomposeTerminal?,
  val selectionFacts: PhaseStrategySelectionFacts,
)

private fun FeatureTaskRuntimeStatusService.statusProjectionFrom(
  parts: StatusProjectionParts,
): FeatureTaskRuntimeStatusProjection {
  val request = parts.request
  val phases = parts.phases
  val terminalDecomposeRecorded = parts.terminalDecomposeRecorded
  return FeatureTaskRuntimeStatusProjection(
    workflowId = request.workflowId,
    featureSize = runInvariantsStore.resolve(request.workflowId)?.featureSize?.name,
    phases = phases,
    completeCount = phases.count { it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED },
    pendingCount =
      if (terminalDecomposeRecorded) {
        0
      } else {
        phases.count {
          it.status.workflowStepStatus()?.let(PHASE_TERMINAL_STATUSES::contains) != true
        }
      },
    blockedCount =
      if (terminalDecomposeRecorded) {
        0
      } else {
        phases.count { it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED }
      },
    currentPhaseId = parts.currentPhaseId,
    resolvedBranch = recorder.loadResolvedBranch(request.workflowId)?.branch,
    finalizingAgentId =
      agentAttributionFromPhaseState(
        recorder.phaseQuery,
        request.workflowId,
      ).finalizingAgentId,
    decomposeTerminal = decomposeTerminalStatus(parts.decomposeTerminal),
    gateRunCount = parts.gateRunCount,
    validationGateExecutionEvidence =
      strategies.stepReporting(PhaseReportedGate.VALIDATION)
        ?.let(parts.records::get)
        ?.let(::validationGateExecutionEvidence),
    currentPhaseExecution =
      currentPhaseExecutionDeriver.derive(
        FeatureTaskRuntimeCurrentPhaseExecutionContext(
          currentPhaseId = parts.currentPhaseId,
          records = parts.records,
          phases = parts.phases,
          ledger = parts.ledger,
          gateRunCount = parts.gateRunCount,
        ),
        parts.selectionFacts,
      ),
    degradedDiagnostic = degradedDiagnosticStatus(request.workflowId),
    operatorDecisionPause = operatorDecisionPause(parts.records),
  )
}

internal fun validationGateExecutionEvidence(
  validationRecord: FeatureTaskRuntimePhaseRecord,
): FeatureTaskRuntimeValidationGateExecutionEvidence? =
  validationRecord.outputArtifact
    ?.let(JsonCodec::parseObjectOrNull)
    ?.let(JsonCodec::jsonElementToValue)
    ?.let(JsonCodec::anyToStringAnyMap)
    ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
    ?.let(JsonCodec::anyToStringAnyMap)
    ?.get(ValidationEvidencePayloadKeys.VALIDATION_RESULT)
    ?.let(JsonCodec::anyToStringAnyMap)
    ?.let { raw ->
      if (
        !raw.containsKey(ValidationEvidencePayloadKeys.GATE_RUN_COUNT) &&
        !raw.containsKey(ValidationEvidencePayloadKeys.GATE_RUNS)
      ) {
        null
      } else {
        decodeValidationGateExecutionEvidenceFromArtifact(raw, "workflow-status.validate")
      }
    }

private fun FeatureTaskRuntimeStatusService.gateRunCountFor(
  request: FeatureTaskRuntimeStatusRequest,
  currentPhaseId: String?,
): Int? {
  val validationGateRunCount =
    recorder.loadValidationGateProgress(request.workflowId)
      ?.gateRunCount
  val buildGateRunCount =
    recorder.loadBuildGateProgress(request.workflowId)
      ?.gateRunCount
  return when (strategies.gateReportedBy(currentPhaseId)) {
    PhaseReportedGate.BUILD -> buildGateRunCount
    PhaseReportedGate.VALIDATION -> validationGateRunCount
    null -> validationGateRunCount ?: buildGateRunCount
  }
}

fun FeatureTaskRuntimeStatusService.degradedDiagnosticStatus(
  workflowId: String,
): FeatureTaskRuntimeDegradedDiagnosticStatus? {
  val diagnosticSignals = recorder.loadDiagnosticSignals(workflowId)
  val latest = diagnosticSignals.lastOrNull() ?: return null
  return FeatureTaskRuntimeDegradedDiagnosticStatus(
    count = diagnosticSignals.size,
    failureClass = latest.failureClass.wireValue,
    phaseId = latest.phaseId,
    attempt = latest.attempt,
  )
}

fun FeatureTaskRuntimeStatusService.decomposeTerminalStatus(
  terminal: FeatureTaskRuntimeDecomposeTerminal?,
): FeatureTaskRuntimeDecomposeTerminalStatus? =
  terminal?.let {
    FeatureTaskRuntimeDecomposeTerminalStatus(
      reason = it.reason,
      parentSpecPath = it.parentSpecPath,
      decompositionManifestPath = it.decompositionManifestPath,
      subtaskSpecPaths = it.subtaskSpecPaths,
    )
  }
