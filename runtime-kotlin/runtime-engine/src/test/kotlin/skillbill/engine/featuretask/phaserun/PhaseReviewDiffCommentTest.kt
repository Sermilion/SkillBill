package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.engine.featuretask.runner.BranchSetupTestConfig
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.defaultPhaseOutput
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.DiffCommentTestStore
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.reviewStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentThreadState
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhaseReviewDiffCommentTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-review-comment-repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-review-comment-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val git = committedRepoBranchSetup().gitOperations.also { it.repositoryFingerprintValue = "reviewed" }
  private val store = DiffCommentTestStore()

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
    store.close()
  }

  @Test
  fun `a rejected typed finding after a prose entry declines the thread it was posted under`() {
    val commented = review(COMMENTED_RUN_ID, withComments = true)
    val plain = review(PLAIN_RUN_ID, withComments = false)

    assertIs<PhaseRunResult.Completed>(commented, commented.toString())
    assertIs<PhaseRunResult.Completed>(plain, plain.toString())
    assertEquals(requireNotNull(plain.reviewResult).output, requireNotNull(commented.reviewResult).output)
    val threads = store.threads(repoRoot)
    assertEquals(
      setOf("review:$COMMENTED_RUN_ID:F-001", "review:$COMMENTED_RUN_ID:F-002"),
      threads.mapNotNull { it.sourceRef }.toSet(),
    )
    val rejected = store.threadBySourceRef(repoRoot, "review:$COMMENTED_RUN_ID:F-001")
    val confirmed = store.threadBySourceRef(repoRoot, "review:$COMMENTED_RUN_ID:F-002")
    assertTrue(REJECTED_DEFECT in rejected.comments.first().body, rejected.comments.first().body)
    assertEquals(DiffCommentThreadState.DECLINED, rejected.state)
    assertEquals(DiffCommentOutcome.REFUTED, rejected.comments.last().outcome)
    assertEquals(2, rejected.comments.size)
    assertTrue(CONFIRMED_DEFECT in confirmed.comments.single().body, confirmed.comments.single().body)
    assertEquals(DiffCommentThreadState.OPEN, confirmed.state)
  }

  private fun review(
    reviewRunId: String,
    withComments: Boolean,
  ): PhaseRunResult {
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        launcher = RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) },
        reviewRunner = RejectingVerifierReviewRunner,
        comments = store.service.takeIf { withComments },
      )
    val harness = telemetryRunnerHarness(runtimeConfig = config, databaseFactory = { database })
    return phaseRunEntry(
      harness.strategies,
      git,
      database,
      clock,
      harness.runLoopEntry.delegateTo(TestFeatureTaskRuntimeRunLoopEntry()),
    ).run(
      PhaseRunRequest(
        definitionId = SkeletonDefinition.REVIEW.id,
        repoRoot = repoRoot,
        invokedAgentId = REVIEW_AGENT,
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        reviewInvocation = ReviewInvocation(reviewRunId = reviewRunId),
      ),
    )
  }

  private object RejectingVerifierReviewRunner : PhaseRunner {
    override fun run(
      input: PhaseStepInput,
      state: PhaseLaunchState,
    ): PhaseStepOutput =
      reviewStepOutput(
        if (input.directive.startsWith("Independently verify")) VERIFIER_REGISTER else REVIEW_REPORT,
      )
  }

  private companion object {
    const val REVIEW_AGENT = "claude"
    const val COMMENTED_RUN_ID = "rvw-20260927-120000-cmnt"
    const val PLAIN_RUN_ID = "rvw-20260927-120000-plan"
    const val REJECTED_DEFECT = "the cache key ignores the tenant id"
    const val CONFIRMED_DEFECT = "the retry loop never stops on a fatal error"
    const val REVIEW_REPORT =
      "- The change also renames the cache module, which makes the diff noisy.\n" +
        "- [F-001] Major | High | src/Cache.kt:3 | $REJECTED_DEFECT\n" +
        "- [F-002] Major | High | src/Retry.kt:7 | $CONFIRMED_DEFECT\n" +
        "verdict: changes_requested"
    const val VERIFIER_REGISTER = "F-001: confirmed\nF-002: rejected\nF-003: confirmed"
  }
}
