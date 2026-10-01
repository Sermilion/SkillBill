package skillbill.engine.goalrunner.manifest

import skillbill.engine.featuretask.lifecycle.checkpoint.pruneCompletedSubtaskCheckpointRefs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
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
  manifest: DecompositionManifest,
  authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
): DecompositionManifest {
  val context =
    GoalManifestReconciliationContext(
      issueKey = manifest.issueKey,
      authoritativeOutcomes = authoritativeOutcomes,
      acceptances = acceptances,
      outcomeStore = outcomeStore,
    )
  return manifest.copy(subtasks = manifest.subtasks.map { subtask -> context.reconcile(subtask) })
    .withParentStatus()
    .withDerivedCurrentIntent()
}

fun pruneEligibleCheckpointRefsForManifest(
  manifest: DecompositionManifest,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  record: (String) -> Unit,
) {
  manifest.subtasks.forEach { subtask ->
    if (subtask.status.decompositionStatus() == DecompositionStatus.COMPLETE && !subtask.commitSha.isNullOrBlank()) {
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

private data class GoalManifestReconciliationContext(
  val issueKey: String,
  val authoritativeOutcomes: Map<Int, GoalRunnerStoredOutcome>,
  val acceptances: Map<Int, GoalRunnerOutOfBandAcceptance>,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) {
  fun reconcile(subtask: DecompositionSubtask): DecompositionSubtask {
    val workflowId = subtask.workflowId?.takeIf(String::isNotBlank)
    val outcome = workflowId?.let { id -> preferredOutcome(subtask, id) }

    acceptances[subtask.id]
      ?.takeIf { outcome?.status != GoalRunnerTerminalStatus.COMPLETE }
      ?.let { acceptance ->
        return subtask.copy(
          status = DecompositionStatus.COMPLETE.wireValue,
          commitSha = acceptance.commitSha,
          blockedReason = null,
          lastResumableStep = null,
        )
      }

    val staleRetryOutcome =
      workflowId != null &&
        outcome?.workflowId == workflowId &&
        outcome.status != GoalRunnerTerminalStatus.COMPLETE &&
        outcomeStore.progress(workflowId)?.workflowStatus == WorkflowStatus.RUNNING
    return if (staleRetryOutcome) {
      subtask.copy(status = DecompositionStatus.IN_PROGRESS.wireValue, blockedReason = null)
    } else if (outcome == null || shouldPreserveCompletedSubtask(subtask, outcome)) {
      subtask
    } else {
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

  private fun preferredOutcome(
    subtask: DecompositionSubtask,
    workflowId: String,
  ): GoalRunnerStoredOutcome? =
    authoritativeOutcomes[subtask.id]
      ?.takeIf { outcome -> canApplyAuthoritativeOutcome(subtask, workflowId, outcome) }
      ?: outcomeStore.terminalOutcome(
        workflowId = workflowId,
        issueKey = issueKey,
        subtaskId = subtask.id,
      )
}

private fun canApplyAuthoritativeOutcome(
  subtask: DecompositionSubtask,
  workflowId: String,
  outcome: GoalRunnerStoredOutcome,
): Boolean {
  val resetPendingSubtask =
    subtask.status.decompositionStatus() == DecompositionStatus.PENDING &&
      subtask.workflowId.isNullOrBlank()
  if (resetPendingSubtask && outcome.status != GoalRunnerTerminalStatus.COMPLETE) {
    return false
  }

  val nonCompleteSibling = outcome.workflowId != workflowId && outcome.status != GoalRunnerTerminalStatus.COMPLETE
  return subtask.status.decompositionStatus() != DecompositionStatus.IN_PROGRESS || !nonCompleteSibling
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
    -> DecompositionStatus.BLOCKED.wireValue
  }

private fun DecompositionManifest.withDerivedCurrentIntent(): DecompositionManifest {
  val nextIntent =
    subtasks.firstOrNull {
      it.status.decompositionStatus() == DecompositionStatus.BLOCKED
    }?.let { blocked ->
      CurrentSubtaskIntent(subtaskId = blocked.id, action = DecompositionSubtaskAction.BLOCKED.wireValue)
    } ?: subtasks.firstOrNull {
      it.status.decompositionStatus() == DecompositionStatus.IN_PROGRESS
    }?.let { inProgress ->
      CurrentSubtaskIntent(subtaskId = inProgress.id, action = DecompositionSubtaskAction.RESUME.wireValue)
    } ?: firstRunnablePendingSubtask()?.let { pending ->
      CurrentSubtaskIntent(subtaskId = pending.id, action = DecompositionSubtaskAction.START.wireValue)
    } ?: CurrentSubtaskIntent(subtaskId = 0, action = DecompositionSubtaskAction.COMPLETE.wireValue)
  return copy(currentSubtaskIntent = nextIntent)
}

private fun DecompositionManifest.firstRunnablePendingSubtask(): DecompositionSubtask? {
  val subtasksById = subtasks.associateBy(DecompositionSubtask::id)
  return subtasks.firstOrNull { subtask ->
    subtask.status.decompositionStatus() == DecompositionStatus.PENDING &&
      subtask.dependencies.all { dependency ->
        val dependencySubtask = subtasksById[dependency.subtaskId]
        dependencySubtask?.status.decompositionStatus() in
          setOf(
            DecompositionStatus.COMPLETE,
            DecompositionStatus.SKIPPED,
          ) || (dependency.optional && dependency.skipped)
      }
  } ?: subtasks.firstOrNull { it.status.decompositionStatus() == DecompositionStatus.PENDING }
}
