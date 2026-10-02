package skillbill.engine.goalrunner.status

import skillbill.application.workflow.decomposition.findDecomposedParentWorkflow
import skillbill.engine.goalrunner.manifest.SavedManifestProjection
import skillbill.engine.goalrunner.manifest.mergeConcurrentGoalProgress
import skillbill.engine.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerPausePersistenceResult
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.pauseAtOperatorBoundary
import skillbill.goalrunner.model.targetReached
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorizationResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Path
import java.time.Clock

internal class GoalRunnerControlCoordinator(
  internal val database: DatabaseSessionFactory,
  internal val decompositionManifestValidator: DecompositionManifestValidator,
  internal val clock: Clock,
  internal val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  internal val saveProjection: (UnitOfWork, GoalRunnerManifestState) -> SavedManifestProjection,
) {
  fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    database.read { unitOfWork -> unitOfWork.goalRunnerControls.controlState(parentWorkflowId) }

  fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState =
    database.transaction { unitOfWork ->
      unitOfWork.goalRunnerControls.persistControlState(parentWorkflowId, state)
    }

  fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState =
    database.transaction { unitOfWork ->
      unitOfWork.goalRunnerControls.clearRunnerInterruptedPause(parentWorkflowId)
    }

  fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? =
    database.read { unitOfWork -> unitOfWork.goalRunnerControls.executionLease(parentWorkflowId) }

  fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    beforeMutation: (UnitOfWork) -> Unit = {},
  ): Boolean =
    database.transaction { unitOfWork ->
      if (unitOfWork.goalRunnerControls.controlState(
          parentWorkflowId,
        ).executionLease?.ownerToken != expectedOwnerToken
      ) {
        return@transaction false
      }
      beforeMutation(unitOfWork)
      reconcileControlStateForManifest(unitOfWork, parentWorkflowId)
      unitOfWork.goalRunnerControls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken)
    }

  fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean =
    database.transaction { unitOfWork ->
      reconcileControlStateForManifest(unitOfWork, parentWorkflowId)
      unitOfWork.goalRunnerControls.heartbeatExecutionLease(parentWorkflowId, lease)
    }

  fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean =
    database.transaction { unitOfWork ->
      unitOfWork.goalRunnerControls.releaseExecutionLease(parentWorkflowId, ownerToken, generation)
    }

  fun releaseExecutionLeaseIfExpired(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    nowInstant: String,
  ): Boolean =
    database.transaction { unitOfWork ->
      unitOfWork.goalRunnerControls.releaseExecutionLeaseIfExpired(
        parentWorkflowId,
        ownerToken,
        generation,
        nowInstant,
      )
    }

  fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerLaunchAuthorization =
    database.transaction { unitOfWork ->
      require(subtaskId > 0) { "subtaskId must be positive." }
      val existing = requireParent(unitOfWork, state.parentWorkflowId)
      val controls = unitOfWork.goalRunnerControls.controlState(existing.workflowId)
      val manifest = existing.decompositionRuntime() ?: state.manifest
      GoalRunnerLaunchAuthorization(
        authorized = !controls.requiresPauseBoundary(manifest),
        controlState = controls,
        spawnAuthorization = spawnAuthorization(state),
      )
    }

  fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
  ): GoalRunnerControlState? =
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
        ?: return@transaction null
      val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
      if (existing.paused && !overwriteExistingReason) {
        return@transaction existing
      }
      unitOfWork.goalRunnerControls.persistControlState(
        parentWorkflowId,
        existing.copy(
          pauseRequested = true,
          pauseConsumed = true,
          paused = true,
          pauseReason = reason,
          pausedAt = pausedAt,
        ),
      )
    }

  fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState =
    database.transaction { unitOfWork ->
      val parent = requireParent(unitOfWork, state.parentWorkflowId)
      val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
      val authoritativeManifest = parent.decompositionRuntime() ?: state.manifest
      val authoritativeState = state.copy(manifest = authoritativeManifest)
      val targetReached = controls.targetReached(authoritativeManifest)
      val pausedControls =
        if (controls.requiresPauseBoundary(authoritativeManifest)) {
          controls.pauseAtOperatorBoundary(clock.instant().toString(), targetReached)
        } else {
          controls
        }
      val saved = saveProjection(unitOfWork, authoritativeState)
      if (pausedControls != controls) {
        unitOfWork.goalRunnerControls.persistControlState(parent.workflowId, pausedControls)
      }
      saved.state.copy(controlState = pausedControls)
    }

  fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
  ): GoalRunnerCompletionPersistenceResult =
    database.transaction { unitOfWork ->
      val parent = requireParent(unitOfWork, state.parentWorkflowId)
      val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
      val persistedManifest = parent.decompositionRuntime() ?: state.manifest
      val authoritativeManifest = mergeConcurrentGoalProgress(persistedManifest, state.manifest)
      val authoritativeState = state.copy(manifest = authoritativeManifest)
      val targetReached = controls.stopAfterSubtaskId == subtaskId && !controls.stopAfterConsumed
      val operatorRequested = controls.pauseRequested && !controls.pauseConsumed
      val shouldPause = controls.requiresPauseBoundary(authoritativeManifest) || targetReached || operatorRequested
      val nextControls =
        if (shouldPause) controls.pauseAtOperatorBoundary(clock.instant().toString(), targetReached) else controls
      val saved = saveProjection(unitOfWork, authoritativeState)
      if (nextControls != controls) {
        unitOfWork.goalRunnerControls.persistControlState(parent.workflowId, nextControls)
      }
      GoalRunnerCompletionPersistenceResult(
        state = saved.state.copy(controlState = nextControls),
        paused = shouldPause,
      )
    }
}

internal fun reconcileControlStateForManifest(
  unitOfWork: UnitOfWork,
  parentWorkflowId: String,
) {
  val parent = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId) ?: return
  val manifest = parent.decompositionRuntime() ?: return
  val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
  val reconciled = existing.reconciledForCurrentSubtask(manifest.currentSubtaskIntent.subtaskId)
  if (reconciled != existing) {
    unitOfWork.goalRunnerControls.persistControlState(parentWorkflowId, reconciled)
  }
}

internal fun GoalRunnerControlCoordinator.requireParent(
  unitOfWork: UnitOfWork,
  parentWorkflowId: String,
): WorkflowStateSnapshot =
  unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
    ?: error("Unknown decomposed parent workflow '$parentWorkflowId'.")

internal fun GoalRunnerControlCoordinator.spawnAuthorization(
  state: GoalRunnerManifestState,
): AgentRunSpawnAuthorization =
  object : AgentRunSpawnAuthorization {
    override fun <T> withAuthorization(spawn: () -> T): AgentRunSpawnAuthorizationResult<T> =
      database.transaction { unitOfWork ->
        val parent = requireParent(unitOfWork, state.parentWorkflowId)
        val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
        val manifest = parent.decompositionRuntime() ?: state.manifest
        if (controls.requiresPauseBoundary(manifest)) {
          AgentRunSpawnAuthorizationResult.Denied(controls.pauseReason)
        } else {
          AgentRunSpawnAuthorizationResult.Authorized(spawn())
        }
      }
  }

internal fun GoalRunnerControlCoordinator.bindRepositoryIdentity(
  parentWorkflowId: String,
  repositoryIdentity: String,
): GoalRunnerControlState =
  database.transaction { unitOfWork ->
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity is required." }
    val parent = requireParent(unitOfWork, parentWorkflowId)
    val existing = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
    if (existing.repositoryIdentity == repositoryIdentity) {
      existing
    } else {
      unitOfWork.goalRunnerControls.persistControlState(
        parent.workflowId,
        existing.copy(repositoryIdentity = repositoryIdentity),
      )
    }
  }

internal fun GoalRunnerControlCoordinator.planningSpawnAuthorization(
  parentWorkflowId: String,
): AgentRunSpawnAuthorization =
  object : AgentRunSpawnAuthorization {
    override fun <T> withAuthorization(spawn: () -> T): AgentRunSpawnAuthorizationResult<T> =
      database.transaction { unitOfWork ->
        val parent = requireParent(unitOfWork, parentWorkflowId)
        val controls = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
        val manifest =
          parent.decompositionRuntime()
            ?: error("Goal parent '$parentWorkflowId' has no decomposition manifest.")
        if (controls.requiresPauseBoundary(manifest)) {
          AgentRunSpawnAuthorizationResult.Denied(controls.pauseReason)
        } else {
          AgentRunSpawnAuthorizationResult.Authorized(spawn())
        }
      }
  }

internal fun GoalRunnerControlCoordinator.persistStopAfterSubtask(
  parentWorkflowId: String,
  subtaskId: Int,
): GoalRunnerControlState =
  database.transaction { unitOfWork ->
    require(subtaskId > 0) { "stop-after subtask id must be positive." }
    val parent = requireParent(unitOfWork, parentWorkflowId)
    val manifest =
      parent.decompositionRuntime()
        ?: error("Goal parent '$parentWorkflowId' has no decomposition manifest.")
    require(manifest.subtasks.any { it.id == subtaskId }) {
      "Goal parent '$parentWorkflowId' has no subtask '$subtaskId'."
    }
    val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
    require(existing.stopAfterSubtaskId == null || existing.stopAfterSubtaskId == subtaskId) {
      "Goal parent '$parentWorkflowId' already has stop-after subtask ${existing.stopAfterSubtaskId}."
    }
    if (existing.stopAfterSubtaskId != null) {
      existing
    } else {
      unitOfWork.goalRunnerControls.persistControlState(
        parentWorkflowId,
        existing.copy(stopAfterSubtaskId = subtaskId),
      )
    }
  }

internal fun GoalRunnerControlCoordinator.resume(parentWorkflowId: String): GoalRunnerManifestState? =
  database.transaction { unitOfWork ->
    val parent =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
        ?: return@transaction null
    val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
    val resumed =
      if (existing.paused || existing.pauseRequested) {
        unitOfWork.goalRunnerControls.persistControlState(
          parentWorkflowId,
          existing.copy(
            pauseRequested = false,
            pauseConsumed = false,
            paused = false,
            pauseReason = null,
            pausedAt = null,
          ),
        )
      } else {
        existing
      }
    parent.decompositionRuntime()?.let { manifest ->
      GoalRunnerManifestState(
        parentWorkflowId = parent.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = manifest,
        controlState = resumed,
      )
    }
  }

internal fun GoalRunnerControlCoordinator.persistPauseRequest(
  unitOfWork: UnitOfWork,
  parentWorkflowId: String,
): GoalRunnerControlState {
  val existing = unitOfWork.goalRunnerControls.controlState(parentWorkflowId)
  return if (existing.paused || existing.pauseRequested) {
    existing
  } else {
    unitOfWork.goalRunnerControls.persistControlState(
      parentWorkflowId,
      existing.copy(
        pauseRequested = true,
        pauseConsumed = false,
        pauseReason = GOAL_PAUSE_REASON_OPERATOR_REQUEST,
      ),
    )
  }
}

internal fun GoalRunnerControlCoordinator.requestPause(parentWorkflowId: String): GoalRunnerControlState? =
  database.transaction { unitOfWork ->
    unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)?.let { _ ->
      persistPauseRequest(unitOfWork, parentWorkflowId)
    }
  }

internal fun GoalRunnerControlCoordinator.requestPauseByIssueKey(
  issueKey: String,
  repoRoot: Path?,
): GoalRunnerPausePersistenceResult? =
  database.transaction { unitOfWork ->
    val parent =
      unitOfWork.workflowStates.findDecomposedParentWorkflow(
        issueKey,
      ) ?: return@transaction null
    val existing = unitOfWork.goalRunnerControls.controlState(parent.workflowId)
    if (repoRoot != null) {
      val identity = repositoryEnclosingRootPort.repositoryIdentity(repoRoot)
      if (existing.repositoryIdentity != identity) {
        unitOfWork.goalRunnerControls.persistControlState(
          parent.workflowId,
          existing.copy(repositoryIdentity = identity),
        )
      }
    }
    GoalRunnerPausePersistenceResult(parent.workflowId, persistPauseRequest(unitOfWork, parent.workflowId))
  }
