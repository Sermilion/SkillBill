package skillbill.workflow.taskruntime.validation

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReviewFixCapReconciliationTest {
  private val transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions

  private val reviewFixEdge =
    transitions.backwardEdges.single { edge ->
      edge.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    }

  private fun transitionAt(
    iteration: Int,
    verdict: FeatureTaskRuntimeVerdict,
  ) = FeatureTaskRuntimeTransitionFunction.nextTransition(
    declaration = transitions,
    currentPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
    verdict = verdict,
    edgeIterationCount = iteration,
    context =
      FeatureTaskRuntimeTransitionContext(
        settledVerdictsByPhaseId =
          buildMap {
            put(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT, FeatureTaskRuntimeVerdict.SATISFIED)
            put(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW, FeatureTaskRuntimeVerdict.APPROVED)
            if (verdict == FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED) {
              put(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
                FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
              )
            }
          },
      ),
  )

  @Test
  fun `the review fix edge declares cap one with advance exhaustion`() {
    assertEquals(1, reviewFixEdge.perEdgeCap)
    assertEquals(
      FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE,
      reviewFixEdge.capExhaustionBehavior,
    )
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS, reviewFixEdge.fromPhaseId)
  }

  @Test
  fun `the first findings_verified re-enters implement_fix`() {
    val transition = transitionAt(0, FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED)
    val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(transition)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX, next.phaseId)
    assertEquals(1, next.edgeIteration)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID, next.loopId)
  }

  @Test
  fun `a second findings_verified advances to validate without re-review`() {
    val next =
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transitionAt(1, FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED),
      )
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE, next.phaseId)
    assertEquals(null, next.loopId)
  }

  @Test
  fun `no_findings_verified advances to validate`() {
    val next =
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transitionAt(0, FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED),
      )
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE, next.phaseId)
  }

  @Test
  fun `the child definition carries paused as a non-terminal status`() {
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    assertTrue("paused" in definition.workflowStatuses)
    assertTrue(
      "paused" !in definition.terminalStatuses,
      "Pause is SKILL-141's resumable status, not a terminal one.",
    )
  }
}
