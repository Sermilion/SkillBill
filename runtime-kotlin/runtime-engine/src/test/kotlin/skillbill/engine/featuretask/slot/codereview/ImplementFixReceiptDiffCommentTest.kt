package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.slot.DiffCommentTestStore
import skillbill.engine.featuretask.slot.InterfaceStubs
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentThread
import skillbill.review.diffcomment.model.DiffCommentThreadState
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImplementFixReceiptDiffCommentTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-implement-fix-receipt-repo")
  private val store = DiffCommentTestStore()
  private val diagnostics = DiffCommentTestStore.RecordingDiagnostics()
  private val stubs = InterfaceStubs()

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    store.close()
  }

  @Test
  fun `each receipt outcome replies to its own review thread and only a fix resolves it`() {
    val prefix = "review:$WORKFLOW_ID:pass-$GOAL_PASS_NUMBER"
    val ids = FINDING_IDS.take(3)
    store.service.recordReviewFindings(repoRoot, prefix, "claude", ids.map(::reviewFinding))
    val binding = goalBinding()

    val withComments = settle(store.service, binding, GOAL_REPAIR_PROSE)
    val withoutComments = settle(null, binding, GOAL_REPAIR_PROSE)
    val first = threads(prefix, ids)
    settle(store.service, binding, GOAL_REPAIR_PROSE)
    val replay = threads(prefix, ids)

    assertEquals(withoutComments, withComments)
    assertEquals(PhaseStepOutputCheck.Accept, withComments)
    assertEquals(
      listOf(DiffCommentThreadState.RESOLVED, DiffCommentThreadState.OPEN, DiffCommentThreadState.OPEN),
      first.map { it.state },
    )
    assertEquals(
      listOf(DiffCommentOutcome.FIXED, DiffCommentOutcome.SKIPPED, DiffCommentOutcome.FAILED),
      first.map { it.comments.last().outcome },
    )
    assertReplyPrefixes(first)
    assertEquals(listOf(2, 2, 2), first.map { it.comments.size })
    assertEquals(first.map { it.comments }, replay.map { it.comments })
    assertEquals(first.map { it.state }, replay.map { it.state })
    assertEquals(emptyList(), diagnostics.warnings)
    assertEquals(emptyList(), stubs.unexpectedCalls)
  }

  @Test
  fun `a non-goal repair replies on the first pass threads and skips the finding verification refuted`() {
    val prefix = "review:$WORKFLOW_ID:pass-1"
    store.service.recordReviewFindings(repoRoot, prefix, "claude", FINDING_IDS.map(::reviewFinding))
    val binding = nonGoalBinding()

    val withComments = settle(store.service, binding, NON_GOAL_REPAIR_PROSE)
    val withoutComments = settle(null, binding, NON_GOAL_REPAIR_PROSE)
    val first = threads(prefix, FINDING_IDS)
    settle(store.service, binding, NON_GOAL_REPAIR_PROSE)
    val replay = threads(prefix, FINDING_IDS)

    assertEquals(PhaseStepOutputCheck.Accept, withComments)
    assertEquals(withoutComments, withComments)
    assertEquals(
      listOf(
        DiffCommentThreadState.RESOLVED,
        DiffCommentThreadState.OPEN,
        DiffCommentThreadState.OPEN,
        DiffCommentThreadState.OPEN,
      ),
      first.map { it.state },
    )
    assertEquals(
      listOf(DiffCommentOutcome.FIXED, DiffCommentOutcome.SKIPPED, DiffCommentOutcome.FAILED),
      first.take(3).map { it.comments.last().outcome },
    )
    assertReplyPrefixes(first.take(3))
    assertEquals(listOf(2, 2, 2, 1), first.map { it.comments.size })
    assertEquals(null, first[3].comments.single().outcome)
    assertEquals(first.map { it.comments }, replay.map { it.comments })
    assertEquals(first.map { it.state }, replay.map { it.state })
    assertEquals(emptyList(), diagnostics.warnings)
    assertEquals(emptyList(), stubs.unexpectedCalls)
  }

  private fun assertReplyPrefixes(threads: List<DiffCommentThread>) {
    val replies = threads.map { it.comments.last().body }
    assertTrue(replies[0].startsWith("Fixed"), replies[0])
    assertTrue(replies[1].startsWith("Skipped: "), replies[1])
    assertTrue(replies[2].startsWith("Failed to fix: "), replies[2])
  }

  private fun threads(
    prefix: String,
    ids: List<String>,
  ): List<DiffCommentThread> = ids.map { store.threadBySourceRef(repoRoot, "$prefix:$it") }

  private fun settle(
    comments: DiffCommentService?,
    binding: PhaseImplementFixStepBinding,
    prose: String,
  ): PhaseStepOutputCheck =
    ImplementFixReceipt.settle(
      outputContext(),
      binding,
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to prose)),
      ),
      comments,
    )

  private fun outputContext(): PhaseStepOutputContext {
    val request =
      stubs.stub<FeatureTaskRuntimeRunFacts> { name, _ ->
        when (name) {
          "getRepoRoot" -> repoRoot
          "getWorkflowId" -> WORKFLOW_ID
          "getIssueKey" -> ISSUE_KEY
          "getGoalContinuation" -> null
          else -> InterfaceStubs.Unstubbed
        }
      }
    return stubs.stub { name, _ ->
      when (name) {
        "getRequest" -> request
        "getDiagnostics" -> diagnostics
        else -> InterfaceStubs.Unstubbed
      }
    }
  }

  private fun goalBinding(): PhaseImplementFixStepBinding =
    stubs.stub { name, _ ->
      when (name) {
        "goalReviewState" -> GOAL_REVIEW_STATE
        "unaddressedReviewFindings" -> emptyList<Any>()
        "recordRepairReceipt" -> true
        else -> InterfaceStubs.Unstubbed
      }
    }

  private fun nonGoalBinding(): PhaseImplementFixStepBinding =
    stubs.stub { name, args ->
      when (name) {
        "goalReviewState" -> null
        "completedStepEnvelope" ->
          when (args.single()) {
            PHASE_REVIEW -> envelope(REVIEW_ENVELOPE)
            PHASE_VERIFY_FINDINGS -> envelope(VERIFY_ENVELOPE)
            else -> null
          }
        "recordedFindingVerdicts" -> emptyList<Any>()
        else -> InterfaceStubs.Unstubbed
      }
    }

  private fun envelope(json: String): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(json))))

  private fun reviewFinding(id: String) =
    ParallelReviewMergedFinding(
      fNumber = id,
      agentIds = listOf("claude"),
      severity = ParallelReviewSeverity.MAJOR,
      confidence = "high",
      location = "src/Main.kt:${id.takeLast(1)}",
      description = "Defect $id",
      repositoryPath = "src/Main.kt",
      line = id.takeLast(1).toInt(),
    )

  private companion object {
    const val WORKFLOW_ID = "wfl-implement-fix"
    const val ISSUE_KEY = "SKILL-451"
    const val GOAL_PASS_NUMBER = 2
    const val REFUTATION_REASON = "the cache key already includes the tenant id at src/Main.kt:4"
    val FINDING_IDS = listOf("F-001", "F-002", "F-003", "F-004")
    const val GOAL_REPAIR_PROSE =
      "F-001 was fixed by guarding the null path.\n" +
        "F-002 needs no edit because the guard already exists upstream.\n" +
        "F-003 is still open; the API offers no hook for it."
    const val NON_GOAL_REPAIR_PROSE =
      "$GOAL_REPAIR_PROSE\n" +
        "F-004 was fixed by renaming the cache key."
    val SHA = "a".repeat(40)

    val REVIEW_ENVELOPE =
      """
      {
        "contract_version": "0.7",
        "phase_id": "review",
        "status": "completed",
        "summary": "Review produced a validated output.",
        "produced_outputs": {
          "value": "four findings",
          "findings": [
            ${FINDING_IDS.joinToString(",\n") { id -> reviewFindingJson(id) }}
          ],
          "blocker_dispositions": []
        }
      }
      """.trimIndent()

    val VERIFY_ENVELOPE =
      """
      {
        "contract_version": "0.7",
        "phase_id": "verify_findings",
        "status": "completed",
        "summary": "Verified three findings and rejected F-004.",
        "produced_outputs": {
          "value": "F-004 rejected",
          "finding_dispositions": [
            {"finding_id": "F-001", "disposition": "verified", "boundary_context_unavailable": true},
            {"finding_id": "F-002", "disposition": "verified", "boundary_context_unavailable": true},
            {"finding_id": "F-003", "disposition": "verified", "boundary_context_unavailable": true},
            {
              "finding_id": "F-004",
              "disposition": "$UNADDRESSED_FINDING_REJECTED_DISPOSITION",
              "reason": "$REFUTATION_REASON",
              "boundary_context_unavailable": true
            }
          ]
        }
      }
      """.trimIndent()

    val GOAL_REVIEW_STATE =
      GoalSubtaskReviewState(
        reviewBaseSha = SHA,
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        completedPassCount = GOAL_PASS_NUMBER,
        remediationBaseSha = SHA,
        passResults =
          (1..GOAL_PASS_NUMBER).map { pass ->
            GoalSubtaskReviewPassResult(
              passNumber = pass,
              verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
              reviewResultArtifact = "goal_subtask_review_results.$pass",
              unresolvedFindingCount = 3,
              findings =
                FINDING_IDS.take(3).map { id ->
                  GoalSubtaskReviewCompactFinding("major", "Main", "Defect $id", id)
                },
            )
          },
      )

    fun reviewFindingJson(id: String): String =
      """{"severity": "major", "finding_id": "$id", "message": "Defect $id", "location": "src/Main.kt:${id.last()}"}"""
  }
}
