package skillbill.engine

import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditAcListRetryTest {
  private val remainingHint = "- AC-002. Missing meaningful test coverage."

  @Test
  fun `nonempty then empty audit retries only the unresolved criteria`() {
    var auditLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        if (phaseIdFromPrompt(prompt) != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditLaunches += 1
        if (auditLaunches == 1) {
          CRITERIA.forEach { assertContains(prompt, it) }
        } else {
          val scopedCriteria =
            prompt.substringAfter("acceptance_criteria:")
              .substringBefore("mandates_and_overrides:")
          assertContains(scopedCriteria, remainingHint)
          assertFalse(scopedCriteria.contains("AC-001. First criterion."))
        }
        assertContains(prompt, "remaining acceptance criteria")
        when (auditLaunches) {
          1 -> {
            assertFalse(prompt.contains(remainingHint))
            facts(auditRemainingAcOutput(remainingHint))
          }
          2 -> {
            val focusSection =
              prompt
                .substringAfter("## Prior audit focus hint (remaining criteria only)")
                .substringBefore("\n## ")
            assertEquals(remainingHint, focusSection.lineSequence().last { it.isNotBlank() }.trim())
            facts(auditSatisfiedOutput())
          }
          else -> error("unexpected audit launch $auditLaunches")
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          acceptanceCriteria = CRITERIA,
          launcher = launcher,
          validator = realFeatureTaskRuntimePhaseOutputValidator,
        ),
      )
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(2, auditLaunches)
    assertTrue("implement" !in harness.launchedPromptPhaseOrder().dropWhile { it != "audit" }.drop(1))
    assertTrue(harness.launchOrder().indexOf("review") > harness.launchOrder().lastIndexOf("audit"))
  }

  @Test
  fun `audit prompt requests remaining criteria only as final response`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    seedPlanningUpstreamPhases(harness)
    harness.runner.run(harness.request())
    val auditPrompt =
      harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .first { phaseIdFromPrompt(it) == "audit" }
    assertContains(auditPrompt, "remaining acceptance criteria")
    assertContains(auditPrompt, "explicit empty list")
    assertContains(auditPrompt, "re-check the entire in-scope criterion list from the beginning")
    assertContains(auditPrompt, "Do not spawn subagents")
  }

  @Test
  fun `process failure overrides an empty result and never retries or advances review`() {
    val outcomes =
      listOf(
        "nonzero exit" to auditFacts(auditSatisfiedOutput(), AgentRunTermination.Exited(1)),
        "timeout" to auditFacts(auditSatisfiedOutput(), AgentRunTermination.TimedOut),
        "interruption" to auditFacts(auditSatisfiedOutput(), AgentRunTermination.Interrupted),
        "missing final response" to
          auditFacts(auditSatisfiedOutput().replace("\"value\": \"[]\"", "\"value_missing\": \"yes\"")),
        "whitespace-only final response" to
          auditFacts(auditSatisfiedOutput().replace("\"value\": \"[]\"", "\"value\": \"   \"")),
      )

    outcomes.forEach { (label, outcome) ->
      var auditLaunches = 0
      val launcher =
        RuntimeRecordingLauncher { request ->
          val prompt = requireNotNull(request.skillRunRequest.promptOverride)
          if (phaseIdFromPrompt(prompt) != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          auditLaunches += 1
          outcome
        }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            launcher = launcher,
            validator = realFeatureTaskRuntimePhaseOutputValidator,
          ),
        )

      val report = harness.runner.run(harness.request())
      val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, label)
      assertEquals(1, auditLaunches, label)
      assertTrue(blocked.blockedReason.contains("audit"), label)
      assertTrue("review" !in harness.launchOrder(), label)
    }
  }

  @Test
  fun `invented audit verdict is ignored and remaining criteria still retry`() {
    var auditLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        if (phaseIdFromPrompt(prompt) != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditLaunches += 1
        when (auditLaunches) {
          1 ->
            facts(
              auditRemainingAcOutput(remainingHint).trimEnd().removeSuffix("}") +
                """, "verdict": "remediation_required" }""",
            )
          2 -> facts(auditSatisfiedOutput())
          else -> error("unexpected audit launch $auditLaunches")
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          acceptanceCriteria = CRITERIA,
          launcher = launcher,
          validator = realFeatureTaskRuntimePhaseOutputValidator,
        ),
      )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(2, auditLaunches)
  }

  @Test
  fun `completed audit retries accept arbitrary nonempty final text`() {
    listOf(
      "- AC-002 still open",
      "1. AC-002 still open",
      "AC-002 remains unresolved because the assertion is missing",
    ).forEach { remainingText ->
      var auditLaunches = 0
      val launcher =
        RuntimeRecordingLauncher { request ->
          val prompt = requireNotNull(request.skillRunRequest.promptOverride)
          if (phaseIdFromPrompt(prompt) != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          auditLaunches += 1
          if (auditLaunches == 1) facts(auditRemainingAcOutput(remainingText)) else facts(auditSatisfiedOutput())
        }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            launcher = launcher,
            validator = realFeatureTaskRuntimePhaseOutputValidator,
          ),
        )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()), remainingText)
      assertEquals(2, auditLaunches, remainingText)
    }
  }

  @Test
  fun `resume starts a fresh audit without the interrupted retry hint`() {
    var auditLaunches = 0
    val auditPrompts = mutableListOf<String>()
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        if (phaseIdFromPrompt(prompt) != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        auditPrompts += prompt
        auditLaunches += 1
        when (auditLaunches) {
          1 -> facts(auditRemainingAcOutput(remainingHint))
          2 -> auditFacts(auditSatisfiedOutput(), AgentRunTermination.Exited(1))
          else -> facts(auditSatisfiedOutput())
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = launcher,
          validator = realFeatureTaskRuntimePhaseOutputValidator,
        ),
      )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(3, auditPrompts.size)
    assertContains(auditPrompts[1], remainingHint)
    assertFalse(auditPrompts[2].contains("Prior audit focus hint"))
  }

  @Test
  fun `each completed audit round checkpoints before retry and review`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    var auditLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement" || phaseId == "audit") {
          git.worktreeStatusValue = " M src/AuditRepair.kt"
          git.ownedPathsValue = listOf("src/AuditRepair.kt")
        }
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            if (auditLaunches == 1) facts(auditRemainingAcOutput(remainingHint)) else facts(auditSatisfiedOutput())
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          branchSetup = BranchSetupTestConfig(gitOperations = git),
          launcher = launcher,
          validator = realFeatureTaskRuntimePhaseOutputValidator,
        ),
      )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(1, git.createCommitMessages.size, git.createCommitMessages.toString())
    assertEquals(2, git.amendCommitMessages.size, git.amendCommitMessages.toString())
    assertEquals(2, auditLaunches)
    assertTrue(harness.launchOrder().indexOf("review") > harness.launchOrder().lastIndexOf("audit"))
  }

  @Test
  fun `audit commit failure blocks before retry or review`() {
    val git =
      RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch").also {
        it.createCommitResult =
          WorkflowGitCommitResult.Failed(
            error = "audit commit failed",
          )
      }
    var auditLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement" || phaseId == "audit") {
          git.worktreeStatusValue = " M src/AuditRepair.kt"
          git.ownedPathsValue = listOf("src/AuditRepair.kt")
        }
        if (phaseId == "audit") {
          auditLaunches += 1
          facts(auditRemainingAcOutput(remainingHint))
        } else {
          facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          branchSetup = BranchSetupTestConfig(gitOperations = git),
          launcher = launcher,
          validator = realFeatureTaskRuntimePhaseOutputValidator,
        ),
      )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals(1, auditLaunches)
    assertContains(blocked.blockedReason, "audit commit failed")
    assertTrue("review" !in harness.launchOrder())
  }

  private fun auditFacts(
    stdout: String,
    termination: AgentRunTermination = AgentRunTermination.Exited(0),
  ): AgentRunLaunchFacts =
    agentRunLaunchFacts(
      agent = SupportedAgent.CLAUDE,
      termination = termination,
      stdout = stdout,
      stderr = "",
    )

  private fun seedPlanningUpstreamPhases(harness: RunnerHarness) {
    harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, "claude", IMPLEMENT_OUTPUT)
  }

  private companion object {
    val CRITERIA =
      listOf(
        "AC-001. First criterion.",
        "AC-002. Second criterion.",
      )
  }
}
