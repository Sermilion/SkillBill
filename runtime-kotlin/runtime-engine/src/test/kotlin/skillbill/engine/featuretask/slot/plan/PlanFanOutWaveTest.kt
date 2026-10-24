package skillbill.engine.featuretask.slot.plan

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.planning.PlanDecompositionStop
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.concurrency.SequentialBoundedWorkFanOutPort
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlanFanOutWaveTest {
  @Test
  fun `a finished planner is checkpointed before a peer is interrupted`() {
    val settled = mutableListOf<Int>()
    val fanOut = recordingFanOut(settled)

    assertFailsWith<CancellationException> {
      runPlanFanOutWave(SequentialBoundedWorkFanOutPort, 2, fanOut, listOf(1, 2)) { id ->
        if (id == 2) {
          assertEquals(listOf(1), settled)
          throw CancellationException("Shutdown interrupted the remaining planner")
        }
        completed()
      }
    }

    assertEquals(listOf(1), settled)
  }

  @Test
  fun `an earlier failed planner does not discard successful peer checkpoints`() {
    val settled = mutableListOf<Int>()
    val fanOut = recordingFanOut(settled)

    val stopped =
      runPlanFanOutWave(SequentialBoundedWorkFanOutPort, 2, fanOut, listOf(1, 2)) { id ->
        if (id == 1) error("Planner could not launch")
        completed()
      }

    assertEquals(PhaseOutcome.blocked("Planner could not launch"), stopped)
    assertEquals(listOf(2), settled)
    assertNull(runPlanFanOutWave(SequentialBoundedWorkFanOutPort, 2, fanOut, emptyList()) { completed() })
  }

  private fun completed(): PhaseOutcome {
    val output = PlanDecompositionStop.settledOutput("plan_subtasks", "Planned", "Retained plan")
    return PhaseOutcome.completed(FeatureTaskRuntimePhaseOutput("plan_subtasks", 1, output.canonicalJson, output, null))
  }

  private fun recordingFanOut(settled: MutableList<Int>): PhaseRunFanOut =
    object : PhaseRunFanOut {
      override val outputSink = AgentRunOutputSink.NONE

      override fun pendingUnits() = error("The wave already has its units")

      override fun pauseBefore(unitId: Int): PhaseOutcome? = null

      override fun <B : PhaseAcceptedStepExecution> unitState(
        unitId: Int,
        outputSink: AgentRunOutputSink,
        descriptor: PhaseStepDescriptor<B>,
      ): B = error("The test supplies planner execution")

      override fun settleUnit(
        unitId: Int,
        result: Result<PhaseOutcome>,
      ): PhaseOutcome? {
        val outcome = result.getOrElse { return PhaseOutcome.blocked(it.message.orEmpty()) }
        if (outcome !is PhaseOutcome.Completed) return outcome
        settled += unitId
        return null
      }

      override fun completed() = error("The wave does not settle its parent step")
    }
}
