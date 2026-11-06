package skillbill.engine.goalrunner.monitoring

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.truncateWalBestEffort
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.ports.db.DatabaseCheckpoint
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.Clock

@Inject
public class GoalCompletionRecorder(
  private val manifestStore: GoalRunnerManifestStore,
  private val clock: Clock,
  private val checkpoint: DatabaseCheckpoint,
  private val diagnostics: RuntimeDiagnostics,
  private val worktreeSettlement: GoalWorktreeCompletionSettlement,
) {
  internal fun clock(): Clock = clock

  internal fun record(
    parentWorkflowId: String,
    report: GoalRunnerRunReport,
  ) {
    val pullRequestUrl =
      when (report) {
        is GoalRunnerRunReport.Completed -> report.pullRequestUrl?.takeIf(String::isNotBlank)
        is GoalRunnerRunReport.CompletedNoChange -> null
        else -> return
      }
    manifestStore.updateControlState(parentWorkflowId) { current ->
      current.copy(goalCompletedAt = clock.instant().toString(), goalPullRequestUrl = pullRequestUrl)
    }
    checkpoint.truncateWalBestEffort(diagnostics, "goal_completion")
  }

  internal fun settleWorktree(state: GoalRunnerManifestState) = worktreeSettlement.settle(state)

  internal fun retryWorktreeIfPending(state: GoalRunnerManifestState) = worktreeSettlement.retryIfPending(state)
}
