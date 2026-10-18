package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.runnerHarness
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionResult
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeTransitionFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FeatureTaskRuntimePhaseBoundaryTest {
  @Test
  fun `no workflow phase can route back into an earlier phase`() {
    val phases = listOf("preplan", "plan", "implement", "audit", "review", "validate", "write_history", "commit_push")
    phases.zipWithNext().forEach { (previous, current) ->
      val harness = runnerHarness()
      val plan = harness.strategies.executionPlan(strategySelectionFacts(harness.request()))
      val base = plan.traversal
      val verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
      val edge = FeatureTaskRuntimeBackwardEdge(current, verdict, previous, "forbidden-return", 1)
      val runner =
        harness.withEntry(
          object : TestFeatureTaskRuntimeRunLoopEntry() {
            override fun run(
              context: FeatureTaskRuntimeRunLoopContext,
              beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
            ): FeatureTaskRuntimeRunReport {
              val declaration = base.copy(backwardEdges = base.backwardEdges + edge, entryGates = emptyList())
              val state =
                object : PhaseRunState by context.runState {
                  override val transitions = plan.withTraversal(declaration).phaseTraversal()
                }
              val guarded = this.context(context.request, state)
              val expected =
                assertIs<FeatureTaskRuntimeTransitionResult.Resolved>(
                  FeatureTaskRuntimeTransitionFunction.nextTransition(
                    base.copy(entryGates = emptyList()),
                    current,
                    verdict,
                    0,
                  ),
                ).next
              val destination = (expected as? FeatureTaskRuntimeNextPhase.Next)?.phaseId
              assertEquals(destination, FeatureTaskRuntimeRunLoopDrive.nextPhaseAfter(guarded, current, verdict))
              assertNull(guarded.session.blocked)
              return super.run(context, beforeDrive)
            }
          },
        )
      assertIs<FeatureTaskRuntimeRunReport.Completed>(runner.run(harness.request()))
    }
  }
}
