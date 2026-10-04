package skillbill.engine.goalrunner.planning.model

import skillbill.contracts.workflow.identity.status.GOAL_PLANNING_WAVE_CAP
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface GoalPlanningSweepOutcome {
  data class PreparedAll(
    val identity: GoalPlanningIdentity? = null,
    val provenance: GoalPlanningContractProvenance? = null,
    val descriptors: List<GovernedGoalSubtaskDescriptor> = emptyList(),
  ) : GoalPlanningSweepOutcome {
    fun hydrationFor(subtaskId: Int) =
      identity?.let { expectedIdentity ->
        val expectedProvenance = requireNotNull(provenance)
        val descriptor = descriptors.singleOrNull { it.subtaskId == subtaskId } ?: return@let null
        GoalChildPlanningHydrationRequest(
          expectedIdentity,
          expectedProvenance,
          descriptor,
        )
      }
  }

  data class Stopped(
    val issueKey: String,
    val currentSubtaskId: Int,
    val reason: GoalRunnerStopReason,
    val blockedReason: String,
    val lastResumableStep: String,
  ) : GoalPlanningSweepOutcome
}

sealed interface GoalPlanningPhaseProduction {
  data class Captured(
    val payload: String,
    val agentId: String = "",
  ) : GoalPlanningPhaseProduction

  data class EmptyProviderTurn(
    val reason: String,
    val evidence: GoalPlanningEmptyTurnEvidence,
  ) : GoalPlanningPhaseProduction

  data class Stopped(val outcome: GoalPlanningSweepOutcome.Stopped) : GoalPlanningPhaseProduction

  /** A required phase write was rejected; nothing ran after it and the caller blocks the attempt. */
  data class RequiredWriteRejected(val rejection: RequiredPhaseWrite.Rejected) : GoalPlanningPhaseProduction
}

data class GoalPlanningEmptyTurnEvidence(
  val agentId: String,
  val durationMs: Long,
  val exitStatus: Int?,
  val assistantEventCount: Int?,
  val rawOutputPreview: String?,
) {
  fun summary(): String =
    buildString {
      append("EmptyProviderTurn: agent=")
      append(agentId)
      append(" durationMs=")
      append(durationMs)
      append(" exitStatus=")
      append(exitStatus ?: "none")
      append(" assistantEvents=")
      append(assistantEventCount ?: "unknown")
    }
}

data class GoalPlanningRejectionRecord(
  val parentWorkflowId: String,
  val issueKey: String,
  val phaseId: String,
  val subtaskId: Int,
  val attempt: Int,
  val rule: String,
  val reason: String,
  val agentId: String,
  val rawEvidence: String,
)

data class GoalPlanningBurstSchedule(
  val planFanOutCap: Int,
  val emptyTurnBackoffBase: Duration,
  val emptyTurnBackoffFactor: Int,
  val waitSlice: Duration,
) {
  init {
    require(planFanOutCap >= 1) { "planFanOutCap must be at least 1." }
    require(emptyTurnBackoffBase.isPositive()) { "emptyTurnBackoffBase must be positive." }
    require(emptyTurnBackoffFactor >= 2) { "emptyTurnBackoffFactor must be at least 2." }
    require(waitSlice.isPositive()) { "waitSlice must be positive." }
  }

  fun emptyTurnBackoffAfterAttempt(failedAttempt: Int): Duration {
    require(failedAttempt >= 1) { "failedAttempt must be at least 1." }
    var scale = 1
    repeat(failedAttempt - 1) { scale *= emptyTurnBackoffFactor }
    return emptyTurnBackoffBase * scale
  }

  companion object {
    const val DEFAULT_PLAN_FAN_OUT_CAP: Int = GOAL_PLANNING_WAVE_CAP
    val DEFAULT_EMPTY_TURN_BACKOFF_BASE: Duration = 30.seconds
    const val DEFAULT_EMPTY_TURN_BACKOFF_FACTOR: Int = 2
    val DEFAULT_WAIT_SLICE: Duration = 1.seconds
  }
}

data class GoalPlanningStatusAlignRequest(
  val snapshot: GoalPlanningStatusSnapshot,
  val parentWorkflowId: String,
  val issueKey: String,
  val manifest: DecompositionManifest,
  val repoRoot: Path,
)
