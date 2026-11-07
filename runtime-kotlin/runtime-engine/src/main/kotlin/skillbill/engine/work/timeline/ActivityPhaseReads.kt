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
import java.time.Instant

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
    return attemptOpenings(ledger).map { start ->
      val record = records[start.phaseId]?.takeIf { it.attemptCount == start.attemptCount }
      val envelope =
        settlements[start.phaseId]?.get(start.attemptCount)?.let { settlement ->
          JsonCodec.parseObjectOrNull(settlement.envelopeJson)?.let(JsonCodec::jsonElementToValue)
            ?.let(JsonCodec::anyToStringAnyMap)
            ?: throw invalidWorkflowStateSchemaError("Invalid phase settlement for $workflowId/${start.phaseId}.")
        }
      val model = record?.launchedModel?.takeIf { modelMatchesAgent(start.resolvedAgentId, it) }
      WorkActivityPhase(
        start.phaseId, start.attemptCount, start.timestamp, attemptFinishedAt(start, ledger),
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
    "cursor" -> model.startsWith("grok") || model.startsWith("cursor-")
    else -> false
  }

private fun attemptOpenings(
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): List<FeatureTaskRuntimePhaseLedgerEntry> {
  val opened = mutableSetOf<Pair<String, Int>>()
  return ledger.filter { it.executionOrigin == FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED }
    .sortedBy { it.sequenceNumber }
    .filter { entry ->
      val firstForAttempt = entry.action in openingActions && opened.add(entry.phaseId to entry.attemptCount)
      entry.action == FeatureTaskRuntimePhaseLedgerAction.START || firstForAttempt
    }
}

private fun attemptFinishedAt(
  start: FeatureTaskRuntimePhaseLedgerEntry,
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
): Instant? {
  val later = ledger.filter { it.sequenceNumber > start.sequenceNumber && it.phaseId == start.phaseId }
  val terminal = later.firstOrNull { it.attemptCount == start.attemptCount && it.action in endActions }
  val superseded = later.any { it.attemptCount > start.attemptCount && it.action in openingActions }
  return terminal?.timestamp ?: start.timestamp.takeIf { superseded }
}

private val openingActions =
  setOf(
    FeatureTaskRuntimePhaseLedgerAction.START,
    FeatureTaskRuntimePhaseLedgerAction.RESUME,
  )

private val endActions =
  setOf(
    FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
    FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
    FeatureTaskRuntimePhaseLedgerAction.PAUSED,
  )
