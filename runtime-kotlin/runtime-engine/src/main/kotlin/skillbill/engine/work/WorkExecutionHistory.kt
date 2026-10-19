package skillbill.engine.work

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.work.pagination.WorkDetailAnchor
import skillbill.engine.work.pagination.WorkDetailPagePosition
import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementReasonLookup
import skillbill.ports.idestatus.model.ExecutionReasonRestoration
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandalonePhaseExecutionQuery
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkExecutionInstance
import skillbill.ports.persistence.UnitOfWork
import java.time.Instant

private val historyLogger = System.getLogger("skillbill.execution-history")

internal fun phaseGroupIdentity(record: StandalonePhaseStatusRecord): Triple<String, String, String> =
  Triple(
    record.repositoryIdentity,
    record.phaseId,
    record.monitorTarget?.pullRequestKey
      ?: record.issueKey?.uppercase()
      ?: record.workflowId
      ?: record.branchCorrelation,
  )

internal fun StandalonePhaseStatusRecord.monitorTargetDetail(): String? =
  monitorTarget?.let { target ->
    listOfNotNull(
      "Pull request ${target.pullRequestUrl}",
      "repository ${target.pullRequestKey.substringBefore('#')}",
      "branch ${target.headRef} (${target.headRepository})",
      "invocation $invocationId",
      target.worktreePath?.let { "worktree $it" },
      monitorCleanup?.let { cleanup -> "cleanup ${cleanup.describe()}" },
    ).joinToString("; ", prefix = "[", postfix = "]")
  }

private fun StandaloneMonitorCleanup.describe(): String =
  when (this) {
    StandaloneMonitorCleanup.Released -> "released"
    is StandaloneMonitorCleanup.Retained -> "retained (${reason.wireValue}): $detail"
  }

internal fun latestPhaseExecutions(records: List<StandalonePhaseStatusRecord>): List<StandalonePhaseStatusRecord> =
  records.groupBy(::phaseGroupIdentity).values.map { instances ->
    instances.maxWith(
      compareBy<StandalonePhaseStatusRecord> { it.runSequence.length }
        .thenBy { it.runSequence }.thenBy { it.invocationId },
    )
  }

internal fun supersededPhase(
  record: StandalonePhaseStatusRecord?,
  unit: UnitOfWork,
  observedAt: Instant,
): Boolean {
  if (record == null) return false
  val latest =
    unit.standalonePhaseStatuses.readPhaseExecutions(
      StandalonePhaseExecutionQuery(
        record.repositoryIdentity,
        record.phaseId,
        phaseGroupIdentity(record).third,
        observedAt,
        0,
        1,
      ),
    ).firstOrNull()
  return latest != null && latest.invocationId != record.invocationId
}

internal fun workExecutionHistory(
  unitOfWork: UnitOfWork,
  standalone: StandalonePhaseStatusRecord?,
  workflowId: String,
  page: WorkDetailPagePosition,
  limit: Int,
): List<WorkExecutionInstance> {
  val anchor = (page.anchor as? WorkDetailAnchor.Execution)?.anchor
  val offset = if (anchor == null) page.offset else 0
  return if (standalone != null) {
    unitOfWork.standalonePhaseStatuses.readPhaseExecutions(
      StandalonePhaseExecutionQuery(
        standalone.repositoryIdentity,
        standalone.phaseId,
        phaseGroupIdentity(standalone).third,
        page.observedAt,
        offset,
        limit,
        anchor,
      ),
    )
      .map {
        val reason = resolveExecutionReason(it, unitOfWork)
        WorkExecutionInstance(
          it.invocationId,
          it.executionId,
          it.lifecycleState,
          it.startedAt,
          it.updatedAt,
          it.finishedAt,
          reason.text,
          reason.restoration,
          it.runSequence,
        )
      }
  } else {
    unitOfWork.standalonePhaseStatuses
      .readWorkflowExecutions(workflowId, page.observedAt, offset, limit, anchor)
      .map {
        WorkExecutionInstance(
          it.invocationId,
          it.executionId,
          it.lifecycleState,
          it.startedAt,
          it.updatedAt,
          null,
          null,
          runSequence = it.runSequence,
        )
      }
  }
}

internal data class ResolvedExecutionReason(
  val text: String?,
  val restoration: ExecutionReasonRestoration,
)

internal fun resolveExecutionReason(
  record: StandalonePhaseStatusRecord,
  unitOfWork: UnitOfWork,
): ResolvedExecutionReason {
  val resolved = resolveStoredReason(record, unitOfWork)
  val detail = record.monitorTargetDetail() ?: return resolved
  return resolved.copy(text = resolved.text?.let { "$it $detail" })
}

private fun resolveStoredReason(
  record: StandalonePhaseStatusRecord,
  unitOfWork: UnitOfWork,
): ResolvedExecutionReason {
  val stored =
    record.acceptedResult ?: record.terminalResult
      ?: return ResolvedExecutionReason(null, ExecutionReasonRestoration.MISSING_CONTENT)
  if (record.acceptedResult != null || !stored.endsWith("...")) {
    return ResolvedExecutionReason(stored, ExecutionReasonRestoration.EXACT)
  }
  val workflowId =
    record.workflowId
      ?: return ResolvedExecutionReason(stored, ExecutionReasonRestoration.MISSING)
  val prefix = stored.removeSuffix("...")
  val lookup =
    unitOfWork.featureTaskPhaseSettlements.findReasonAtOrBefore(
      workflowId,
      record.phaseId,
      record.startedAt,
      record.updatedAt,
      stored,
    )
  val restored =
    when (lookup) {
      is FeatureTaskPhaseSettlementReasonLookup.Unique -> lookup.settlement.restoreReason(workflowId, stored, prefix)
      FeatureTaskPhaseSettlementReasonLookup.Missing,
      FeatureTaskPhaseSettlementReasonLookup.Ambiguous,
      FeatureTaskPhaseSettlementReasonLookup.BudgetExceeded,
      -> null
    }
  val restoration = restorationOutcome(lookup, restored)
  if (restoration != ExecutionReasonRestoration.RESTORED) {
    historyLogger.log(
      System.Logger.Level.WARNING,
      "legacy_settlement_reason_unrestored outcome=${restoration.name.lowercase()}",
    )
  }
  return ResolvedExecutionReason(restored ?: stored, restoration)
}

private fun restorationOutcome(
  lookup: FeatureTaskPhaseSettlementReasonLookup,
  restored: String?,
): ExecutionReasonRestoration =
  if (restored != null) {
    ExecutionReasonRestoration.RESTORED
  } else {
    when (lookup) {
      FeatureTaskPhaseSettlementReasonLookup.Missing -> ExecutionReasonRestoration.MISSING
      FeatureTaskPhaseSettlementReasonLookup.Ambiguous -> ExecutionReasonRestoration.AMBIGUOUS
      FeatureTaskPhaseSettlementReasonLookup.BudgetExceeded -> ExecutionReasonRestoration.BUDGET_EXCEEDED
      is FeatureTaskPhaseSettlementReasonLookup.Unique -> ExecutionReasonRestoration.MISSING_CONTENT
    }
  }

private fun FeatureTaskPhaseSettlement.restoreReason(
  workflowId: String,
  stored: String,
  prefix: String,
): String? {
  val envelope =
    JsonCodec.parseObjectOrNull(envelopeJson)
      ?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)
      ?: throw invalidIdeStatusSchemaError(
        "phase settlement $workflowId",
        "envelope_json",
        "Invalid settlement JSON.",
      )
  val summary = envelope[SharedPayloadKeys.SUMMARY] as? String ?: return null
  if (!stored.endsWith(summary)) return null
  val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
  val full = produced?.get(SharedPayloadKeys.VALUE) as? String ?: return null
  val restored = stored.removeSuffix(summary) + full
  return restored.takeIf { it.startsWith(prefix) }
}
