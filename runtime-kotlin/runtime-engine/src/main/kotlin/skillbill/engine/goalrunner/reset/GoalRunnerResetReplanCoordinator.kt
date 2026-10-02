package skillbill.engine.goalrunner.reset

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.checkpoint.pruneResetSubtaskCheckpointRefs
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.toAcceptedSubtasks
import skillbill.engine.goalrunner.manifest.toResetSnapshot
import skillbill.engine.goalrunner.model.GoalRunnerChildRecoveryDiagnostic
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerReplanRequest
import skillbill.engine.goalrunner.model.GoalRunnerReplanResult
import skillbill.engine.goalrunner.model.GoalRunnerReplanSnapshot
import skillbill.engine.goalrunner.model.GoalRunnerResetRequest
import skillbill.engine.goalrunner.model.GoalRunnerResetResult
import skillbill.engine.goalrunner.model.GoalRunnerResetSnapshot
import skillbill.engine.goalrunner.model.GoalRunnerResetSubtaskSnapshot
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.status.GoalRunnerStatusDurableReadTracker
import skillbill.engine.goalrunner.status.GoalRunnerStatusProjectionAssembler
import skillbill.engine.recovery.DurableChildRecoveryClass
import skillbill.engine.recovery.classifyDurableChild
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.model.RepositoryRoot
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.replanIntent
import skillbill.workflow.decomposition.resetManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.decompositionStatus
import java.nio.file.Path

@Inject
class GoalRunnerResetReplanCoordinator(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val diagnostics: RuntimeDiagnostics,
  private val projectionAssembler: GoalRunnerStatusProjectionAssembler,
  private val repositoryRoot: RepositoryRoot,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  fun reset(request: GoalRunnerResetRequest): GoalRunnerResetResult? {
    val loaded = loadResetState(request) ?: return null
    if (request.deleteChildWorkflow) return deleteIncompatibleChildWorkflow(request, loaded)
    reconcileResetState(loaded)
    val latest = manifestStore.loadByIssueKey(request.issueKey, request.repoRoot) ?: loaded
    val hardResetRepoRoot = request.takeHardResetRepositoryRoot(latest)
    val coordination = hardResetBranchCoordination(request, latest, hardResetRepoRoot)
    if (coordination is GoalRunnerHardResetBranchCoordination.Refused) {
      return refusedResetResult(latest, coordination)
    }
    val branchActionTaken =
      (coordination as? GoalRunnerHardResetBranchCoordination.Documented)
        ?.branchActionTaken
    val before = latest.manifest.toResetSnapshot()
    val saved = saveResetState(request, latest, before, hardResetRepoRoot)
    val staleChild =
      if (!request.hard) {
        currentChildRecoveryDiagnostic(saved.manifest)
      } else {
        null
      }
    return GoalRunnerResetResult(
      issueKey = saved.manifest.issueKey,
      mode = if (request.hard) "hard" else "soft",
      parentWorkflowId = saved.parentWorkflowId,
      before = before,
      after = saved.manifest.toResetSnapshot(),
      recovery = staleChild,
      branchActionTaken = branchActionTaken,
    )
  }

  private fun loadResetState(request: GoalRunnerResetRequest): GoalRunnerManifestState? =
    if (request.deleteChildWorkflow) {
      manifestStore.loadDurableByIssueKey(request.issueKey)?.copy(repoRoot = request.repoRoot)
    } else {
      manifestStore.loadByIssueKey(request.issueKey, request.repoRoot)
    }

  private fun reconcileResetState(loaded: GoalRunnerManifestState) {
    outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = loaded.manifest.issueKey,
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(allowInactiveReconciliation = true),
    )
  }

  private fun hardResetBranchCoordination(
    request: GoalRunnerResetRequest,
    latest: GoalRunnerManifestState,
    repoRoot: Path?,
  ): GoalRunnerHardResetBranchCoordination {
    if (!request.hard) return GoalRunnerHardResetBranchCoordination.NotApplicable
    val root = requireNotNull(repoRoot)
    return when (val inspection = inspectHardResetOrphanTrap(latest.manifest, root, gitOperations)) {
      GoalRunnerHardResetOrphanTrapInspection.NotApplicable ->
        GoalRunnerHardResetBranchCoordination.NotApplicable
      is GoalRunnerHardResetOrphanTrapInspection.Detected ->
        coordinateHardResetOrphanTrap(inspection.trap, latest.manifest.issueKey, root, gitOperations)
      is GoalRunnerHardResetOrphanTrapInspection.Unavailable ->
        GoalRunnerHardResetBranchCoordination.Refused(
          reason = inspection.reason,
          remedyCommand = inspection.remedyCommand,
        )
    }
  }

  private fun refusedResetResult(
    latest: GoalRunnerManifestState,
    refusal: GoalRunnerHardResetBranchCoordination.Refused,
  ): GoalRunnerResetResult {
    val snapshot = latest.manifest.toResetSnapshot()
    return GoalRunnerResetResult(
      issueKey = latest.manifest.issueKey,
      mode = "hard",
      parentWorkflowId = latest.parentWorkflowId,
      before = snapshot,
      after = snapshot,
      refusalReason = refusal.reason,
      remedyCommand = refusal.remedyCommand,
    )
  }

  private fun saveResetState(
    request: GoalRunnerResetRequest,
    latest: GoalRunnerManifestState,
    before: GoalRunnerResetSnapshot,
    repoRoot: Path?,
  ): GoalRunnerManifestState {
    val resetState = latest.copy(manifest = latest.manifest.resetManifest(request.hard))
    val saved =
      if (request.hard) {
        manifestStore.saveHardReset(resetState, request.preservePlanning)
      } else {
        manifestStore.save(resetState)
      }
    if (request.hard) {
      pruneResetSubtaskCheckpointRefs(
        gitOperations = gitOperations,
        repoRoot = requireNotNull(repoRoot),
        issueKey = saved.manifest.issueKey,
        subtaskIds = before.subtasks.map { it.id },
        record = { message -> RuntimeDiagnosticsBestEffortWarning.record(diagnostics, message) },
      )
    }
    return saved
  }

  fun replan(request: GoalRunnerReplanRequest): GoalRunnerReplanResult? {
    val loaded =
      manifestStore.loadDurableByIssueKey(request.issueKey)
        ?: return null
    val selected = requireReplanTarget(loaded.manifest, request)
    requireIdleForScopedReplan(loaded, request)
    val beforeSubtasks = loaded.manifest.toResetSnapshot().subtasks
    val expectedSharedDigest =
      if (request.includeSharedPreplan) {
        manifestStore.sharedPreplanPayloadSha256(loaded.parentWorkflowId)
      } else {
        null
      }
    val planningIdentity =
      if (request.includeSharedPreplan && expectedSharedDigest != null) {
        GoalPlanningIdentity(
          parentGoalWorkflowId = loaded.parentWorkflowId,
          normalizedIssueKey = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(loaded.manifest.issueKey),
          repositoryIdentity =
            goalRepositoryIdentity(
              request.repoRoot ?: repositoryRoot.path,
              repositoryEnclosingRootPort,
            ),
        )
      } else {
        null
      }
    val retargeted =
      loaded.copy(
        manifest = loaded.manifest.copy(currentSubtaskIntent = replanIntent(selected)),
        repoRoot = request.repoRoot,
      )
    val written =
      manifestStore.saveScopedReplan(
        state = retargeted,
        subtaskId = request.subtaskId,
        options =
          GoalRunnerScopedReplanOptions(
            includeSharedPreplan = request.includeSharedPreplan,
            expectedSharedPayloadSha256 = expectedSharedDigest,
            planningIdentity = planningIdentity,
          ),
      )
    pruneResetSubtaskCheckpointRefs(
      gitOperations = gitOperations,
      repoRoot = request.repoRoot ?: repositoryRoot.path,
      issueKey = written.state.manifest.issueKey,
      subtaskIds = written.clearedChildSubtaskIds,
      record = { message -> RuntimeDiagnosticsBestEffortWarning.record(diagnostics, message) },
    )
    return toReplanResult(request, loaded, written, beforeSubtasks)
  }

  fun hardResetPreflight(issueKey: String): List<GoalRunnerAcceptedSubtask> {
    val state = manifestStore.loadDurableByIssueKey(issueKey) ?: return emptyList()
    return manifestStore.outOfBandAcceptances(state.parentWorkflowId).toAcceptedSubtasks()
  }

  private fun GoalRunnerResetRequest.takeHardResetRepositoryRoot(latest: GoalRunnerManifestState): Path? {
    if (!hard) return null
    val repoRoot =
      requireNotNull(repoRoot) {
        "A repository root is required for a hard reset so checkpoint refs are pruned from the correct repository."
      }
    manifestStore.bindRepositoryIdentity(
      latest.parentWorkflowId,
      goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort),
    )
    return repoRoot
  }

  private fun requireReplanTarget(
    manifest: DecompositionManifest,
    request: GoalRunnerReplanRequest,
  ): DecompositionSubtask {
    val selected = manifest.subtasks.singleOrNull { it.id == request.subtaskId }
    require(selected != null) {
      "Subtask '${request.subtaskId}' is not part of goal '${request.issueKey}'."
    }
    val terminalStatuses = setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
    require(selected.status.decompositionStatus() !in terminalStatuses) {
      "Subtask '${request.subtaskId}' is terminal (${selected.status}); use reset to reopen it before replanning."
    }
    return selected
  }

  private fun requireIdleForScopedReplan(
    loaded: GoalRunnerManifestState,
    request: GoalRunnerReplanRequest,
  ) {
    val currentSubtask =
      loaded.manifest.subtasks.firstOrNull { subtask ->
        subtask.id == loaded.manifest.currentSubtaskIntent.subtaskId
      }
    val liveness =
      projectionAssembler.resolveExecutionLiveness(
        parentWorkflowId = loaded.parentWorkflowId,
        currentSubtask = currentSubtask,
        durableRead = GoalRunnerStatusDurableReadTracker(diagnostics),
      )
    require(liveness == ExecutionLiveness.IDLE) {
      when (liveness) {
        ExecutionLiveness.LIVE ->
          "Goal '${request.issueKey}' is live; refuse scoped replan while a child or parent run is active."
        ExecutionLiveness.UNKNOWN ->
          "Goal '${request.issueKey}' has unknown execution liveness; refuse scoped replan."
        ExecutionLiveness.IDLE -> "Goal '${request.issueKey}' is idle."
      }
    }
  }

  private fun toReplanResult(
    request: GoalRunnerReplanRequest,
    before: GoalRunnerManifestState,
    written: GoalRunnerScopedReplanWriteResult,
    beforeSubtasks: List<GoalRunnerResetSubtaskSnapshot>,
  ): GoalRunnerReplanResult =
    GoalRunnerReplanResult(
      issueKey = written.state.manifest.issueKey,
      parentWorkflowId = written.state.parentWorkflowId,
      subtaskId = request.subtaskId,
      discardedPlan = written.deletedPlanCount > 0,
      discardedSharedPreplan = written.discardedSharedPreplan,
      cascadedPlanSubtaskIds = written.cascadedPlanSubtaskIds,
      clearedChildSubtaskIds = written.clearedChildSubtaskIds,
      before =
        GoalRunnerReplanSnapshot(
          status = before.manifest.status,
          currentSubtaskId = before.manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 },
          currentAction = before.manifest.currentSubtaskIntent.action,
          sharedPreplanPrepared = written.sharedPreplanPreparedBefore,
          plannedSubtaskIds = written.plannedSubtaskIdsBefore,
          subtasks = beforeSubtasks,
        ),
      after =
        GoalRunnerReplanSnapshot(
          status = written.state.manifest.status,
          currentSubtaskId = written.state.manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 },
          currentAction = written.state.manifest.currentSubtaskIntent.action,
          sharedPreplanPrepared = written.sharedPreplanPrepared,
          plannedSubtaskIds = written.plannedSubtaskIdsAfter,
          subtasks = written.state.manifest.toResetSnapshot().subtasks,
        ),
    )

  private fun currentChildRecoveryDiagnostic(manifest: DecompositionManifest): GoalRunnerChildRecoveryDiagnostic? {
    val subtask = manifest.subtasks.firstOrNull { it.id == manifest.currentSubtaskIntent.subtaskId } ?: return null
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank) ?: return null
    val classification = classifyDurableChild(outcomeStore.progress(workflowId))
    return classification.takeIf { it == DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL }?.let {
      GoalRunnerChildRecoveryDiagnostic(
        subtaskId = subtask.id,
        workflowId = workflowId,
        classification = it.wireValue,
        recoveryCommand =
          recommendedDurableChildRecoveryCommand(
            manifest.issueKey,
            subtask.id,
            subtask.status.decompositionStatus(),
            outcomeStore.progress(workflowId),
          ),
      )
    }
  }

  private fun deleteIncompatibleChildWorkflow(
    request: GoalRunnerResetRequest,
    authoritativeState: GoalRunnerManifestState,
  ): GoalRunnerResetResult {
    val subtaskId = requireNotNull(request.subtaskId)
    val selected =
      authoritativeState.manifest.subtasks.singleOrNull { it.id == subtaskId }
        ?: error("Unknown or ambiguous goal subtask '$subtaskId'.")
    require(selected.status.decompositionStatus() == DecompositionStatus.BLOCKED) {
      "Subtask '$subtaskId' is '${selected.status}'; scoped child deletion requires a blocked subtask."
    }
    val workflowId =
      selected.workflowId?.takeIf(String::isNotBlank)
        ?: error("Subtask '$subtaskId' has no durable child workflow to delete.")
    val classification = classifyDurableChild(outcomeStore.progress(workflowId))
    require(classification == DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL) {
      "Child workflow '$workflowId' is ${classification.wireValue}; scoped deletion requires an incompatible " +
        "terminal child."
    }
    val saved =
      manifestStore.deleteIncompatibleChildWorkflow(
        authoritativeState,
        subtaskId,
        workflowId,
      )
    pruneResetSubtaskCheckpointRefs(
      gitOperations = gitOperations,
      repoRoot = request.repoRoot ?: repositoryRoot.path,
      issueKey = saved.manifest.issueKey,
      subtaskIds = listOf(subtaskId),
      record = { message -> RuntimeDiagnosticsBestEffortWarning.record(diagnostics, message) },
    )
    return GoalRunnerResetResult(
      issueKey = saved.manifest.issueKey,
      mode = "scoped_child_recovery",
      parentWorkflowId = saved.parentWorkflowId,
      before = authoritativeState.manifest.toResetSnapshot(),
      after = saved.manifest.toResetSnapshot(),
      recovery =
        GoalRunnerChildRecoveryDiagnostic(
          subtaskId = subtaskId,
          workflowId = workflowId,
          classification = classification.wireValue,
          recoveryCommand = null,
        ),
    )
  }
}
