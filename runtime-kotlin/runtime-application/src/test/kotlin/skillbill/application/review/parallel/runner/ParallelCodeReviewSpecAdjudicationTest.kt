package skillbill.application.review.parallel.runner

import skillbill.application.review.snapshot.HARNESS_HEAD_REVISION
import skillbill.application.review.snapshot.RecordedCommit
import skillbill.application.review.snapshot.RecordedWorkerResponse
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewed
import skillbill.application.review.snapshot.sparseReviewPack
import skillbill.application.review.spec.ReviewSpecAdjudicationRunner
import skillbill.application.review.verification.ReviewClaimVerificationRunner
import skillbill.application.runner
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageReached
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelCodeReviewSpecAdjudicationTest {
  private val pack =
    sparseReviewPack(
      slug = "kotlin",
      requiredArea = "architecture",
      pathAreas = mapOf("testing" to listOf("src/test/")),
    )

  @Test
  fun `a review that produced findings records the adjudication boundary`() {
    val recorder = ReviewRecorder()
    val repo = specRepo()
    reviewHarness(adjudicationConfig(), recorder).reviewed(
      harnessRequest(
        repoRoot = repo,
        reviewRunId = "adj-wired",
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
      ).copy(specPath = repo.resolve("spec.md")),
    )
    assertTrue(recorder.adjudicationLaunches.isNotEmpty())
    assertTrue(
      recorder.durableStageBoundaries.any {
        it.stage == ReviewStage.ADJUDICATION && it.reached == ReviewStageReached.REACHED
      },
    )
    assertTrue(
      recorder.durableFindingVerdicts.any { it.stage == ReviewStage.ADJUDICATION },
    )
  }

  @Test
  fun `a run whose stage 1 refuted every finding records the boundary without launching adjudication`() {
    val recorder = ReviewRecorder()
    val repo = specRepo()
    reviewHarness(refutedConfig(), recorder).reviewed(
      harnessRequest(
        repoRoot = repo,
        reviewRunId = "adj-refuted",
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
      ).copy(specPath = repo.resolve("spec.md")),
    )
    assertTrue(recorder.adjudicationLaunches.isEmpty())
    assertTrue(
      recorder.durableFindingVerdicts.filter { it.stage == ReviewStage.VERIFICATION }
        .all { it.claimVerdict == ReviewClaimVerdict.REFUTED },
    )
    assertTrue(
      recorder.durableStageBoundaries.any {
        it.stage == ReviewStage.ADJUDICATION && it.reached == ReviewStageReached.REACHED
      },
    )
  }

  @Test
  fun `a crash after verification resumes into adjudication without relaunching lanes`() {
    val recorder = ReviewRecorder()
    val repo = specRepo()
    val request =
      harnessRequest(
        repoRoot = repo,
        reviewRunId = "adj-resume",
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
      ).copy(specPath = repo.resolve("spec.md"))
    val first = reviewHarness(adjudicationConfig(), recorder).reviewed(request)
    assertTrue(assertNotNull(first.stageResume).holdsDurableResult(ReviewStage.VERIFICATION))
    val specialistCount = recorder.specialistLaunches.size
    val verificationCount = recorder.verificationLaunches.size
    val adjudicationCount = recorder.adjudicationLaunches.size
    recorder.durableFindingVerdicts.removeAll { it.stage == ReviewStage.ADJUDICATION }
    recorder.durableStageBoundaries.removeAll { it.stage == ReviewStage.ADJUDICATION }

    val resumed = reviewHarness(adjudicationConfig(), recorder).reviewed(request)
    assertEquals(specialistCount, recorder.specialistLaunches.size)
    assertEquals(verificationCount, recorder.verificationLaunches.size)
    assertEquals(adjudicationCount + 2, recorder.adjudicationLaunches.size)
    val resume = assertNotNull(resumed.stageResume)
    assertTrue(resume.holdsDurableResult(ReviewStage.VERIFICATION))
    assertTrue(resume.holdsDurableResult(ReviewStage.ADJUDICATION))
    assertTrue(resume.holdsDurableResult(ReviewStage.REVIEW))
    assertEquals(null, resume.reentryStage)
  }

  private fun specRepo(): Path {
    val repo = Files.createTempDirectory("review-adj-spec")
    Files.writeString(
      repo.resolve("spec.md"),
      """
      # Feature

      ## Intended Outcome
      Ship the adjudication stage.

      ## Acceptance Criteria
      1. Stage two runs.

      ## Constraints
      - Do not launch workers on refuted findings.

      ## Non-Goals
      - Re-testing claims.
      """.trimIndent(),
    )
    return repo
  }

  private fun adjudicationConfig(): ReviewHarnessConfig =
    workerConfig(
      verificationStdout = CONFIRMED,
      adjudicationStdout = IN_SCOPE,
    )

  private fun refutedConfig(): ReviewHarnessConfig =
    workerConfig(
      verificationStdout = REFUTED,
      adjudicationStdout = IN_SCOPE,
    )

  private fun workerConfig(
    verificationStdout: String,
    adjudicationStdout: String,
  ): ReviewHarnessConfig {
    val paths = listOf("src/Main.kt", "src/test/AppTest.kt")
    val shas =
      paths.indices.map { index ->
        if (index == paths.lastIndex) HARNESS_HEAD_REVISION else "c$index"
      }
    return ReviewHarnessConfig(
      manifests = listOf(pack),
      diff = diffForPaths(*paths.toTypedArray()),
      response = { request ->
        when (request.skillRunRequest.issueKey) {
          "code-review" -> RecordedWorkerResponse(stdout = FINDINGS)
          ReviewClaimVerificationRunner.ISSUE_KEY -> RecordedWorkerResponse(stdout = verificationStdout)
          ReviewSpecAdjudicationRunner.ISSUE_KEY -> RecordedWorkerResponse(stdout = adjudicationStdout)
          else -> RecordedWorkerResponse()
        }
      },
      commits =
        paths.mapIndexed { index, path ->
          RecordedCommit(shas[index], "commit touching $path", diffForPaths(path))
        },
    )
  }

  private companion object {
    const val FINDINGS =
      "- [F-001] Major | High | specialist=bill-kotlin-code-review-architecture | " +
        "path=\"src/Main.kt\" | line=1 | null is unchecked\n" +
        "- [F-002] Nit | Low | specialist=bill-kotlin-code-review-testing | " +
        "path=\"src/test/AppTest.kt\" | line=1 | test name is vague"
    const val CONFIRMED = """{"claim_verdict":"confirmed"}"""
    const val REFUTED = """{"claim_verdict":"refuted","citations":[{"path":"src/Main.kt","line":1}]}"""
    const val IN_SCOPE = """{"scope_disposition":"in_scope"}"""
  }
}

private val ReviewRecorder.adjudicationLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == ReviewSpecAdjudicationRunner.ISSUE_KEY }

private val ReviewRecorder.verificationLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == ReviewClaimVerificationRunner.ISSUE_KEY }

private val ReviewRecorder.specialistLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == "code-review" }
