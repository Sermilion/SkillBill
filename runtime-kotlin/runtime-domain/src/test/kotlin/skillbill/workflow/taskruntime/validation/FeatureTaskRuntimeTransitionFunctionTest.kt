package skillbill.workflow.taskruntime.validation

import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.goalreview.blocksAdvance
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeTransitionFunctionTest {
  private val pipeline = listOf("a", "b", "c", "d")
  private val needsFix = FeatureTaskRuntimeVerdict("needs_fix")
  private val edge =
    FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "c",
      triggeringVerdict = needsFix,
      destinationPhaseId = "b",
      loopId = "c-to-b",
      perEdgeCap = 2,
    )
  private val cyclic =
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = pipeline,
      backwardEdges = listOf(edge),
    )
  private val forwardOnly = FeatureTaskRuntimeTransitionDeclaration(forwardPhaseIds = pipeline)

  @Test
  fun `default advance with no matching edge progresses to the next forward index`() {
    listOf("a" to "b", "b" to "c", "c" to "d").forEach { (current, expectedNext) ->
      val transition =
        FeatureTaskRuntimeTransitionFunction.nextTransition(
          declaration = cyclic,
          currentPhaseId = current,
          verdict = FeatureTaskRuntimeVerdict.ADVANCE,
          edgeIterationCount = 0,
        )
      val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(transition)
      assertEquals(expectedNext, next.phaseId)
      assertEquals(null, next.loopId)
      assertEquals(null, next.edgeIteration)
    }
  }

  @Test
  fun `pipeline end terminates with advance`() {
    val transition =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = cyclic,
        currentPhaseId = "d",
        verdict = FeatureTaskRuntimeVerdict.ADVANCE,
        edgeIterationCount = 0,
      )
    assertIs<FeatureTaskRuntimeNextPhase.TerminalAdvance>(transition)
  }

  @Test
  fun `matching backward edge below cap re-enters the destination tagged with loop and iteration`() {
    val cap = requireNotNull(edge.perEdgeCap)
    (0 until cap).forEach { alreadyFired ->
      val transition =
        FeatureTaskRuntimeTransitionFunction.nextTransition(
          declaration = cyclic,
          currentPhaseId = "c",
          verdict = needsFix,
          edgeIterationCount = alreadyFired,
        )
      val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(transition)
      assertEquals("b", next.phaseId)
      assertEquals("c-to-b", next.loopId)
      assertEquals(alreadyFired + 1, next.edgeIteration)
    }
  }

  @Test
  fun `matching backward edge at cap blocks loudly with loop id iteration and unresolved verdict`() {
    val cap = requireNotNull(edge.perEdgeCap)
    val transition =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = cyclic,
        currentPhaseId = "c",
        verdict = needsFix,
        edgeIterationCount = cap,
      )
    val block = assertIs<FeatureTaskRuntimeNextPhase.TerminalBlock>(transition)
    assertEquals("c-to-b", block.loopId)
    assertEquals(cap, block.edgeIteration)
    assertEquals(needsFix, block.unresolvedVerdict)
  }

  @Test
  fun `non-triggering verdict at the edge phase falls through to the forward edge`() {
    val transition =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = cyclic,
        currentPhaseId = "c",
        verdict = FeatureTaskRuntimeVerdict.ADVANCE,
        edgeIterationCount = 5,
      )
    assertEquals("d", assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
  }

  @Test
  fun `edge-free declaration is forward-only for every phase verdict and iteration`() {
    val verdicts = listOf(FeatureTaskRuntimeVerdict.ADVANCE, needsFix, FeatureTaskRuntimeVerdict("other"))
    pipeline.forEachIndexed { index, phaseId ->
      verdicts.forEach { verdict ->
        (0..3).forEach { iteration ->
          val transition =
            FeatureTaskRuntimeTransitionFunction.nextTransition(
              declaration = forwardOnly,
              currentPhaseId = phaseId,
              verdict = verdict,
              edgeIterationCount = iteration,
            )
          if (index < pipeline.lastIndex) {
            assertEquals(pipeline[index + 1], assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
          } else {
            assertIs<FeatureTaskRuntimeNextPhase.TerminalAdvance>(transition)
          }
        }
      }
    }
  }

  @Test
  fun `verdict round trips through its wire value`() {
    assertEquals("advance", FeatureTaskRuntimeVerdict.ADVANCE.wireValue)
    assertEquals(needsFix, FeatureTaskRuntimeVerdict.fromWire(needsFix.wireValue))
    assertEquals(FeatureTaskRuntimeVerdict.ADVANCE, FeatureTaskRuntimeVerdict.fromWire("advance"))
  }

  @Test
  fun `verdict fromWire loud-fails on a blank wire value`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> { FeatureTaskRuntimeVerdict.fromWire("  ") }
  }

  @Test
  fun `removed verdicts loud-fail through rejectRemovedVerdict`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeVerdict.rejectRemovedVerdict("escalated", "test")
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeVerdict.rejectRemovedVerdict("repair_planned", "test")
    }
  }

  private val loopPipeline = listOf("plan", "impl", "fix", "review", "audit")
  private val reviewFixEdge =
    FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "review",
      triggeringVerdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      destinationPhaseId = "fix",
      loopId = "review_fix",
      perEdgeCap = 3,
      capExhaustionBehavior = FeatureTaskRuntimeCapExhaustionBehavior.BLOCK,
    )
  private val loopDeclaration =
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = loopPipeline,
      backwardEdges = listOf(reviewFixEdge),
      loopOnlyPhaseIds = setOf("fix"),
    )

  @Test
  fun `forward edge skips a loop-only phase to the next non-loop-only phase`() {
    val transition =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = loopDeclaration,
        currentPhaseId = "impl",
        verdict = FeatureTaskRuntimeVerdict.ADVANCE,
        edgeIterationCount = 0,
      )
    assertEquals("review", assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
  }

  @Test
  fun `loop-only phase forward-advances to the next non-loop-only phase`() {
    val transition =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = loopDeclaration,
        currentPhaseId = "fix",
        verdict = FeatureTaskRuntimeVerdict.ADVANCE,
        edgeIterationCount = 0,
      )
    assertEquals("review", assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
  }

  @Test
  fun `loop-only phase is reachable only via a backward edge destination`() {
    loopPipeline.filterNot { it == "fix" }.forEach { phaseId ->
      val transition =
        FeatureTaskRuntimeTransitionFunction.nextTransition(
          declaration = loopDeclaration,
          currentPhaseId = phaseId,
          verdict = FeatureTaskRuntimeVerdict.ADVANCE,
          edgeIterationCount = 0,
        )
      val landed = (transition as? FeatureTaskRuntimeNextPhase.Next)?.phaseId
      assertTrue(landed != "fix", "forward advance from '$phaseId' must not reach the loop-only phase")
    }

    val backward =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = loopDeclaration,
        currentPhaseId = "review",
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        edgeIterationCount = 0,
      )
    assertEquals("fix", assertIs<FeatureTaskRuntimeNextPhase.Next>(backward).phaseId)
  }

  @Test
  fun `empty loopOnlyPhaseIds leaves the forward advance unchanged`() {
    val noLoopOnly = loopDeclaration.copy(loopOnlyPhaseIds = emptySet())
    val transition =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = noLoopOnly,
        currentPhaseId = "impl",
        verdict = FeatureTaskRuntimeVerdict.ADVANCE,
        edgeIterationCount = 0,
      )
    assertEquals("fix", assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
  }

  @Test
  fun `loopOnlyPhaseIds must be a subset of the forward pipeline`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeTransitionDeclaration(
        forwardPhaseIds = loopPipeline,
        loopOnlyPhaseIds = setOf("not_in_pipeline"),
      )
    }
  }

  @Test
  fun `review approved forwards past the loop-only fix phase to audit`() {
    val transition =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = loopDeclaration,
        currentPhaseId = "review",
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        edgeIterationCount = 0,
      )
    assertEquals("audit", assertIs<FeatureTaskRuntimeNextPhase.Next>(transition).phaseId)
  }

  @Test
  fun `review changes_requested under cap re-enters fix tagged with review_fix and incrementing iteration`() {
    val cap = requireNotNull(reviewFixEdge.perEdgeCap)
    (0 until cap).forEach { alreadyFired ->
      val transition =
        FeatureTaskRuntimeTransitionFunction.nextTransition(
          declaration = loopDeclaration,
          currentPhaseId = "review",
          verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
          edgeIterationCount = alreadyFired,
        )
      val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(transition)
      assertEquals("fix", next.phaseId)
      assertEquals("review_fix", next.loopId)
      assertEquals(alreadyFired + 1, next.edgeIteration)
    }
  }

  @Test
  fun `review changes_requested at cap blocks on a blocking exhaustion edge`() {
    val cap = requireNotNull(reviewFixEdge.perEdgeCap)
    val transition =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = loopDeclaration,
        currentPhaseId = "review",
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        edgeIterationCount = cap,
      )
    assertEquals("review_fix", assertIs<FeatureTaskRuntimeNextPhase.TerminalBlock>(transition).loopId)
  }

  @Test
  fun `review verdict constants carry their stable wire values`() {
    assertEquals("approved", FeatureTaskRuntimeVerdict.APPROVED.wireValue)
    assertEquals("changes_requested", FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue)
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, FeatureTaskRuntimeVerdict.fromWire("approved"))
    assertEquals(
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      FeatureTaskRuntimeVerdict.fromWire("changes_requested"),
    )
  }

  @Test
  fun `Blocker findings derive changes_requested and expose the unresolved findings`() {
    val verdict =
      FeatureTaskRuntimeReviewVerdict(
        listOf(
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.NIT, "tidy import"),
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.BLOCKER, "blocking issue"),
        ),
      )
    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, verdict.verdict)
    assertEquals(listOf("blocking issue"), verdict.unresolvedFindings.map { it.message })
  }

  @Test
  fun `findings with only Minor or Nit or no findings derive approved`() {
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, FeatureTaskRuntimeReviewVerdict(emptyList()).verdict)
    val minorOnly =
      FeatureTaskRuntimeReviewVerdict(
        listOf(
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.MINOR, "consider renaming"),
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.NIT, "trailing space"),
        ),
      )
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, minorOnly.verdict)
    assertTrue(minorOnly.unresolvedFindings.isEmpty())
    assertTrue(minorOnly.remediationFindings.isEmpty())
  }

  @Test
  fun `requiresRemediation and blocksAdvance are true for Blocker and Major only`() {
    FeatureTaskRuntimeReviewSeverity.entries.forEach { severity ->
      val expected =
        severity == FeatureTaskRuntimeReviewSeverity.BLOCKER ||
          severity == FeatureTaskRuntimeReviewSeverity.MAJOR
      assertEquals(expected, severity.requiresRemediation, "$severity.requiresRemediation")
      assertEquals(expected, severity.blocksAdvance, "$severity.blocksAdvance")
    }
  }

  @Test
  fun `mixed Blocker Major and Minor findings request changes and keep remediable order`() {
    val verdict =
      FeatureTaskRuntimeReviewVerdict(
        listOf(
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.MINOR, "minor-a"),
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.BLOCKER, "blocker-1"),
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.MINOR, "minor-b"),
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.MAJOR, "major-1"),
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.MINOR, "minor-c"),
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.MAJOR, "major-2"),
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.MINOR, "minor-d"),
        ),
      )
    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, verdict.verdict)
    assertEquals(
      listOf("blocker-1", "major-1", "major-2"),
      verdict.remediationFindings.map { it.message },
    )
    assertEquals(
      listOf("blocker-1", "major-1", "major-2"),
      verdict.unresolvedFindings.map { it.message },
    )
  }

  @Test
  fun `a Major finding requests changes and is advance-blocking`() {
    val majorOnly =
      FeatureTaskRuntimeReviewVerdict(
        listOf(
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.MAJOR, "follow-up risk"),
          FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.MINOR, "consider renaming"),
        ),
      )

    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, majorOnly.verdict)
    assertEquals(listOf("follow-up risk"), majorOnly.remediationFindings.map { it.message })
    assertEquals(listOf("follow-up risk"), majorOnly.unresolvedFindings.map { it.message })
  }

  @Test
  fun `review severity fromWire loud-fails on an unknown value`() {
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimeReviewSeverity.fromWire("catastrophic") }
  }

  private val auditGapEdge =
    FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "audit",
      triggeringVerdict = FeatureTaskRuntimeVerdict.GAPS_FOUND,
      destinationPhaseId = "impl",
      loopId = "audit_gap",
      perEdgeCap = null,
    )
  private val twoEdgeDeclaration =
    FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = loopPipeline,
      backwardEdges = listOf(reviewFixEdge, auditGapEdge),
      loopOnlyPhaseIds = setOf("fix"),
    )

  @Test
  fun `each backward edge fires from its own source and iteration without touching the other`() {
    val reviewFix =
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        FeatureTaskRuntimeTransitionFunction.nextTransition(
          declaration = twoEdgeDeclaration,
          currentPhaseId = "review",
          verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
          edgeIterationCount = 1,
        ),
      )
    assertEquals("fix", reviewFix.phaseId)
    assertEquals("review_fix", reviewFix.loopId)
    assertEquals(2, reviewFix.edgeIteration)

    val auditGap =
      assertIs<FeatureTaskRuntimeNextPhase.Next>(
        FeatureTaskRuntimeTransitionFunction.nextTransition(
          declaration = twoEdgeDeclaration,
          currentPhaseId = "audit",
          verdict = FeatureTaskRuntimeVerdict.GAPS_FOUND,
          edgeIterationCount = 0,
        ),
      )
    assertEquals("impl", auditGap.phaseId)
    assertEquals("audit_gap", auditGap.loopId)
    assertEquals(1, auditGap.edgeIteration)
  }

  @Test
  fun `audit satisfied forwards while gaps_found remains eligible after many iterations`() {
    val satisfied =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = twoEdgeDeclaration,
        currentPhaseId = "audit",
        verdict = FeatureTaskRuntimeVerdict.SATISFIED,
        edgeIterationCount = 1,
      )
    assertIs<FeatureTaskRuntimeNextPhase.TerminalAdvance>(satisfied)

    val reentry =
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = twoEdgeDeclaration,
        currentPhaseId = "audit",
        verdict = FeatureTaskRuntimeVerdict.GAPS_FOUND,
        edgeIterationCount = 100,
      )
    val next = assertIs<FeatureTaskRuntimeNextPhase.Next>(reentry)
    assertEquals("impl", next.phaseId)
    assertEquals("audit_gap", next.loopId)
    assertEquals(101, next.edgeIteration)
  }
}
