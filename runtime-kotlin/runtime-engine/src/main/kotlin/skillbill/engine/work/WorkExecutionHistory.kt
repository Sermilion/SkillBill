package skillbill.engine.work

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.work.pagination.WorkDetailPagePosition
import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkExecutionInstance
import skillbill.ports.persistence.UnitOfWork

internal fun phaseGroupIdentity(record: StandalonePhaseStatusRecord): Triple<String, String, String> =
  Triple(
    record.repositoryIdentity,
    record.phaseId,
    record.issueKey?.uppercase() ?: record.workflowId ?: record.invocationId,
  )

internal fun latestPhaseExecutions(records: List<StandalonePhaseStatusRecord>): List<StandalonePhaseStatusRecord> =
  records.groupBy(::phaseGroupIdentity).values.map { instances ->
    instances.maxWith(compareBy<StandalonePhaseStatusRecord> { it.runSequence.length }.thenBy { it.runSequence })
  }

internal fun workExecutionHistory(
  unitOfWork: UnitOfWork,
  standalone: StandalonePhaseStatusRecord?,
  workflowId: String,
  page: WorkDetailPagePosition,
  limit: Int,
): List<WorkExecutionInstance> =
  if (standalone != null) {
    unitOfWork.standalonePhaseStatuses.readPhaseExecutions(standalone.repositoryIdentity, standalone.phaseId)
      .filter { phaseGroupIdentity(it) == phaseGroupIdentity(standalone) }
      .filter { !it.startedAt.isAfter(page.observedAt) }
      .drop(page.offset).take(limit)
      .map {
        WorkExecutionInstance(
          it.invocationId,
          it.executionId,
          it.lifecycleState,
          it.startedAt,
          it.updatedAt,
          it.finishedAt,
          restoredExecutionReason(it, unitOfWork),
        )
      }
  } else {
    unitOfWork.standalonePhaseStatuses.readWorkflowExecutions(workflowId)
      .filter { !it.startedAt.isAfter(page.observedAt) }
      .drop(page.offset).take(limit).map {
        WorkExecutionInstance(
          it.invocationId,
          it.executionId,
          it.lifecycleState,
          it.startedAt,
          it.updatedAt,
          null,
          null,
        )
      }
  }

internal fun restoredExecutionReason(
  record: StandalonePhaseStatusRecord,
  unitOfWork: UnitOfWork,
): String? {
  val stored = record.terminalResult ?: return null
  val workflowId = record.workflowId ?: return stored
  if (!stored.endsWith("...")) return stored
  val prefix = stored.removeSuffix("...")
  return unitOfWork.featureTaskPhaseSettlements.history(workflowId, record.phaseId).firstNotNullOfOrNull { settlement ->
    val envelope =
      JsonCodec.parseObjectOrNull(settlement.envelopeJson)
        ?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)
        ?: throw invalidIdeStatusSchemaError(
          "phase settlement $workflowId",
          "envelope_json",
          "Invalid settlement JSON.",
        )
    val summary = envelope[SharedPayloadKeys.SUMMARY] as? String ?: return@firstNotNullOfOrNull null
    if (!stored.endsWith(summary)) return@firstNotNullOfOrNull null
    val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
    val full = produced?.get(SharedPayloadKeys.VALUE) as? String ?: return@firstNotNullOfOrNull null
    val restored = stored.removeSuffix(summary) + full
    restored.takeIf { it.startsWith(prefix) }
  } ?: stored
}
