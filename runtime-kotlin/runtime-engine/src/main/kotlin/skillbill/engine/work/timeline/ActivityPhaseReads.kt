package skillbill.engine.work.timeline

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.work.timeline.model.WorkActivityPhase
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.UnitOfWork
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.phaseRecords
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry

@Inject
public class ActivityPhaseReads {
  public fun phases(
    workflowId: String,
    unit: UnitOfWork,
  ): List<WorkActivityPhase> {
    val ledger = ledger(workflowId, unit)
    if (ledger.isEmpty()) return emptyList()
    val records =
      unit.workflowStates.readFeatureTaskArtifacts(
        workflowId,
        setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS),
      )?.phaseRecords().orEmpty()
    val settlements =
      ledger.map { it.phaseId }.distinct().associateWith { phase ->
        unit.featureTaskPhaseSettlements.history(workflowId, phase).associateBy { it.attempt }
      }
    return ledger.filter {
      it.action == FeatureTaskRuntimePhaseLedgerAction.START &&
        it.executionOrigin == FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED
    }.map { start ->
      val finish =
        ledger.firstOrNull {
          it.sequenceNumber > start.sequenceNumber && it.phaseId == start.phaseId &&
            it.attemptCount == start.attemptCount &&
            it.action in endActions
        }
      val record = records[start.phaseId]?.takeIf { it.attemptCount == start.attemptCount }
      val envelope =
        settlements[start.phaseId]?.get(start.attemptCount)?.let { settlement ->
          JsonCodec.parseObjectOrNull(settlement.envelopeJson)?.let(JsonCodec::jsonElementToValue)
            ?.let(JsonCodec::anyToStringAnyMap)
            ?: throw invalidWorkflowStateSchemaError("Invalid phase settlement for $workflowId/${start.phaseId}.")
        }
      val model = record?.launchedModel?.takeIf { modelMatchesAgent(start.resolvedAgentId, it) }
      WorkActivityPhase(
        start.phaseId, start.attemptCount, start.timestamp, finish?.timestamp,
        start.resolvedAgentId, model, record?.launchedEffort?.takeIf { model != null },
        envelope?.get(SharedPayloadKeys.SUMMARY) as? String,
        envelope?.get(SharedPayloadKeys.VERDICT) as? String,
      )
    }.sortedBy { it.startedAt }
  }

  private fun ledger(
    workflowId: String,
    unit: UnitOfWork,
  ): List<FeatureTaskRuntimePhaseLedgerEntry> =
    buildList {
      var offset: Int? = 0
      do {
        val page =
          unit.workflowStates.readFeatureTaskPhaseLedgerPage(
            workflowId,
            requireNotNull(offset),
            WorkPaginationLimits.MAX_PAGE_SIZE,
          ) ?: break
        addAll(page.entries)
        offset = page.nextOffset
      } while (offset != null)
    }
}

internal fun modelMatchesAgent(
  agent: String?,
  model: String,
): Boolean =
  when (agent) {
    "claude" -> model.startsWith("claude")
    "codex" -> model.startsWith("gpt")
    else -> false
  }

private val endActions =
  setOf(
    FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
    FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
    FeatureTaskRuntimePhaseLedgerAction.PAUSED,
  )
