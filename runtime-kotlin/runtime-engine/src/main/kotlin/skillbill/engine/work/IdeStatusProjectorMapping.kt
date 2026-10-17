package skillbill.engine.work

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.runner.operatorDecisionPause
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.idestatus.model.IdeStatusCurrentModel
import skillbill.ports.idestatus.model.IdeStatusCurrentSubtask
import skillbill.ports.idestatus.model.IdeStatusPlanning
import skillbill.ports.idestatus.model.IdeStatusProgress
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal fun goalCurrentSubtask(
  projection: GoalRunnerStatusProjection?,
  context: IdeStatusProjectionContext,
): IdeStatusCurrentSubtask? =
  projection?.currentSubtaskId?.takeIf { it > 0 }?.let { subtaskId ->
    IdeStatusCurrentSubtask(
      id = subtaskId.toString(),
      startedAt =
        projection.currentChildWorkflowId?.takeIf(String::isNotBlank)?.let { workflowId ->
          parseInstantOrNull(
            context.unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)?.startedAt,
          )
        },
      activeDurationMs = projection.recordedSubtaskActiveDurationMs(),
      activeDurationAsOf = projection.liveSubtaskActiveDurationAnchor(),
    )
  }

internal fun livePlanningOverlay(
  planning: IdeStatusPlanning?,
  projectedStep: String?,
  lifecycle: IdeStatusLifecycleState,
): IdeStatusPlanning? =
  planning?.takeIf {
    it.state != GoalPlanningStatusState.PREPARED &&
      !lifecycle.isSettled() &&
      projectedStep.isLivePlanningStep()
  }

internal fun String?.isLivePlanningStep(): Boolean =
  isNullOrBlank() || this in LIVE_PLANNING_STEP_IDS

internal fun goalStep(
  planningStep: IdeStatusPlanning?,
  projectedStep: String?,
  lifecycle: IdeStatusLifecycleState,
): IdeStatusStep {
  if (planningStep != null) return IdeStatusStep(id = "planning", label = "Planning")
  val projected = projectedStep?.takeIf(String::isNotBlank)
  if (projected != null) return IdeStatusStep(id = projected, label = projected)
  return if (lifecycle == IdeStatusLifecycleState.TERMINAL) {
    IdeStatusStep(id = "done", label = "Complete")
  } else {
    IdeStatusStep(id = "goal", label = "Goal")
  }
}

internal fun FeatureTaskRuntimePhaseStatus.toIdeStatusCurrentModel(): IdeStatusCurrentModel? {
  if (status.workflowStepStatus() == WorkflowStepStatus.COMPLETED) return null
  return launchedModel?.takeIf(String::isNotBlank)?.let { model ->
    IdeStatusCurrentModel(
      model = model,
      effort = launchedEffort?.takeIf(String::isNotBlank),
      phaseId = phaseId.takeIf(String::isNotBlank),
    )
  }
}

internal fun GoalPlanningStatusSnapshot.toIdeStatusPlanning(): IdeStatusPlanning =
  IdeStatusPlanning(
    state = state,
    sharedPreplanPrepared = sharedPreplanPrepared,
    plannedSubtaskCount = plannedSubtaskCount,
    totalSubtaskCount = totalSubtaskCount,
    currentPlanningSubtaskId = currentPlanningSubtaskId?.toString(),
    planningWaveSubtaskIds = planningWaveSubtaskIds.map(Int::toString),
    reason = reason,
  )

internal fun IdeStatusLifecycleState.isSettled(): Boolean =
  this == IdeStatusLifecycleState.BLOCKED ||
    this == IdeStatusLifecycleState.FAILED ||
    this == IdeStatusLifecycleState.TERMINAL

internal fun goalPlanningSummary(
  issueKey: String,
  planning: IdeStatusPlanning,
): String {
  val concurrent = planning.planningWaveSubtaskIds.size
  val wave =
    when (concurrent) {
      0 -> ""
      1 -> " 1 subtask is being planned now."
      else -> " $concurrent subtasks are being planned now."
    }
  return "Goal $issueKey is planning subtasks " +
    "(${planning.plannedSubtaskCount}/${planning.totalSubtaskCount} planned).$wave"
}

internal fun GoalRunnerStatusProjection.hasUnfinishedSubtasks(): Boolean = pendingCount > 0 || blockedCount > 0

internal fun goalStaysOnOpenCiMonitor(projection: GoalRunnerStatusProjection?): Boolean {
  if (projection == null || projection.paused) return false
  if (projection.admittedParentComplete()) return false
  if (projection.pendingCount > 0 || projection.blockedCount > 0) return false
  return projection.completeCount > 0
}

internal fun goalShowsOpenCiMonitor(
  projection: GoalRunnerStatusProjection?,
  lifecycle: IdeStatusLifecycleState,
): Boolean =
  goalStaysOnOpenCiMonitor(projection) &&
    projection?.executionLiveness == ExecutionLiveness.LIVE &&
    projection.currentStep in setOf(OPEN_CI_MONITOR_STEP, "monitor_fix") &&
    lifecycle == IdeStatusLifecycleState.ACTIVE

internal fun goalProjectsLiveFinalizationStep(projection: GoalRunnerStatusProjection?): Boolean {
  if (projection == null || projection.executionLiveness != ExecutionLiveness.LIVE) return false
  if (!goalStaysOnOpenCiMonitor(projection)) return false
  val step = projection.currentStep?.takeIf(String::isNotBlank) ?: return false
  return step in LIVE_GOAL_FINALIZATION_STEP_IDS
}

internal data class GoalCurrentStepSignals(
  val openCiMonitor: Boolean,
  val liveFinalizationStep: Boolean,
)

internal fun goalCurrentStep(
  planningStep: IdeStatusPlanning?,
  childPhaseId: String?,
  projectedStep: String?,
  lifecycle: IdeStatusLifecycleState,
  signals: GoalCurrentStepSignals,
): IdeStatusStep {
  val terminal = lifecycle == IdeStatusLifecycleState.TERMINAL
  val childPhaseStep =
    childPhaseId
      ?.takeIf { it.isNotBlank() && planningStep == null && !terminal }
      ?.takeUnless { signals.openCiMonitor || signals.liveFinalizationStep }
  return goalStep(
    planningStep,
    childPhaseStep
      ?: projectedStep?.takeUnless { terminal }
      ?: OPEN_CI_MONITOR_STEP.takeIf { signals.openCiMonitor },
    lifecycle,
  )
}

internal fun GoalRunnerStatusProjection.toIdeStatusProgress(): IdeStatusProgress? {
  val total = completeCount + pendingCount + blockedCount
  if (total <= 0) return null
  return IdeStatusProgress(completed = completeCount, total = total)
}

internal const val OPEN_CI_MONITOR_STEP: String = "monitor"

internal val LIVE_PLANNING_STEP_IDS: Set<String> = setOf("plan", "preplan", "planning")

private val LIVE_GOAL_FINALIZATION_STEP_IDS: Set<String> = setOf(OPEN_CI_MONITOR_STEP, "pr", "monitor_fix")

internal fun goalSummary(
  issueKey: String,
  lifecycle: IdeStatusLifecycleState,
  stepLabel: String,
  blockedCount: Int,
  operatorDecisionPause: FeatureTaskRuntimeOperatorDecisionPause? = null,
): String =
  when (lifecycle) {
    IdeStatusLifecycleState.BLOCKED -> {
      operatorDecisionPause?.reason?.takeIf(String::isNotBlank)?.let { reason ->
        "Goal $issueKey is blocked: $reason"
      } ?: run {
        val subtasks = if (blockedCount == 1) "subtask" else "subtasks"
        "Goal $issueKey is blocked" + if (blockedCount > 0) " ($blockedCount $subtasks)." else "."
      }
    }
    IdeStatusLifecycleState.PAUSED -> "Goal $issueKey is paused."
    IdeStatusLifecycleState.FAILED -> "Goal $issueKey failed."
    IdeStatusLifecycleState.CANCELED -> "Goal $issueKey was canceled."
    IdeStatusLifecycleState.TERMINAL -> "Goal $issueKey is complete."
    IdeStatusLifecycleState.ACTIVE -> "Goal $issueKey is active on $stepLabel."
    IdeStatusLifecycleState.IDLE -> "Goal $issueKey is idle."
  }

internal fun familySummary(
  family: IdeStatusWorkflowFamily,
  issueKey: String?,
  lifecycle: IdeStatusLifecycleState,
  stepLabel: String,
): String {
  val subject = issueKey?.let { "${family.wireValue} $it" } ?: family.wireValue
  return when (lifecycle) {
    IdeStatusLifecycleState.ACTIVE -> "$subject is active on $stepLabel."
    IdeStatusLifecycleState.PAUSED -> "$subject is paused at $stepLabel."
    IdeStatusLifecycleState.BLOCKED -> "$subject is blocked at $stepLabel."
    IdeStatusLifecycleState.FAILED -> "$subject failed at $stepLabel."
    IdeStatusLifecycleState.CANCELED -> "$subject was canceled."
    IdeStatusLifecycleState.TERMINAL -> "$subject is terminal."
    IdeStatusLifecycleState.IDLE -> "No matching Skill Bill work for this repository."
  }
}

internal fun parseInstantOrNull(value: String?): Instant? {
  if (value.isNullOrBlank()) return null
  return try {
    Instant.parse(value)
  } catch (_: DateTimeParseException) {
    runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
      ?: runCatching { LocalDateTime.parse(value.trim(), SQLITE_TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC) }
        .getOrNull()
  }
}

internal val SQLITE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

internal fun WorkItemKind.toIdeFamily(): IdeStatusWorkflowFamily? =
  when (this) {
    WorkItemKind.FEATURE_TASK_PROSE -> null
    WorkItemKind.FEATURE_TASK_RUNTIME -> IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME
    WorkItemKind.FEATURE_VERIFY -> IdeStatusWorkflowFamily.FEATURE_VERIFY
    WorkItemKind.FEATURE_GOAL -> IdeStatusWorkflowFamily.FEATURE_GOAL
  }
