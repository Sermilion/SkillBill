package skillbill.workflow.taskruntime.validation

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SemanticLoopWarningThresholdDeclarationTest {
  private val def = FeatureTaskRuntimePhaseWorkflowDefinition
  private val transitions = def.transitions

  @Test
  fun `the bounded review fix edge does not declare a warning threshold`() {
    assertNull(
      transitions.backwardEdges.single { it.loopId == def.REVIEW_FIX_LOOP_ID }.warnAfterIterations,
      "'${def.REVIEW_FIX_LOOP_ID}' is bounded by a finite cap and must not attach a threshold warning.",
    )
  }

  @Test
  fun `unbounded audit repair warns after three rounds`() {
    val audit = transitions.backwardEdges.single { it.loopId == def.AUDIT_REPAIR_LOOP_ID }
    assertEquals(3, audit.warnAfterIterations)
    assertNull(audit.perEdgeCap)
  }

  @Test
  fun `the declared threshold is control-flow inert across every iteration`() {
    val cases =
      listOf(
        def.PHASE_AUDIT to FeatureTaskRuntimeVerdict.ADVANCE,
        def.PHASE_VERIFY_FINDINGS to FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
      )
    cases.forEach { (phaseId, verdict) ->
      val withThreshold = transitions
      val withoutThreshold =
        transitions.copy(
          backwardEdges = transitions.backwardEdges.map { it.copy(warnAfterIterations = null) },
        )
      (1..10).forEach { iteration ->
        assertEquals(
          nextTransition(withoutThreshold, phaseId, verdict, iteration),
          nextTransition(withThreshold, phaseId, verdict, iteration),
          "Iteration $iteration of '$phaseId' must transition identically with and without a threshold.",
        )
      }
    }
  }

  private fun nextTransition(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    iteration: Int,
  ) = FeatureTaskRuntimeTransitionFunction.nextTransition(
    declaration = declaration,
    currentPhaseId = phaseId,
    verdict = verdict,
    edgeIterationCount = iteration,
    context =
      FeatureTaskRuntimeTransitionContext(
        settledVerdictsByPhaseId = mapOf(def.PHASE_AUDIT to FeatureTaskRuntimeVerdict.SATISFIED),
      ),
  )
}
