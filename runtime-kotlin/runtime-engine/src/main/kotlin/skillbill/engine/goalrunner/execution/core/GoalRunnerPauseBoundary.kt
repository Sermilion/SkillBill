package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.execution.support.GoalRunnerChildProgressRead
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationResult
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.status.stopped
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerStopReason
import kotlin.coroutines.cancellation.CancellationException

@Inject
class GoalRunnerProgressReader(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) {
  internal fun read(workflowId: String): GoalRunnerChildProgressRead =
    runCatching { GoalRunnerChildProgressRead.Present(outcomeStore.progress(workflowId)) }
      .fold(
        onSuccess = { it },
        onFailure = { error ->
          when {
            error is CancellationException -> throw error
            error is InterruptedException -> {
              Thread.currentThread().interrupt()
              throw error
            }
            error.isShellContentContractFailure() -> throw error
            else -> {
              val exception = error as? Exception ?: throw error
              GoalRunnerChildProgressRead.Failed(exception)
            }
          }
        },
      )

  fun safeProgress(workflowId: String): GoalRunnerWorkflowProgress? =
    when (val read = read(workflowId)) {
      is GoalRunnerChildProgressRead.Present -> read.progress
      is GoalRunnerChildProgressRead.Absent -> null
      is GoalRunnerChildProgressRead.Failed -> null
    }
}

@Inject
class GoalRunnerPauseBoundary(
  private val manifestStore: GoalRunnerManifestStore,
) {
  internal fun pauseBeforeLaunch(
    state: GoalRunnerManifestState,
    knownControl: GoalRunnerControlState? = null,
  ): GoalRunnerIterationResult? {
    val control = knownControl ?: manifestStore.controlState(state.parentWorkflowId)
    if (!control.requiresPauseBoundary(state.manifest)) return null
    val pausedState =
      manifestStore.pauseAtBoundary(
        state.copy(controlState = control),
      )
    val subtaskId = pausedState.manifest.currentSubtaskIntent.subtaskId
    return GoalRunnerIterationResult(
      state = pausedState,
      report =
        stopped(
          StoppedReportArgs(
            issueKey = pausedState.manifest.issueKey,
            attempted = emptyList(),
            subtaskId = subtaskId,
            reason = GoalRunnerStopReason.PAUSED,
            blockedReason = "Goal paused at a durable boundary: ${pausedState.controlState.pauseReason}",
            workflowId = pausedState.manifest.workflowIdFor(subtaskId),
            lastResumableStep =
              pausedState.manifest.subtasks
                .firstOrNull { it.id == subtaskId }
                ?.lastResumableStep
                .orEmpty()
                .ifBlank { "plan" },
          ),
        ),
    )
  }
}
