package skillbill.application.review.parallel.runner

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.snapshot.RecordedWorkerResponse
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewLayer
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.application.runner
import skillbill.contracts.JsonCodec
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.review.context.model.accounting.ReviewAccountingSummary
import skillbill.workflow.model.goalreview.toReviewAccountingBoundedJson
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelCodeReviewEndToEndTest {
  private val kotlinAreas = listOf("architecture", "security", "testing")
  private val kmpAreas = listOf("platform-correctness", "ui")

  @Test fun `kotlin diff discovers once and launches one parent lane`() {
    val recorder = ReviewRecorder()
    val runner =
      reviewHarness(
        kotlinConfig { RecordedWorkerResponse(stdout = finding("src/Repo.kt", KOTLIN_ARCHITECTURE)) },
        recorder,
      )

    val result = runner.reviewed(harnessRequest())

    assertEquals(
      1,
      recorder.diffQueries.size,
      "Scope discovery must happen once for the whole review, not once per lane.",
    )
    assertEquals(
      1,
      recorder.parentLaunches.filter { it.skillRunRequest.issueKey == "code-review" }.size,
      "Single-agent parallel review runs one parent lane.",
    )
    assertEquals(
      listOf("codex"),
      recorder.parentLaunches
        .filter { it.skillRunRequest.issueKey == "code-review" }
        .map { it.invokedAgentId },
      "Single-agent parallel review uses agent1 only.",
    )
    recorder.parentLaunches
      .filter { it.skillRunRequest.issueKey == "code-review" }
      .mapNotNull { it.skillRunRequest.promptOverride }
      .forEach { prompt ->
        kotlinAreas.forEach { area ->
          assertTrue(
            prompt.contains("bill-kotlin-code-review-$area"),
            "Parent prompt dropped routed rubric identity '$area'.",
          )
        }
      }
    assertTrue(result.lane1.success)
  }

  @Test fun `parent prompts carry no rediscovery affordance`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig(), recorder).reviewed(harnessRequest())

    assertTrue(recorder.parentPrompts.isNotEmpty())
    recorder.parentPrompts.forEach { prompt ->
      listOf("git diff", "gh pr", "merge-base", "AGENTS.md", "platform-packs/").forEach { affordance ->
        assertTrue(!prompt.contains(affordance), "Parent prompt leaked '$affordance'.")
      }
    }
  }

  @Test fun `assigned evidence is carried only in prompt hunk envelopes`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig(), recorder).reviewed(harnessRequest())

    recorder.parentPrompts.forEach { prompt ->
      assertTrue(prompt.contains("## Assigned bundle:"))
      assertTrue(prompt.contains("\"src/Repo.kt\""))
      assertTrue(prompt.contains("they are not read_evidence arguments and passing one is refused"))
    }
  }

  @Test fun `layered kmp composition expands directly to kmp and required kotlin rubrics`() {
    val recorder = ReviewRecorder()

    reviewHarness(kmpConfig(), recorder).reviewed(harnessRequest())

    val expected = kmpAreas.map { "bill-kmp-code-review-$it" } + kotlinAreas.map { "bill-kotlin-code-review-$it" }
    recorder.parentPrompts.forEach { prompt ->
      expected.forEach { specialist ->
        assertTrue(prompt.contains(specialist), "Composed parent prompt dropped '$specialist'.")
      }
    }
  }

  @Test fun `repeated layered runs produce identical findings and accounting`() {
    val repoRoot = Files.createTempDirectory("review-e2e-determinism")

    fun run(): ParallelCodeReviewResult {
      val recorder = ReviewRecorder()
      return reviewHarness(
        kmpConfig {
          RecordedWorkerResponse(
            stdout = finding("src/main/kotlin/App.kt", KOTLIN_ARCHITECTURE),
          )
        },
        recorder,
      ).reviewed(harnessRequest(repoRoot = repoRoot))
    }

    val first = run()
    val second = run()

    assertEquals(first.mergeResult.formattedOutput, second.mergeResult.formattedOutput)
    val firstSummary = assertNotNull(first.accountingSummary)
    val secondSummary = assertNotNull(second.accountingSummary)
    assertEquals(firstSummary.toReviewAccountingBoundedJson(), secondSummary.toReviewAccountingBoundedJson())
    assertEquals(firstSummary.lanes.map { it.lane }, secondSummary.lanes.map { it.lane })
  }

  @Test fun `accounting reports every retained measured dimension`() {
    val recorder = ReviewRecorder()
    val runner =
      reviewHarness(
        kotlinConfig {
          RecordedWorkerResponse(
            stdout = finding("src/Repo.kt", KOTLIN_ARCHITECTURE),
          )
        },
        recorder,
      )

    val summary = assertNotNull(runner.reviewed(harnessRequest()).accountingSummary)

    val lanes = summary.lanes.filter { it.children.isEmpty() }
    assertEquals(1, lanes.size, "Single-agent review owns exactly one accounting node.")
    lanes.forEach { lane ->
      assertTrue(lane.counters.launchBytes > 0, "Lane '${lane.lane}' reported no launch bytes.")
      assertEquals(0, lane.counters.evidenceBytes, "Assigned hunk envelopes require no filesystem evidence reads.")
      assertTrue(lane.counters.resultBytes > 0)
      assertEquals("completed", lane.terminalOutcome.wireValue)
    }
    assertEquals(lanes.sumOf { it.counters.launchBytes }, summary.aggregateCounters.launchBytes)
    assertEquals(summary.aggregateCounters, summary.parent.inclusiveCounters)
  }

  @Test fun `durable accounting is persisted exactly once per review`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig { RecordedWorkerResponse() }, recorder)
      .reviewed(harnessRequest())

    val record = recorder.savedAccounting.single()
    assertEquals("accounting_summary", record.summary.boundedPayload()["kind"])
    assertTrue(record.reviewId.isNotBlank() && record.packetDigest.isNotBlank())
  }

  @Test fun `durable accounting is keyed by the caller review run id telemetry resolves`() {
    val recorder = ReviewRecorder()
    val reviewRunId = "rvw-20260722-101500-ab12"

    reviewHarness(kotlinConfig { RecordedWorkerResponse() }, recorder)
      .reviewed(harnessRequest(reviewRunId = reviewRunId))

    val record = recorder.savedAccounting.single()
    assertEquals(reviewRunId, record.reviewId)
    assertEquals(reviewRunId, record.summary.boundedPayload()["review_id"])
  }

  @Test fun `accounting falls back to the packet review id when no run id is supplied`() {
    val recorder = ReviewRecorder()

    reviewHarness(kotlinConfig { RecordedWorkerResponse() }, recorder)
      .reviewed(harnessRequest())

    assertTrue(recorder.savedAccounting.single().reviewId.startsWith("code-review-"))
  }

  private fun kotlinConfig(
    response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  ) = ReviewHarnessConfig(
    manifests = listOf(reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt"))),
    diff = diffForPaths("src/Repo.kt"),
    response = response,
  )

  private fun kmpConfig(
    response: (GoalRunnerSubtaskLaunchRequest) -> RecordedWorkerResponse = { RecordedWorkerResponse() },
  ) = ReviewHarnessConfig(
    manifests =
      listOf(
        reviewPack(
          "kmp",
          kmpAreas,
          layers = listOf(reviewLayer("kotlin")),
          routingSignals = listOf("*.kt", "commonMain"),
          contentSignals = listOf("expect", "actual"),
        ),
        reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt")),
      ),
    diff =
      diffForChanges(
        "src/commonMain/kotlin/App.kt" to "expect fun platformName(): String",
        "src/main/kotlin/App.kt" to "actual fun platformName(): String = \"jvm\"",
      ),
    response = response,
  )
}

private const val KOTLIN_ARCHITECTURE = "bill-kotlin-code-review-architecture"

private fun ReviewAccountingSummary.boundedPayload(): Map<String, Any?> =
  requireNotNull(
    JsonCodec.parseObjectOrNull(toReviewAccountingBoundedJson())
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap),
  )

internal fun finding(
  path: String,
  specialist: String? = null,
): String {
  val attribution = specialist?.let { "specialist=$it | " }.orEmpty()
  return "- [F-001] Major | High | $attribution" + "path=\"$path\" | line=1 | Bounded specialist finding"
}
