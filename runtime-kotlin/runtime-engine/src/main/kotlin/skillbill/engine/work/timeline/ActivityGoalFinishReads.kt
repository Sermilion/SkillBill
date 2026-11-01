package skillbill.engine.work.timeline

import me.tatarka.inject.annotations.Inject
import skillbill.engine.work.timeline.model.WorkActivityBlock
import skillbill.engine.work.timeline.model.WorkActivityBlockKind
import skillbill.engine.work.timeline.model.WorkActivityFact
import skillbill.engine.work.timeline.model.WorkActivityGroup
import skillbill.engine.work.timeline.model.WorkActivityStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.ledger.declaredProgressEventFrom
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.UnitOfWork
import skillbill.ports.work.model.WorkActivityStop
import skillbill.ports.work.model.WorkItem
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.taskruntime.artifact.toGoalAssessmentRecord
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

@Inject
public class ActivityGoalFinishReads {
  public fun group(
    goal: WorkItem,
    children: List<WorkActivityGroup>,
    stops: List<WorkActivityStop>,
    unit: UnitOfWork,
  ): WorkActivityGroup? {
    val artifacts =
      unit.workflowStates.readFeatureTaskArtifacts(
        goal.workflowId,
        setOf(
          DurableWorkflowArtifactFamily.GOAL_ASSESSMENT_RECORD,
          DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT,
        ),
      )
    val family = DurableWorkflowArtifactFamily.GOAL_ASSESSMENT_RECORD
    val assessment =
      artifacts?.get(family.label())?.let {
        FeatureTaskRuntimeWorkflowArtifactMap.from(it).toGoalAssessmentRecord(family.label())
      }
    val progress = artifacts?.let(::declaredProgressEventFrom)
    val blocks = finishBlocks(goal.workflowId, children, stops, unit)
    val before = assessment?.let { listOf(assessmentFact(it)) }.orEmpty()
    val after =
      progress?.takeIf {
        activityStatus(goal.currentState) == WorkActivityStatus.RUNNING &&
          children.none {
              child ->
            child.status.isUnfinished
          }
      }
        ?.let(::latestFact)?.let(::listOf).orEmpty()
    if (before.isEmpty() && after.isEmpty() && blocks.isEmpty()) return null
    return WorkActivityGroup(
      "goal-finish", null, "Goal finish", activityStatus(goal.currentState),
      null, null, emptyList(), blocks, before, after,
    )
  }

  private fun finishBlocks(
    id: String,
    children: List<WorkActivityGroup>,
    stops: List<WorkActivityStop>,
    unit: UnitOfWork,
  ): List<WorkActivityBlock> {
    val childBlocks = children.flatMap { it.blocks }
    val known = childBlocks.map { it.id }.toSet()
    val blocks = activityBlocks(id, unit).filter { it.id !in known }.toMutableList()
    val lastChildFinish = children.mapNotNull { it.finishedAt }.maxOrNull()
    stops.forEach { stop ->
      val block = stopBlock(stop) ?: return@forEach
      if (lastChildFinish != null && block.at < lastChildFinish) return@forEach
      if ((blocks + childBlocks).none { it.at >= stop.startedAt && it.at <= block.at }) blocks += block
    }
    return blocks.sortedBy { it.at }
  }
}

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
    null,
    kind == WorkActivityBlockKind.AWAITING_DECISION,
  )
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
