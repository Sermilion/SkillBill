package skillbill.engine.featuretask.slot.codereview

import skillbill.config.model.PhaseModelDirective
import skillbill.engine.BranchSetupTestConfig
import skillbill.engine.REVIEW_BLOCKER_MESSAGE
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.RuntimeHarnessConfig
import skillbill.engine.RuntimeRecordingLauncher
import skillbill.engine.auditSatisfiedOutput
import skillbill.engine.defaultPhaseOutput
import skillbill.engine.facts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.slot.runner.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.engine.featuretask.slot.scriptedReviewPhaseRunner
import skillbill.engine.phaseIdFromPrompt
import skillbill.engine.runnerHarness
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class InlineReviewLaunchParityTest {
  @Test
  fun `review launches through the phase runner with the stamp sink, edit observer and model override`() {
    val launcher =
      RuntimeRecordingLauncher { request ->
        facts(if (isReviewLaunch(request)) "reviewed\nverdict: approved" else phaseOutput(request))
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = fingerprintedGit()))
          .copy(launcher = launcher, reviewRunner = null),
      )

    harness.runner.run(
      harness.request().copy(
        modelAssignment =
          FeatureTaskRuntimeModelAssignment(
            perPhaseDirectives = mapOf("review" to PhaseModelDirective("review-model")),
          ),
      ),
    )

    val review = launcher.requests.single(::isReviewLaunch).skillRunRequest
    assertEquals("review-model", review.modelOverride)
    assertNotSame(AgentRunActivityStampSink.NONE, review.activityStampSink)
    assertNotSame(AgentRunWorktreeEditObserver.NONE, review.worktreeEditObserver)
    assertFalse(review.readOnlyPhase)
    assertNull(review.progressIdleTimeout)
  }

  @Test
  fun `verify_findings stays a read-only launch with the thirty minute idle timeout`() {
    var reviews = 0
    val launcher = RuntimeRecordingLauncher { request -> facts(phaseOutput(request)) }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = fingerprintedGit()))
          .copy(
            launcher = launcher,
            reviewRunner =
              scriptedReviewPhaseRunner {
                reviews += 1
                if (reviews == 1) {
                  "- [F-001] Blocker | High | Foo.kt:1 | $REVIEW_BLOCKER_MESSAGE\nverdict: changes_requested"
                } else {
                  "verdict: approved"
                }
              },
          ),
      )

    harness.runner.run(harness.request())

    val verify =
      launcher.requests
        .map { it.skillRunRequest }
        .first { phaseIdFromPrompt(requireNotNull(it.promptOverride)) == "verify_findings" }
    assertTrue(verify.readOnlyPhase)
    assertEquals(READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes, verify.progressIdleTimeout)
  }

  private fun phaseOutput(request: GoalRunnerSubtaskLaunchRequest): String =
    if (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride)) == "audit") {
      auditSatisfiedOutput()
    } else {
      defaultPhaseOutput(request)
    }

  private fun fingerprintedGit() = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "fp-1" }

  private fun isReviewLaunch(request: GoalRunnerSubtaskLaunchRequest): Boolean =
    request.skillRunRequest.promptOverride.orEmpty().startsWith("Review the last commit")
}
