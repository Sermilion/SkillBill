package skillbill.engine.featuretask.slot

import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest

/**
 * Executes one phase step for any strategy: composes the step prompt, launches the agent, reads the step's
 * settlement, and returns the uniform step output. Every strategy owns its own instance.
 */
interface PhaseRunner {
  /** Runs [input] against the per-call [state] through the runner's default agent-launch session. */
  fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): PhaseStepOutput

  /**
   * Runs [input] against [state] with [session] producing the launch outcome in place of the default agent launch.
   * The runner still resolves the launch request, captures the file manifest, and reads the settlement.
   */
  fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
    session: PhaseStepSession,
  ): PhaseStepOutput = run(input, state)
}

/** Produces a step's launch outcome from the launch request the runner resolved for it. */
fun interface PhaseStepSession {
  fun execute(launch: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome
}
