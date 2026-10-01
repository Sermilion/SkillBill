package skillbill.engine.goalrunner.manifest

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerPausePersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPort
import skillbill.engine.goalrunner.reset.WorkflowGoalRunnerChildWorkflowPersistence
import skillbill.engine.goalrunner.reset.WorkflowGoalRunnerScopedReplanPersistence
import skillbill.engine.goalrunner.status.GoalRunnerControlCoordinator
import skillbill.engine.goalrunner.status.acquireExecutionLease
import skillbill.engine.goalrunner.status.bindRepositoryIdentity
import skillbill.engine.goalrunner.status.executionLease
import skillbill.engine.goalrunner.status.heartbeatExecutionLease
import skillbill.engine.goalrunner.status.persistStopAfterSubtask
import skillbill.engine.goalrunner.status.planningSpawnAuthorization
import skillbill.engine.goalrunner.status.releaseExecutionLease
import skillbill.engine.goalrunner.status.releaseExecutionLeaseIfExpired
import skillbill.engine.goalrunner.status.requestPause
import skillbill.engine.goalrunner.status.requestPauseByIssueKey
import skillbill.engine.goalrunner.status.resume
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.model.RepositoryRoot
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.afterIncompatibleChildDeletion
import skillbill.workflow.engine.WorkflowEngine
import java.nio.file.Path
import java.time.Clock
import kotlin.random.Random

class WorkflowGoalRunnerManifestStore
  @Inject
  constructor(
    private val database: DatabaseSessionFactory,
    private val workflowSnapshotValidator: WorkflowSnapshotValidator,
    private val decompositionManifestValidator: DecompositionManifestValidator,
    private val decompositionManifestStore: DecompositionManifestStore,
    private val clock: Clock,
    private val random: Random,
    private val decompositionManifestWriter: DecompositionManifestWriter,
    private val repositoryRoot: RepositoryRoot,
    private val planningHydrator: GoalChildPlanningHydratorPort,
    private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
    private val executionAdmission: FeatureTaskRuntimeExecutionAdmission,
  ) : GoalRunnerManifestStore {
    private val engine: WorkflowEngine = WorkflowEngine()
    private val parentProjection = GoalParentProjectionWriter(engine, decompositionManifestValidator)
    private val manifestLoader =
      WorkflowGoalRunnerManifestLoader(
        database,
        decompositionManifestValidator,
        decompositionManifestStore,
        engine,
        parentProjection,
        clock,
        random,
      )
    private val projectionPersistence =
      WorkflowGoalRunnerManifestProjectionPersistence(
        database,
        engine,
        parentProjection,
        workflowSnapshotValidator,
        decompositionManifestWriter,
        repositoryRoot,
        decompositionManifestValidator,
        decompositionManifestStore,
      )
    private val childWorkflowPersistence =
      WorkflowGoalRunnerChildWorkflowPersistence(
        engine,
        planningHydrator,
        parentProjection,
        executionAdmission,
        clock,
      )
    private val scopedReplanPersistence = WorkflowGoalRunnerScopedReplanPersistence(projectionPersistence)
    private val controls =
      GoalRunnerControlCoordinator(
        database,
        decompositionManifestValidator,
        clock,
        repositoryEnclosingRootPort,
      ) { unitOfWork, state ->
        projectionPersistence.saveInTransaction(unitOfWork, state)
      }

    override fun loadByIssueKey(
      issueKey: String,
      repoRoot: Path?,
    ): GoalRunnerManifestState? {
      val projected = repoRoot?.let { root -> manifestLoader.findProjectedManifest(root, issueKey) }
      val stored = manifestLoader.loadFromWorkflowStore(issueKey, projected)
      if (manifestLoader.shouldRefreshFromCompleteProjection(stored, projected)) {
        return save(
          requireNotNull(stored).copy(manifest = requireNotNull(projected), repoRoot = repoRoot),
        )
      }
      return stored?.copy(repoRoot = repoRoot) ?: projected?.let { manifest ->
        manifestLoader.importFromManifestProjection(manifest)?.copy(repoRoot = repoRoot)
      }
    }

    override fun readByIssueKey(
      issueKey: String,
      repoRoot: Path?,
    ): GoalRunnerManifestState? {
      val projected = repoRoot?.let { root -> manifestLoader.findProjectedManifest(root, issueKey) }
      val stored = manifestLoader.loadFromWorkflowStore(issueKey, projected)
      return manifestLoader.readProjection(stored, projected, repoRoot)
    }

    override fun readByIssueKeyIfPresent(
      issueKey: String,
      repoRoot: Path?,
    ): GoalRunnerManifestState? {
      val projected =
        repoRoot?.let { root ->
          manifestLoader.findProjectedManifest(root, issueKey, recoverPending = false)
        }
      val stored = manifestLoader.loadFromWorkflowStoreIfPresent(issueKey, projected)
      return manifestLoader.readProjection(stored, projected, repoRoot)
    }

    override fun loadDurableByIssueKey(issueKey: String): GoalRunnerManifestState? =
      manifestLoader.loadFromWorkflowStore(issueKey, currentProjectedManifest = null)

    override fun requestPause(parentWorkflowId: String): GoalRunnerControlState? =
      controls.requestPause(
        parentWorkflowId,
      )

    override fun pauseNow(
      parentWorkflowId: String,
      reason: String,
      pausedAt: String,
      overwriteExistingReason: Boolean,
    ): GoalRunnerControlState? = controls.pauseNow(parentWorkflowId, reason, pausedAt, overwriteExistingReason)

    override fun requestPauseByIssueKey(
      issueKey: String,
      repoRoot: Path?,
    ): GoalRunnerPausePersistenceResult? = controls.requestPauseByIssueKey(issueKey, repoRoot)

    override fun resume(parentWorkflowId: String): GoalRunnerManifestState? = controls.resume(parentWorkflowId)

    override fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState =
      controls.pauseAtBoundary(state)

    override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? =
      controls.executionLease(parentWorkflowId)

    override fun acquireExecutionLease(
      parentWorkflowId: String,
      lease: GoalRunnerExecutionLease,
      expectedOwnerToken: String?,
    ): Boolean =
      controls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken) { unitOfWork ->
        requireCompatibleChildPlan(unitOfWork, parentWorkflowId, null, executionAdmission)
      }

    override fun acquireExecutionLeaseWithChildAdmission(
      parentWorkflowId: String,
      lease: GoalRunnerExecutionLease,
      expectedOwnerToken: String?,
      childAdmission: GoalRunnerChildExecutionPlanAdmission,
    ): Boolean =
      controls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken) { unitOfWork ->
        requireCompatibleChildPlan(unitOfWork, parentWorkflowId, childAdmission, executionAdmission)
      }

    override fun heartbeatExecutionLease(
      parentWorkflowId: String,
      lease: GoalRunnerExecutionLease,
    ): Boolean = controls.heartbeatExecutionLease(parentWorkflowId, lease)

    override fun releaseExecutionLease(
      parentWorkflowId: String,
      ownerToken: String,
      generation: Long,
    ): Boolean = controls.releaseExecutionLease(parentWorkflowId, ownerToken, generation)

    override fun releaseExecutionLeaseIfExpired(
      parentWorkflowId: String,
      ownerToken: String,
      generation: Long,
      nowInstant: String,
    ): Boolean =
      controls.releaseExecutionLeaseIfExpired(
        parentWorkflowId,
        ownerToken,
        generation,
        nowInstant,
      )

    override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
      controls.controlState(
        parentWorkflowId,
      )

    override fun persistControlState(
      parentWorkflowId: String,
      state: GoalRunnerControlState,
    ): GoalRunnerControlState = controls.persistControlState(parentWorkflowId, state)

    override fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState =
      controls.clearRunnerInterruptedPause(parentWorkflowId)

    override fun bindRepositoryIdentity(
      parentWorkflowId: String,
      repositoryIdentity: String,
    ): GoalRunnerControlState = controls.bindRepositoryIdentity(parentWorkflowId, repositoryIdentity)

    override fun authorizeSubtaskLaunch(
      state: GoalRunnerManifestState,
      subtaskId: Int,
    ): GoalRunnerLaunchAuthorization = controls.authorizeSubtaskLaunch(state, subtaskId)

    override fun authorizePlanningLaunch(parentWorkflowId: String): AgentRunSpawnAuthorization? =
      controls.planningSpawnAuthorization(parentWorkflowId)

    override fun persistStopAfterSubtask(
      parentWorkflowId: String,
      subtaskId: Int,
    ): GoalRunnerControlState = controls.persistStopAfterSubtask(parentWorkflowId, subtaskId)

    override fun planningStatus(
      parentWorkflowId: String,
      orderedSubtaskIds: List<Int>,
      blockedSubtaskId: Int?,
      blockedReason: String?,
    ): GoalPlanningStatusSnapshot? =
      database.read {
        it.goalPlanningPreparations.boundedStatus(
          parentWorkflowId,
          orderedSubtaskIds,
          blockedSubtaskId,
          blockedReason,
        )
      }

    override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState {
      val saved = projectionPersistence.save(state)
      projectionPersistence.writeProjectionFile(state, saved.projectionArtifacts)
      return saved.state
    }

    override fun saveRuntimeState(state: GoalRunnerManifestState): GoalRunnerManifestState =
      projectionPersistence.save(state).state

    override fun saveCompletedSubtaskAtBoundary(
      state: GoalRunnerManifestState,
      subtaskId: Int,
    ): GoalRunnerCompletionPersistenceResult = controls.saveCompletedSubtaskAtBoundary(state, subtaskId)

    override fun saveHardReset(
      state: GoalRunnerManifestState,
      preservePlanning: Boolean,
    ): GoalRunnerManifestState {
      val saved =
        database.transaction { unitOfWork ->
          if (!preservePlanning) unitOfWork.goalPlanningPreparations.deleteByGoal(state.parentWorkflowId)
          unitOfWork.workflowStates.deleteGoalChildWorkflowsByParent(state.parentWorkflowId)
          val repositoryIdentity = unitOfWork.goalRunnerControls.controlState(state.parentWorkflowId).repositoryIdentity
          val projection =
            projectionPersistence.saveInTransaction(
              unitOfWork,
              state,
              clearOutOfBandAcceptances = true,
              mergeConcurrentProgress = false,
            )
          if (repositoryIdentity != null) {
            unitOfWork.goalRunnerControls.persistControlState(
              projection.state.parentWorkflowId,
              projection.state.controlState.copy(repositoryIdentity = repositoryIdentity),
            )
          }
          projection.copy(
            state =
              projection.state.copy(
                controlState = projection.state.controlState.copy(repositoryIdentity = repositoryIdentity),
              ),
          )
        }
      projectionPersistence.writeProjectionFile(state, saved.projectionArtifacts)
      return saved.state
    }

    override fun deleteIncompatibleChildWorkflow(
      state: GoalRunnerManifestState,
      subtaskId: Int,
      workflowId: String,
    ): GoalRunnerManifestState {
      val saved =
        database.transaction { unitOfWork ->
          val selected =
            state.manifest.subtasks.singleOrNull { it.id == subtaskId }
              ?: error("Unknown or ambiguous goal subtask '$subtaskId'.")
          require(selected.workflowId == workflowId) {
            "Selected subtask '$subtaskId' does not own child workflow '$workflowId'."
          }
          val deleted =
            unitOfWork.workflowStates.deleteGoalChildWorkflow(
              state.parentWorkflowId,
              subtaskId,
              workflowId,
            )
          require(deleted == 1) {
            "Child workflow '$workflowId' is absent, compatible, or not owned by subtask '$subtaskId'."
          }
          val recoveredManifest = state.manifest.afterIncompatibleChildDeletion(subtaskId)
          projectionPersistence.saveInTransaction(unitOfWork, state.copy(manifest = recoveredManifest))
        }
      projectionPersistence.writeProjectionFile(state, saved.projectionArtifacts)
      return saved.state
    }

    override fun saveScopedReplan(
      state: GoalRunnerManifestState,
      subtaskId: Int,
      options: GoalRunnerScopedReplanOptions,
    ): GoalRunnerScopedReplanWriteResult {
      val saved =
        database.transaction { unitOfWork ->
          scopedReplanPersistence.executeScopedReplan(unitOfWork, state, subtaskId, options)
        }
      projectionPersistence.writeProjectionFile(state, saved.second)
      return saved.first
    }

    override fun sharedPreplanPayloadSha256(parentWorkflowId: String): String? =
      database.read {
        it.goalPlanningPreparations.sharedPreplanPayloadSha256(parentWorkflowId)
      }

    override fun saveNewChildWorkflow(
      state: GoalRunnerManifestState,
      setup: GoalRunnerChildWorkflowSetup,
    ): GoalRunnerManifestState {
      val saved =
        database.transaction { unitOfWork ->
          childWorkflowPersistence.saveInTransaction(unitOfWork, state, setup)
        }
      projectionPersistence.writeProjectionFile(state, saved.projectionArtifacts)
      return saved.state
    }

    override fun listOwnedGoalChildWorkflowIds(parentWorkflowId: String): List<String> =
      database.read { it.workflowStates.listGoalChildWorkflowIdsByParent(parentWorkflowId) }

    override fun purgeDecomposedGoal(parentWorkflowId: String) {
      database.transaction { unitOfWork -> unitOfWork.purgeDecomposedGoal(parentWorkflowId) }
    }

    override fun reviewMode(parentWorkflowId: String): CodeReviewExecutionMode? =
      database.read { unitOfWork ->
        unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)?.codeReviewMode
      }

    override fun persistReviewMode(
      parentWorkflowId: String,
      mode: CodeReviewExecutionMode,
    ): CodeReviewExecutionMode =
      database.transaction { unitOfWork ->
        val record =
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
            ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
        val existing = unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)?.codeReviewMode
        if (existing != null) {
          parentProjection.rewrite(unitOfWork, record)
          existing
        } else {
          unitOfWork.goalRunnerControls.persistReviewPolicy(
            parentWorkflowId,
            GoalRunnerReviewPolicy(codeReviewMode = mode),
          )
          parentProjection.rewrite(unitOfWork, record)
          mode
        }
      }

    override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? =
      database.read { unitOfWork ->
        unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)
      }

    override fun persistReviewPolicy(
      parentWorkflowId: String,
      policy: GoalRunnerReviewPolicy,
    ): GoalRunnerReviewPolicy =
      database.transaction { unitOfWork ->
        val record =
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
            ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
        val existing = unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)
        if (existing == policy) {
          parentProjection.rewrite(unitOfWork, record)
          existing
        } else {
          unitOfWork.goalRunnerControls.persistReviewPolicy(parentWorkflowId, policy)
          parentProjection.rewrite(unitOfWork, record)
          policy
        }
      }

    override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> =
      database.read { unitOfWork ->
        unitOfWork.goalRunnerControls.outOfBandAcceptances(parentWorkflowId)
      }

    override fun persistOutOfBandAcceptance(
      parentWorkflowId: String,
      acceptance: GoalRunnerOutOfBandAcceptance,
    ): GoalRunnerOutOfBandAcceptance =
      database.transaction { unitOfWork ->
        val record =
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
            ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
        unitOfWork.goalRunnerControls.persistOutOfBandAcceptance(parentWorkflowId, acceptance)
        parentProjection.rewrite(unitOfWork, record)
        acceptance
      }
  }
