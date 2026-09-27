package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.lifecycle.continuation.GOAL_PLANNING_IMPORT_AGENT_SENTINEL
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

val PHASE_TERMINAL_STATUSES: Set<WorkflowStepStatus> =
  setOf(
    WorkflowStepStatus.COMPLETED,
    WorkflowStepStatus.BLOCKED,
  )
val CONTINUATION_KIND_ACTIONS =
  setOf(
    FeatureTaskRuntimePhaseLedgerAction.START,
    FeatureTaskRuntimePhaseLedgerAction.RESUME,
    FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
    FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
  )
val OPERATOR_DECISION_QUALITY_GATE_PHASE_IDS: Set<String> = PhaseSlot.QUALITY_GATE.steps.toSet()

fun phaseStatuses(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  blockedPhaseIds: Set<String>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): List<FeatureTaskRuntimePhaseStatus> =
  FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.map { phaseId ->
    records[phaseId].toPhaseStatus(
      phaseId = phaseId,
      blocked = phaseId in blockedPhaseIds,
      continuationKind = latestContinuationKind(ledger, phaseId),
    )
  }

fun resolveCurrentPhaseId(
  terminalDecomposeRecorded: Boolean,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  phases: List<FeatureTaskRuntimePhaseStatus>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  loopOnlyStepIds: Set<String>,
): String? {
  if (terminalDecomposeRecorded) return null
  return currentReentryPhaseId(records, ledger) ?: phases.firstOrNull {
    it.status.workflowStepStatus() != WorkflowStepStatus.COMPLETED &&
      !shouldSkipPendingLoopOnlyPhase(it.phaseId, it.status, loopOnlyStepIds)
  }?.phaseId
}

fun shouldSkipPendingLoopOnlyPhase(
  phaseId: String,
  status: String,
  loopOnlyStepIds: Set<String>,
): Boolean = status.workflowStepStatus() == WorkflowStepStatus.PENDING && phaseId in loopOnlyStepIds

fun currentReentryPhaseId(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): String? {
  val edge =
    FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges
      .mapNotNull { declaration ->
        ledger
          .filter {
            it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == declaration.loopId
          }
          .maxByOrNull { it.sequenceNumber }
          ?.let { declaration to it }
      }
      .maxByOrNull { (_, entry) -> entry.sequenceNumber }
      ?: return null
  val (declaration, edgeEntry) = edge
  val destinationIndex =
    FeatureTaskRuntimePhaseWorkflowDefinition.transitions.forwardPhaseIds
      .indexOf(declaration.destinationPhaseId)
  val sourceIndex =
    FeatureTaskRuntimePhaseWorkflowDefinition.transitions.forwardPhaseIds
      .indexOf(declaration.fromPhaseId)
  val reopenedSpan =
    when {
      destinationIndex <= sourceIndex ->
        FeatureTaskRuntimePhaseWorkflowDefinition.transitions.forwardPhaseIds
          .subList(destinationIndex, sourceIndex + 1)
      else ->
        listOf(FeatureTaskRuntimePhaseWorkflowDefinition.transitions.forwardPhaseIds[destinationIndex])
    }
  val completedAfterEdge =
    ledger
      .asSequence()
      .filter { it.sequenceNumber > edgeEntry.sequenceNumber }
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
      .map { it.phaseId }
      .filter { phaseId -> records[phaseId]?.status?.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
      .toMutableSet()
  records.values
    .filter {
      it.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED &&
        it.loopId == declaration.loopId &&
        it.edgeIteration == edgeEntry.edgeIteration
    }
    .mapTo(completedAfterEdge) { it.phaseId }
  return reopenedSpan.firstOrNull { it !in completedAfterEdge }
}

fun operatorDecisionPause(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
): FeatureTaskRuntimeOperatorDecisionPause? =
  records.values
    .firstOrNull { record ->
      record.failureDisposition == FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION &&
        when (record.status.workflowStepStatus()) {
          WorkflowStepStatus.PAUSED -> true
          WorkflowStepStatus.BLOCKED ->
            record.phaseId in OPERATOR_DECISION_QUALITY_GATE_PHASE_IDS &&
              !record.blockedReason.isNullOrBlank()
          else -> false
        }
    }
    ?.let { record ->
      FeatureTaskRuntimeOperatorDecisionPause(
        phaseId = record.phaseId,
        reason = record.blockedReason?.takeIf(String::isNotBlank),
      )
    }

fun latestContinuationKind(
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  phaseId: String,
): String? =
  ledger
    .filter { it.phaseId == phaseId && it.action in CONTINUATION_KIND_ACTIONS }
    .sortedByDescending { it.sequenceNumber }
    .firstNotNullOfOrNull { FeatureTaskRuntimeContinuationKind.fromLedgerDetail(it.blockedReason) }
    ?.wireValue

fun FeatureTaskRuntimePhaseRecord?.toPhaseStatus(
  phaseId: String,
  blocked: Boolean,
  continuationKind: String? = null,
): FeatureTaskRuntimePhaseStatus =
  if (this == null) {
    FeatureTaskRuntimePhaseStatus(
      phaseId = phaseId,
      status = if (blocked) WorkflowStepStatus.BLOCKED.wireValue else WorkflowStepStatus.PENDING.wireValue,
      attemptCount = 0,
      resolvedAgentId = null,
      finished = false,
      continuationKind = continuationKind,
    )
  } else {
    FeatureTaskRuntimePhaseStatus(
      phaseId = phaseId,
      status =
        if (blocked && status != WorkflowStepStatus.COMPLETED) {
          WorkflowStepStatus.BLOCKED.wireValue
        } else {
          status.wireValue
        },
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId.takeUnless { it == GOAL_PLANNING_IMPORT_AGENT_SENTINEL },
      finished = finishedAt != null,
      executionOrigin = executionOrigin.wireValue,
      continuationKind = continuationKind,
      launchedModel = launchedModel,
      launchedEffort = launchedEffort,
    )
  }
