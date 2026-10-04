package skillbill.application

import skillbill.application.review.governed.stubGovernedReviewEvidenceEndpointBinder
import skillbill.application.review.snapshot.reviewed
import skillbill.application.review.snapshot.simulateGovernedEvidenceReads
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.launch.ReviewLaunchAgentStagingPort
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.preparation.ReviewRubricResolver
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParallelCodeReviewRunnerLaneLaunchOwnershipTest {
  @Test
  fun `staging failure after bind closes the endpoint once and launches no worker`() {
    val endpointRoot = Files.createTempDirectory("staging-failure-endpoint")
    val closeCount = AtomicInteger(0)
    val launcher = RecordingSubtaskLauncher()
    val runner =
      createRunner(
        launcher,
        RunnerFixtureConfig(
          evidenceEndpointRoot = endpointRoot,
          catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
          diffResolver = RecordingDiffResolver(default = diffFor("src/FooTest.kt")),
          rubricResolver = delegatedCursorRubricResolver(),
          reviewLaunchAgentStaging = ReviewLaunchAgentStagingPort { throw IllegalStateException("staging failed") },
          evidenceEndpointBinder = countingEndpointBinder(endpointRoot, closeCount),
        ),
      )

    val result =
      runner.reviewed(
        baseRequest(agent1Id = "cursor", scope = ParallelReviewScope.STAGED)
          .copy(codeReviewMode = CodeReviewExecutionMode.DELEGATED),
      )

    assertTrue(launcher.requests.isEmpty())
    assertEquals(1, closeCount.get())
    assertEquals(false, result.lane1.success)
  }

  @Test
  fun `endpoint bind interruption propagates instead of becoming unbound`() {
    val launcher = RecordingSubtaskLauncher()
    val runner =
      createRunner(
        launcher,
        RunnerFixtureConfig(
          catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
          diffResolver = RecordingDiffResolver(default = diffFor("src/FooTest.kt")),
          rubricResolver = delegatedCursorRubricResolver(),
          evidenceEndpointBinder =
            object : GovernedReviewEvidenceEndpointBinder {
              override fun bind(
                lane: String,
                broker: ReviewEvidenceBroker,
                onEvidenceRead: (() -> Unit)?,
              ): GovernedReviewEvidenceEndpointHandle = throw InterruptedException("bind interrupted")
            },
        ),
      )

    assertFailsWith<InterruptedException> {
      runner.run(
        baseRequest(agent1Id = "cursor", scope = ParallelReviewScope.STAGED)
          .copy(codeReviewMode = CodeReviewExecutionMode.DELEGATED),
      )
    }
    assertTrue(launcher.requests.isEmpty())
  }

  @Test
  fun `lane launch ownership matrix preserves primary failure and exactly once close`() {
    launchOwnershipCases().forEach(::assertLaunchOwnershipCase)
  }

  @Test
  fun `interrupted launch preserves its primary failure when cleanup also fails`() {
    val endpointRoot = Files.createTempDirectory("lane-cleanup-failure")
    val closeCount = AtomicInteger(0)
    val primary = InterruptedException("launch interrupted")
    val cleanup = IllegalStateException("cleanup failed")
    val runner =
      createRunner(
        GoalRunnerSubtaskLauncher { throw primary },
        RunnerFixtureConfig(
          evidenceEndpointRoot = endpointRoot,
          catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
          diffResolver = RecordingDiffResolver(default = diffFor("src/FooTest.kt")),
          rubricResolver = delegatedCursorRubricResolver(),
          evidenceEndpointBinder =
            countingEndpointBinder(
              endpointRoot,
              closeCount,
              closeFailure = cleanup,
            ),
        ),
      )

    val error =
      assertFailsWith<InterruptedException> {
        runner.run(
          baseRequest(agent1Id = "cursor", scope = ParallelReviewScope.STAGED)
            .copy(codeReviewMode = CodeReviewExecutionMode.DELEGATED),
        )
      }

    assertSame(primary, error)
    assertEquals(listOf(cleanup), error.suppressed.toList())
    assertEquals(1, closeCount.get())
  }
}

private fun launchOwnershipCases(): List<LaunchOwnershipCase> =
  listOf(
    LaunchOwnershipCase("successful launch", noOpStaging(), RecordingSubtaskLauncher(), true, true),
    LaunchOwnershipCase("unsupported launch", noOpStaging(), unsupportedLauncher(), true, false),
    LaunchOwnershipCase(
      "throwing launch",
      noOpStaging(),
      throwingLauncher(IllegalStateException("launch failed")),
      false,
      false,
    ),
    LaunchOwnershipCase(
      "cancellation",
      noOpStaging(),
      throwingLauncher(CancellationException("cancelled")),
      false,
      false,
      CancellationException::class,
    ),
    LaunchOwnershipCase(
      "interrupted launch",
      noOpStaging(),
      throwingLauncher(InterruptedException("interrupted")),
      false,
      false,
      InterruptedException::class,
    ),
    LaunchOwnershipCase("staging failure", throwingStaging(), RecordingSubtaskLauncher(), false, false),
  )

private fun assertLaunchOwnershipCase(case: LaunchOwnershipCase) {
  val endpointRoot = Files.createTempDirectory("lane-ownership-${case.name}")
  val closeCount = AtomicInteger(0)
  val runner =
    createRunner(
      case.launcher,
      RunnerFixtureConfig(
        evidenceEndpointRoot = endpointRoot,
        catalogGateway = stubCatalogGateway(listOf(platformManifest("kotlin", listOf("*.kt")))),
        diffResolver = RecordingDiffResolver(default = diffFor("src/FooTest.kt")),
        rubricResolver = delegatedCursorRubricResolver(),
        reviewLaunchAgentStaging = case.staging,
        evidenceEndpointBinder = countingEndpointBinder(endpointRoot, closeCount),
      ),
    )
  val run = {
    runner.reviewed(
      baseRequest(agent1Id = "cursor", scope = ParallelReviewScope.STAGED)
        .copy(codeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )
  }
  if (case.expectThrown != null) {
    assertFailsWith(case.expectThrown) { run() }
  } else {
    assertEquals(case.expectSuccess, run().lane1.success, case.name)
  }
  assertLaunchCount(case)
  assertEquals(1, closeCount.get(), case.name)
}

private fun assertLaunchCount(case: LaunchOwnershipCase) {
  if (case.launcher is RecordingSubtaskLauncher) {
    assertEquals(if (case.expectLaunch) 1 else 0, case.launcher.requests.size, case.name)
  }
}

private fun noOpStaging(): ReviewLaunchAgentStagingPort = ReviewLaunchAgentStagingPort { }

private fun throwingStaging(): ReviewLaunchAgentStagingPort =
  ReviewLaunchAgentStagingPort { throw IllegalStateException("staging failed") }

private fun throwingLauncher(error: Throwable): GoalRunnerSubtaskLauncher = GoalRunnerSubtaskLauncher { throw error }

private fun unsupportedLauncher(): GoalRunnerSubtaskLauncher =
  GoalRunnerSubtaskLauncher {
    UnsupportedAgentRunLaunch(
      agent = SupportedAgent.fromNormalizedId("cursor", label = "agentId"),
      reason = "unsupported",
    )
  }

private data class LaunchOwnershipCase(
  val name: String,
  val staging: ReviewLaunchAgentStagingPort,
  val launcher: GoalRunnerSubtaskLauncher,
  val expectLaunch: Boolean,
  val expectSuccess: Boolean,
  val expectThrown: KClass<out Throwable>? = null,
)

private fun countingEndpointBinder(
  root: Path,
  closeCount: AtomicInteger,
  closeFailure: Throwable? = null,
): GovernedReviewEvidenceEndpointBinder =
  object : GovernedReviewEvidenceEndpointBinder {
    override fun bind(
      lane: String,
      broker: ReviewEvidenceBroker,
      onEvidenceRead: (() -> Unit)?,
    ): GovernedReviewEvidenceEndpointHandle {
      val delegate = stubGovernedReviewEvidenceEndpointBinder(root).bind(lane, broker, onEvidenceRead)
      return object : GovernedReviewEvidenceEndpointHandle {
        override val descriptor = delegate.descriptor

        override fun close() {
          closeCount.incrementAndGet()
          delegate.close()
          closeFailure?.let { throw it }
        }
      }
    }
  }

private class RecordingSubtaskLauncher : GoalRunnerSubtaskLauncher {
  val requests = mutableListOf<GoalRunnerSubtaskLaunchRequest>()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest) =
    agentRunLaunchFacts(
      agent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "agentId"),
      stdout = "NO_FINDINGS",
      stderr = "",
      liveness = null,
      processStarted = true,
      mcpStartupObserved = false,
    ).also {
      simulateGovernedEvidenceReads(request.skillRunRequest)
      requests += request
    }
}

private fun delegatedCursorRubricResolver(): ReviewRubricResolver =
  ReviewRubricResolver {
    ResolvedReviewRubric(
      "bill-kotlin-code-review",
      "parent routing rubric",
      specialists =
        listOf(
          ResolvedReviewRubric(
            "bill-kotlin-code-review-architecture",
            "architecture specialist rubric",
            area = "architecture",
          ),
        ),
    )
  }
