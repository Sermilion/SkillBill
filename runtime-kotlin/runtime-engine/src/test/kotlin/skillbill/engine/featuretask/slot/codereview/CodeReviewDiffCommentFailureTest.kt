package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runner.BranchSetupTestConfig
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.runner.auditSatisfiedOutput
import skillbill.engine.featuretask.runner.defaultPhaseOutput
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.runnerHarness
import skillbill.engine.featuretask.slot.DiffCommentTestStore
import skillbill.engine.featuretask.slot.scriptedReviewPhaseRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeReviewDiffCommentFailureTest {
  private val failingStore = DiffCommentTestStore(currentBranch = { error(GIT_FAILURE) })

  @AfterTest
  fun cleanUp() {
    failingStore.close()
  }

  @Test
  fun `a throwing comment service leaves the review outcome and envelope unchanged and records one diagnostic`() {
    val failing = reviewRun(failingStore.service)
    val plain = reviewRun(null)

    assertEquals(plain.report, failing.report)
    assertTrue("review" in plain.completedPhaseIds, plain.report)
    assertEquals(plain.envelope, failing.envelope)
    assertTrue(REVIEW_FINDING in failing.envelope, failing.envelope)
    val commentWarnings = failing.warnings.filter { "post review diff comments" in it }
    assertEquals(1, commentWarnings.size, failing.warnings.toString())
    assertTrue(GIT_FAILURE in commentWarnings.single(), commentWarnings.single())
    assertEquals(emptyList(), plain.warnings.filter { "diff comments" in it })
  }

  private fun reviewRun(comments: DiffCommentService?): ReviewRun {
    val diagnostics = DiffCommentTestStore.RecordingDiagnostics()
    var reviews = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          branchSetup =
            BranchSetupTestConfig(
              gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "fp-1" },
            ),
        ).copy(
          launcher =
            RuntimeRecordingLauncher { request ->
              val phase = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
              facts(if (phase == "audit") auditSatisfiedOutput() else defaultPhaseOutput(request))
            },
          reviewRunner =
            scriptedReviewPhaseRunner {
              reviews += 1
              "- [F-001] Blocker | High | Foo.kt:1 | $REVIEW_FINDING\nverdict: changes_requested"
            },
          diagnostics = diagnostics,
          comments = comments,
        ),
      )
    val report = harness.runner.run(harness.request())
    val envelope = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_subtask_review_results"].toString()
    assertEquals(1, reviews)
    return ReviewRun(
      normalized(report.toString()),
      (report as? FeatureTaskRuntimeRunReport.Blocked)?.completedPhaseIds.orEmpty(),
      normalized(envelope),
      diagnostics.warnings.toList(),
    )
  }

  private fun normalized(text: String): String = REVIEW_RUN_ID.replace(text, "rvw-<id>")

  private data class ReviewRun(
    val report: String,
    val completedPhaseIds: List<String>,
    val envelope: String,
    val warnings: List<String>,
  )

  private companion object {
    const val GIT_FAILURE = "git is unavailable to the comment store"
    const val REVIEW_FINDING = "Foo.kt leaks a connection in the error path"
    val REVIEW_RUN_ID = Regex("rvw-\\d{8}-\\d{6}-[a-z0-9]{4}")
  }
}
