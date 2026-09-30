package skillbill.engine.featuretask.slot.state

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.ports.agentrun.model.AgentRunOutputSink

/**
 * The units one fan-out step runs: the units still pending, the pause checks between them, the state each unit runs
 * over, and how the step settles. The state that keeps the units decides what a unit is and what stops the step.
 */
internal interface PhaseRunFanOut {
  /** The sink unit launches stream to before the fan-out attributes each line to its unit. */
  val outputSink: AgentRunOutputSink

  /** The units the step still has to run, or the outcome that stops it before any unit runs. */
  fun pendingUnits(): PhaseFanOutUnits

  /** The outcome that pauses the step before unit [unitId] runs, or null when the step continues. */
  fun pauseBefore(unitId: Int): PhaseOutcome?

  /**
   * The state unit [unitId] reads and writes under the coordinator-authorized fan-out wave, streaming launches to
   * [outputSink].
   */
  fun unitState(
    unitId: Int,
    outputSink: AgentRunOutputSink,
  ): PhaseAcceptedStepExecution

  /** The outcome that stops the step after unit [unitId] settled with [result], or null when the unit completed. */
  fun settleUnit(
    unitId: Int,
    result: Result<PhaseOutcome>,
  ): PhaseOutcome?

  /** The outcome of the step once every unit completed. */
  fun completed(): PhaseOutcome
}

internal sealed interface PhaseFanOutUnits {
  data class Pending(val unitIds: List<Int>) : PhaseFanOutUnits

  data class Stopped(val outcome: PhaseOutcome) : PhaseFanOutUnits
}
