package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.lifecycle.continuation.agentAttributionFromPhaseState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStatusRead
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeDecomposeTerminalStatus
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeDegradedDiagnosticStatus
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusProjection
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.runloop.durable.LEGACY_QUALITY_GATE_SELECTION
import skillbill.engine.featuretask.slot.PhaseReportedGate
import skillbill.engine.featuretask.slot.state.PhaseHistoricalInterpreter
import skillbill.engine.featuretask.slot.state.PhaseHistoricalPolicy
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodeValidationGateExecutionEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.phase.task.withoutPlanSubtaskUnitRecords

private const val MAX_INLINE_CRITERION_ALIAS = 32
private const val PRE_CONTRACT_REPAIR_EXPLANATION =
  "Acceptance repair state predates per-criterion repair and cannot resume; abandon this run or reset its subtask"

@Inject
class FeatureTaskRuntimeStatusService(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  private val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
) {
  internal val history = PhaseHistoricalInterpreter(PhaseHistoricalPolicy.REVISION_1)

  fun status(request: FeatureTaskRuntimeStatusRequest): FeatureTaskRuntimeStatusProjection? {
    val records = recorder.loadPhaseRecords(request.workflowId)?.withoutPlanSubtaskUnitRecords() ?: return null
    val decomposeTerminal = decomposeTerminalRecorder.loadDecomposeTerminal(request.workflowId)
    val ledger = statusPhaseHistory(recorder.phaseQuery, request.workflowId, records, history)
    val workflowCurrentStepId = recorder.phaseQuery.currentStepId(request.workflowId)
    return buildStatusProjection(request, records, decomposeTerminal, ledger, workflowCurrentStepId)
  }

  fun phasePage(
    workflowId: String,
    afterPhaseId: String?,
    limit: Int,
    offset: Int = 0,
  ): FeatureTaskRuntimePhasePage? {
    val projection = status(FeatureTaskRuntimeStatusRequest(workflowId)) ?: return null
    val phaseIds = projection.phases.map { it.phaseId }
    val start =
      if (afterPhaseId == null) {
        offset.coerceIn(0, phaseIds.size)
      } else {
        phaseIds.indexOf(afterPhaseId).let { if (it < 0) phaseIds.size else it + 1 }
      }
    val end = minOf(start + limit, phaseIds.size)
    return FeatureTaskRuntimePhasePage(
      status = projection.copy(phases = projection.phases.subList(start, end)),
      phaseIds = phaseIds,
      hasMore = end < phaseIds.size,
    )
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

  fun buildStatusProjection(
    request: FeatureTaskRuntimeStatusRequest,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    decomposeTerminal: FeatureTaskRuntimeDecomposeTerminal?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    workflowCurrentStepId: String?,
  ): FeatureTaskRuntimeStatusProjection {
    val statelessAuditInputs =
      history.normalize(
        records,
        ledger,
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
    val goalContinuation = recorder.loadGoalContinuation(request.workflowId)
    val hiddenPhaseIds = goalChildHiddenPhaseIds(goalContinuation != null, normalizedRecords.keys)
    val phases =
      phaseStatuses(normalizedRecords, blockedPhaseIds, normalizedLedger).filterNot { it.phaseId in hiddenPhaseIds }
    val terminalDecomposeRecorded = decomposeTerminal != null
    val currentPhaseId =
      resolveCurrentPhaseId(
        terminalDecomposeRecorded,
        normalizedRecords,
        phases,
        normalizedLedger,
        FeatureTaskRuntimePhaseWorkflowQueries.loopOnlyPhaseIds(
          goalContinuation?.qualityGateSelection ?: LEGACY_QUALITY_GATE_SELECTION,
        ) - setOfNotNull(workflowCurrentStepId),
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
      ),
    )
  }

  private fun statusProjectionFrom(parts: StatusProjectionParts): FeatureTaskRuntimeStatusProjection {
    val request = parts.request
    val phases = parts.phases
    val terminalDecomposeRecorded = parts.terminalDecomposeRecorded
    val acceptanceRepair = workflowPersistence.readAcceptanceCriterionRepairStatus(request.workflowId)
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
          includeParticipatingAgents = false,
        ).finalizingAgentId,
      decomposeTerminal = decomposeTerminalStatus(parts.decomposeTerminal),
      gateRunCount = parts.gateRunCount,
      validationGateExecutionEvidence =
        history.stepReporting(PhaseReportedGate.VALIDATION)
          .let(parts.records::get)
          ?.let(::validationGateExecutionEvidence),
      currentPhaseExecution =
        history.currentExecution(
          FeatureTaskRuntimeCurrentPhaseExecutionContext(
            currentPhaseId = parts.currentPhaseId,
            records = parts.records,
            phases = parts.phases,
            ledger = parts.ledger,
            gateRunCount = parts.gateRunCount,
            acceptanceCriterionRepair = (acceptanceRepair as? AcceptanceCriterionRepairStatusRead.Current)?.state,
          ),
        ),
      degradedDiagnostic = degradedDiagnosticStatus(request.workflowId),
      operatorDecisionPause = operatorDecisionPause(parts.records),
      criterionExplanation =
        when (val repair = acceptanceRepair) {
          null -> null
          is AcceptanceCriterionRepairStatusRead.Current -> acceptanceCriterionRepairExplanation(repair.state)
          AcceptanceCriterionRepairStatusRead.PreContract -> PRE_CONTRACT_REPAIR_EXPLANATION
        },
    )
  }

  private fun acceptanceCriterionRepairExplanation(state: AcceptanceCriterionRepairState): String {
    val current = state.currentCriterionRef?.let { ref -> state.criteria.firstOrNull { it.canonicalRef == ref } }
    val prefix =
      listOfNotNull(
        current?.let { criterionBudgetExplanation(state, it.canonicalRef, it.originalAlias) },
        criterionRepairStageExplanation(state),
        "Source acceptance is separate from later tests, build, and project-quality checks",
      ).joinToString("; ")
    val gap = current?.evidenceRef?.takeUnless { it == state.specDigest }
    val detail =
      listOfNotNull(
        current?.originalAlias?.takeIf { it.length > MAX_INLINE_CRITERION_ALIAS }?.let { "Authored alias: $it" },
        gap?.let { "Latest gap: $it" },
        current?.originalText,
        state.pendingClarificationQuestion?.let { "Clarification question: $it" },
      )
    return listOfNotNull(prefix, detail.takeIf { it.isNotEmpty() }?.joinToString(". ")).joinToString(". ")
  }

  private fun criterionBudgetExplanation(
    state: AcceptanceCriterionRepairState,
    criterionRef: String,
    originalAlias: String?,
  ): String {
    val failures = state.unsuccessfulTotals.getOrDefault(criterionRef, 0)
    val remaining = (AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES - failures).coerceAtLeast(0)
    val authoredQueue =
      state.criteria
        .filter { it.canonicalRef in state.pendingAuditRefs || it.canonicalRef in state.unresolvedQueue }
        .sortedBy { it.ordinal }
    val queuePosition = authoredQueue.indexOfFirst { it.canonicalRef == criterionRef }.takeIf { it >= 0 }?.plus(1)
    val alias = originalAlias?.takeIf { it.length <= MAX_INLINE_CRITERION_ALIAS }
    return listOfNotNull(
      "Criterion $criterionRef${alias?.let { " ($it)" }.orEmpty()}",
      "$failures failures used",
      "$remaining failures remaining",
      queuePosition?.let { "queue position $it of ${authoredQueue.size}" },
    ).joinToString("; ")
  }

  private fun criterionRepairStageExplanation(state: AcceptanceCriterionRepairState): String =
    if (state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY) {
      "Initial full source discovery"
    } else {
      when (state.stage) {
        AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED -> "Focused source assessment selected"
        AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED -> "Read-only source audit or clarification required"
        AcceptanceCriterionRepairStage.REPAIR_RESERVED -> "Focused repair cycle reserved"
        AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ->
          "Repair completed; targeted verification required"
        AcceptanceCriterionRepairStage.ACCEPTED -> "Every acceptance criterion accepted; source acceptance recorded"
        AcceptanceCriterionRepairStage.BUDGET_BLOCKED -> "Criterion exhausted; source repair cannot advance"
      }
    }

  private fun gateRunCountFor(
    request: FeatureTaskRuntimeStatusRequest,
    currentPhaseId: String?,
  ): Int? {
    val validationGateRunCount =
      recorder.loadValidationGateProgress(request.workflowId)
        ?.gateRunCount
    val buildGateRunCount =
      recorder.loadBuildGateProgress(request.workflowId)
        ?.gateRunCount
    return when (history.gateReportedBy(currentPhaseId)) {
      PhaseReportedGate.BUILD -> buildGateRunCount
      PhaseReportedGate.VALIDATION -> validationGateRunCount
      null -> validationGateRunCount ?: buildGateRunCount
    }
  }

  fun degradedDiagnosticStatus(workflowId: String): FeatureTaskRuntimeDegradedDiagnosticStatus? {
    val diagnosticSignals = recorder.loadDiagnosticSignals(workflowId)
    val latest = diagnosticSignals.lastOrNull() ?: return null
    return FeatureTaskRuntimeDegradedDiagnosticStatus(
      count = diagnosticSignals.size,
      failureClass = latest.failureClass.wireValue,
      phaseId = latest.phaseId,
      attempt = latest.attempt,
    )
  }

  fun decomposeTerminalStatus(
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
}

data class FeatureTaskRuntimePhasePage(
  val status: FeatureTaskRuntimeStatusProjection,
  val phaseIds: List<String>,
  val hasMore: Boolean,
)

private data class StatusProjectionParts(
  val request: FeatureTaskRuntimeStatusRequest,
  val phases: List<FeatureTaskRuntimePhaseStatus>,
  val terminalDecomposeRecorded: Boolean,
  val currentPhaseId: String?,
  val gateRunCount: Int?,
  val records: Map<String, FeatureTaskRuntimePhaseRecord>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val decomposeTerminal: FeatureTaskRuntimeDecomposeTerminal?,
)

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
