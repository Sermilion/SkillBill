package skillbill.engine.work.timeline

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.featuretask.GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.GOAL_ASSESSMENT_RECORD_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.GoalAssessmentRecordPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.work.timeline.model.WorkActivityBlock
import skillbill.engine.work.timeline.model.WorkActivityBlockKind
import skillbill.engine.work.timeline.model.WorkActivityFact
import skillbill.engine.work.timeline.model.WorkActivityGroup
import skillbill.engine.work.timeline.model.WorkActivityGroupKind
import skillbill.engine.work.timeline.model.WorkActivityStatus
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.ledger.declaredProgressEventFrom
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.UnitOfWork
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.work.model.WorkActivityStop
import skillbill.ports.work.model.WorkItem
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.taskruntime.artifact.toGoalAssessmentRecord
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.time.Instant

@Inject
public class ActivityGoalFinishReads {
  internal fun groups(
    goal: WorkItem,
    children: List<WorkActivityGroup>,
    stops: List<WorkActivityStop>,
    unit: UnitOfWork,
    diagnostics: RuntimeDiagnostics,
  ): List<WorkActivityGroup> {
    val artifacts =
      unit.workflowStates.readFeatureTaskArtifacts(
        goal.workflowId,
        setOf(
          DurableWorkflowArtifactFamily.GOAL_ASSESSMENT_RECORD,
          DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT,
        ),
      )
    val family = DurableWorkflowArtifactFamily.GOAL_ASSESSMENT_RECORD
    val assessment = artifacts?.get(family.label())?.let { assessmentActivity(it, goal.workflowId, diagnostics) }
    val progress = artifacts?.let(::declaredProgressEventFrom)
    val blocks = parentBlocks(goal.workflowId, children, stops, unit)
    val childrenFinished = childrenFinished(children)
    val before = assessment?.let(::listOf).orEmpty()
    val after =
      progress?.takeIf {
        activityStatus(goal.currentState) == WorkActivityStatus.RUNNING &&
          childrenFinished
      }
        ?.let(::latestFact)?.let(::listOf).orEmpty()
    return buildList {
      if (blocks.isNotEmpty()) {
        add(
          WorkActivityGroup(
            "goal-interruptions",
            null,
            "General blocks",
            WorkActivityStatus.INTERRUPTED,
            null,
            null,
            emptyList(),
            blocks,
            kind = WorkActivityGroupKind.GENERAL_BLOCKS,
          ),
        )
      }
      if (before.isNotEmpty() || after.isNotEmpty()) {
        add(
          WorkActivityGroup(
            "goal-finish", null, "Goal finish",
            finishStatus(activityStatus(goal.currentState), children),
            null, null, emptyList(), emptyList(), before, after, WorkActivityGroupKind.GOAL_FINISH,
          ),
        )
      }
    }
  }

  private fun childrenFinished(children: List<WorkActivityGroup>): Boolean {
    val latest =
      children.groupBy { it.subtaskId }.values.map { attempts ->
        attempts.maxWith(compareBy { it.startedAt })
      }
    return latest.isNotEmpty() &&
      latest.all {
        it.status == WorkActivityStatus.COMPLETE
      }
  }

  private fun parentBlocks(
    id: String,
    children: List<WorkActivityGroup>,
    stops: List<WorkActivityStop>,
    unit: UnitOfWork,
  ): List<WorkActivityBlock> {
    val childBlocks = children.flatMap { it.blocks }
    val known = childBlocks.map { it.id }.toSet()
    val blocks = activityBlocks(id, unit).filter { it.id !in known }.toMutableList()
    val lastChildFinish = children.mapNotNull { it.finishedAt }.maxOrNull()
    stops.forEachIndexed { index, stop ->
      val block = stopBlock(stop) ?: return@forEachIndexed
      if (lastChildFinish != null && block.at < lastChildFinish) return@forEachIndexed
      val nextStopStartedAt = stops.getOrNull(index + 1)?.startedAt
      val hasDetailedBlock =
        (blocks + childBlocks).any {
          it.at >= stop.startedAt && (nextStopStartedAt == null || it.at < nextStopStartedAt)
        }
      if (!hasDetailedBlock) blocks += block
    }
    return blocks.sortedBy { it.at }
  }
}

private fun finishStatus(
  goalStatus: WorkActivityStatus,
  children: List<WorkActivityGroup>,
): WorkActivityStatus =
  if (goalStatus in activeGoalStatuses && !allSubtasksSettled(children)) WorkActivityStatus.PENDING else goalStatus

private fun allSubtasksSettled(children: List<WorkActivityGroup>): Boolean =
  children.groupBy { it.subtaskId }.values.all { attempts ->
    attempts.maxBy { it.startedAt ?: Instant.MIN }.status in settledSubtaskStatuses
  }

private val activeGoalStatuses = setOf(WorkActivityStatus.RUNNING, WorkActivityStatus.BLOCKED)
private val settledSubtaskStatuses = setOf(WorkActivityStatus.COMPLETE, WorkActivityStatus.INTERRUPTED)

private fun stopBlock(stop: WorkActivityStop): WorkActivityBlock? {
  val at = stop.finishedAt ?: return null
  val reason = GoalRunnerStopReason.entries.firstOrNull { it.name == stop.reason }
  val kind =
    when (reason) {
      GoalRunnerStopReason.PULL_REQUEST_FAILED -> WorkActivityBlockKind.PULL_REQUEST_FAILED
      GoalRunnerStopReason.AWAITING_OPERATOR_DECISION -> WorkActivityBlockKind.AWAITING_DECISION
      GoalRunnerStopReason.BLOCKED -> WorkActivityBlockKind.BLOCKED
      else -> return null
    }
  return WorkActivityBlock(
    stop.runId,
    at,
    kind,
    null,
    null,
    stop.blockedReason,
    kind == WorkActivityBlockKind.AWAITING_DECISION,
  )
}

private fun assessmentActivity(
  raw: Any,
  workflowId: String,
  diagnostics: RuntimeDiagnostics,
): WorkActivityFact {
  val wire = FeatureTaskRuntimeWorkflowArtifactMap.from(raw)
  val legacy =
    wire[GoalAssessmentRecordPayloadKeys.CONTRACT_VERSION] == GOAL_ASSESSMENT_RECORD_PREVIOUS_CONTRACT_VERSION
  val projected =
    if (legacy) {
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        wire + (GoalAssessmentRecordPayloadKeys.CONTRACT_VERSION to GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION),
      )
    } else {
      wire
    }
  return try {
    val record = projected.toGoalAssessmentRecord(DurableWorkflowArtifactFamily.GOAL_ASSESSMENT_RECORD.label())
    val fact = assessmentFact(record)
    if (legacy) fact.copy(detail = "${fact.detail}, legacy assessment; test-value review unavailable") else fact
  } catch (failure: SkillBillRuntimeException) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "work_activity goal=$workflowId assessment=unavailable cause=${failure.message}",
      failure,
    )
    WorkActivityFact("goal_assessment", "Assessment unavailable", null, null, null, null, failure.message, false)
  }
}

private fun assessmentFact(record: GoalAssessmentRecord): WorkActivityFact {
  val summary =
    record.rounds.flatMap { it.findings }.distinctBy { it.findingRef }
      .groupingBy { it.severity.wireValue }.eachCount().entries
      .joinToString(", ") { (severity, count) -> "$count $severity finding${if (count == 1) "" else "s"}" }
  val detail = listOfNotNull("${record.rounds.size} rounds", record.exitReason?.wireValue).joinToString(", ")
  return WorkActivityFact(
    "goal_assessment",
    detail,
    null,
    null,
    null,
    null,
    summary.takeIf(String::isNotBlank)?.let { "Found $it." },
    false,
  )
}

private fun latestFact(event: GoalProgressEvent): WorkActivityFact? {
  val stepId = event.stepId ?: return null
  val agentId = event.agentId ?: return null
  if (!event.processAlive) return null
  val model = event.launchedModel?.takeIf { modelMatchesAgent(agentId, it) }
  return WorkActivityFact(
    stepId,
    "latest update",
    event.timestamp,
    agentId,
    model,
    event.effort?.takeIf { model != null },
    event.operationName,
    true,
  )
}
