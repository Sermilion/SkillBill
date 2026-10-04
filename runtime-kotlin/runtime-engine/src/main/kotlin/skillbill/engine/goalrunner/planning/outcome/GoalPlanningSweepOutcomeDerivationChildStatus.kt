package skillbill.engine.goalrunner.planning.outcome

import skillbill.engine.goalrunner.execution.core.EmptyOrStoppedArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningEmptyTurnEvidence
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.agentrun.model.exitCode
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path
import kotlin.time.Duration

fun persistenceReason(
  subtask: DecompositionSubtask,
  error: Throwable,
): String = "Goal planning subtask '${subtask.id}' plan could not be checkpointed: ${error.message.orEmpty()}"

fun resolvedGovernedPath(
  canonicalRepository: Path,
  governingPath: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path {
  val lexical = lexicalPath(canonicalRepository, governingPath)
  return repositoryEnclosingRootPort.optionalRealPath(lexical) ?: lexical
}

fun resolvedSubSpecPath(
  canonicalRepository: Path,
  specPath: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path? {
  if (specPath.isBlank()) return null
  val lexical = lexicalPath(canonicalRepository, specPath)
  val resolved = repositoryEnclosingRootPort.optionalRealPath(lexical) ?: lexical
  return resolved.takeIf { it.startsWith(canonicalRepository) }
}

fun lexicalPath(
  canonicalRepository: Path,
  governingPath: String,
): Path {
  val path = Path.of(governingPath)
  return (if (path.isAbsolute) path else canonicalRepository.resolve(path)).toAbsolutePath().normalize()
}

internal fun emptyOrStopped(args: EmptyOrStoppedArgs): GoalPlanningPhaseProduction {
  val outcome = args.outcome
  val shared = args.shared
  val evidence =
    emptyTurnEvidence(outcome, args.durationMs)
      ?: return GoalPlanningPhaseProduction.Stopped(
        stopped(
          shared,
          args.currentSubtaskId,
          exhaustedReason(outcome, args.request.planningBudget),
          args.phaseId,
        ),
      )
  return GoalPlanningPhaseProduction.EmptyProviderTurn(
    emptyTurnReason(args.phaseId, evidence),
    evidence,
  )
}

fun emptyTurnEvidence(
  outcome: AgentRunLaunchOutcome,
  durationMs: Long,
): GoalPlanningEmptyTurnEvidence? {
  if (outcome !is AgentRunLaunchFacts) return null
  if (outcome.termination != AgentRunTermination.Exited(0)) return null
  return GoalPlanningEmptyTurnEvidence(
    agentId = outcome.agent.id,
    durationMs = durationMs,
    exitStatus = outcome.termination.exitCode,
    assistantEventCount = outcome.assistantEventCount,
    rawOutputPreview = outcome.rawOutputPreview,
  )
}

fun launchedAgentId(outcome: AgentRunLaunchOutcome): String =
  when (outcome) {
    is AgentRunLaunchFacts -> outcome.agent.id
    is UnsupportedAgentRunLaunch, is AgentRunLaunchDenied -> "unknown"
  }

fun stdoutFor(outcome: AgentRunLaunchOutcome): String? =
  when (outcome) {
    is AgentRunLaunchFacts ->
      outcome.stdout.takeIf { stdout ->
        outcome.termination == AgentRunTermination.Exited(0) && stdout.isNotBlank()
      }
    is UnsupportedAgentRunLaunch, is AgentRunLaunchDenied -> null
  }

fun exhaustedReason(
  outcome: AgentRunLaunchOutcome,
  planningBudget: Duration?,
): String =
  when (outcome) {
    is UnsupportedAgentRunLaunch -> "Goal planning could not launch a planning agent: ${outcome.reason}"
    is AgentRunLaunchDenied -> error("A denied planning launch is settled as a pause and has no exhausted reason.")
    is AgentRunLaunchFacts ->
      "Goal planning produced no usable agent output: ${exhaustedCause(outcome, planningBudget)}."
  }
