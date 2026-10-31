package skillbill.engine.goalrunner.repair

import skillbill.engine.goalrunner.model.GoalRunnerWedgeClass
import skillbill.engine.goalrunner.model.GoalRunnerWedgeFinding
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import java.time.Clock

public const val PASSED_PARENT_EXECUTION_LEASE: String = "parent_execution_lease_absent_or_unexpired"
public const val PASSED_PARENT_PAUSE_STATE: String = "parent_pause_not_runner_interrupted"

internal data class GoalRunnerParentWedgeDiagnosis(
  val wedges: List<GoalRunnerWedgeFinding> = emptyList(),
  val passedChecks: List<String> = emptyList(),
) {
  val isHealthy: Boolean get() = wedges.isEmpty()
}

internal class GoalRunnerParentRepairWedgeDiagnosis(
  private val clock: Clock,
) {
  fun diagnose(controlState: GoalRunnerControlState): GoalRunnerParentWedgeDiagnosis {
    val wedges = mutableListOf<GoalRunnerWedgeFinding>()
    val passed = mutableListOf<String>()
    diagnoseExecutionLease(controlState, wedges, passed)
    diagnoseRunnerInterruptedPause(controlState, wedges, passed)
    return GoalRunnerParentWedgeDiagnosis(wedges = wedges, passedChecks = passed)
  }

  private fun diagnoseExecutionLease(
    controlState: GoalRunnerControlState,
    wedges: MutableList<GoalRunnerWedgeFinding>,
    passed: MutableList<String>,
  ) {
    val lease = controlState.executionLease
    if (lease == null || !leaseExpired(lease)) {
      passed += PASSED_PARENT_EXECUTION_LEASE
      return
    }
    wedges +=
      GoalRunnerWedgeFinding(
        wedgeClass = GoalRunnerWedgeClass.STALE_EXECUTION_LEASE,
        field = GoalRunnerWedgeClass.STALE_EXECUTION_LEASE.durableField,
        currentValue = lease.expiresAt.toString(),
      )
  }

  private fun diagnoseRunnerInterruptedPause(
    controlState: GoalRunnerControlState,
    wedges: MutableList<GoalRunnerWedgeFinding>,
    passed: MutableList<String>,
  ) {
    val runnerInterrupted =
      (controlState.paused || controlState.pauseRequested) &&
        controlState.pauseReason == GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
    if (!runnerInterrupted) {
      passed += PASSED_PARENT_PAUSE_STATE
      return
    }
    wedges +=
      GoalRunnerWedgeFinding(
        wedgeClass = GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE,
        field = GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE.durableField,
        currentValue = controlState.pauseReason,
      )
  }

  private fun leaseExpired(lease: GoalRunnerExecutionLease): Boolean = !lease.expiresAtInstant.isAfter(clock.instant())
}
