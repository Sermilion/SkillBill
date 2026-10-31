package skillbill.engine.goalrunner.manifest

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.goalrunner.history.GoalChildHistoryEvidenceStore
import skillbill.engine.goalrunner.history.restoreGoalChildHistoryAcceptance
import skillbill.engine.goalrunner.model.GoalChildHistoryAcceptanceRestoreResult
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.engine.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.engine.goalrunner.persist.quality.WorkflowGoalRunnerQualityExecutionStore
import skillbill.engine.goalrunner.reset.GoalChildWorkflowSaveResult
import skillbill.engine.goalrunner.reset.GoalRunnerOperatorCompletion
import skillbill.engine.goalrunner.reset.WorkflowGoalRunnerChildWorkflowPersistence
import skillbill.engine.goalrunner.reset.WorkflowGoalRunnerScopedReplanPersistence
import skillbill.engine.goalrunner.status.GoalRunnerControlCoordinator
import skillbill.engine.goalrunner.status.bindRepositoryIdentity
import skillbill.engine.goalrunner.status.executionLease
import skillbill.engine.goalrunner.status.persistStopAfterSubtask
import skillbill.engine.goalrunner.status.planningSpawnAuthorization
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusSummary
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.planningStatusSnapshot
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.afterIncompatibleChildDeletion
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import java.nio.file.Path
import java.time.Clock

public class WorkflowGoalRunnerManifestStore
  @Inject
  constructor(
    private val database: DatabaseSessionFactory,
    private val decompositionManifestValidator: DecompositionManifestValidator,
    private val clock: Clock,
    private val manifestLoader: WorkflowGoalRunnerManifestLoader,
    private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
    private val executionAdmission: FeatureTaskRuntimeExecutionAdmission,
    private val childWorkflowPersistence: WorkflowGoalRunnerChildWorkflowPersistence,
    private val historyEvidence: GoalChildHistoryEvidenceStore,
    private val qualityExecution: WorkflowGoalRunnerQualityExecutionStore,
    private val projectionPersistence: WorkflowGoalRunnerManifestProjectionPersistence,
  ) : GoalRunnerManifestStore,
    GoalRunnerManifestPurgeCommands by WorkflowGoalRunnerPurgePersistence(database, repositoryEnclosingRootPort),
    GoalRunnerStatusManifestQueries by WorkflowGoalRunnerStatusManifestQueries(
      database,
      repositoryEnclosingRootPort,
    ),
    GoalRunnerManifestExecutionCommands by WorkflowGoalRunnerExecutionCommands(
      GoalRunnerControlCoordinator(
        database,
        decompositionManifestValidator,
        clock,
        repositoryEnclosingRootPort,
      ) { unitOfWork, state ->
        projectionPersistence.saveInTransaction(unitOfWork, state)
      },
      executionAdmission,
    ) {
    private val parentProjection = GoalParentProjectionWriter(WorkflowEngine(), decompositionManifestValidator)
    private val scopedReplanPersistence =
      WorkflowGoalRunnerScopedReplanPersistence(
        projectionPersistence,
        historyEvidence,
        qualityExecution,
      )
    private val operatorCompletion = GoalRunnerOperatorCompletion(qualityExecution, projectionPersistence)
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
    ): GoalRunnerCompletionPersistenceResult =
      controls.saveCompletedSubtaskAtBoundary(state, subtaskId) { unit ->
        val childId = state.manifest.subtasks.single { it.id == subtaskId }.workflowId
        val current =
          unit.workflowStates.get(
            WorkflowFamily.TASK_RUNTIME,
            state.parentWorkflowId,
          )?.decompositionRuntime()
            ?.subtasks?.singleOrNull { it.id == subtaskId }
        if (childId == null || current?.workflowId != childId ||
          !historyEvidence.hasRequired(unit, state.parentWorkflowId, subtaskId, childId)
        ) {
          throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
            "Current goal child cannot complete without its accepted history disposition.",
          )
        }
      }

    override fun saveOperatorGoalCompletion(
      state: GoalRunnerManifestState,
      commitSha: String,
      reason: String,
      completedAt: String,
    ): GoalRunnerManifestState {
      val saved =
        database.transaction { unitOfWork ->
          operatorCompletion.saveInTransaction(unitOfWork, state, commitSha, reason, completedAt)
        }
      projectionPersistence.writeProjectionFile(state, saved.projectionArtifacts)
      return saved.state
    }

    override fun saveHardReset(
      state: GoalRunnerManifestState,
      preservePlanning: Boolean,
    ): GoalRunnerManifestState {
      val saved =
        database.transaction { unitOfWork ->
          val parent =
            unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId)
              ?: throw SkillBillRuntimeException(
                WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
                "Goal parent is missing during hard reset.",
              )
          val manifest =
            parent.decompositionRuntime()
              ?: throw SkillBillRuntimeException(
                WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
                "Goal manifest is missing during hard reset.",
              )
          captureSelectedChildren(unitOfWork, state.parentWorkflowId, manifest)
          qualityExecution.retireForSelectionChange(unitOfWork, state.parentWorkflowId)
          if (!preservePlanning) unitOfWork.goalPlanningPreparations.deleteByGoal(state.parentWorkflowId)
          deleteCapturedChildren(unitOfWork, state.parentWorkflowId, manifest)
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

    private fun deleteCapturedChildren(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
      manifest: DecompositionManifest,
    ) {
      val childIds = unitOfWork.workflowStates.listGoalChildWorkflowIdsByParent(parentWorkflowId).distinct()
      val referencedChildIds = manifest.subtasks.mapNotNull { it.workflowId }
      if (referencedChildIds.any { it !in childIds }) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "A manifest child is not an owned goal-child row during hard reset.",
        )
      }
      childIds.forEach { childId -> captureUnselectedChild(unitOfWork, parentWorkflowId, manifest, childId) }
      val deleted = unitOfWork.workflowStates.deleteGoalChildWorkflowsByParent(parentWorkflowId)
      if (deleted != childIds.size) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Hard reset deleted $deleted of ${childIds.size} selected child workflows.",
        )
      }
    }

    private fun captureUnselectedChild(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
      manifest: DecompositionManifest,
      childId: String,
    ) {
      val child =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childId)
          ?: throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
            "Selected child '$childId' disappeared during hard reset.",
          )
      if (manifest.subtasks.none { it.workflowId == childId }) {
        captureRetiredChild(unitOfWork, parentWorkflowId, manifest, childId, child.artifacts)
      }
    }

    private fun captureRetiredChild(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
      manifest: DecompositionManifest,
      childId: String,
      artifacts: DurableWorkflowArtifacts,
    ) {
      val continuation =
        DurableWorkflowArtifacts.fromMap(artifacts).goalContinuationArtifact()
          ?: throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
            "Selected child '$childId' has no continuation during hard reset.",
          )
      if (manifest.subtasks.none { it.id == continuation.subtaskId }) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Selected child '$childId' continuation names an unknown subtask.",
        )
      }
      historyEvidence.capture(unitOfWork, parentWorkflowId, continuation.subtaskId, childId)
      historyEvidence.retireChildEvidence(
        unitOfWork,
        parentWorkflowId,
        continuation.subtaskId,
        childId,
      )
    }

    private fun captureSelectedChildren(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
      manifest: DecompositionManifest,
    ) {
      val acceptances = unitOfWork.goalRunnerControls.outOfBandAcceptances(parentWorkflowId)
      manifest.subtasks.forEach { subtask ->
        if (subtask.workflowId?.isBlank() == true) {
          throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
            "Subtask '${subtask.id}' has a blank child workflow identity during hard reset.",
          )
        }
        val restoredChildId =
          if (subtask.workflowId == null) {
            historyEvidence.restoredChildId(unitOfWork, parentWorkflowId, subtask.id)
          } else {
            null
          }
        if (subtask.workflowId == null && subtask.id in acceptances && restoredChildId == null) {
          throw SkillBillRuntimeException(
            WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
            "Subtask '${subtask.id}' acceptance has no verified child association during hard reset.",
          )
        }
        val childId = subtask.workflowId ?: restoredChildId
        if (childId != null) {
          captureSelectedChild(unitOfWork, parentWorkflowId, subtask, childId, acceptances[subtask.id])
        }
      }
    }

    private fun captureSelectedChild(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
      subtask: DecompositionSubtask,
      childId: String,
      acceptance: GoalRunnerOutOfBandAcceptance?,
    ) {
      if (acceptance != null && acceptance.commitSha != subtask.commitSha) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Subtask '${subtask.id}' acceptance does not match its durable child association.",
        )
      }
      historyEvidence.retireOtherChildren(unitOfWork, parentWorkflowId, subtask.id, childId)
      if (subtask.workflowId != null &&
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childId) == null
      ) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Selected child '$childId' is missing during hard reset.",
        )
      }
      historyEvidence.capture(
        unitOfWork,
        parentWorkflowId,
        subtask.id,
        childId,
        acceptance,
      )
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
          val parent =
            unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId)
              ?: throw SkillBillRuntimeException(
                WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
                "Goal parent is missing while deleting child '$workflowId'.",
              )
          val durableSubtask = parent.decompositionRuntime()?.subtasks?.singleOrNull { it.id == subtaskId }
          if (durableSubtask?.workflowId != workflowId) {
            throw SkillBillRuntimeException(
              WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
              "Durable parent no longer assigns child '$workflowId' to subtask '$subtaskId'.",
            )
          }
          historyEvidence.capture(unitOfWork, state.parentWorkflowId, subtaskId, workflowId)
          qualityExecution.retireForSelectionChange(unitOfWork, state.parentWorkflowId)
          val deleted =
            unitOfWork.workflowStates.deleteGoalChildWorkflow(
              state.parentWorkflowId,
              subtaskId,
              workflowId,
            )
          if (deleted != 1) {
            throw SkillBillRuntimeException(
              WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
              "Child workflow '$workflowId' could not be deleted with its preserved evidence.",
            )
          }
          historyEvidence.retire(unitOfWork, state.parentWorkflowId, subtaskId)
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

    override fun restoreOutOfBandAcceptance(
      state: GoalRunnerManifestState,
      acceptance: GoalRunnerOutOfBandAcceptance,
    ): GoalChildHistoryAcceptanceRestoreResult {
      val result =
        database.transaction { unit ->
          val priorManifest =
            unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId)
              ?.decompositionRuntime()
          val priorSubtask = priorManifest?.subtasks?.singleOrNull { it.id == acceptance.subtaskId }
          val priorRestoredChild = historyEvidence.restoredChildId(unit, state.parentWorkflowId, acceptance.subtaskId)
          when (val restored = restoreGoalChildHistoryAcceptance(unit, state, acceptance, historyEvidence)) {
            is GoalChildHistoryAcceptanceRestoreResult.Refused -> restored to null
            is GoalChildHistoryAcceptanceRestoreResult.Restored -> {
              val restoredSubtask = restored.state.manifest.subtasks.single { it.id == acceptance.subtaskId }
              val restoredChild =
                restoredSubtask.workflowId ?: historyEvidence.restoredChildId(
                  unit,
                  state.parentWorkflowId,
                  acceptance.subtaskId,
                )
              val priorChild = priorSubtask?.workflowId ?: priorRestoredChild
              if (priorChild != restoredChild) {
                qualityExecution.retireForSelectionChange(unit, state.parentWorkflowId)
              }
              val projection =
                projectionPersistence.saveInTransaction(
                  unit,
                  restored.state,
                  mergeConcurrentProgress = false,
                )
              GoalChildHistoryAcceptanceRestoreResult.Restored(
                projection.state,
                restored.acceptance,
              ) to projection.projectionArtifacts
            }
          }
        }
      result.second?.let { projectionPersistence.writeProjectionFile(state, it) }
      return result.first
    }
  }
