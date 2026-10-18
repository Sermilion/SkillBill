package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.port.ControlAction
import dev.skillbill.tray.core.data.port.ControlRefusal
import dev.skillbill.tray.core.data.port.ControlResult
import dev.skillbill.tray.core.data.session.DatabaseSession
import dev.skillbill.tray.core.data.session.staleCursor
import dev.skillbill.tray.core.domain.model.ControlConfirmation
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.StorageFailure
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Goal and run controls for the selected database; every request is fenced to the generation the item was read in. */
@Inject
@SingleIn(DatabaseSessionScope::class)
class RuntimeWorkControlRepository(
  private val session: DatabaseSession,
  private val diagnostics: Diagnostics,
) : WorkControlRepository {
  override suspend fun pause(item: WorkItem): Outcome<ControlConfirmation> = goalControl(item, ControlAction.PAUSE)

  override suspend fun stop(item: WorkItem): Outcome<ControlConfirmation> = goalControl(item, ControlAction.STOP)

  override suspend fun cancel(item: WorkItem): Outcome<Unit> = runAction(item, ControlAction.CANCEL, null)

  override suspend fun launch(item: WorkItem, agentId: String?): Outcome<Unit> = runAction(item, ControlAction.LAUNCH, agentId)

  private suspend fun goalControl(item: WorkItem, action: ControlAction): Outcome<ControlConfirmation> =
    request(item, action, null) { result ->
      when (result) {
        ControlResult.Accepted -> Outcome.Value(ControlConfirmation.REQUEST_ACCEPTED)
        ControlResult.IntentRecorded -> Outcome.Value(ControlConfirmation.INTENT_RECORDED)
        ControlResult.ExitConfirmed -> Outcome.Value(ControlConfirmation.EXIT_CONFIRMED)
        ControlResult.TerminationAttempted -> Outcome.Value(ControlConfirmation.TERMINATION_ATTEMPTED)
        ControlResult.Stale -> staleCursor()
        is ControlResult.LaunchFailed -> Outcome.Failure(Problem(ProblemCode.UNKNOWN, result.reason))
        is ControlResult.Rejected -> {
          diagnostics.record("goal_control_${result.reasonCode}")
          Outcome.Failure(goalRefusal(result.refusal))
        }
      }
    }

  private suspend fun runAction(item: WorkItem, action: ControlAction, agentId: String?): Outcome<Unit> =
    request(item, action, agentId) { result ->
      when (result) {
        ControlResult.Accepted, ControlResult.IntentRecorded, ControlResult.ExitConfirmed -> Outcome.Value(Unit)
        ControlResult.TerminationAttempted ->
          Outcome.Failure(Problem(ProblemCode.STALE_TARGET, runRefusalMessage(ControlRefusal.TERMINATION_INCOMPLETE)))
        ControlResult.Stale -> staleCursor()
        is ControlResult.LaunchFailed -> {
          diagnostics.record("run_launch_failed")
          Outcome.Failure(Problem(ProblemCode.UNKNOWN, result.reason))
        }
        is ControlResult.Rejected -> {
          diagnostics.record("run_action_${result.reasonCode}")
          Outcome.Failure(Problem(ProblemCode.STALE_TARGET, runRefusalMessage(result.refusal)))
        }
      }
    }

  private suspend fun <T> request(
    item: WorkItem,
    action: ControlAction,
    agentId: String?,
    translate: (ControlResult) -> Outcome<T>,
  ): Outcome<T> = session.read { _ ->
    val generation = session.generation
    if (!session.currentFileMatches() || item.databaseGeneration != generation) return@read staleCursor()
    translate(
      session.gateway.request(item, action, agentId) {
        session.currentFileMatches() && item.databaseGeneration == session.generation
      },
    )
  }

  /** A goal stop that could not confirm the runner's exit is not "stopped": the stop is recorded and the runner may still run. */
  private fun goalRefusal(refusal: ControlRefusal): Problem = when (refusal) {
    ControlRefusal.TERMINATION_INCOMPLETE -> Problem(
      ProblemCode.STALE_TARGET,
      "The stop was recorded, but the goal runner has not exited yet. Refresh and check it before retrying.",
    )
    ControlRefusal.OWNERSHIP_MISMATCH -> Problem(
      ProblemCode.STALE_TARGET,
      "The goal runner's identity could not be confirmed. Refresh before retrying.",
    )
    ControlRefusal.FOREIGN_HOST -> Problem(
      ProblemCode.STALE_TARGET,
      "The goal runner process is on another host. Refresh before retrying stop or pause.",
    )
    ControlRefusal.PID_REUSED -> Problem(
      ProblemCode.STALE_TARGET,
      "The goal runner's PID was reused by a different process. Refresh before retrying stop or pause.",
    )
    ControlRefusal.UNINSPECTABLE -> Problem(
      ProblemCode.STALE_TARGET,
      "The goal runner process could not be inspected. Refresh before retrying stop or pause.",
    )
    ControlRefusal.IDENTITY_MISMATCH -> Problem(
      ProblemCode.STALE_TARGET,
      "The goal runner's identity no longer matches this goal. Refresh before retrying stop or pause.",
    )
    else -> Problem(
      ProblemCode.STALE_TARGET,
      "This goal changed or cannot accept this control. Refresh before retrying.",
      StorageFailure.STALE_QUERY,
    )
  }

  private fun runRefusalMessage(refusal: ControlRefusal): String = when (refusal) {
    ControlRefusal.AGENT_REQUIRED -> "Choose an agent to start this run."
    ControlRefusal.BRANCH_CHANGED -> "Check out this run's recorded branch before starting it again."
    ControlRefusal.BRANCH_UNAVAILABLE ->
      "Cannot read this run's checkout branch. Restore its checkout or check out its recorded branch before starting it again."
    ControlRefusal.TERMINATION_INCOMPLETE -> "The runner has not stopped yet. Refresh and retry cancellation."
    ControlRefusal.OWNERSHIP_MISMATCH -> "The runner's identity could not be confirmed. Refresh before retrying."
    ControlRefusal.FOREIGN_HOST -> "The runner process is on another host. Refresh before retrying cancellation."
    ControlRefusal.PID_REUSED -> "The runner PID was reused by a different process. Refresh before retrying cancellation."
    ControlRefusal.UNINSPECTABLE -> "The runner process could not be inspected. Refresh before retrying cancellation."
    ControlRefusal.IDENTITY_MISMATCH -> "The runner identity no longer matches this run. Refresh before retrying cancellation."
    ControlRefusal.OTHER -> "This run changed or cannot accept this action. Refresh before retrying."
  }
}
