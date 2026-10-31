package skillbill.engine.goalrunner.manifest

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goal.GoalManifestProjectionReader
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerResetSnapshot
import skillbill.engine.goalrunner.model.GoalRunnerResetSubtaskSnapshot
import skillbill.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

public fun DecompositionManifest.toResetSnapshot(): GoalRunnerResetSnapshot =
  GoalRunnerResetSnapshot(
    status = status,
    currentSubtaskId = currentSubtaskIntent.subtaskId.takeIf { it > 0 },
    currentAction = currentSubtaskIntent.action,
    subtasks =
      subtasks.map { subtask ->
        GoalRunnerResetSubtaskSnapshot(
          id = subtask.id,
          status = subtask.status,
          branch = subtask.branch,
          workflowId = subtask.workflowId,
          commitSha = subtask.commitSha,
          blockedReason = subtask.blockedReason,
          lastResumableStep = subtask.lastResumableStep,
        )
      },
  )

public fun GoalObservabilityProgressEvent.toStatusMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    SharedPayloadKeys.SUBTASK_ID to subtaskId,
    "workflow_phase" to workflowPhase,
    "worker_role" to workerRole,
    "liveness_class" to livenessClass,
    "activity_summary" to activitySummary,
    "sequence_number" to sequenceNumber,
    "timestamp" to timestamp,
  )

public fun Map<Int, GoalRunnerOutOfBandAcceptance>.toAcceptedSubtasks(): List<GoalRunnerAcceptedSubtask> =
  values.sortedBy(GoalRunnerOutOfBandAcceptance::subtaskId).map { acceptance ->
    GoalRunnerAcceptedSubtask(
      subtaskId = acceptance.subtaskId,
      commitSha = acceptance.commitSha,
      reason = acceptance.reason,
      acceptedAt = acceptance.acceptedAt,
    )
  }

@Inject
public class GoalRunnerManifestProjectionReader(
  private val manifestStore: GoalRunnerManifestStore,
) : GoalManifestProjectionReader {
  public override fun read(
    issueKey: String,
    repoRoot: Path,
  ): GoalRunnerManifestState? = manifestStore.loadDurableByIssueKey(issueKey, repoRoot)
}
