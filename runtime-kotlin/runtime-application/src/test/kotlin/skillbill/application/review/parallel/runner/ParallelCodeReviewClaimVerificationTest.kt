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
import skillbill.application.review.verification.ReviewClaimVerificationRunner
import skillbill.application.runner
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.review.model.ReviewStageReached
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelCodeReviewClaimVerificationTest {
  private val pack =
    sparseReviewPack(
      slug = "kotlin",
      requiredArea = "architecture",
      pathAreas = mapOf("testing" to listOf("src/test/")),
    )

  @Test
  fun `every merged finding at every severity is verified`() {
    val recorder = ReviewRecorder()
    reviewHarness(delegatedConfig(), recorder).reviewed(delegatedRequest())
    val findings = recorder.durableFindingVerdicts.filter { it.stage == ReviewStage.VERIFICATION }
    assertEquals(2, findings.size)
    assertEquals(setOf("F-001", "F-002"), findings.map { it.findingRef }.toSet())
    assertTrue(findings.all { it.claimVerdict == ReviewClaimVerdict.CONFIRMED })
    assertEquals(2, recorder.verificationLaunches.size)
    assertTrue(
      recorder.durableStageBoundaries.any {
        it.stage == ReviewStage.VERIFICATION && it.reached == ReviewStageReached.REACHED
      },
    )
  }

  @Test
  fun `resume after the review pass reenters verification without relaunching lanes or settled findings`() {
    val recorder = ReviewRecorder()
    val first = reviewHarness(delegatedConfig(), recorder).reviewed(delegatedRequest())
    assertEquals(2, recorder.verificationLaunches.size)
    assertTrue(assertNotNull(first.stageResume).holdsDurableResult(ReviewStage.REVIEW))
    val specialistCount = recorder.specialistLaunches.size
    val verificationCount = recorder.verificationLaunches.size
    recorder.durableFindingVerdicts.removeAll { it.findingRef == "F-002" }
    recorder.durableStageBoundaries.removeAll { it.stage == ReviewStage.VERIFICATION }

    val resumed = reviewHarness(delegatedConfig(), recorder).reviewed(delegatedRequest())
    assertEquals(specialistCount, recorder.specialistLaunches.size)
    assertEquals(verificationCount + 1, recorder.verificationLaunches.size)
    val prompt = recorder.verificationLaunches.last().skillRunRequest.promptOverride.orEmpty()
    assertTrue("F-002" in prompt)
    assertTrue("F-001" in prompt)
    val resume = assertNotNull(resumed.stageResume)
    assertTrue(resume.holdsDurableResult(ReviewStage.VERIFICATION))
    assertEquals(ReviewStage.ADJUDICATION, resume.reentryStage)
    assertTrue(recorder.durableIntegrationPass != null)
    assertTrue(recorder.durableStageBoundaries.any { it.stage == ReviewStage.REVIEW })
    assertTrue(recorder.durableStageBoundaries.any { it.stage == ReviewStage.VERIFICATION })
  }

  @Test
  fun `a later resumed lane's findings still receive verification launches`() {
    val recorder = ReviewRecorder()
    reviewHarness(architectureOnlyConfig(), recorder).reviewed(delegatedRequest())
    assertEquals(1, recorder.verificationLaunches.size)
    assertEquals(listOf("F-001"), recorder.durablePassClaims?.findings?.map { it.fNumber })
    val firstPrompt = recorder.verificationLaunches.single().skillRunRequest.promptOverride.orEmpty()
    assertTrue("null is unchecked" in firstPrompt)

    reviewHarness(architectureAndTestingConfig(), recorder).reviewed(delegatedRequest())
    assertEquals(
      listOf("F-001", "F-002"),
      recorder.durablePassClaims?.findings?.map { it.fNumber },
    )
    assertEquals(
      mapOf(
        "F-001" to "bill-kotlin-code-review-architecture",
        "F-002" to "bill-kotlin-code-review-testing",
      ),
      recorder.durableFindingLanes.toMap(),
    )
    assertEquals(2, recorder.verificationLaunches.size)
    val laterPrompt = recorder.verificationLaunches.last().skillRunRequest.promptOverride.orEmpty()
    assertTrue("F-002" in laterPrompt)
    assertTrue("test name is vague" in laterPrompt)
    assertTrue(
      recorder.durableStageBoundaries.any {
        it.stage == ReviewStage.VERIFICATION && it.reached == ReviewStageReached.REACHED
      },
    )
  }

  @Test
  fun `prose claims still launch verification when register admission is empty`() {
    val recorder = ReviewRecorder()
    val prose =
      """
      The review found a missing validation branch.
      [F-001] Major | architecture | path="src/Main.kt" | line=1 | validation is missing
      verdict: changes_requested
      """.trimIndent()

    reviewHarness(
      verificationConfig(
        paths = listOf("src/Main.kt"),
        findings = prose,
      ),
      recorder,
    ).reviewed(delegatedRequest())

    val verificationPrompt = recorder.verificationLaunches.single().skillRunRequest.promptOverride.orEmpty()
    assertTrue(prose in verificationPrompt)
    assertTrue(ReviewClaimVerificationRunner.VERIFY_CLAIMS_ACTION in verificationPrompt)
  }

  @Test
  fun `a review pass that returned no output leaves verification unreached and records why`() {
    val recorder = ReviewRecorder()
    reviewHarness(verificationConfig(paths = listOf("src/Main.kt"), findings = ""), recorder)
      .reviewed(harnessRequest(reviewRunId = RUN_ID, codeReviewMode = CodeReviewExecutionMode.DELEGATED))

    assertTrue(
      recorder.durableStageBoundaries.none {
        it.stage == ReviewStage.VERIFICATION && it.reached == ReviewStageReached.REACHED
      },
    )
    assertEquals(
      listOf(ReviewStageDegradationReason.REVIEW_PASS_OUTPUT_ABSENT),
      recorder.stageDegradations
        .filter { it.reason == ReviewStageDegradationReason.REVIEW_PASS_OUTPUT_ABSENT }
        .map { it.reason },
    )
  }

  @Test
  fun `a degradation write that fails is absorbed instead of failing the review run`() {
    val recorder = ReviewRecorder()
    recorder.failStageDegradationWrite = true

    val result =
      reviewHarness(
        verificationConfig(paths = listOf("src/Main.kt"), findings = ""),
        recorder,
      ).reviewed(harnessRequest(reviewRunId = RUN_ID, codeReviewMode = CodeReviewExecutionMode.DELEGATED))

    assertTrue(recorder.stageDegradations.isEmpty())
    assertTrue(result.mergeResult.findings.isEmpty())
  }

  private fun delegatedRequest() =
    harnessRequest(
      reviewRunId = RUN_ID,
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    )

  private fun delegatedConfig(): ReviewHarnessConfig = verificationConfig()

  private fun architectureOnlyConfig(): ReviewHarnessConfig =
    verificationConfig(
      paths = listOf("src/Main.kt"),
      findings = ARCHITECTURE_FINDING,
    )

  private fun architectureAndTestingConfig(): ReviewHarnessConfig =
    verificationConfig(
      paths = listOf("src/Main.kt", "src/test/AppTest.kt"),
      findings = TESTING_FINDING,
    )

  private fun verificationConfig(): ReviewHarnessConfig =
    verificationConfig(
      paths = listOf("src/Main.kt", "src/test/AppTest.kt"),
      findings = FINDINGS,
    )

  private fun verificationConfig(
    paths: List<String>,
    findings: String,
  ): ReviewHarnessConfig {
    val shas =
      paths.indices.map { index ->
        if (index == paths.lastIndex) HARNESS_HEAD_REVISION else "c$index"
      }
    return ReviewHarnessConfig(
      manifests = listOf(pack),
      diff = diffForPaths(*paths.toTypedArray()),
      response = { request ->
        when (request.skillRunRequest.issueKey) {
          "code-review" -> RecordedWorkerResponse(stdout = findings)
          ReviewClaimVerificationRunner.ISSUE_KEY -> RecordedWorkerResponse(stdout = CONFIRMED)
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
    const val RUN_ID = "review-run-claim-verify"
    const val ARCHITECTURE_FINDING =
      "- [F-001] Major | High | specialist=bill-kotlin-code-review-architecture | " +
        "path=\"src/Main.kt\" | line=1 | null is unchecked"
    const val TESTING_FINDING =
      "- [F-001] Nit | Low | specialist=bill-kotlin-code-review-testing | " +
        "path=\"src/test/AppTest.kt\" | line=1 | test name is vague"
    const val FINDINGS = "$ARCHITECTURE_FINDING\n$TESTING_FINDING"
    const val CONFIRMED = """{"claim_verdict":"confirmed"}"""
  }
}

private val ReviewRecorder.verificationLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == ReviewClaimVerificationRunner.ISSUE_KEY }

private val ReviewRecorder.specialistLaunches: List<GoalRunnerSubtaskLaunchRequest>
  get() = parentLaunches.filter { it.skillRunRequest.issueKey == "code-review" }
