package skillbill.cli.work

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.identity.status.IdeStatusPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkBoardPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkDetailPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.contracts.workflow.payload.WorktreeEditJournalPayloadKeys
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.WorktreeEditSource
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailPhase
import skillbill.workflow.decomposition.model.DecompositionManifest

internal fun workBoardDiagnosticWireMap(diagnostic: WorkBoardDiagnostic): Map<String, Any?> =
  buildMap {
    put(WorkBoardPayloadKeys.REASON, diagnostic.reason.wireValue)
    put(WorkBoardPayloadKeys.REPOSITORY_IDENTITY, diagnostic.repositoryIdentity)
    diagnostic.workflowId?.let { put(SharedPayloadKeys.WORKFLOW_ID, it) }
    diagnostic.invocationId?.let { put(IdeStatusPayloadKeys.INVOCATION_ID, it) }
  }

internal fun workDetailItemWireMap(
  item: WorkDetailItem,
  snapshot: Map<String, Any?>,
): Map<String, Any?> =
  buildMap {
    put(WorkBoardPayloadKeys.SNAPSHOT, snapshot)
    put(WorkBoardPayloadKeys.REPOSITORY_ROOT, item.repositoryRoot)
    put(WorkBoardPayloadKeys.FINISHED_AT, item.finishedAt?.toString())
    put(WorkBoardPayloadKeys.FINISHED_AT_SOURCE, item.finishedAtSource?.wireValue)
    put(WorkDetailPayloadKeys.STEP_PIPELINE, item.stepPipeline)
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
    item.goalManifest?.let { putAll(goalManifestWireMap(it, item.goalFinishPipeline, item.pageOffset, item.pageSize)) }
    item.goalStatus?.let { putAll(goalStatusWireMap(it)) }
  }

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
  offset: Int,
  pageSize: Int,
): Map<String, Any?> =
  linkedMapOf(
    WorkDetailPayloadKeys.FEATURE_TITLE to manifest.featureName,
    WorkDetailPayloadKeys.GOAL_FINISH_PIPELINE to finishPipeline,
    WorkDetailPayloadKeys.SUBTASKS to
      manifest.subtasks.drop(offset).take(pageSize).map { subtask ->
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
  }
