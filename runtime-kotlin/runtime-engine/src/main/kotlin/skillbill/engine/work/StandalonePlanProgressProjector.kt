package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.identity.status.GOAL_PLANNING_WAVE_CAP
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.goal.StandalonePlanReportPort
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.ExecutionProgressStall
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.planningStatusSnapshot
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePlanProgress
import skillbill.ports.idestatus.model.StandalonePlanStage
import skillbill.ports.idestatus.model.StandalonePlanSubtask
import skillbill.ports.idestatus.model.StandalonePlanUnit
import skillbill.ports.idestatus.model.StandalonePlanUnitState
import skillbill.ports.idestatus.model.StandalonePlanWave
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodePlanFanOutHistoryFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanFanOutHistory
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import java.time.Instant

@Inject
public class StandalonePlanProgressProjector(
  private val reportPort: StandalonePlanReportPort,
  private val runtimeStatus: FeatureTaskRuntimeStatusService,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun project(
    phase: StandalonePhaseStatusRecord,
    repoRoot: Path,
    unitOfWork: UnitOfWork,
    observedAt: Instant,
    output: StandalonePhaseOutputSnapshot,
  ): StandalonePlanProgress {
    val workflowId = phase.workflowId
    val workflow = workflowId?.let { unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, it) }
    val records = workflow?.let { decodePhaseRecords(it.artifacts) }.orEmpty()
    val briefingTexts =
      workflowId?.let {
        runtimeStatus.phaseBriefingTexts(it) { error ->
          RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "Standalone plan briefing read failed", error)
        }
      }.orEmpty()
    val savedIds =
      records.entries.filter { (_, record) -> isSaved(record) }.mapNotNullTo(mutableSetOf()) { (id, _) ->
        id.substringAfterLast(':').toIntOrNull()
      }
    val report = phase.issueKey?.let { reportPort.detailReport(it, repoRoot, savedIds) }
    val manifestUnits = report?.subtasks.orEmpty()
    val blockedUnits =
      manifestUnits.mapNotNull { subtask ->
        records[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtask.id)]
          ?.takeIf { it.status == WorkflowStepStatus.BLOCKED || !it.blockedReason.isNullOrBlank() }
          ?.let { subtask.id to it }
      }
    val blocked = blockedUnits.firstOrNull()
    val blockedIds = blockedUnits.map { it.first }
    val blockedId = blocked?.first
    val blockedReason = blocked?.second?.blockedReason
    val savedManifestIds =
      manifestUnits.map { it.id }.filterTo(mutableSetOf()) { id ->
        records[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(id)]?.let(::isSaved) == true
      }
    val planState =
      planningStatusSnapshot(
        manifestUnits.map { it.id },
        savedManifestIds.toList(),
        records[FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN]?.outputArtifact != null,
        blockedId,
        blockedReason,
      )
    val projectedPlanState = projectActiveWave(planState, manifestUnits.map { it.id }, records, blockedId)
    val waveHistory = waveHistory(workflow?.artifacts?.toMap())
    val wave = waveHistory?.waves?.lastOrNull()
    val stages = stages(records, briefingTexts)
    val units = units(manifestUnits, records, output)
    val stall = stall(phase, output, observedAt)
    return StandalonePlanProgress(
      report,
      stages,
      projectedPlanState.plannedSubtaskCount,
      projectedPlanState.totalSubtaskCount,
      projectedPlanState.currentPlanningSubtaskId,
      projectedPlanState.planningWaveSubtaskIds,
      blockedId,
      blockedReason,
      units,
      stall,
      wave?.ordinal,
      wave?.capacity,
      waveHistory?.complete == true,
      blockedIds,
      waves(waveHistory, units),
    )
  }

  private fun waves(
    waveHistory: FeatureTaskRuntimePlanFanOutHistory?,
    units: List<StandalonePlanUnit>,
  ): List<StandalonePlanWave> =
    waveHistory?.waves.orEmpty().mapIndexed { index, recorded ->
      val overwritten = waveHistory?.waves.orEmpty().drop(index + 1).flatMap { it.unitIds }.toSet()
      val startedAt =
        if (recorded.unitIds.any { it in overwritten }) {
          null
        } else {
          units.filter { it.subtaskId in recorded.unitIds }.mapNotNull { it.startedAt }.minOrNull()
        }
      StandalonePlanWave(recorded.ordinal, recorded.unitIds, startedAt)
    }

  private fun waveHistory(artifacts: Map<String, Any?>?): FeatureTaskRuntimePlanFanOutHistory? =
    artifacts
      ?.let(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_FAN_OUT_HISTORY::value)
      ?.let { raw ->
        decodePlanFanOutHistoryFromArtifact(raw)
      }

  private fun stall(
    phase: StandalonePhaseStatusRecord,
    output: StandalonePhaseOutputSnapshot,
    observedAt: Instant,
  ): Instant? =
    ExecutionProgressStall.detect(
      if (
        phase.finishedAt == null &&
        (
          phase.lifecycleState == IdeStatusLifecycleState.ACTIVE.wireValue ||
            phase.lifecycleState == IdeStatusLifecycleState.BLOCKED.wireValue
        )
      ) {
        ExecutionLiveness.LIVE
      } else {
        ExecutionLiveness.IDLE
      },
      output.latestLineAt,
      observedAt,
    )?.lastProgressAt

  private fun projectActiveWave(
    planState: GoalPlanningStatusSnapshot,
    ids: List<Int>,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    blockedId: Int?,
  ): GoalPlanningStatusSnapshot {
    val activeWave =
      if (blockedId != null) {
        emptyList()
      } else {
        ids.filter { id ->
          records[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(id)]
            ?.status == WorkflowStepStatus.RUNNING
        }.take(GOAL_PLANNING_WAVE_CAP)
      }
    return if (activeWave.isEmpty()) {
      planState
    } else {
      planState.copy(
        currentPlanningSubtaskId = activeWave.min(),
        planningWaveSubtaskIds = activeWave,
      )
    }
  }

  private fun stages(
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    briefingTexts: Map<String, String>,
  ): List<StandalonePlanStage> {
    return listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
    ).map { id ->
      records[id].let { record ->
        StandalonePlanStage(
          id,
          record?.firstStartedAt,
          record?.finishedAt,
          briefingTexts[id],
          record?.status?.wireValue,
        )
      }
    }
  }

  private fun units(
    manifestUnits: List<StandalonePlanSubtask>,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
    output: StandalonePhaseOutputSnapshot,
  ): List<StandalonePlanUnit> {
    return manifestUnits.map { subtask ->
      val record = records[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtask.id)]
      StandalonePlanUnit(
        subtask.id,
        subtask.name,
        subtask.specPath,
        subtask.dependsOn,
        standalonePlanUnitState(record),
        record?.startedAt,
        record?.finishedAt,
        record?.blockedReason,
        output.latestAttributedLines[subtask.id],
        output.latestAttributedLineAt[subtask.id],
      )
    }
  }

  private fun isSaved(record: FeatureTaskRuntimePhaseRecord): Boolean =
    record.status == WorkflowStepStatus.COMPLETED && !record.outputArtifact.isNullOrBlank()
}

internal fun standalonePlanUnitState(record: FeatureTaskRuntimePhaseRecord?): StandalonePlanUnitState =
  when {
    record?.status == WorkflowStepStatus.BLOCKED || !record?.blockedReason.isNullOrBlank() ->
      StandalonePlanUnitState.BLOCKED
    record != null && record.status == WorkflowStepStatus.COMPLETED && !record.outputArtifact.isNullOrBlank() ->
      StandalonePlanUnitState.SAVED
    record?.finishedAt == null && record != null -> StandalonePlanUnitState.WRITING
    else -> StandalonePlanUnitState.QUEUED
  }
