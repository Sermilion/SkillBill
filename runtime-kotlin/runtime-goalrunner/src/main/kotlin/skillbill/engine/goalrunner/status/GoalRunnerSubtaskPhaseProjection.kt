package skillbill.engine.goalrunner.status

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeCommitPushPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.error.core.catchingNonCancellation
import skillbill.error.shellcontent.invalidFeatureTaskRuntimePhaseOutputSchema
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeValidationEvidenceSchema
import skillbill.goalrunner.model.GoalRunnerHistoryReceipt
import skillbill.goalrunner.model.GoalRunnerPhaseCard
import skillbill.goalrunner.model.GoalRunnerPhaseCardEdit
import skillbill.goalrunner.model.GoalRunnerPhaseCardOutput
import skillbill.goalrunner.model.GoalRunnerPhaseCardState
import skillbill.goalrunner.model.GoalRunnerPhaseConclusion
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditAttempt
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditCriterion
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditOutcome
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditResult
import skillbill.goalrunner.model.GoalRunnerSubtaskExecution
import skillbill.goalrunner.model.GoalRunnerSubtaskPhaseResults
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.decodeValidationGateExecutionEvidenceFromArtifact
import skillbill.workflow.taskruntime.artifact.isPreContractAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.artifact.supersededAcceptanceCriterionRepairStateVersion
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionAuditAttempt
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.auditAttempts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun projectSubtaskAudit(
  artifacts: DurableWorkflowArtifacts,
  record: FeatureTaskRuntimePhaseRecord?,
  conclusion: GoalRunnerPhaseConclusion?,
  degradation: SubtaskReadDegradation,
): GoalRunnerSubtaskAuditResult? {
  if (record == null) return null
  val state = readAuditState(artifacts, degradation)
  val attempts = state?.auditAttempts().orEmpty()
  val outcome = subtaskAuditOutcome(record, state, attempts, conclusion?.verdict) ?: return null
  val criteria =
    state?.criteria.orEmpty()
      .filter { it.sourceDisposition != AcceptanceCriterionSourceDisposition.NOT_APPLICABLE }
      .map { criterion ->
        val fixedAttempt =
          if (criterion.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED) {
            val repairIds = attempts.filter { it.number >= 2 }.mapTo(hashSetOf()) { it.id }
            state?.sourceTransitions?.lastOrNull {
              it.criterionRef == criterion.canonicalRef &&
                it.toDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED &&
                it.producingId in repairIds
            }?.producingId?.let { id -> attempts.lastOrNull { it.id == id }?.number }
          } else {
            null
          }
        GoalRunnerSubtaskAuditCriterion(
          ref = criterion.canonicalRef,
          text =
            criterionText(
              criterion.originalText,
              listOfNotNull(criterion.originalAlias?.takeIf(String::isNotBlank), criterion.canonicalRef),
            ),
          met = criterion.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED,
          fixedInAttempt = fixedAttempt,
        )
      }
  val metCount = criteria.count { it.met }.takeIf { state != null }
  val totalCount = criteria.size.takeIf { state != null }
  val projectedAttempts =
    attempts.mapIndexed { index, attempt ->
      val lastBudgetAttempt =
        index == attempts.lastIndex && state?.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED
      GoalRunnerSubtaskAuditAttempt(
        id = attempt.id,
        number = attempt.number,
        passed = attempt.passed,
        uncoveredCount = attempt.uncoveredCount,
        nextPhaseId =
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX
            .takeIf { attempt.uncoveredCount > 0 && !lastBudgetAttempt },
      )
    }
  return GoalRunnerSubtaskAuditResult(
    outcome = outcome,
    metCount = metCount,
    totalCount = totalCount,
    criteria = criteria,
    attempts = projectedAttempts,
    attemptTotal = if (state != null) attempts.size else conclusion?.attemptCount,
  )
}

private fun readAuditState(
  artifacts: DurableWorkflowArtifacts,
  degradation: SubtaskReadDegradation,
): AcceptanceCriterionRepairState? {
  val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
  return if (!family.contains(artifacts)) {
    null
  } else {
    catchingNonCancellation {
      val payload = FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(artifacts))
      if (payload.isPreContractAcceptanceCriterionRepairState() ||
        payload.supersededAcceptanceCriterionRepairStateVersion() != null
      ) {
        null
      } else {
        payload.toAcceptanceCriterionRepairState("workflow-artifact:${family.label()}")
      }
    }.getOrElse { error ->
      degradation.record(
        source = GoalRunnerSubtaskReadSource.SUBTASK_AUDIT_CRITERIA,
        expected = "audit_criteria",
        used = "omitted",
        error = error,
      )
      null
    }
  }
}

internal fun subtaskAuditOutcome(
  record: FeatureTaskRuntimePhaseRecord?,
  state: AcceptanceCriterionRepairState?,
  attempts: List<AcceptanceCriterionAuditAttempt>,
  verdict: String?,
): GoalRunnerSubtaskAuditOutcome? =
  when {
    record == null -> null
    record.status == WorkflowStepStatus.RUNNING -> GoalRunnerSubtaskAuditOutcome.RUNNING
    state?.stage == AcceptanceCriterionRepairStage.ACCEPTED -> GoalRunnerSubtaskAuditOutcome.PASSED
    auditBlocked(record, state) ->
      GoalRunnerSubtaskAuditOutcome.FAILED
    state != null && attempts.isNotEmpty() &&
      state.criteria.any { it.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED } ->
      GoalRunnerSubtaskAuditOutcome.FAILED
    state != null -> GoalRunnerSubtaskAuditOutcome.RUNNING
    verdict == FeatureTaskRuntimeVerdict.SATISFIED.wireValue ||
      verdict == FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue -> GoalRunnerSubtaskAuditOutcome.PASSED
    verdict == FeatureTaskRuntimeVerdict.ADVANCE.wireValue -> GoalRunnerSubtaskAuditOutcome.FAILED
    else -> GoalRunnerSubtaskAuditOutcome.UNKNOWN
  }

private fun auditBlocked(
  record: FeatureTaskRuntimePhaseRecord,
  state: AcceptanceCriterionRepairState?,
): Boolean =
  state?.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED ||
    record.status == WorkflowStepStatus.BLOCKED || record.status == WorkflowStepStatus.FAILED

private fun criterionText(
  originalText: String,
  prefixes: List<String>,
): String {
  val collapsed = originalText.trim().replace(Regex("\\s+"), " ")
  val prefix =
    prefixes.firstOrNull { value ->
      collapsed.startsWith(value, ignoreCase = true) &&
        (collapsed.length == value.length || collapsed[value.length].isWhitespace() || collapsed[value.length] in ".:/")
    }
  val withoutPrefix =
    if (prefix != null) {
      collapsed.drop(prefix.length).dropWhile { it.isWhitespace() || it == '.' || it == ':' || it == '/' }.trim()
    } else {
      collapsed
    }
  return withoutPrefix.ifBlank { collapsed }
}

internal fun subtaskExecution(
  workflowId: String,
  records: Collection<FeatureTaskRuntimePhaseRecord>,
): GoalRunnerSubtaskExecution? {
  if (records.isEmpty()) return null
  val latestFirst = records.sortedByDescending { it.startedAt }
  val launched = latestFirst.firstOrNull { !it.launchedModel.isNullOrBlank() }
  return GoalRunnerSubtaskExecution(
    workflowId = workflowId,
    agentId = latestFirst.firstOrNull { it.resolvedAgentId.isNotBlank() }?.resolvedAgentId,
    model = launched?.launchedModel,
    effort = launched?.launchedEffort?.takeIf(String::isNotBlank),
    startedAt = records.minOf { it.firstStartedAt },
    updatedAt = records.maxOf { it.finishedAt ?: it.startedAt },
    phaseAttemptCounts = records.filter { it.attemptCount > 0 }.associate { it.phaseId to it.attemptCount },
  )
}

private const val FILE_LIMIT = 50
private const val EDIT_SAMPLE_LIMIT = 8

internal fun attachLivePhaseCardFacts(
  results: List<GoalRunnerSubtaskPhaseResults>,
  currentSubtaskId: Int?,
  tick: WorktreeEditTick?,
  stamp: AgentActivityStamp?,
): List<GoalRunnerSubtaskPhaseResults> =
  results.map { result ->
    if (result.subtaskId != currentSubtaskId) {
      result
    } else {
      val activityCard =
        result.phaseCards.lastOrNull {
          it.state == GoalRunnerPhaseCardState.RUNNING || it.state == GoalRunnerPhaseCardState.BLOCKED
        }
      result.copy(
        phaseCards =
          result.phaseCards.map { card ->
            card.copy(
              edit =
                if (tick?.phaseId == card.phaseId) {
                  GoalRunnerPhaseCardEdit(
                    tick.recordedAt,
                    tick.entries.size,
                    tick.entries.sumOf { it.insertions },
                    tick.entries.sumOf { it.deletions },
                    tick.entries.map { it.path }.take(EDIT_SAMPLE_LIMIT),
                  )
                } else {
                  card.edit
                },
              activityAt = stamp?.recordedAt.takeIf { card.phaseId == activityCard?.phaseId },
              activityLabel = stamp?.label.takeIf { card.phaseId == activityCard?.phaseId },
            )
          },
      )
    }
  }

internal data class LivePhaseCardSource(
  val currentSubtaskId: Int?,
  val workflowId: String?,
  val tick: WorktreeEditTick?,
)

internal fun readLivePhaseCardFacts(
  results: List<GoalRunnerSubtaskPhaseResults>,
  source: LivePhaseCardSource,
  database: DatabaseSessionFactory,
  durableRead: GoalRunnerStatusDurableReadTracker,
): List<GoalRunnerSubtaskPhaseResults> {
  val stamp =
    source.workflowId?.takeIf {
      source.currentSubtaskId != null && results.any { it.subtaskId == source.currentSubtaskId }
    }?.let { id ->
      catchingNonCancellation { database.read { unit -> unit.agentActivityStamps.read(id) } }.getOrElse { error ->
        durableRead.recordDegradedRead(
          "goal-status.phase_card_activity_stamp",
          "agent_activity_stamp",
          "omitted",
          error,
        )
        null
      }
    }
  return attachLivePhaseCardFacts(results, source.currentSubtaskId, source.tick, stamp)
}

internal fun FeatureTaskRuntimePhaseRecord.toPhaseCardHeader(): GoalRunnerPhaseCard? {
  val state = status.toPhaseCardState() ?: return null
  return GoalRunnerPhaseCard(
    phaseId = phaseId,
    state = state,
    round =
      if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
        reviewPassNumber ?: attemptCount
      } else {
        attemptCount
      },
    startedAt = firstStartedAt,
    finishedAt = finishedAt,
    durationMillis = durationMillis,
    agentId = resolvedAgentId.takeIf(String::isNotBlank),
    model = launchedModel?.takeIf(String::isNotBlank),
    effort = launchedEffort?.takeIf(String::isNotBlank),
    blockedReason = blockedReason,
    files = fileManifestIntroduced.take(FILE_LIMIT),
  )
}

private fun WorkflowStepStatus.toPhaseCardState(): GoalRunnerPhaseCardState? =
  when (this) {
    WorkflowStepStatus.PENDING -> null
    WorkflowStepStatus.RUNNING -> GoalRunnerPhaseCardState.RUNNING
    WorkflowStepStatus.COMPLETED -> GoalRunnerPhaseCardState.COMPLETED
    WorkflowStepStatus.FAILED -> GoalRunnerPhaseCardState.FAILED
    WorkflowStepStatus.BLOCKED -> GoalRunnerPhaseCardState.BLOCKED
    WorkflowStepStatus.SKIPPED -> GoalRunnerPhaseCardState.SKIPPED
    WorkflowStepStatus.PAUSED -> GoalRunnerPhaseCardState.PAUSED
  }

internal fun phaseCardOutput(
  envelope: Map<String, Any?>,
  sourceLabel: String,
): GoalRunnerPhaseCardOutput {
  val produced = envelope[SharedPayloadKeys.PRODUCED_OUTPUTS].asStringMap()
  val facts = produced[FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS].asStringMap()
  val commit = produced[FeatureTaskRuntimeCommitPushPayloadKeys.COMMIT_PUSH_RESULT].asStringMap()

  fun knownString(value: Any?): String? =
    (value as? String)?.takeIf { it.isNotBlank() && it != FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN }

  fun gate(key: String): FeatureTaskRuntimeValidationGateExecutionEvidence? {
    val raw = produced[key] ?: return null
    return decodeValidationGateExecutionEvidenceFromArtifact(raw, sourceLabel)
      ?: throw invalidFeatureTaskRuntimeValidationEvidenceSchema(sourceLabel, "$key must be an object.")
  }
  val changedPaths = facts[FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS].stringListOrNull()
  val history =
    if (changedPaths != null || facts.containsKey(FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN) ||
      facts.containsKey(FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED)
    ) {
      GoalRunnerHistoryReceipt(
        changedPaths,
        facts[FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN].knownBoolean(),
        facts[FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED].knownBoolean(),
      )
    } else {
      null
    }
  return GoalRunnerPhaseCardOutput(
    verdict = knownString(envelope[SharedPayloadKeys.VERDICT]),
    buildReceipt = gate(ValidationEvidencePayloadKeys.BUILD_RECEIPT),
    validationReceipt = gate(ValidationEvidencePayloadKeys.VALIDATION_RESULT),
    historyReceipt = history,
    commitSha = knownString(commit[DecompositionManifestPayloadKeys.COMMIT_SHA]),
    pushedBranch = knownString(commit[DecompositionPlanningPayloadKeys.BRANCH]),
    pushed = commit[FeatureTaskRuntimeCommitPushPayloadKeys.PUSHED].knownBoolean(),
    pullRequestUrl = knownString(facts[FeatureTaskRuntimeMeasuredFactKeys.PR_URL]),
    pullRequestNumber = (facts[FeatureTaskRuntimeMeasuredFactKeys.PR_NUMBER] as? Number)?.toInt(),
  )
}

internal fun subtaskPhaseCards(
  workflowId: String,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  database: DatabaseSessionFactory,
  degradation: SubtaskReadDegradation,
): List<GoalRunnerPhaseCard> {
  val cards =
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.mapNotNull { phaseId ->
      records[phaseId]?.let { record -> record.toPhaseCardHeader()?.let { it to record } }
    }
  if (cards.isEmpty()) return emptyList()
  return catchingNonCancellation {
    database.read { unit ->
      cards.map { (card, record) ->
        val output =
          catchingNonCancellation {
            val stored = unit.featureTaskPhaseSettlements.find(workflowId, card.phaseId, record.attemptCount)
            val raw = stored?.envelopeJson ?: record.outputArtifact
            raw?.let { parseEnvelope(it, card.phaseId) }?.let { phaseCardOutput(it, card.phaseId) }
          }.getOrElse { error ->
            degradation.record(
              GoalRunnerSubtaskReadSource.PHASE_CARD_OUTPUT,
              "${card.phaseId}_settlement_envelope",
              "omitted",
              error,
            )
            null
          }
        card.copy(output = output)
      }
    }
  }.getOrElse { error ->
    degradation.record(GoalRunnerSubtaskReadSource.PHASE_CARD_OUTPUT, "phase_settlement_envelopes", "omitted", error)
    cards.map { (card, _) -> card }
  }
}

private fun parseEnvelope(
  raw: String,
  sourceLabel: String,
): Map<String, Any?> =
  JsonCodec.parseObjectOrNull(raw)?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)
    ?: throw invalidFeatureTaskRuntimePhaseOutputSchema(sourceLabel, "Phase card envelope must be a JSON object.")

private fun Any?.asStringMap(): Map<String, Any?> =
  (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

private fun Any?.knownBoolean(): Boolean? = this as? Boolean

private fun Any?.stringListOrNull(): List<String>? =
  (this as? List<*>)?.mapNotNull {
    (it as? String)?.takeIf { value -> value.isNotBlank() && value != FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN }
  }
