package skillbill.engine.goalrunner.status

import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.model.GoalRunnerPhaseConclusion
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditAttempt
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditCriterion
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditOutcome
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditResult
import skillbill.goalrunner.model.GoalRunnerSubtaskExecution
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.historical.toEngineRepairState
import skillbill.workflow.taskruntime.artifact.isPreContractAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.artifact.isPreviousVersionAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.history.auditHistory
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun projectSubtaskAudit(
  artifacts: DurableWorkflowArtifacts,
  record: FeatureTaskRuntimePhaseRecord?,
  conclusion: GoalRunnerPhaseConclusion?,
  degradation: SubtaskReadDegradation,
): GoalRunnerSubtaskAuditResult? {
  if (record == null) return null
  val state = readAuditState(artifacts, degradation)
  val history = state?.auditHistory()
  val attempts = history?.attempts.orEmpty()
  val outcome = subtaskAuditOutcome(record, state, conclusion?.verdict) ?: return null
  val criteria =
    state?.criteria.orEmpty()
      .filter { it.sourceDisposition != AcceptanceCriterionSourceDisposition.NOT_APPLICABLE }
      .map { criterion ->
        val fixedAttempt =
          if (criterion.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED) {
            val repairId =
              history?.closures?.singleOrNull { it.criterionRef == criterion.canonicalRef }?.repairAttemptId
            attempts.singleOrNull { it.id == repairId }?.number
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
        payload.isPreviousVersionAcceptanceCriterionRepairState()
      ) {
        null
      } else {
        payload.toEngineRepairState("workflow-artifact:${family.label()}")
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
  verdict: String?,
): GoalRunnerSubtaskAuditOutcome? =
  when {
    record == null -> null
    record.status == WorkflowStepStatus.RUNNING -> GoalRunnerSubtaskAuditOutcome.RUNNING
    state?.stage == AcceptanceCriterionRepairStage.ACCEPTED -> GoalRunnerSubtaskAuditOutcome.PASSED
    auditBlocked(record, state) ->
      GoalRunnerSubtaskAuditOutcome.FAILED
    state != null &&
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
