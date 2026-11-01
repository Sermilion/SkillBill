package skillbill.engine.goal

import skillbill.goalrunner.model.parseExecutionLeaseInstant
import skillbill.ports.goalrunner.GoalRunnerControlRepository

public fun GoalRunnerControlRepository.releaseExecutionLease(
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

public fun GoalRunnerControlRepository.releaseExecutionLeaseIfExpired(
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
