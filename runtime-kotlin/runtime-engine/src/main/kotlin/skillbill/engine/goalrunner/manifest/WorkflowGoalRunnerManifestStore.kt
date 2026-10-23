package skillbill.engine.goalrunner.manifest

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.engine.goalrunner.reset.GoalChildWorkflowSaveResult
import skillbill.engine.goalrunner.reset.WorkflowGoalRunnerChildWorkflowPersistence
import skillbill.engine.goalrunner.reset.WorkflowGoalRunnerScopedReplanPersistence
import skillbill.engine.goalrunner.status.GoalRunnerControlCoordinator
import skillbill.engine.goalrunner.status.bindRepositoryIdentity
import skillbill.engine.goalrunner.status.executionLease
import skillbill.engine.goalrunner.status.persistStopAfterSubtask
import skillbill.engine.goalrunner.status.planningSpawnAuthorization
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusSummary
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.planningStatusSnapshot
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

class WorkflowGoalRunnerManifestStore
  @Inject
  constructor(
    private val database: DatabaseSessionFactory,
    private val workflowSnapshotValidator: WorkflowSnapshotValidator,
    private val decompositionManifestValidator: DecompositionManifestValidator,
    private val decompositionManifestStore: DecompositionManifestStore,
    private val clock: Clock,
    private val manifestLoader: WorkflowGoalRunnerManifestLoader,
    private val decompositionManifestWriter: DecompositionManifestWriter,
    private val repositoryRoot: RepositoryRoot,
    private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
    private val executionAdmission: FeatureTaskRuntimeExecutionAdmission,
    private val childWorkflowPersistence: WorkflowGoalRunnerChildWorkflowPersistence,
  ) : GoalRunnerManifestStore,
    GoalRunnerManifestPurgeCommands by WorkflowGoalRunnerPurgePersistence(database, repositoryEnclosingRootPort),
    GoalRunnerStatusManifestQueries by WorkflowGoalRunnerStatusManifestQueries(database, repositoryEnclosingRootPort),
    GoalRunnerManifestExecutionCommands by WorkflowGoalRunnerExecutionCommands(
      GoalRunnerControlCoordinator(
        database,
        decompositionManifestValidator,
        clock,
        repositoryEnclosingRootPort,
      ) { unitOfWork, state ->
        WorkflowGoalRunnerManifestProjectionPersistence(
          database,
          WorkflowEngine(),
          GoalParentProjectionWriter(WorkflowEngine(), decompositionManifestValidator),
          workflowSnapshotValidator,
          decompositionManifestWriter,
          repositoryRoot,
          decompositionManifestValidator,
          decompositionManifestStore,
        ).saveInTransaction(unitOfWork, state)
      },
      executionAdmission,
    ) {
    private val engine: WorkflowEngine = WorkflowEngine()
    private val parentProjection = GoalParentProjectionWriter(engine, decompositionManifestValidator)
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
      val identity = repoRoot?.let(repositoryEnclosingRootPort::repositoryIdentity)
      val stored = manifestLoader.loadFromWorkflowStore(issueKey, projected, identity)
      if (manifestLoader.shouldRefreshFromCompleteProjection(stored, projected)) {
        return save(
          requireNotNull(stored).copy(manifest = requireNotNull(projected), repoRoot = repoRoot),
        )
      }
      return stored?.copy(repoRoot = repoRoot) ?: projected?.let { manifest ->
        manifestLoader.importFromManifestProjection(manifest, identity)?.copy(repoRoot = repoRoot)
      }
    }

    override fun readByIssueKey(
      issueKey: String,
      repoRoot: Path?,
    ): GoalRunnerManifestState? {
      val projected = repoRoot?.let { root -> manifestLoader.findProjectedManifest(root, issueKey) }
      val identity = repoRoot?.let(repositoryEnclosingRootPort::repositoryIdentity)
      val stored = manifestLoader.loadFromWorkflowStore(issueKey, projected, identity)
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
      val identity = repoRoot?.let(repositoryEnclosingRootPort::repositoryIdentity)
      val stored = manifestLoader.loadFromWorkflowStoreIfPresent(issueKey, projected, identity)
      return manifestLoader.readProjection(stored, projected, repoRoot)
    }

    override fun loadDurableByIssueKey(
      issueKey: String,
      repoRoot: Path?,
    ): GoalRunnerManifestState? =
      manifestLoader.loadFromWorkflowStore(
        issueKey,
        repositoryIdentity = repoRoot?.let(repositoryEnclosingRootPort::repositoryIdentity),
      )

    override fun planningStatusFromSummary(
      parentWorkflowId: String,
      summary: GoalPlanningStatusSummary,
      blockedSubtaskId: Int?,
      blockedReason: String?,
    ): GoalPlanningStatusSnapshot =
      database.read { unit ->
        planningStatusSnapshot(
          summary,
          unit.goalPlanningPreparations.hasPreparedSharedPreplan(parentWorkflowId) ||
            unit.planWorkflowPlanningProgress(parentWorkflowId)?.preplanRecorded == true,
          blockedSubtaskId,
          blockedReason,
        )
      }

    override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? =
      controls.executionLease(parentWorkflowId)

    override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
      controls.controlState(
        parentWorkflowId,
      )

    override fun persistControlState(
      parentWorkflowId: String,
      state: GoalRunnerControlState,
    ): GoalRunnerControlState = controls.persistControlState(parentWorkflowId, state)

    override fun updateControlState(
      parentWorkflowId: String,
      update: (GoalRunnerControlState) -> GoalRunnerControlState,
    ): GoalRunnerControlState = controls.updateControlState(parentWorkflowId, update)

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
      database.read { unit ->
        val legacy =
          unit.goalPlanningPreparations.boundedStatus(
            parentWorkflowId,
            orderedSubtaskIds,
            blockedSubtaskId,
            blockedReason,
          )
        unit.planWorkflowPlanningProgress(parentWorkflowId)?.let { progress ->
          planningStatusSnapshot(
            orderedSubtaskIds,
            (unit.goalPlanningPreparations.listPreparedPlanSubtaskIds(parentWorkflowId) + progress.plannedSubtaskIds)
              .distinct(),
            legacy.sharedPreplanPrepared || progress.preplanRecorded,
            blockedSubtaskId,
            blockedReason,
          )
        } ?: legacy
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

    override fun clearReplannedChildWorkflows(
      state: GoalRunnerManifestState,
      subtaskIds: List<Int>,
    ): GoalRunnerManifestState {
      val saved =
        database.transaction { unitOfWork ->
          scopedReplanPersistence.clearReplannedChildren(unitOfWork, state, subtaskIds)
        }
      saved.second?.let { projectionPersistence.writeProjectionFile(state, it) }
      return saved.first
    }

    override fun saveNewChildWorkflow(
      state: GoalRunnerManifestState,
      setup: GoalRunnerChildWorkflowSetup,
    ): GoalRunnerChildWorkflowSaveResult {
      return when (
        val saved =
          database.transaction { unitOfWork ->
            childWorkflowPersistence.saveInTransaction(unitOfWork, state, setup)
          }
      ) {
        is GoalChildWorkflowSaveResult.Conflicted -> GoalRunnerChildWorkflowSaveResult.Conflicted(saved.conflict)
        is GoalChildWorkflowSaveResult.Saved -> {
          projectionPersistence.writeProjectionFile(state, saved.saved.projectionArtifacts)
          GoalRunnerChildWorkflowSaveResult.Saved(saved.saved.state)
        }
      }
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
