package skillbill.engine.featuretask.slot.plan

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseFanOutUnits
import skillbill.engine.featuretask.slot.state.PhasePlanningStepBinding
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.workflow.taskruntime.model.persistence.task.runtime.run.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

class GoalPlanFanOutStrategy(
  private val fanOutPort: BoundedWorkFanOutPort,
  private val planFanOutCap: Int,
) : PhaseStrategy() {
  private val plan = AgentPlanStrategy()

  override val plansInFanOut: Boolean = true

  override val slot: PhaseSlot = PhaseSlot.PLAN
  override val strategyId: String = ID
  override val steps: List<String> = plan.steps
  override val entryStep: String = plan.entryStep

  override fun policyFor(stepId: String): PhaseStepPolicy = plan.policyFor(stepId)

  internal override fun acceptsAttemptStrategy(attemptStrategyId: String): Boolean =
    attemptStrategyId == plan.strategyId

  override fun directiveFor(stepId: String): String = plan.directiveFor(stepId)

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = plan.promptSections(stepId, inputs)

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> =
    plan.briefingInvariantFields(stepId)

  override fun resumeRules(stepId: String): PhaseResumeRules = plan.resumeRules(stepId)

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome {
    val planning = state as PhasePlanningStepBinding
    val fanOut = planning.fanOut(run.phaseId)
    planning.authorizeFanOutWave(run)
    return try {
      runFanOutStep(run, fanOut)
    } finally {
      planning.releaseFanOutWave(run)
    }
  }

  private fun runFanOutStep(
    run: PhaseRun,
    fanOut: PhaseRunFanOut,
  ): PhaseOutcome {
    val pending =
      when (val units = fanOut.pendingUnits()) {
        is PhaseFanOutUnits.Stopped -> return units.outcome
        is PhaseFanOutUnits.Pending -> units.unitIds
      }
    val waves = pending.chunked(planFanOutCap)
    for ((index, wave) in waves.withIndex()) {
      val nextWaveFirstId = waves.getOrNull(index + 1)?.first()
      val stopped =
        fanOut.pauseBefore(wave.first())
          ?: runWave(run, fanOut, wave)
          ?: nextWaveFirstId?.let(fanOut::pauseBefore)
      if (stopped != null) return stopped
    }
    return fanOut.completed()
  }

  private fun runWave(
    run: PhaseRun,
    fanOut: PhaseRunFanOut,
    wave: List<Int>,
  ): PhaseOutcome? {
    val units = wave.map { unitId -> { runUnit(run, fanOut, unitId) } }
    val results = fanOutPort.runBounded(planFanOutCap, units)
    return wave.indices.firstNotNullOfOrNull { index -> fanOut.settleUnit(wave[index], results[index]) }
  }

  private fun runUnit(
    run: PhaseRun,
    fanOut: PhaseRunFanOut,
    unitId: Int,
  ): PhaseOutcome {
    val sink = UnitAttributedOutputSink(fanOutPort, fanOut.outputSink, unitId)
    val state = fanOut.unitState(unitId, sink)
    return try {
      plan.runStep(run, state)
    } finally {
      state.finishStepExecution()
      sink.flushTrailingLines()
    }
  }

  private class UnitAttributedOutputSink(
    private val fanOutPort: BoundedWorkFanOutPort,
    private val delegate: AgentRunOutputSink,
    unitId: Int,
  ) : AgentRunOutputSink {
    private val attribution = "[subtask $unitId] "
    private val pending = mutableMapOf<AgentRunOutputStream, StringBuilder>()

    override fun write(
      stream: AgentRunOutputStream,
      text: String,
    ) = fanOutPort.runExclusively {
      val buffer = pending.getOrPut(stream) { StringBuilder() }.append(text)
      var newline = buffer.indexOf("\n")
      while (newline >= 0) {
        val line = buffer.substring(0, newline + 1)
        buffer.delete(0, newline + 1)
        delegate.write(stream, attribution + line)
        newline = buffer.indexOf("\n")
      }
    }

    fun flushTrailingLines() =
      fanOutPort.runExclusively {
        pending.forEach { (stream, buffer) ->
          if (buffer.isNotEmpty()) {
            delegate.write(stream, attribution + buffer + "\n")
            buffer.setLength(0)
          }
        }
      }
  }

  companion object {
    const val ID = "goal-plan-fan-out"
  }
}
