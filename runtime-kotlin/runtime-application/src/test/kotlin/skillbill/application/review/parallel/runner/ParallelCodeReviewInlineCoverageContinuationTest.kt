package skillbill.application.review.parallel.runner

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.parallel.verification.ParallelCodeReviewRunnerFailureAdmission
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.execution.ResolvedReviewExecutionMode
import skillbill.review.context.model.hunk.ReviewBudgetOutcome
import skillbill.review.context.model.hunk.ReviewContextBudgetPolicy
import skillbill.review.context.model.packet.ReviewExpansionRecord
import skillbill.review.context.model.packet.ReviewLaneCompletionState
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.parallel.ParallelReviewFindingParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelCodeReviewInlineCoverageContinuationTest {
  @Test
  fun `a coverage continuation closes the replaced endpoint before binding the next and closes the last on exit`() {
    var delivered = 0
    val first = RecordingEndpoint("first")
    val endpoints = mutableListOf(first)
    var firstClosedWhenNextBound = false
    var firstClosedAtSecondSlice = false
    val binder =
      object : GovernedReviewEvidenceEndpointBinder {
        override fun bind(
          lane: String,
          broker: ReviewEvidenceBroker,
          onEvidenceRead: (() -> Unit)?,
        ): GovernedReviewEvidenceEndpointHandle {
          firstClosedWhenNextBound = first.closed
          return RecordingEndpoint(lane).also(endpoints::add)
        }
      }
    val launcher =
      GoalRunnerSubtaskLauncher { request ->
        if (request.skillRunRequest.reviewEvidenceEndpoint === first) {
          delivered += 1
          agentRunLaunchFacts(SupportedAgent.CODEX)
        } else {
          firstClosedAtSecondSlice = first.closed
          throw SecondSliceReached()
        }
      }
    val continuation =
      ParallelCodeReviewInlineCoverageContinuation(
        parentReviewLauncher = launcher,
        governedEvidenceEndpointBinder = binder,
        failureAdmission = ParallelCodeReviewRunnerFailureAdmission(ParallelReviewFindingParser::parse),
        sliceOutcome = { _, _ -> ParallelReviewLaneOutcome(success = true, rawOutput = "") },
        evidenceReadCallback = { _, _ -> null },
      )

    assertFailsWith<SecondSliceReached> {
      continuation.run(launchedArgs(first, UnitCountingBroker(required = 2, delivered = { delivered })))
    }

    assertEquals(2, endpoints.size)
    assertTrue(firstClosedWhenNextBound, "the replaced endpoint must be closed before the next bind")
    assertTrue(firstClosedAtSecondSlice, "the second slice must not run beside the first endpoint")
    assertTrue(endpoints.last().closed, "the last endpoint must be closed when the run ends")
  }

  private fun launchedArgs(
    endpoint: GovernedReviewEvidenceEndpointHandle,
    broker: ReviewEvidenceBroker,
  ) = LaunchedBoundParentArgs(
    launch =
      ParallelCodeReviewInlineParentLaunch(
        agentId = "codex",
        selected = emptyList(),
        prompt = "review",
        bundleState =
          ReviewLaneCompletionState(
            disposition = ReviewLaneReviewDisposition.COMPLETE,
            bundleCompositionDigest = "a".repeat(SHA256_HEX_LENGTH),
            segments = emptyList(),
          ),
      ),
    bound = ParallelCodeReviewGovernedEvidenceBind.Bound(broker, endpoint, AtomicLong(0)),
    budget = ReviewContextBudgetPolicy(),
    request =
      ParallelCodeReviewRequest(
        agent1Id = "codex",
        scope = ParallelReviewScope.BRANCH,
        repoRoot = Files.createTempDirectory("review-continuation"),
        timeout = null,
      ),
    modelOverride = null,
    resolvedMode = ResolvedReviewExecutionMode.INLINE,
  )

  private class SecondSliceReached : RuntimeException()

  private class RecordingEndpoint(lane: String) : GovernedReviewEvidenceEndpointHandle {
    var closed = false
      private set

    override val descriptor =
      GovernedReviewEvidenceEndpointDescriptor(
        lane = lane,
        socketPath = Path.of("/tmp/$lane.sock"),
        mcpConfigPath = Path.of("/tmp/$lane.json"),
        token = "continuation-token",
      )

    override fun close() {
      closed = true
    }
  }

  private class UnitCountingBroker(
    private val required: Int,
    private val delivered: () -> Int,
  ) : ReviewEvidenceBroker {
    override fun accounting(): ReviewLaneAccounting =
      ReviewLaneAccounting(
        lane = "lane-1",
        requiredEvidenceUnits = required,
        deliveredEvidenceUnits = delivered(),
      )

    override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? = null

    override fun terminalOutcome(): ReviewBudgetOutcome? = null

    override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
      error("unused")

    override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult = error("unused")

    override fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult = error("unused")

    override fun recordModelTurn(): ReviewBudgetOutcome? = error("unused")

    override fun validateLaneResult(result: String): ReviewBudgetOutcome? = error("unused")
  }

  private companion object {
    const val SHA256_HEX_LENGTH = 64
  }
}
