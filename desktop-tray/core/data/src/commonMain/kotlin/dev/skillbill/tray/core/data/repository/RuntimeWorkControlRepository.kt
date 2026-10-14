package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.port.ControlAction
import dev.skillbill.tray.core.data.port.ControlRefusal
import dev.skillbill.tray.core.data.port.ControlResult
import dev.skillbill.tray.core.data.session.DatabaseSession
import dev.skillbill.tray.core.data.session.staleCursor
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.StorageFailure
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Goal and run controls for the selected database; every request is fenced to the generation the item was read in. */
@Inject
@SingleIn(DatabaseSessionScope::class)
class RuntimeWorkControlRepository(
  private val session: DatabaseSession,
  private val diagnostics: Diagnostics,
) : WorkControlRepository {
  override suspend fun pause(item: WorkItem): Outcome<Unit> = goalControl(item, ControlAction.PAUSE)

  override suspend fun stop(item: WorkItem): Outcome<Unit> = goalControl(item, ControlAction.STOP)

  override suspend fun cancel(item: WorkItem): Outcome<Unit> = runAction(item, ControlAction.CANCEL, null)

  override suspend fun launch(item: WorkItem, agentId: String?): Outcome<Unit> = runAction(item, ControlAction.LAUNCH, agentId)

  private suspend fun goalControl(item: WorkItem, action: ControlAction): Outcome<Unit> = request(item, action, null) { result ->
    when (result) {
      ControlResult.Accepted -> Outcome.Value(Unit)
      ControlResult.Stale -> staleCursor()
      is ControlResult.LaunchFailed -> Outcome.Failure(Problem(ProblemCode.UNKNOWN, result.reason))
      is ControlResult.Rejected -> {
        diagnostics.record("goal_control_${result.reasonCode}")
        Outcome.Failure(
          Problem(
            ProblemCode.STALE_TARGET,
            "This goal changed or cannot accept this control. Refresh before retrying.",
            StorageFailure.STALE_QUERY,
          ),
        )
      }
    }
  }

  private suspend fun runAction(item: WorkItem, action: ControlAction, agentId: String?): Outcome<Unit> =
    request(item, action, agentId) { result ->
      when (result) {
        ControlResult.Accepted -> Outcome.Value(Unit)
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

  private suspend fun request(
    item: WorkItem,
    action: ControlAction,
    agentId: String?,
    translate: (ControlResult) -> Outcome<Unit>,
  ): Outcome<Unit> = session.mutex.withLock {
    session.safeRead {
      session.ensureSession()
      val generation = session.generation
      if (!session.currentFileMatches() || item.databaseGeneration != generation) return@safeRead staleCursor()
      translate(
        session.gateway.request(item, action, agentId) {
          session.currentFileMatches() && item.databaseGeneration == session.generation
        },
      )
    }
  }

  private fun runRefusalMessage(refusal: ControlRefusal): String = when (refusal) {
    ControlRefusal.AGENT_REQUIRED -> "Choose an agent to start this run."
    ControlRefusal.BRANCH_CHANGED -> "Check out this run's recorded branch before starting it again."
    ControlRefusal.TERMINATION_INCOMPLETE -> "The runner has not stopped yet. Refresh and retry cancellation."
    ControlRefusal.OWNERSHIP_MISMATCH -> "The runner's identity could not be confirmed. Refresh before retrying."
    ControlRefusal.OTHER -> "This run changed or cannot accept this action. Refresh before retrying."
  }
}
