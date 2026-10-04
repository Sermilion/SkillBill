package skillbill.workflow.taskruntime.validation

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionResult
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal fun shippedTransition(
  declaration: FeatureTaskRuntimeTransitionDeclaration,
  currentPhaseId: String,
  verdict: FeatureTaskRuntimeVerdict,
  edgeIterationCount: Int = 0,
  settledVerdicts: Map<String, FeatureTaskRuntimeVerdict>,
): FeatureTaskRuntimeNextPhase =
  assertIs<FeatureTaskRuntimeTransitionResult.Resolved>(
    FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = declaration,
      currentPhaseId = currentPhaseId,
      verdict = verdict,
      edgeIterationCount = edgeIterationCount,
      context = FeatureTaskRuntimeTransitionContext(settledVerdictsByPhaseId = settledVerdicts),
    ),
  ).next

internal fun resolvedTransition(
  declaration: FeatureTaskRuntimeTransitionDeclaration,
  currentPhaseId: String,
  verdict: FeatureTaskRuntimeVerdict,
  edgeIterationCount: Int = 0,
  context: FeatureTaskRuntimeTransitionContext = FeatureTaskRuntimeTransitionContext(),
): FeatureTaskRuntimeNextPhase =
  assertIs<FeatureTaskRuntimeTransitionResult.Resolved>(
    FeatureTaskRuntimeTransitionFunction.nextTransition(
      declaration = declaration,
      currentPhaseId = currentPhaseId,
      verdict = verdict,
      edgeIterationCount = edgeIterationCount,
      context = context,
    ),
  ).next

private fun assertReviewFixLoopDeclaration(shipped: FeatureTaskRuntimeTransitionDeclaration) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val reviewFix = shipped.backwardEdges.single { it.loopId == def.REVIEW_FIX_LOOP_ID }
  assertEquals(def.PHASE_VERIFY_FINDINGS, reviewFix.fromPhaseId)
  assertEquals(1, reviewFix.perEdgeCap)
  assertEquals(FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE, reviewFix.capExhaustionBehavior)
}

private fun assertFindingsVerifiedRoutesToImplementFix(
  shipped: FeatureTaskRuntimeTransitionDeclaration,
  findingsVerifiedSettled: Map<String, FeatureTaskRuntimeVerdict>,
) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val fix =
    assertIs<FeatureTaskRuntimeNextPhase.Next>(
      shippedTransition(
        shipped,
        def.PHASE_VERIFY_FINDINGS,
        FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
        edgeIterationCount = 0,
        settledVerdicts = findingsVerifiedSettled,
      ),
    )
  assertEquals(def.PHASE_IMPLEMENT_FIX, fix.phaseId)
  assertEquals(def.REVIEW_FIX_LOOP_ID, fix.loopId)
  assertEquals(1, fix.edgeIteration)
}

private fun assertFindingsVerifiedCapExhaustionAdvancesToValidate(
  shipped: FeatureTaskRuntimeTransitionDeclaration,
  findingsVerifiedSettled: Map<String, FeatureTaskRuntimeVerdict>,
) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val capExhausted =
    assertIs<FeatureTaskRuntimeNextPhase.Next>(
      shippedTransition(
        shipped,
        def.PHASE_VERIFY_FINDINGS,
        FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
        edgeIterationCount = 1,
        settledVerdicts = findingsVerifiedSettled,
      ),
    )
  assertEquals(def.PHASE_VALIDATE, capExhausted.phaseId)
  assertEquals(null, capExhausted.loopId)
}

private fun assertNoFindingsVerifiedAdvancesToValidate(
  shipped: FeatureTaskRuntimeTransitionDeclaration,
  noFindingsSettled: Map<String, FeatureTaskRuntimeVerdict>,
) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val noFindings =
    assertIs<FeatureTaskRuntimeNextPhase.Next>(
      shippedTransition(
        shipped,
        def.PHASE_VERIFY_FINDINGS,
        FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED,
        settledVerdicts = noFindingsSettled,
      ),
    )
  assertEquals(def.PHASE_VALIDATE, noFindings.phaseId)
  assertEquals(null, noFindings.loopId)
}

internal fun assertVerifyFindingsRemediationRouting(
  shipped: FeatureTaskRuntimeTransitionDeclaration,
  satisfiedAudit: Map<String, FeatureTaskRuntimeVerdict>,
) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  assertReviewFixLoopDeclaration(shipped)
  val reviewApproved = satisfiedAudit + mapOf(def.PHASE_REVIEW to FeatureTaskRuntimeVerdict.APPROVED)
  assertEquals(
    def.PHASE_VERIFY_FINDINGS,
    assertIs<FeatureTaskRuntimeNextPhase.Next>(
      shippedTransition(
        shipped,
        def.PHASE_REVIEW,
        FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        settledVerdicts = reviewApproved,
      ),
    ).phaseId,
  )
  val findingsVerifiedSettled =
    reviewApproved +
      mapOf(
        def.PHASE_VERIFY_FINDINGS to FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
      )
  assertFindingsVerifiedRoutesToImplementFix(shipped, findingsVerifiedSettled)
  assertFindingsVerifiedCapExhaustionAdvancesToValidate(shipped, findingsVerifiedSettled)
  val noFindingsSettled =
    reviewApproved +
      mapOf(
        def.PHASE_VERIFY_FINDINGS to FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED,
      )
  assertNoFindingsVerifiedAdvancesToValidate(shipped, noFindingsSettled)
  assertEquals(
    def.PHASE_VALIDATE,
    assertIs<FeatureTaskRuntimeNextPhase.Next>(
      shippedTransition(
        shipped,
        def.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimeVerdict.ADVANCE,
        settledVerdicts = findingsVerifiedSettled,
      ),
    ).phaseId,
  )
}
