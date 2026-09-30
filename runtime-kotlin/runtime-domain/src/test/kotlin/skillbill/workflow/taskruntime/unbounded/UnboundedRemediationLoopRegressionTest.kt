package skillbill.workflow.taskruntime.unbounded

import skillbill.error.shellcontent.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeTransitionFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UnboundedRemediationLoopRegressionTest {
  private val def = FeatureTaskRuntimePhaseWorkflowDefinition
  private val transitions = def.transitions

  private val auditSatisfied = mapOf(def.PHASE_AUDIT to FeatureTaskRuntimeVerdict.SATISFIED)

  private fun transition(
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    iteration: Int,
    settled: Map<String, FeatureTaskRuntimeVerdict> = emptyMap(),
  ) = FeatureTaskRuntimeTransitionFunction.nextTransition(
    declaration = transitions,
    currentPhaseId = phaseId,
    verdict = verdict,
    edgeIterationCount = iteration,
    context = FeatureTaskRuntimeTransitionContext(settledVerdictsByPhaseId = settled),
  )

  @Test
  fun `gaps_found cannot advance to review or reenter implement at any iteration`() {
    listOf(0, 3, 11).forEach { consumed ->
      assertFailsWith<FeatureTaskRuntimePhaseOrderViolationError> {
        transition(
          def.PHASE_AUDIT,
          FeatureTaskRuntimeVerdict.GAPS_FOUND,
          consumed,
          mapOf(def.PHASE_AUDIT to FeatureTaskRuntimeVerdict.GAPS_FOUND),
        )
      }
    }
    assertTrue(transitions.backwardEdges.none { it.loopId == def.AUDIT_GAP_LOOP_ID })
  }

  @Test
  fun `a satisfied audit advances to review at any gap iteration count`() {
    listOf(0, 4, 17).forEach { consumed ->
      val next =
        assertIs<FeatureTaskRuntimeNextPhase.Next>(
          transition(def.PHASE_AUDIT, FeatureTaskRuntimeVerdict.SATISFIED, consumed, auditSatisfied),
        )
      assertEquals(def.PHASE_REVIEW, next.phaseId)
    }
  }

  @Test
  fun `audit repair remains available until audit progress guard blocks`() {
    val edge = transitions.backwardEdges.single { it.loopId == def.AUDIT_REPAIR_LOOP_ID }
    assertEquals(null, edge.perEdgeCap)
    val next =
      transition(
        def.PHASE_AUDIT,
        FeatureTaskRuntimeVerdict.ADVANCE,
        iteration = 1,
      )
    assertIs<FeatureTaskRuntimeNextPhase.Next>(next)
    assertEquals(def.PHASE_AUDIT_IMPLEMENT_FIX, next.phaseId)
  }

  @Test
  fun `no backward edge is a record-regeneration loop after implement prose migration`() {
    assertEquals(emptySet(), def.REGENERATION_LOOP_IDS)
    assertTrue(transitions.backwardEdges.none { def.isRegenerationLoopId(it.loopId) })
  }
}
