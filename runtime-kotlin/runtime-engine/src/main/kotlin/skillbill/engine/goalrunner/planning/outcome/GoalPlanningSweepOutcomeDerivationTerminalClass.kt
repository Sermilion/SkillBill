package skillbill.engine.goalrunner.planning.outcome

import skillbill.engine.agentoutput.stderrExcerpt
import skillbill.engine.goalrunner.planning.model.GoalPlanningEmptyTurnEvidence
import skillbill.error.core.failureCodeLabel
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import kotlin.time.Duration

fun exhaustedCause(
  facts: AgentRunLaunchFacts,
  planningBudget: Duration?,
): String =
  when (val termination = facts.termination) {
    AgentRunTermination.SpawnFailed ->
      stderrExcerpt(facts.stderr, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS)
        ?.let { excerpt -> "the planning agent failed to spawn — $excerpt" }
        ?: "the planning agent failed to spawn"
    AgentRunTermination.TimedOut ->
      "the planning agent exhausted its $planningBudget planning budget; " +
        "raise or disable it with --planning-budget-minutes"
    AgentRunTermination.Interrupted -> "the planning agent was interrupted"
    is AgentRunTermination.Exited ->
      if (termination.code == 0) {
        "the planning agent produced no usable output"
      } else {
        "the planning agent exited with status ${termination.code}"
      }
  }

fun unexpectedPlanningFailureReason(
  phaseId: String,
  error: Throwable,
): String =
  "Goal planning '$phaseId' failed before its output could be checkpointed: " +
    "${error.failureCodeLabel() ?: error::class.simpleName ?: "Throwable"}: ${error.message.orEmpty()}"

fun emptyTurnReason(
  phaseId: String,
  evidence: GoalPlanningEmptyTurnEvidence,
): String = "Goal planning '$phaseId' agent turn exited cleanly and returned no output. ${evidence.summary()}"

fun recoverySubtaskId(error: Throwable): Int =
  (error as? IncompatibleGoalPlanningPreparationRecoveryError)?.subtaskId ?: 0
