package skillbill.cli.kernel.work

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.work.WorkIncidentWireKeys
import skillbill.contracts.workflow.identity.status.IdeStatusPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkBoardPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkDetailPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.contracts.workflow.payload.WorktreeEditJournalPayloadKeys
import skillbill.goalrunner.model.GoalRunnerAssessmentResult
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.WorktreeEditSource
import skillbill.ports.idestatus.model.StandalonePlanActivity
import skillbill.ports.idestatus.model.StandalonePlanProgress
import skillbill.ports.idestatus.model.StandalonePlanUnit
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailPhase
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.contracts.workflow.featuretask.GoalAssessmentRecordPayloadKeys as AssessmentKeys

internal fun workBoardDiagnosticWireMap(diagnostic: WorkBoardDiagnostic): Map<String, Any?> =
  buildMap {
    put(WorkBoardPayloadKeys.REASON, diagnostic.reason.wireValue)
    put(WorkBoardPayloadKeys.REPOSITORY_IDENTITY, diagnostic.repositoryIdentity)
    diagnostic.workflowId?.let { put(SharedPayloadKeys.WORKFLOW_ID, it) }
    diagnostic.invocationId?.let { put(IdeStatusPayloadKeys.INVOCATION_ID, it) }
    diagnostic.failure?.let { put(WorkBoardPayloadKeys.FAILURE, it) }
    diagnostic.recoveryGuidance?.let { put(WorkBoardPayloadKeys.RECOVERY_GUIDANCE, it) }
  }

internal fun workDetailItemWireMap(
  item: WorkDetailItem,
  snapshot: Map<String, Any?>,
): Map<String, Any?> =
  buildMap {
    put(WorkBoardPayloadKeys.SNAPSHOT, snapshot)
    put(WorkIncidentWireKeys.INCIDENT, item.incident?.let { workIncidentWireMap(it, item.recoveryWorkId) })
    put(
      WorkIncidentWireKeys.INCIDENT_HISTORY,
      item.incidentHistory.map { workIncidentWireMap(it, item.recoveryWorkId) },
    )
    put(WorkBoardPayloadKeys.REPOSITORY_ROOT, item.repositoryRoot)
    put(WorkBoardPayloadKeys.FINISHED_AT, item.finishedAt?.toString())
    put(WorkBoardPayloadKeys.FINISHED_AT_SOURCE, item.finishedAtSource?.wireValue)
    put(WorkDetailPayloadKeys.BUILD, item.build?.let(::workBuildWireMap))
    put(WorkDetailPayloadKeys.APP_RUN_PLATFORMS, item.appRunPlatforms)
    put(
      DecompositionPlanningPayloadKeys.DEPENDENCIES,
      item.dependencies.map { entry ->
        mapOf(
          SharedPayloadKeys.SUBTASK_ID to entry.subtaskId,
          DecompositionPlanningPayloadKeys.DEPENDENCIES to
            mapOf(
              SharedPayloadKeys.SUBTASK_ID to entry.dependency.subtaskId,
              DecompositionPlanningPayloadKeys.OPTIONAL to entry.dependency.optional,
              DecompositionPlanningPayloadKeys.SKIPPED to entry.dependency.skipped,
            ),
        )
      },
    )
    put(WorkDetailPayloadKeys.STEP_PIPELINE, item.stepPipeline)
    putAll(planOutputWireMap(item))
    put(WorkDetailPayloadKeys.PLAN_ACTIVITY, item.planActivity.map(::planActivityWireMap))
    item.standalonePlanProgress?.let {
      put(WorkDetailPayloadKeys.STANDALONE_PLAN_PROGRESS, standalonePlanProgressWireMap(it))
    }
    put(
      WorkDetailPayloadKeys.EXECUTION_HISTORY,
      item.executionHistory.map { instance ->
        mapOf(
          IdeStatusPayloadKeys.INVOCATION_ID to instance.invocationId,
          IdeStatusPayloadKeys.EXECUTION_ID to instance.executionId,
          IdeStatusPayloadKeys.LIFECYCLE_STATE to instance.lifecycleState,
          WorkflowWirePayloadKeys.STARTED_AT to instance.startedAt.toString(),
          WorkflowWirePayloadKeys.UPDATED_AT to instance.updatedAt.toString(),
          WorkBoardPayloadKeys.FINISHED_AT to instance.finishedAt?.toString(),
          WorkDetailPayloadKeys.EXECUTION_RESULT to instance.result,
        )
      },
    )
    item.runtimeStatus?.let { status ->
      put(WorkDetailPayloadKeys.PHASES, status.phases.map(::phaseWireMap))
      put(WorkDetailPayloadKeys.CURRENT_PHASE, status.currentPhase)
      put(WorkDetailPayloadKeys.COMPLETE_COUNT, status.completeCount)
      put(WorkDetailPayloadKeys.PENDING_COUNT, status.pendingCount)
      put(WorkDetailPayloadKeys.BLOCKED_COUNT, status.blockedCount)
    }
    item.goalManifest?.let { putAll(goalManifestWireMap(it, item.goalFinishPipeline)) }
    item.goalStatus?.let { putAll(goalStatusWireMap(it)) }
  }

private fun standalonePlanProgressWireMap(progress: StandalonePlanProgress): Map<String, Any?> =
  linkedMapOf(
    WorkDetailPayloadKeys.PLAN_REPORT to
      progress.report?.let { report ->
        linkedMapOf(
          WorkDetailPayloadKeys.FEATURE_NAME to report.featureName,
          WorkDetailPayloadKeys.PARENT_SPEC_PATH to report.parentSpecPath,
          WorkDetailPayloadKeys.SUBTASKS to
            report.subtasks.map { subtask ->
              linkedMapOf(
                SharedPayloadKeys.SUBTASK_ID to subtask.id,
                WorkDetailPayloadKeys.NAME to subtask.name,
                WorkDetailPayloadKeys.SPEC_PATH to subtask.specPath,
                WorkDetailPayloadKeys.DEPENDS_ON to subtask.dependsOn,
              )
            },
          WorkDetailPayloadKeys.BUNDLE_FILES to report.bundleFiles,
        )
      },
    WorkDetailPayloadKeys.PLAN_STAGES to
      progress.stages.map { stage ->
        linkedMapOf(
          WorkDetailPayloadKeys.PHASE_ID to stage.phaseId,
          WorkDetailPayloadKeys.STATUS to stage.status,
          WorkDetailPayloadKeys.FIRST_STARTED_AT to stage.firstStartedAt?.toString(),
          WorkBoardPayloadKeys.FINISHED_AT to stage.finishedAt?.toString(),
          WorkDetailPayloadKeys.BRIEFING_TEXT to stage.briefingText,
        )
      },
    WorkDetailPayloadKeys.PLANNED_SUBTASK_COUNT to progress.plannedSubtaskCount,
    WorkDetailPayloadKeys.TOTAL_SUBTASK_COUNT to progress.totalSubtaskCount,
    WorkDetailPayloadKeys.CURRENT_PLANNING_SUBTASK_ID to progress.currentPlanningSubtaskId,
    WorkDetailPayloadKeys.PLANNING_WAVE_SUBTASK_IDS to progress.planningWaveSubtaskIds,
    WorkDetailPayloadKeys.PLANNING_WAVE_NUMBER to progress.planningWaveNumber,
    WorkDetailPayloadKeys.PLANNING_WAVE_CAPACITY to progress.planningWaveCapacity,
    WorkDetailPayloadKeys.PLANNING_WAVE_HISTORY_COMPLETE to progress.planningWaveHistoryComplete,
    WorkDetailPayloadKeys.BLOCKED_SUBTASK_ID to progress.blockedSubtaskId,
    WorkDetailPayloadKeys.BLOCKED_REASON to progress.blockedReason,
    WorkDetailPayloadKeys.PLAN_UNITS to progress.units.map(::standalonePlanUnitWireMap),
    WorkDetailPayloadKeys.PLANNING_WAVES to
      progress.waves.map { wave ->
        mapOf(
          WorkDetailPayloadKeys.WAVE_NUMBER to wave.number,
          WorkDetailPayloadKeys.WAVE_SUBTASK_IDS to wave.subtaskIds,
          WorkDetailPayloadKeys.STARTED_AT to wave.startedAt?.toString(),
        )
      },
    WorkDetailPayloadKeys.STALLED_SINCE to progress.stalledSince?.toString(),
  )

private fun standalonePlanUnitWireMap(unit: StandalonePlanUnit): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.SUBTASK_ID to unit.subtaskId,
    WorkDetailPayloadKeys.NAME to unit.name,
    WorkDetailPayloadKeys.SPEC_PATH to unit.specPath,
    WorkDetailPayloadKeys.DEPENDS_ON to unit.dependsOn,
    WorkDetailPayloadKeys.UNIT_STATE to unit.state.wireValue,
    WorkDetailPayloadKeys.STARTED_AT to unit.startedAt?.toString(),
    WorkBoardPayloadKeys.FINISHED_AT to unit.finishedAt?.toString(),
    WorkDetailPayloadKeys.BLOCKED_REASON to unit.blockedReason,
    WorkDetailPayloadKeys.LATEST_OUTPUT to unit.latestOutput,
    WorkDetailPayloadKeys.LATEST_OUTPUT_AT to unit.latestOutputAt?.toString(),
  )

private fun phaseWireMap(phase: WorkDetailPhase): Map<String, Any?> =
  linkedMapOf(
    WorkDetailPayloadKeys.PHASE_ID to phase.phaseId,
    SharedPayloadKeys.STATUS to phase.status,
    WorkDetailPayloadKeys.ATTEMPT_COUNT to phase.attemptCount,
    WorkDetailPayloadKeys.RESOLVED_AGENT to phase.resolvedAgent,
    WorkDetailPayloadKeys.FINISHED to phase.finished,
    WorkDetailPayloadKeys.EXECUTION_ORIGIN to phase.executionOrigin,
    WorkDetailPayloadKeys.CONTINUATION_KIND to phase.continuationKind,
    WorkDetailPayloadKeys.LAUNCHED_MODEL to phase.launchedModel,
    WorkDetailPayloadKeys.EFFORT to phase.effort,
  )

private fun goalManifestWireMap(
  manifest: DecompositionManifest,
  finishPipeline: List<String>,
): Map<String, Any?> =
  linkedMapOf(
    WorkDetailPayloadKeys.FEATURE_TITLE to manifest.featureName,
    WorkDetailPayloadKeys.GOAL_FINISH_PIPELINE to finishPipeline,
    WorkDetailPayloadKeys.SUBTASKS to
      manifest.subtasks.map { subtask ->
        linkedMapOf(
          SharedPayloadKeys.SUBTASK_ID to subtask.id,
          DecompositionPlanningPayloadKeys.NAME to subtask.name,
          SharedPayloadKeys.STATUS to subtask.status,
          DecompositionManifestPayloadKeys.COMMIT_SHA to subtask.commitSha,
          DecompositionManifestPayloadKeys.BLOCKED_REASON to subtask.blockedReason,
          DecompositionPlanningPayloadKeys.DEPENDENCIES to
            subtask.dependencies.map { dependency ->
              linkedMapOf(
                DecompositionPlanningPayloadKeys.SUBTASK_ID to dependency.subtaskId,
                DecompositionPlanningPayloadKeys.OPTIONAL to dependency.optional,
                DecompositionPlanningPayloadKeys.SKIPPED to dependency.skipped,
              )
            },
        )
      },
  )

private fun goalStatusWireMap(projection: GoalRunnerStatusProjection): Map<String, Any?> =
  buildMap {
    put(WorkDetailPayloadKeys.ACTIVE_AGENT, projection.activeAgent)
    put(WorkDetailPayloadKeys.EXECUTION_LIVENESS, projection.executionLiveness.wireValue)
    projection.progressStall?.let { stall ->
      put(WorkDetailPayloadKeys.PROGRESS_STALLED_SINCE, stall.lastProgressAt.toString())
    }
    put(WorkDetailPayloadKeys.PHASE_ATTEMPT_COUNTS, projection.phaseAttemptCounts)
    put(WorkDetailPayloadKeys.CUMULATIVE_FIX_ITERATIONS, projection.cumulativeFixIterations)
    put(WorkDetailPayloadKeys.RE_ATTEMPT_CAUSE_COUNTS, projection.reAttemptCauseCounts)
    put(WorkDetailPayloadKeys.FINDINGS_IN_SCOPE, projection.findingsInScope)
    put(WorkDetailPayloadKeys.SUPERVISOR_KILL_COUNT, projection.supervisorKillCount)
    projection.latestObservabilityEvent?.let { event ->
      put(WorkDetailPayloadKeys.LATEST_OBSERVABILITY_EVENT, event.toCompactSummaryWire().toMap())
    }
    projection.latestWorktreeEdit?.let { edit ->
      put(
        WorkDetailPayloadKeys.LATEST_WORKTREE_EDIT,
        linkedMapOf(
          WorktreeEditJournalPayloadKeys.RECORDED_AT to edit.recordedAt.toString(),
          WorktreeEditJournalPayloadKeys.PHASE_ID to edit.phaseId,
          WorktreeEditJournalPayloadKeys.PATH_SAMPLE to edit.pathSample,
          WorktreeEditJournalPayloadKeys.NET_INSERTIONS to edit.netInsertions,
          WorktreeEditJournalPayloadKeys.NET_DELETIONS to edit.netDeletions,
          WorktreeEditJournalPayloadKeys.SOURCE to WorktreeEditSource.WORKTREE_PROBE.wireValue,
        ),
      )
    }
    put(
      WorkDetailPayloadKeys.COMPLETED_SUBTASK_VALIDATION,
      projection.completedSubtaskValidation.map { it.toStatusWire().toMap() },
    )
    projection.goalAssessmentResult?.let { put(WorkDetailPayloadKeys.GOAL_ASSESSMENT, goalAssessmentWireMap(it)) }
  }

private fun goalAssessmentWireMap(result: GoalRunnerAssessmentResult): Map<String, Any?> =
  linkedMapOf(
    AssessmentKeys.STATE to result.state.wireValue,
    AssessmentKeys.ROUND to result.round,
    AssessmentKeys.EXIT_REASON to result.exitReason?.wireValue,
    WorkDetailPayloadKeys.FIX_RAN to result.fixRan,
    AssessmentKeys.OPEN_FINDING_REFS to result.openFindingRefs,
    AssessmentKeys.FINDINGS to
      result.findings.map { finding ->
        linkedMapOf(
          AssessmentKeys.FINDING_REF to finding.ref,
          AssessmentKeys.SEVERITY to finding.severity.wireValue,
          AssessmentKeys.PATH to finding.path,
          AssessmentKeys.LINE to finding.line,
          AssessmentKeys.FAILURE_SCENARIO to finding.failureScenario,
          AssessmentKeys.VERIFICATION to finding.verification.wireValue,
          AssessmentKeys.STATE to finding.state.wireValue,
          AssessmentKeys.EVIDENCE to finding.evidence,
        )
      },
  )

private fun planActivityWireMap(activity: StandalonePlanActivity): Map<String, Any?> {
  val event = activity.event
  return mapOf(
    WorkDetailPayloadKeys.OUTPUT_ID to event.id,
    WorkDetailPayloadKeys.PHASE_ID to activity.phaseId,
    WorkDetailPayloadKeys.ACTIVITY_KIND to event.kind.wireValue,
    WorkDetailPayloadKeys.OUTPUT_TEXT to event.text,
    WorkDetailPayloadKeys.OUTPUT_AT to activity.at.toString(),
    WorkflowWirePayloadKeys.UPDATED_AT to activity.updatedAt.toString(),
    WorkDetailPayloadKeys.ACTIVITY_AGENT to event.agentId,
    WorkDetailPayloadKeys.ACTIVITY_PATH to event.path,
    SharedPayloadKeys.STATUS to event.state.wireValue,
    WorkDetailPayloadKeys.ACTIVITY_DURATION_MS to event.durationMs,
    WorkDetailPayloadKeys.ACTIVITY_EXIT_CODE to event.exitCode,
    SharedPayloadKeys.SUBTASK_ID to event.subtaskId,
  )
}

private fun planOutputWireMap(item: WorkDetailItem): Map<String, Any?> =
  buildMap {
    if (item.stepPipeline.lastOrNull() == "plan_subtasks") {
      put(
        WorkDetailPayloadKeys.PLAN_OUTPUT_LINES,
        item.planOutputLines.map { line ->
          mapOf(
            WorkDetailPayloadKeys.OUTPUT_ID to line.id,
            WorkDetailPayloadKeys.OUTPUT_TEXT to line.text,
            WorkDetailPayloadKeys.OUTPUT_AT to line.at.toString(),
            SharedPayloadKeys.SUBTASK_ID to line.subtaskId,
            WorkDetailPayloadKeys.OUTPUT_SEQUENCE to line.sequence,
            WorkDetailPayloadKeys.OUTPUT_RAW to line.isRaw,
          )
        },
      )
      put(WorkDetailPayloadKeys.PLAN_OUTPUT_TAIL, item.planOutputTail)
      put(WorkDetailPayloadKeys.PLAN_OUTPUT_UPDATED_AT, item.planOutputUpdatedAt?.toString())
      put(WorkDetailPayloadKeys.PLAN_SUBTASK_LATEST_LINES, item.planSubtaskLatestLines.mapKeys { it.key.toString() })
    }
  }
