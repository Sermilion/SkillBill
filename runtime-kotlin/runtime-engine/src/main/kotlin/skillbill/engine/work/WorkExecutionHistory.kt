package skillbill.engine.work

import skillbill.application.review.service.StandaloneReviewReportReader
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.qa.report.QaReportParser
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.pagination.WorkDetailAnchor
import skillbill.engine.work.pagination.WorkDetailPagePosition
import skillbill.engine.work.validation.validationChecksFor
import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementReasonLookup
import skillbill.ports.idestatus.model.ExecutionReasonRestoration
import skillbill.ports.idestatus.model.IdeStatusWorkflowExecution
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandalonePhaseExecutionQuery
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandaloneReviewReport
import skillbill.ports.idestatus.model.WorkExecutionInstance
import skillbill.ports.idestatus.model.WorkQaFinding
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
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
      if (target.branchRef != null) "Branch ${target.pullRequestUrl}" else "Pull request ${target.pullRequestUrl}",
      "repository ${target.pullRequestKey.substringBefore(if (target.branchRef != null) '@' else '#')}",
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
      compareBy<StandalonePhaseStatusRecord> { if (it.displayedLive()) 1 else 0 }
        .thenBy { it.runSequence.length }
        .thenBy { it.runSequence }
        .thenBy { it.invocationId },
    )
  }

private fun StandalonePhaseStatusRecord.displayedLive(): Boolean =
  IdeStatusPolicy.standaloneLifecycle(lifecycleState) in LIVE_DISPLAYED_STANDALONE

private val LIVE_DISPLAYED_STANDALONE =
  setOf(IdeStatusLifecycleState.ACTIVE, IdeStatusLifecycleState.PAUSED)

internal fun supersededPhase(
  record: StandalonePhaseStatusRecord?,
  unit: UnitOfWork,
  observedAt: Instant,
): Boolean {
  if (record == null) return false
  val displayed =
    latestPhaseExecutions(
      unit.standalonePhaseStatuses.readPhaseExecutions(
        StandalonePhaseExecutionQuery(
          record.repositoryIdentity,
          record.phaseId,
          phaseGroupIdentity(record).third,
          observedAt,
          0,
          DISPLAYED_PHASE_SCAN,
        ),
      ),
    ).singleOrNull()
  return displayed != null && displayed.invocationId != record.invocationId
}

private const val DISPLAYED_PHASE_SCAN = 100

internal data class WorkflowExecutionCurrentState(
  val executionId: String?,
  val lifecycleState: IdeStatusLifecycleState,
  val finishedAt: Instant?,
)

internal fun workExecutionHistory(
  unitOfWork: UnitOfWork,
  candidate: IdeStatusCandidate,
  page: WorkDetailPagePosition,
  limit: Int,
  current: WorkflowExecutionCurrentState? = null,
): List<WorkExecutionInstance> {
  val standalone = candidate.standaloneStatus
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
      .map { workExecutionInstance(it, standalone.invocationId, unitOfWork) }
  } else {
    unitOfWork.standalonePhaseStatuses
      .readWorkflowExecutions(candidate.workflowId, page.observedAt, offset, limit, anchor)
      .map { workflowExecutionInstance(it, current) }
  }
}

internal fun workflowExecutionInstance(
  execution: IdeStatusWorkflowExecution,
  current: WorkflowExecutionCurrentState?,
): WorkExecutionInstance {
  val stored =
    WorkExecutionInstance(
      execution.invocationId,
      execution.executionId,
      execution.lifecycleState,
      execution.startedAt,
      execution.updatedAt,
      null,
      null,
      runSequence = execution.runSequence,
    )
  val currentExecutionId = current?.executionId ?: return stored
  return if (currentExecutionId == execution.executionId) {
    stored.copy(lifecycleState = current.lifecycleState.wireValue, finishedAt = current.finishedAt)
  } else {
    stored.copy(lifecycleState = IdeStatusLifecycleState.TERMINAL.wireValue, result = SUPERSEDED_WORKFLOW_EXECUTION)
  }
}

private const val SUPERSEDED_WORKFLOW_EXECUTION = "Ended; a later run of this workflow replaced it."

internal fun workSelectedExecution(
  record: StandalonePhaseStatusRecord?,
  unitOfWork: UnitOfWork,
): WorkExecutionInstance? = record?.let { workExecutionInstance(it, it.invocationId, unitOfWork) }

internal fun workExecutionInstance(
  record: StandalonePhaseStatusRecord,
  selectedInvocationId: String,
  unitOfWork: UnitOfWork,
): WorkExecutionInstance {
  val reason = resolveExecutionReason(record, unitOfWork)
  val currentValidation =
    record.phaseId == SkeletonDefinition.VALIDATION.id && record.invocationId == selectedInvocationId
  return WorkExecutionInstance(
    record.invocationId,
    record.executionId,
    record.lifecycleState,
    record.startedAt,
    record.updatedAt,
    record.finishedAt,
    reason.text,
    reason.restoration,
    record.runSequence,
    record.qaOutcome,
    record.qaReportText,
    record.planReport,
    reviewReport =
      if (record.phaseId == SkeletonDefinition.REVIEW.id) {
        reviewReport(record, reason.text, unitOfWork)
      } else {
        null
      },
    qaChecklist = record.qaChecklist,
    qaFindings = qaFindingsOf(record.qaReportText),
    qaDetails = record.qaDetails,
    liveChecks = if (currentValidation) record.liveChecks else null,
    validationOutcome = if (currentValidation) record.validationOutcome else null,
    validationChecks = validationChecksFor(currentValidation, record.invocationId, unitOfWork),
  )
}

private fun qaFindingsOf(text: String?): List<WorkQaFinding> =
  text?.let {
    QaReportParser.parse(it, "").findings.map {
        finding ->
      WorkQaFinding(finding.id, finding.title)
    }
  }.orEmpty()

public fun reviewReport(
  record: StandalonePhaseStatusRecord,
  text: String?,
  unitOfWork: UnitOfWork,
): StandaloneReviewReport? {
  val report = StandaloneReviewReportReader.read(text) ?: return null
  val runId = record.reviewRunId ?: return report
  val recorded =
    unitOfWork.reviews.fetchReviewRunLanes(runId)
      .sortedBy { it.orderIndex }
      .map { it.laneSkillName }
  val statuses = unitOfWork.reviews.fetchFindingStatuses(runId)
  return report.copy(
    lanes = recorded.ifEmpty { report.lanes },
    findingActions = unitOfWork.reviews.findingActions(runId),
    findings = report.findings.map { finding -> statuses[finding.ref]?.let { finding.copy(status = it) } ?: finding },
  )
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
