package skillbill.engine.goalrunner.manifest

import skillbill.engine.featuretask.lifecycle.checkpoint.pruneCompletedSubtaskCheckpointRefs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRestoredChildReference
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.withParentStatus
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.DecompositionSubtaskAction
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.workflowStatus
import java.nio.file.Path

fun reconcileGoalManifest(
  state: GoalRunnerManifestState,
  authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  restoredChildren: Map<Int, GoalRunnerRestoredChildReference>? = null,
): DecompositionManifest {
  val context =
    GoalManifestReconciliationContext(
      state.manifest.issueKey,
      state.parentWorkflowId,
      authoritativeOutcomes,
      acceptances,
      outcomeStore,
      null,
      restoredChildren,
    )
  return state.manifest.copy(subtasks = state.manifest.subtasks.map(context::reconcile))
    .withParentStatus().withDerivedCurrentIntent()
}

fun pruneEligibleCheckpointRefsForManifest(
  manifest: DecompositionManifest,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  record: (String) -> Unit,
) {
  manifest.subtasks.forEach { subtask ->
    if (subtask.status.decompositionStatus() == DecompositionStatus.COMPLETE &&
      !subtask.commitSha.isNullOrBlank()
    ) {
      pruneCompletedSubtaskCheckpointRefs(
        gitOperations = gitOperations,
        repoRoot = repoRoot,
        request =
          FeatureTaskRuntimeCheckpointRefPruneRequest(
            issueKey = manifest.issueKey,
            subtaskId = subtask.id.toString(),
            manifestCommitSha = subtask.commitSha,
            featureBranch = manifest.featureBranch,
          ),
        record = record,
      )
    }
  }
}

internal class GoalManifestReconciliationContext(
  val issueKey: String,
  val parentWorkflowId: String,
  val authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  val acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val unitOfWork: UnitOfWork?,
  val restoredChildren: Map<Int, GoalRunnerRestoredChildReference>? = null,
) {
  fun reconcile(subtask: DecompositionSubtask): DecompositionSubtask {
    val existingStatus = subtask.status.decompositionStatus()
    validateCompletion(subtask)
    if (existingStatus == DecompositionStatus.COMPLETED_NO_CHANGE) return subtask
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank)
    val outcome =
      workflowId?.takeIf { existingStatus != DecompositionStatus.PENDING }
        ?.let { id -> preferredOutcome(subtask, id) }
    restoredAcceptance(subtask, outcome)?.let { return it }
    return reconcileOutcome(subtask, outcome)
  }

  private fun restoredChildId(subtask: DecompositionSubtask): String? {
    if (restoredChildren != null) return restoredChildren[subtask.id]?.childWorkflowId
    return if (unitOfWork != null) {
      outcomeStore.restoredGoalChildWorkflowId(unitOfWork, parentWorkflowId, subtask.id)
    } else {
      outcomeStore.restoredGoalChildWorkflowId(parentWorkflowId, subtask.id)
    }
  }

  private fun validateCompletion(subtask: DecompositionSubtask) {
    if (subtask.status.decompositionStatus() in
      setOf(DecompositionStatus.COMPLETE, DecompositionStatus.COMPLETED_NO_CHANGE)
    ) {
      val childId = subtask.workflowId ?: restoredChildId(subtask) ?: return
      requireDraft(childId, subtask.id)
    }
  }

  private fun restoredAcceptance(
    subtask: DecompositionSubtask,
    outcome: GoalRunnerStoredOutcome?,
  ): DecompositionSubtask? {
    if (subtask.workflowId != null || outcome?.status == GoalRunnerTerminalStatus.COMPLETE) return null
    val childId = restoredChildId(subtask) ?: return null
    val acceptance = restoredChildren?.get(subtask.id)?.acceptance ?: acceptances[subtask.id] ?: return null
    requireDraft(childId, subtask.id)
    return subtask.copy(
      status = DecompositionStatus.COMPLETE.wireValue,
      commitSha = acceptance.commitSha,
      blockedReason = null,
      lastResumableStep = null,
    )
  }

  private fun reconcileOutcome(
    subtask: DecompositionSubtask,
    outcome: GoalRunnerStoredOutcome?,
  ): DecompositionSubtask {
    val workflowId = subtask.workflowId
    val staleRetryOutcome =
      workflowId != null &&
        outcome?.workflowId == workflowId &&
        outcome.status != GoalRunnerTerminalStatus.COMPLETE &&
        (unitOfWork?.let { outcomeStore.progress(it, workflowId) } ?: outcomeStore.progress(workflowId))
          ?.workflowStatus == WorkflowStatus.RUNNING
    return if (staleRetryOutcome) {
      subtask.copy(status = DecompositionStatus.IN_PROGRESS.wireValue, blockedReason = null)
    } else if (outcome == null || shouldPreserveCompletedSubtask(subtask, outcome)) {
      subtask
    } else {
      if (outcome.status == GoalRunnerTerminalStatus.COMPLETE) {
        requireDraft(outcome.workflowId, subtask.id)
      }
      val status = outcome.toManifestStatus()
      subtask.copy(
        status = status,
        workflowId = outcome.workflowId.takeIf(String::isNotBlank) ?: subtask.workflowId,
        commitSha = outcome.commitSha ?: subtask.commitSha,
        blockedReason =
          outcome.blockedReason
            ?.takeIf { status.decompositionStatus() == DecompositionStatus.BLOCKED }
            ?: subtask.blockedReason.takeIf { status.decompositionStatus() == DecompositionStatus.BLOCKED },
        lastResumableStep = outcome.lastResumableStep ?: subtask.lastResumableStep,
      )
    }
  }

  private fun requireDraft(
    childWorkflowId: String?,
    subtaskId: Int,
  ) {
    val childId =
      childWorkflowId?.takeIf(String::isNotBlank)
        ?: throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
          "Completed goal subtask $subtaskId has no child identity to verify its accepted history draft or skip.",
        )
    val hasDraft =
      unitOfWork?.let { outcomeStore.hasRequiredGoalChildHistoryDraft(it, parentWorkflowId, subtaskId, childId) }
        ?: outcomeStore.hasRequiredGoalChildHistoryDraft(parentWorkflowId, subtaskId, childId)
    if (!hasDraft) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_DRAFT,
        "Goal child $childId cannot be reconciled as complete without its accepted history draft or skip.",
      )
    }
  }

  private fun preferredOutcome(
    subtask: DecompositionSubtask,
    workflowId: String,
  ): GoalRunnerStoredOutcome? =
    authoritativeOutcomes[subtask.id]
      ?.takeIf { outcome -> canApplyAuthoritativeOutcome(subtask, workflowId, outcome) }
      ?: if (unitOfWork != null) {
        outcomeStore.terminalOutcome(unitOfWork, workflowId, issueKey, subtask.id)
      } else {
        outcomeStore.terminalOutcome(workflowId, issueKey, subtask.id)
      }
}

private fun canApplyAuthoritativeOutcome(
  subtask: DecompositionSubtask,
  workflowId: String,
  outcome: GoalRunnerStoredOutcome,
): Boolean {
  return outcome.workflowId == workflowId &&
    subtask.workflowId == workflowId &&
    subtask.status.decompositionStatus() != DecompositionStatus.PENDING
}

private fun shouldPreserveCompletedSubtask(
  subtask: DecompositionSubtask,
  outcome: GoalRunnerStoredOutcome,
): Boolean =
  subtask.status.decompositionStatus() == DecompositionStatus.COMPLETE &&
    !subtask.commitSha.isNullOrBlank() &&
    outcome.status != GoalRunnerTerminalStatus.COMPLETE

private fun GoalRunnerStoredOutcome.toManifestStatus(): String =
  when (status) {
    GoalRunnerTerminalStatus.COMPLETE -> DecompositionStatus.COMPLETE.wireValue

    GoalRunnerTerminalStatus.RECONCILABLE -> DecompositionStatus.IN_PROGRESS.wireValue

    GoalRunnerTerminalStatus.PAUSED -> DecompositionStatus.IN_PROGRESS.wireValue
    GoalRunnerTerminalStatus.BLOCKED,
    GoalRunnerTerminalStatus.FAILED,
    GoalRunnerTerminalStatus.TIMEOUT,
    GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
    GoalRunnerTerminalStatus.COMPLETED_NO_CHANGE,
    -> DecompositionStatus.BLOCKED.wireValue
  }

private fun DecompositionManifest.withDerivedCurrentIntent(): DecompositionManifest {
  val selected =
    selectGoalCurrentSubtask(
      subtasks.firstOrNull { it.status.decompositionStatus() == DecompositionStatus.BLOCKED },
      subtasks.firstOrNull { it.status.decompositionStatus() == DecompositionStatus.IN_PROGRESS },
      firstRunnablePendingSubtask(),
      null,
    )
  val action =
    when (selected?.status.decompositionStatus()) {
      DecompositionStatus.BLOCKED -> DecompositionSubtaskAction.BLOCKED
      DecompositionStatus.IN_PROGRESS -> DecompositionSubtaskAction.RESUME
      DecompositionStatus.PENDING -> DecompositionSubtaskAction.START
      else -> DecompositionSubtaskAction.COMPLETE
    }
  val nextIntent = CurrentSubtaskIntent(selected?.id ?: 0, action.wireValue)
  return copy(currentSubtaskIntent = nextIntent)
}

private fun DecompositionManifest.firstRunnablePendingSubtask(): DecompositionSubtask? {
  val subtasksById = subtasks.associateBy(DecompositionSubtask::id)
  return subtasks.firstOrNull { subtask ->
    subtask.status.decompositionStatus() == DecompositionStatus.PENDING &&
      subtask.dependencies.all { dependency ->
        val dependencySubtask = subtasksById[dependency.subtaskId]
        goalDependencySatisfied(dependency, dependencySubtask?.status.decompositionStatus())
      }
  } ?: subtasks.firstOrNull { it.status.decompositionStatus() == DecompositionStatus.PENDING }
}

internal fun selectGoalCurrentSubtask(
  blocked: DecompositionSubtask?,
  inProgress: DecompositionSubtask?,
  runnablePending: DecompositionSubtask?,
  pending: DecompositionSubtask?,
): DecompositionSubtask? = blocked ?: inProgress ?: runnablePending ?: pending

internal fun goalDependencySatisfied(
  dependency: DecompositionDependency,
  status: DecompositionStatus?,
): Boolean =
  status in
    setOf(
      DecompositionStatus.COMPLETE,
      DecompositionStatus.SKIPPED,
    ) || (dependency.optional && dependency.skipped)
