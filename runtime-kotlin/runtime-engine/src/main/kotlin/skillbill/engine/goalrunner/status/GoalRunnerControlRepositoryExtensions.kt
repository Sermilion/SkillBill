package skillbill.engine.goalrunner.status

import skillbill.goalrunner.model.GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.parseExecutionLeaseInstant
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import java.time.Duration
import java.time.Instant

internal fun GoalRunnerControlRepository.executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? =
  controlState(parentWorkflowId).executionLease

internal fun GoalRunnerControlRepository.acquireExecutionLease(
  parentWorkflowId: String,
  lease: GoalRunnerExecutionLease,
  expectedOwnerToken: String? = null,
): Boolean {
  val state = controlState(parentWorkflowId)
  if (state.executionLease?.ownerToken != expectedOwnerToken) return false
  persistControlState(
    parentWorkflowId,
    state.copy(
      executionLease = lease,
      activeDurationAsOf = lease.heartbeatAt.toString(),
      subtaskActiveDurationAsOf = lease.heartbeatAt.toString().takeIf { state.currentSubtaskId != null },
    ),
  )
  return true
}

internal fun GoalRunnerControlRepository.heartbeatExecutionLease(
  parentWorkflowId: String,
  lease: GoalRunnerExecutionLease,
): Boolean {
  val state = controlState(parentWorkflowId)
  val current = state.executionLease ?: return false
  if (current.ownerToken != lease.ownerToken || current.generation != lease.generation) return false
  val proposedHeartbeat = lease.heartbeatAtInstant
  if (lease.heartbeatIsStale(current, proposedHeartbeat)) {
    return false
  }
  persistControlState(parentWorkflowId, state.advancedBy(lease.heartbeatAt).copy(executionLease = lease))
  return true
}

internal fun GoalRunnerControlRepository.releaseExecutionLease(
  parentWorkflowId: String,
  ownerToken: String,
  generation: Long,
): Boolean {
  val state = controlState(parentWorkflowId)
  val current = state.executionLease ?: return false
  if (current.ownerToken != ownerToken || current.generation != generation) return false
  persistControlState(
    parentWorkflowId,
    state.copy(executionLease = null, activeDurationAsOf = null, subtaskActiveDurationAsOf = null),
  )
  return true
}

internal fun GoalRunnerControlRepository.releaseExecutionLeaseIfExpired(
  parentWorkflowId: String,
  ownerToken: String,
  generation: Long,
  nowInstant: String,
): Boolean {
  val state = controlState(parentWorkflowId)
  val current = state.executionLease ?: return false
  if (current.ownerToken != ownerToken || current.generation != generation) return false
  val now = parseExecutionLeaseInstant("now_instant", nowInstant)
  if (current.expiresAtInstant.isAfter(now)) return false
  return releaseExecutionLease(parentWorkflowId, ownerToken, generation)
}

private fun GoalRunnerExecutionLease.heartbeatIsStale(
  current: GoalRunnerExecutionLease,
  proposedHeartbeat: Instant,
): Boolean =
  !current.expiresAtInstant.isAfter(proposedHeartbeat) ||
    !expiresAtInstant.isAfter(proposedHeartbeat) ||
    expiresAtInstant <= current.expiresAtInstant ||
    heartbeatAtInstant < current.heartbeatAtInstant

private fun GoalRunnerControlState.advancedBy(heartbeatAt: Instant): GoalRunnerControlState {
  val goal = advanceAccumulator(activeDurationMs, activeDurationAsOf, heartbeatAt)
  val subtask =
    if (currentSubtaskId != null) {
      advanceAccumulator(subtaskActiveDurationMs, subtaskActiveDurationAsOf, heartbeatAt)
    } else {
      subtaskActiveDurationMs to subtaskActiveDurationAsOf
    }
  return copy(
    activeDurationMs = goal.first,
    activeDurationAsOf = goal.second,
    subtaskActiveDurationMs = subtask.first,
    subtaskActiveDurationAsOf = subtask.second,
  )
}

private fun advanceAccumulator(
  accumulatedMs: Long,
  asOf: String?,
  heartbeatAt: Instant,
): Pair<Long, String?> {
  val previous = asOf ?: return accumulatedMs to heartbeatAt.toString()
  val elapsedMs =
    Duration.between(
      parseExecutionLeaseInstant("active_duration_as_of", previous),
      heartbeatAt,
    ).toMillis()
  val counted = elapsedMs.coerceIn(0, GOAL_ACTIVE_HEARTBEAT_GAP_LIMIT_MS)
  return accumulatedMs + counted to heartbeatAt.toString()
}
