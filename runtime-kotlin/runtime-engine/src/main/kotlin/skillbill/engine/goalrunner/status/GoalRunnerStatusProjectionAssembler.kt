package skillbill.engine.goalrunner.status

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.payload.WorktreeEditJournalPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.continuation.agentAttributionFromPhaseState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.featuretask.review.core.auditGapIterationCount
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeStatusService
import skillbill.engine.goalrunner.execution.core.asWorkerOwnership
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.pruneEligibleCheckpointRefsForManifest
import skillbill.engine.goalrunner.manifest.reconcileGoalManifest
import skillbill.engine.goalrunner.manifest.toAcceptedSubtasks
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.persist.GoalRunnerAttemptLedgerStore
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.planning.model.GoalPlanningStatusAlignRequest
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningStatusReasonCoherence
import skillbill.engine.goalrunner.planning.recovery.resolveChildExecutionLiveness
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerStatusProjectionRuntimeInputs
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.goalrunner.model.GoalRunnerSubtaskValidationEvidence
import skillbill.idestatus.model.WorktreeEditSummary
import skillbill.idestatus.model.summary
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.io.IOException

@Inject
class GoalRunnerStatusProjectionAssembler(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val phaseQuery: FeatureTaskRuntimePhaseQuery,
  private val attemptLedgerStore: GoalRunnerAttemptLedgerStore,
  private val database: DatabaseSessionFactory,
  private val gitOperations: WorkflowGitOperations,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val planningStatusReasonCoherence: GoalPlanningStatusReasonCoherence,
  private val diagnostics: RuntimeDiagnostics,
  private val runtimeStatusService: FeatureTaskRuntimeStatusService?,
  private val repositoryRoot: RepositoryRoot,
) {
  fun project(
    loadedState: GoalRunnerManifestState,
    request: GoalRunnerStatusRequest,
  ): GoalRunnerStatusProjection {
    val acceptances = manifestStore.outOfBandAcceptances(loadedState.parentWorkflowId)
    val manifest = reconcileStatusManifest(loadedState, request, acceptances)
    val currentSubtask =
      manifest.subtasks.firstOrNull { subtask ->
        subtask.id == manifest.currentSubtaskIntent.subtaskId
      }
    return GoalRunnerStatusProjector.project(
      manifest = manifest,
      activeAgent = resolveActiveAgent(currentSubtask),
      extras =
        statusProjectionRuntimeInputs(
          loadedState = loadedState,
          request = request,
          manifest = manifest,
          currentSubtask = currentSubtask,
          acceptances = acceptances,
        ),
    )
  }

  internal fun resolveExecutionLiveness(
    parentWorkflowId: String,
    currentSubtask: DecompositionSubtask?,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): ExecutionLiveness {
    val workflowId =
      currentSubtask?.workflowId?.takeIf(String::isNotBlank)
        ?: return resolveParentExecutionLiveness(parentWorkflowId, durableRead)
    val childLiveness = resolveChildExecutionLiveness(workflowId, durableRead)
    if (childLiveness == ExecutionLiveness.LIVE || childLiveness == ExecutionLiveness.UNKNOWN) {
      return childLiveness
    }
    return resolveParentExecutionLiveness(parentWorkflowId, durableRead)
  }

  internal fun resolvePurgeBlockingLiveness(
    parentWorkflowId: String,
    childWorkflowIds: List<String>,
  ): ExecutionLiveness? {
    val durableRead = GoalRunnerStatusDurableReadTracker(diagnostics)
    val parentLiveness = resolveParentExecutionLiveness(parentWorkflowId, durableRead)
    if (parentLiveness == ExecutionLiveness.LIVE || parentLiveness == ExecutionLiveness.UNKNOWN) {
      return parentLiveness
    }
    childWorkflowIds.forEach { childWorkflowId ->
      val childLiveness = resolveChildExecutionLiveness(childWorkflowId, durableRead)
      if (childLiveness == ExecutionLiveness.LIVE || childLiveness == ExecutionLiveness.UNKNOWN) {
        return childLiveness
      }
    }
    return null
  }

  private fun statusProjectionRuntimeInputs(
    loadedState: GoalRunnerManifestState,
    request: GoalRunnerStatusRequest,
    manifest: DecompositionManifest,
    currentSubtask: DecompositionSubtask?,
    acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  ): GoalRunnerStatusProjectionRuntimeInputs {
    val durableRead = GoalRunnerStatusDurableReadTracker(diagnostics)
    val childWorkflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank)
    val progress = childWorkflowId?.let { workflowId -> outcomeStore.progress(workflowId) }
    val ledgerSummary =
      runCatching {
        attemptLedgerStore.readAttemptLedgerSummary(loadedState.manifest.issueKey)
      }.getOrElse { error ->
        durableRead.recordDegradedRead(
          seam = "goal-status.attempt_ledger",
          expected = "ledger_summary",
          used = "degraded",
          error = error,
        )
        null
      }
    return buildStatusProjectionRuntimeInputs(
      GoalStatusProjectionRuntimeAssembly(
        loadedState = loadedState,
        request = request,
        manifest = manifest,
        currentSubtask = currentSubtask,
        acceptances = acceptances,
        durableRead = durableRead,
        progress = progress,
        ledgerSummary = ledgerSummary,
        childWorkflowId = childWorkflowId,
        latestWorktreeEdit = latestWorktreeEditSummary(childWorkflowId, durableRead),
        auditAcRetryCount = measuredAuditAcRetryCount(childWorkflowId, durableRead),
      ),
    )
  }

  private data class GoalStatusProjectionRuntimeAssembly(
    val loadedState: GoalRunnerManifestState,
    val request: GoalRunnerStatusRequest,
    val manifest: DecompositionManifest,
    val currentSubtask: DecompositionSubtask?,
    val acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
    val durableRead: GoalRunnerStatusDurableReadTracker,
    val progress: GoalRunnerWorkflowProgress?,
    val ledgerSummary: GoalRunnerAttemptLedgerSummary?,
    val childWorkflowId: String?,
    val latestWorktreeEdit: WorktreeEditSummary?,
    val auditAcRetryCount: Int?,
  )

  private fun buildStatusProjectionRuntimeInputs(
    assembly: GoalStatusProjectionRuntimeAssembly,
  ): GoalRunnerStatusProjectionRuntimeInputs =
    GoalRunnerStatusProjectionRuntimeInputs(
      executionLiveness =
        resolveExecutionLiveness(
          parentWorkflowId = assembly.loadedState.parentWorkflowId,
          currentSubtask = assembly.currentSubtask,
          durableRead = assembly.durableRead,
        ),
      planning =
        alignedPlanningStatus(
          assembly.loadedState,
          assembly.request,
          assembly.manifest,
          assembly.currentSubtask,
        ),
      currentStepOverride =
        assembly.progress?.currentStepId
          ?: derivedChildCurrentStep(assembly.childWorkflowId),
      currentWorkflowStatus = assembly.progress?.workflowStatus,
      latestLivenessSignal = assembly.progress?.latestLivenessSignal,
      latestObservabilityEvent = assembly.progress?.latestGoalObservabilityEvent?.toObservabilityEvent(),
      requestedDiffStat = requestedDiffStat(assembly.request),
      selectedDiffHunks = requestedSelectedDiffHunks(assembly.request),
      blockedAttemptCount = assembly.ledgerSummary?.blockedAttemptCount ?: 0,
      supervisorKillCount = assembly.ledgerSummary?.supervisorKillCount ?: 0,
      phaseAttemptCounts = assembly.ledgerSummary?.phaseAttemptCounts ?: emptyMap(),
      cumulativeFixIterations = assembly.ledgerSummary?.cumulativeFixIterations ?: emptyMap(),
      reAttemptCauseCounts = assembly.ledgerSummary?.reAttemptCauseCounts ?: emptyMap(),
      findingsInScope = assembly.ledgerSummary?.findingsInScope,
      outOfBandAcceptances = assembly.acceptances.toAcceptedSubtasks(),
      completedSubtaskValidation =
        completedSubtaskValidation(
          assembly.manifest,
        ),
      paused = assembly.loadedState.controlState.paused,
      pauseRequested = assembly.loadedState.controlState.pauseRequested,
      pauseReason = assembly.loadedState.controlState.pauseReason,
      pausedAt = assembly.loadedState.controlState.pausedAt,
      stopAfterSubtaskId = assembly.loadedState.controlState.stopAfterSubtaskId,
      activeDurationMs = assembly.loadedState.controlState.activeDurationMs,
      activeDurationAsOf = assembly.loadedState.controlState.activeDurationAsOf,
      subtaskActiveDurationMs = assembly.loadedState.controlState.subtaskActiveDurationMs,
      subtaskActiveDurationAsOf = assembly.loadedState.controlState.subtaskActiveDurationAsOf,
      degradedDurableRead = assembly.durableRead.degraded,
      latestWorktreeEdit = assembly.latestWorktreeEdit,
      auditAcRetryCount = assembly.auditAcRetryCount,
    )

  private fun latestWorktreeEditSummary(
    childWorkflowId: String?,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ) = childWorkflowId?.let { workflowId ->
    runCatching {
      database.readIfPresent { unitOfWork ->
        unitOfWork.worktreeEditJournal.latestTick(workflowId)
      }
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.worktree_edit_journal",
        expected = "latest_tick",
        used = "omitted",
        error = error,
      )
      null
    }?.summary(WorktreeEditJournalPayloadKeys.PATH_SAMPLE_LIMIT)
  }

  private fun measuredAuditAcRetryCount(
    childWorkflowId: String?,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ) = childWorkflowId?.let { workflowId ->
    runCatching {
      auditGapIterationCount(phaseQuery.loadPhaseLedger(workflowId))
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.audit_ac_retry_count",
        expected = "ledger_count",
        used = "omitted",
        error = error,
      )
      null
    }?.takeIf { count -> count > 0 }
  }

  private fun completedSubtaskValidation(manifest: DecompositionManifest): List<GoalRunnerSubtaskValidationEvidence> =
    manifest.subtasks
      .filter { it.status.decompositionStatus() == DecompositionStatus.COMPLETE }
      .map { subtask -> completedSubtaskValidationFor(subtask) }

  private fun completedSubtaskValidationFor(subtask: DecompositionSubtask): GoalRunnerSubtaskValidationEvidence {
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank)
    val record =
      workflowId?.let { phaseQuery.loadPhaseRecords(it) }
        ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    val envelope =
      record?.outputArtifact
        ?.let(JsonCodec::parseObjectOrNull)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    val passed = true.takeIf { envelope?.get(SharedPayloadKeys.STATUS) == WorkflowStepStatus.COMPLETED.wireValue }
    return GoalRunnerSubtaskValidationEvidence(
      subtaskId = subtask.id,
      validationPassed = passed,
      integrityProblem =
        when {
          envelope == null -> "Completed subtask has no boolean validation result."
          passed == null || record.status != WorkflowStepStatus.COMPLETED -> "Validation phase is not completed."
          else -> null
        },
    )
  }

  private fun alignedPlanningStatus(
    loadedState: GoalRunnerManifestState,
    request: GoalRunnerStatusRequest,
    manifest: DecompositionManifest,
    currentSubtask: DecompositionSubtask?,
  ) = currentSubtask?.takeIf { subtask ->
    subtask.status.decompositionStatus() == DecompositionStatus.BLOCKED &&
      subtask.lastResumableStep in setOf("preplan", "plan")
  }.let { planningBlock ->
    manifestStore.planningStatus(
      loadedState.parentWorkflowId,
      manifest.subtasks.filter { it.status.decompositionStatus() != DecompositionStatus.SKIPPED }.map { it.id },
      planningBlock?.id,
      planningBlock?.blockedReason,
    )?.let { snapshot ->
      planningStatusReasonCoherence.align(
        GoalPlanningStatusAlignRequest(
          snapshot = snapshot,
          parentWorkflowId = loadedState.parentWorkflowId,
          issueKey = manifest.issueKey,
          manifest = manifest,
          repoRoot = request.repoRoot ?: repositoryRoot.path,
        ),
      )
    }
  }

  private fun reconcileStatusManifest(
    state: GoalRunnerManifestState,
    request: GoalRunnerStatusRequest,
    acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  ): DecompositionManifest {
    val reconciled =
      reconcileGoalManifest(
        manifest = state.manifest,
        authoritativeOutcomes = outcomeStore.authoritativeOutcomes(state.manifest.issueKey),
        acceptances = acceptances,
        outcomeStore = outcomeStore,
      )
    request.repoRoot?.let { repoRoot ->
      pruneEligibleCheckpointRefsForManifest(
        manifest = reconciled,
        gitOperations = gitOperations,
        repoRoot = repoRoot,
        record = {},
      )
    }
    return reconciled
  }

  private fun derivedChildCurrentStep(childWorkflowId: String?): String? {
    val workflowId = childWorkflowId?.takeIf(String::isNotBlank) ?: return null
    val statusService = runtimeStatusService ?: return null
    return try {
      statusService.status(
        FeatureTaskRuntimeStatusRequest(
          workflowId = workflowId,
        ),
      )?.currentPhaseId?.takeIf(String::isNotBlank)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Goal status omitted derived child phase for workflow '$workflowId': " +
          "the child's durable status could not be read.",
        error,
      )
      null
    } catch (error: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Goal status omitted derived child phase for workflow '$workflowId': " +
          "the child's durable status could not be read.",
        error,
      )
      null
    }
  }

  private fun resolveChildExecutionLiveness(
    workflowId: String,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): ExecutionLiveness =
    runCatching {
      if (phaseQuery.existingWorkflowMode(workflowId) != FeatureTaskWorkflowMode.RUNTIME) {
        ExecutionLiveness.UNKNOWN
      } else {
        val ownership = phaseQuery.workerOwnership(workflowId)
        if (ownership == null) {
          ExecutionLiveness.IDLE
        } else {
          livenessOfLeaseOwner(ownership)
        }
      }
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.child_execution_liveness",
        expected = "live_or_idle",
        used = ExecutionLiveness.UNKNOWN.wireValue,
        error = error,
      )
      ExecutionLiveness.UNKNOWN
    }

  private fun resolveParentExecutionLiveness(
    parentWorkflowId: String,
    durableRead: GoalRunnerStatusDurableReadTracker,
  ): ExecutionLiveness =
    runCatching {
      val lease =
        manifestStore.executionLease(parentWorkflowId)
          ?: return@runCatching ExecutionLiveness.IDLE
      livenessOfLeaseOwner(lease.asWorkerOwnership(parentWorkflowId))
    }.getOrElse { error ->
      durableRead.recordDegradedRead(
        seam = "goal-status.parent_execution_liveness",
        expected = "live_or_idle",
        used = ExecutionLiveness.UNKNOWN.wireValue,
        error = error,
      )
      ExecutionLiveness.UNKNOWN
    }

  private fun livenessOfLeaseOwner(ownership: FeatureTaskRuntimeWorkerOwnership): ExecutionLiveness =
    when (workerSupervisor.inspect(ownership)) {
      FeatureTaskRuntimeProcessInspection.NotRunning -> ExecutionLiveness.IDLE
      FeatureTaskRuntimeProcessInspection.ExactLive,
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch,
      is FeatureTaskRuntimeProcessInspection.Unsupported,
      -> ExecutionLiveness.LIVE
    }

  private fun resolveActiveAgent(currentSubtask: DecompositionSubtask?): String? {
    if (currentSubtask == null) return null
    val workflowId = currentSubtask.workflowId?.takeIf(String::isNotBlank)
    if (workflowId != null &&
      phaseQuery.existingWorkflowMode(workflowId) == FeatureTaskWorkflowMode.RUNTIME
    ) {
      agentAttributionFromPhaseState(phaseQuery, workflowId).finalizingAgentId
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    }
    return currentSubtask.finalizingAgentId?.takeIf(String::isNotBlank)
      ?: currentSubtask.participatingAgentIds.firstOrNull()?.takeIf(String::isNotBlank)
  }

  private fun requestedDiffStat(request: GoalRunnerStatusRequest) =
    if (request.includeDiffStat) {
      request.repoRoot
        ?.let(gitOperations::worktreeActivity)
        ?.takeIf { result -> result.status == WorkflowGitOperationStatus.OK }
        ?.diffStat
    } else {
      null
    }

  private fun requestedSelectedDiffHunks(request: GoalRunnerStatusRequest) =
    if (request.selectedDiffHunkPaths.isNotEmpty()) {
      request.repoRoot
        ?.let { root ->
          gitOperations.selectedDiffHunks(
            root,
            WorkflowSelectedDiffHunksRequest(
              paths = request.selectedDiffHunkPaths,
              maxHunks = request.selectedDiffMaxHunks,
              maxLines = request.selectedDiffMaxLines,
              maxBytes = request.selectedDiffMaxBytes,
            ),
          )
        }
        ?.takeIf { result -> result.status == WorkflowGitOperationStatus.OK }
        ?.selectedDiffHunks
    } else {
      null
    }
}
