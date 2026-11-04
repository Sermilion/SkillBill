package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.slot.DiffCommentTestStore
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentThreadState
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ImplementFixReceiptDiffCommentTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-implement-fix-receipt-repo")
  private val store = DiffCommentTestStore()
  private val diagnostics = DiffCommentTestStore.RecordingDiagnostics()

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    store.close()
  }

  @Test
  fun `each receipt outcome replies to its own review thread and only a fix resolves it`() {
    store.service.recordReviewFindings(repoRoot, PASS_PREFIX, "claude", FINDING_IDS.map(::reviewFinding))
    val recorded = mutableListOf<FeatureTaskRuntimeRepairReceipt>()

    val withComments = settle(store.service, recorded)
    val withoutComments = settle(null, recorded)
    val first = FINDING_IDS.map { store.threadBySourceRef(repoRoot, "$PASS_PREFIX:$it") }
    settle(store.service, recorded)
    val replay = FINDING_IDS.map { store.threadBySourceRef(repoRoot, "$PASS_PREFIX:$it") }

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
    val replies = first.map { it.comments.last().body }
    assertTrue(replies[0].startsWith("Fixed"), replies[0])
    assertTrue(replies[1].startsWith("Skipped: "), replies[1])
    assertTrue(replies[2].startsWith("Failed to fix: "), replies[2])
    assertEquals(listOf(2, 2, 2), first.map { it.comments.size })
    assertEquals(first.map { it.comments }, replay.map { it.comments })
    assertEquals(first.map { it.state }, replay.map { it.state })
    assertEquals(emptyList(), diagnostics.warnings)
  }

  private fun settle(
    comments: DiffCommentService?,
    recorded: MutableList<FeatureTaskRuntimeRepairReceipt>,
  ): PhaseStepOutputCheck =
    ImplementFixReceipt.settle(
      outputContext(),
      fixBinding(recorded),
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to REPAIR_PROSE)),
      ),
      comments,
    )

  private fun outputContext(): PhaseStepOutputContext {
    val request =
      stub<FeatureTaskRuntimeRunFacts> { name, _ ->
        when (name) {
          "getRepoRoot" -> repoRoot
          "getWorkflowId" -> WORKFLOW_ID
          "getIssueKey" -> ISSUE_KEY
          else -> Unstubbed
        }
      }
    return stub { name, _ ->
      when (name) {
        "getRequest" -> request
        "getDiagnostics" -> diagnostics
        else -> Unstubbed
      }
    }
  }

  private fun fixBinding(recorded: MutableList<FeatureTaskRuntimeRepairReceipt>): PhaseImplementFixStepBinding =
    stub { name, args ->
      when (name) {
        "goalReviewState" -> REVIEW_STATE
        "unaddressedReviewFindings" -> emptyList<Any>()
        "recordRepairReceipt" -> recorded.add(args.single() as FeatureTaskRuntimeRepairReceipt)
        else -> Unstubbed
      }
    }

  private object Unstubbed

  private inline fun <reified T : Any> stub(crossinline answer: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
      when (method.name) {
        "toString" -> "stub ${T::class.simpleName}"
        "hashCode" -> 0
        "equals" -> false
        else ->
          answer(method.name, args?.toList().orEmpty()).also { value ->
            if (value === Unstubbed) fail("unexpected call ${T::class.simpleName}.${method.name}")
          }
      }
    } as T

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
    const val PASS_NUMBER = 2
    const val PASS_PREFIX = "review:$WORKFLOW_ID:pass-$PASS_NUMBER"
    val FINDING_IDS = listOf("F-001", "F-002", "F-003")
    const val REPAIR_PROSE =
      "F-001 was fixed by guarding the null path.\n" +
        "F-002 needs no edit because the guard already exists upstream.\n" +
        "F-003 is still open; the API offers no hook for it."
    val SHA = "a".repeat(40)

    val REVIEW_STATE =
      GoalSubtaskReviewState(
        reviewBaseSha = SHA,
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        completedPassCount = PASS_NUMBER,
        remediationBaseSha = SHA,
        passResults =
          (1..PASS_NUMBER).map { pass ->
            GoalSubtaskReviewPassResult(
              passNumber = pass,
              verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
              reviewResultArtifact = "goal_subtask_review_results.$pass",
              unresolvedFindingCount = FINDING_IDS.size,
              findings =
                FINDING_IDS.map { id -> GoalSubtaskReviewCompactFinding("major", "Main", "Defect $id", id) },
            )
          },
      )
  }
}
