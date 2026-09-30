package skillbill.workflow.taskruntime.validation

import skillbill.error.shellcontent.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeTransitionFunctionShippedTest {
  private val shipped = FeatureTaskRuntimePhaseWorkflowDefinition.transitions
  private val satisfiedAudit =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to FeatureTaskRuntimeVerdict.SATISFIED,
    )

  private fun transition(
    currentPhaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    edgeIterationCount: Int = 0,
    settledVerdicts: Map<String, FeatureTaskRuntimeVerdict> = satisfiedAudit,
  ): FeatureTaskRuntimeNextPhase =
    shippedTransition(
      shipped,
      currentPhaseId,
      verdict,
      edgeIterationCount,
      settledVerdicts,
    )

  @Test
  fun `a clean run advances implement to simplify to audit to review to verify_findings to validate`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    assertEquals(
      def.PHASE_SIMPLIFY,
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(def.PHASE_IMPLEMENT, FeatureTaskRuntimeVerdict.ADVANCE, settledVerdicts = emptyMap()),
      ).phaseId,
    )
    assertEquals(
      def.PHASE_AUDIT,
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(def.PHASE_SIMPLIFY, FeatureTaskRuntimeVerdict.ADVANCE, settledVerdicts = emptyMap()),
      ).phaseId,
    )
    assertEquals(
      def.PHASE_REVIEW,
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(def.PHASE_AUDIT, FeatureTaskRuntimeVerdict.SATISFIED),
      ).phaseId,
    )
    assertEquals(
      def.PHASE_VERIFY_FINDINGS,
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(def.PHASE_REVIEW, FeatureTaskRuntimeVerdict.APPROVED),
      ).phaseId,
    )
    val noFindingsVerified =
      satisfiedAudit +
        mapOf(
          def.PHASE_REVIEW to FeatureTaskRuntimeVerdict.APPROVED,
          def.PHASE_VERIFY_FINDINGS to FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED,
        )
    assertEquals(
      def.PHASE_VALIDATE,
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(
          def.PHASE_VERIFY_FINDINGS,
          FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED,
          settledVerdicts = noFindingsVerified,
        ),
      ).phaseId,
    )
  }

  @Test
  fun `entering review with no audit verdict loud-fails with the typed phase-order error`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val error =
      assertFailsWith<FeatureTaskRuntimePhaseOrderViolationError> {
        transition(def.PHASE_AUDIT, FeatureTaskRuntimeVerdict.SATISFIED, settledVerdicts = emptyMap())
      }
    assertEquals(def.PHASE_REVIEW, error.phaseId)
    assertEquals(def.PHASE_AUDIT, error.requiredPhaseId)
    assertEquals("satisfied", error.requiredVerdict)
    assertTrue(error.message.orEmpty().contains(def.PHASE_REVIEW))
    assertTrue(error.message.orEmpty().contains(def.PHASE_AUDIT))
  }

  @Test
  fun `entering review with a gaps_found audit verdict loud-fails with the typed phase-order error`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val gapsFound = mapOf(def.PHASE_AUDIT to FeatureTaskRuntimeVerdict.GAPS_FOUND)
    val error =
      assertFailsWith<FeatureTaskRuntimePhaseOrderViolationError> {
        transition(def.PHASE_AUDIT, FeatureTaskRuntimeVerdict.SATISFIED, settledVerdicts = gapsFound)
      }
    assertEquals(def.PHASE_REVIEW, error.phaseId)
    assertEquals("gaps_found", error.observedVerdict)
  }

  @Test
  fun `entering implement_fix with no_findings_verified verify verdict loud-fails with the typed phase-order error`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val settled =
      satisfiedAudit +
        mapOf(
          def.PHASE_REVIEW to FeatureTaskRuntimeVerdict.APPROVED,
          def.PHASE_VERIFY_FINDINGS to FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED,
        )
    val error =
      assertFailsWith<FeatureTaskRuntimePhaseOrderViolationError> {
        transition(
          def.PHASE_VERIFY_FINDINGS,
          FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
          settledVerdicts = settled,
        )
      }
    assertEquals(def.PHASE_IMPLEMENT_FIX, error.phaseId)
    assertEquals(def.PHASE_VERIFY_FINDINGS, error.requiredPhaseId)
    assertEquals("findings_verified", error.requiredVerdict)
    assertEquals("no_findings_verified", error.observedVerdict)
  }

  @Test
  fun `Minor and Nit only verified findings still route the review_fix round`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val reviewApproved = satisfiedAudit + mapOf(def.PHASE_REVIEW to FeatureTaskRuntimeVerdict.APPROVED)
    val minorOnlyVerified =
      reviewApproved +
        mapOf(
          def.PHASE_VERIFY_FINDINGS to FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
        )
    val fix =
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(
          def.PHASE_VERIFY_FINDINGS,
          FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
          edgeIterationCount = 0,
          settledVerdicts = minorOnlyVerified,
        ),
      )
    assertEquals(def.PHASE_IMPLEMENT_FIX, fix.phaseId)
    assertEquals(def.REVIEW_FIX_LOOP_ID, fix.loopId)
  }

  @Test
  fun `verify_findings remediation enters implement_fix once then advances to validate without re-review`() {
    assertVerifyFindingsRemediationRouting(shipped, satisfiedAudit)
  }

  @Test
  fun `write_history only regenerates receipts and commit_push never originates a backward edge`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val fromPhases = shipped.backwardEdges.map { it.fromPhaseId }.toSet()
    val historyEdges = shipped.backwardEdges.filter { it.fromPhaseId == def.PHASE_WRITE_HISTORY }
    assertEquals(setOf(def.PHASE_BUILD, def.PHASE_VALIDATE), historyEdges.map { it.destinationPhaseId }.toSet())
    assertTrue(historyEdges.all { it.triggeringVerdict == FeatureTaskRuntimeVerdict.RECORD_REJECTED })
    assertTrue(def.PHASE_COMMIT_PUSH !in fromPhases)
  }

  @Test
  fun `RECORD_REJECTED at audit advances forward when no regeneration edge targets implement`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val next =
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(
          def.PHASE_AUDIT,
          FeatureTaskRuntimeVerdict.RECORD_REJECTED,
          edgeIterationCount = 0,
        ),
      )
    assertEquals(def.PHASE_REVIEW, next.phaseId)
    assertEquals(null, next.loopId)
  }

  @Test
  fun `RECORD_REJECTED at plan advances forward when no regeneration edge targets preplan`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val next =
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        transition(
          def.PHASE_PLAN,
          FeatureTaskRuntimeVerdict.RECORD_REJECTED,
          edgeIterationCount = 0,
        ),
      )
    assertEquals(def.PHASE_IMPLEMENT, next.phaseId)
    assertEquals(null, next.loopId)
  }
}
