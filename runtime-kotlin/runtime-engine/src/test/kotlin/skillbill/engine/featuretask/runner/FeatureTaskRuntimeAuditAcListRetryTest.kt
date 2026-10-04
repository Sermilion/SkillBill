package skillbill.engine.featuretask.runner

import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.slot.ApprovingReviewPhaseRunner
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditAcListRetryTest {
  private val remainingHint = "- AC-002. Missing production admission before recovery."

  @Test
  fun `unfinished audit repair continues before audit runs again`() {
    var auditLaunches = 0
    var repairLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            if (auditLaunches ==
              1
            ) {
              facts(auditRemainingAcOutput(remainingHint))
            } else {
              facts(auditSatisfiedOutput())
            }
          }
          "audit_implement_fix" -> {
            repairLaunches += 1
            if (repairLaunches == 1) {
              facts(
                defaultPhaseOutput(request).replace(
                  "audit_repair_complete: true",
                  "Deliberately not claiming audit_repair_complete: true",
                ),
              )
            } else {
              facts(defaultPhaseOutput(request))
            }
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher = launcher,
        ),
      )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(2, auditLaunches)
    assertEquals(2, repairLaunches)
    assertEquals(
      listOf("audit", "audit_implement_fix", "audit_implement_fix", "audit"),
      harness.launchedPromptPhaseOrder().filter { it == "audit" || it == "audit_implement_fix" },
    )
    val repairPrompts =
      harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .filter { phaseIdFromPrompt(it) == "audit_implement_fix" }
    assertContains(repairPrompts.last(), "Deliberately not claiming")
  }

  @Test
  fun `retryable audit repair keeps fixing without another audit or operator resume`() {
    var auditLaunches = 0
    var repairLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches == 1) auditRemainingAcOutput(remainingHint) else auditSatisfiedOutput())
          }
          "audit_implement_fix" -> {
            repairLaunches += 1
            if (repairLaunches ==
              1
            ) {
              facts(terminalRepairOutput("retryable"))
            } else {
              facts(defaultPhaseOutput(request))
            }
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(launcher = launcher),
      )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(2, auditLaunches)
    assertEquals(2, repairLaunches)
    assertEquals(
      listOf("audit", "audit_implement_fix", "audit_implement_fix", "audit"),
      harness.launchedPromptPhaseOrder().filter { it == "audit" || it == "audit_implement_fix" },
    )
    val repairPrompts =
      harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .filter { phaseIdFromPrompt(it) == "audit_implement_fix" }
    assertContains(repairPrompts.last(), "Runner removal remains unfinished")
  }

  @Test
  fun `audit repair stops for a concrete operator blocker`() {
    var repairLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> facts(auditRemainingAcOutput(remainingHint))
          "audit_implement_fix" -> {
            repairLaunches += 1
            facts(
              terminalRepairOutput(
                "needs_user_action",
                "Required external schema is unavailable; operator must provide it.",
              ),
            )
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(launcher = launcher),
      )

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit_implement_fix", report.lastIncompletePhase)
    assertContains(report.blockedReason, "operator must provide it")
    assertEquals(1, repairLaunches)
    assertTrue("review" !in harness.launchOrder())
  }

  @Test
  fun `repeated retryable audit repair failures exhaust the failure budget`() {
    var repairLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> facts(auditRemainingAcOutput(remainingHint))
          "audit_implement_fix" -> {
            repairLaunches += 1
            check(repairLaunches <= 10) { "Repair retry budget did not stop the loop." }
            facts(terminalRepairOutput("retryable"))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(launcher = launcher),
      )

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit_implement_fix", report.lastIncompletePhase)
    assertEquals(3, repairLaunches)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "audit" })
    assertTrue("review" !in harness.launchOrder())
  }

  private fun terminalRepairOutput(
    disposition: String,
    value: String = "Runner removal remains unfinished",
  ) = """{"contract_version":"0.7","phase_id":"audit_implement_fix","status":"blocked",""" +
    """"summary":"$value","failure_disposition":"$disposition","produced_outputs":{"value":"$value"}}"""

  @Test
  fun `audit blocks when an unresolved repair recheck stays unshrunk past the cap`() {
    var auditLaunches = 0
    var repairLaunches = 0
    val warnings = mutableListOf<String>()
    val diagnostics =
      object : RuntimeDiagnostics {
        override fun warning(
          message: String,
          error: Throwable?,
        ) {
          warnings += message
        }

        override fun error(
          message: String,
          error: Throwable?,
        ) = Unit
      }
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        when (phaseIdFromPrompt(prompt)) {
          "audit" -> {
            auditLaunches += 1
            CRITERIA.forEach { assertContains(prompt, it) }
            assertTrue(request.skillRunRequest.readOnlyPhase)
            assertEquals("configured-reasoner", request.skillRunRequest.modelOverride)
            assertEquals("high", request.skillRunRequest.effortOverride)
            facts(auditRemainingAcOutput(remainingHint))
          }
          "audit_implement_fix" -> {
            repairLaunches += 1
            assertContains(prompt, remainingHint)
            assertFalse(request.skillRunRequest.readOnlyPhase)
            assertEquals("configured-implementer", request.skillRunRequest.modelOverride)
            assertEquals("medium", request.skillRunRequest.effortOverride)
            facts(defaultPhaseOutput(request))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          acceptanceCriteria = CRITERIA,
          launcher = launcher,
          diagnostics = diagnostics,
        ),
      )
    val matrix =
      ExecutionMatrix(
        agents =
          mapOf(
            SupportedAgent.CLAUDE to
              mapOf(
                ExecutionTier.REASONING to PhaseModelDirective("configured-reasoner", "high"),
                ExecutionTier.IMPLEMENTATION to PhaseModelDirective("configured-implementer", "medium"),
              ),
          ),
      )
    val report =
      harness.runner.run(
        harness.request().copy(
          modelAssignment = FeatureTaskRuntimeModelAssignment(matrix = matrix),
          agentAssignment = FeatureTaskRuntimeAgentAssignment(override = "claude"),
        ),
      )
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, report.toString())
    assertEquals(4, auditLaunches)
    assertEquals(3, repairLaunches)
    assertEquals(0, warnings.count { "audit_repair" in it && "warning threshold" in it })
    assertEquals(
      listOf("audit", "audit_implement_fix", "audit", "audit_implement_fix", "audit", "audit_implement_fix", "audit"),
      harness.launchedPromptPhaseOrder().filter {
        it == "audit" || it == "audit_implement_fix"
      },
    )
    assertTrue("review" !in harness.launchOrder())
  }

  @Test
  fun `audit repair reenters when JSON aliases identify a shrinking remaining list`() {
    var auditLaunches = 0
    var repairLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            when (auditLaunches) {
              1 -> facts(auditRemainingAcOutput("AC-001 remains; AC-002 remains"))
              2 ->
                facts(
                  auditRemainingAcOutput(
                    """[{"criterion_id":"AC-002","criterion":"S3-AC6. Guard misses helper access"}]""",
                  ),
                )
              else -> facts(auditSatisfiedOutput())
            }
          }
          "audit_implement_fix" -> {
            repairLaunches += 1
            facts(defaultPhaseOutput(request))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          acceptanceCriteria = listOf("S3-AC3. First criterion.", "S3-AC6. Second criterion."),
          launcher = launcher,
        ),
      )

    val report = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(3, auditLaunches)
    assertEquals(2, repairLaunches)
    assertTrue(harness.launchOrder().indexOf("review") > harness.launchOrder().lastIndexOf("audit"))
  }

  @Test
  fun `audit refuses a report that reopens previously satisfied criteria`() {
    var auditLaunches = 0
    var repairLaunches = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            if (auditLaunches == 1) {
              facts(auditRemainingAcOutput("AC-001 remains"))
            } else {
              facts(auditRemainingAcOutput("AC-001 remains; AC-002 remains"))
            }
          }
          "audit_implement_fix" -> {
            repairLaunches += 1
            facts(defaultPhaseOutput(request))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          acceptanceCriteria = CRITERIA,
          launcher = launcher,
        ),
      )

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertContains(report.toString(), "schema-invalid output")
    assertEquals(2, auditLaunches)
    assertEquals(1, repairLaunches)
    assertTrue("review" !in harness.launchOrder())
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
    assertContains(auditPrompt, "no production criteria remain")
    assertContains(auditPrompt, "inspect only the unresolved criteria in the last accepted audit report")
    assertContains(auditPrompt, "Do not spawn subagents")
  }

  @Test
  fun `process failure overrides an empty result and never retries or advances review`() {
    val outcomes =
      listOf(
        "satisfied with remaining criteria" to
          auditFacts(
            auditRemainingAcOutput(remainingHint).trimEnd().removeSuffix("}") +
              """, "verdict": "satisfied" }""",
          ),
        "nonzero exit" to auditFacts(auditSatisfiedOutput(), AgentRunTermination.Exited(1)),
        "timeout" to auditFacts(auditSatisfiedOutput(), AgentRunTermination.TimedOut),
        "interruption" to auditFacts(auditSatisfiedOutput(), AgentRunTermination.Interrupted),
        "missing final response" to
          auditFacts(
            auditSatisfiedOutput().replace("\"value\": \"$AUDIT_SATISFIED_VALUE\"", "\"value_missing\": \"yes\""),
          ),
        "whitespace-only final response" to
          auditFacts(auditSatisfiedOutput().replace("\"value\": \"$AUDIT_SATISFIED_VALUE\"", "\"value\": \"   \"")),
      )

    outcomes.forEach { (label, outcome) ->
      var auditLaunches = 0
      val launcher =
        RuntimeRecordingLauncher { request ->
          val prompt = requireNotNull(request.skillRunRequest.promptOverride)
          if (phaseIdFromPrompt(prompt) !=
            "audit"
          ) {
            return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          }
          auditLaunches += 1
          outcome
        }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            launcher = launcher,
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
        if (phaseIdFromPrompt(prompt) !=
          "audit"
        ) {
          return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        }
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
          if (phaseIdFromPrompt(prompt) !=
            "audit"
          ) {
            return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          }
          auditLaunches += 1
          if (auditLaunches ==
            1
          ) {
            facts(auditRemainingAcOutput(remainingText))
          } else {
            facts(auditSatisfiedOutput())
          }
        }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            launcher = launcher,
          ),
        )

      val report = harness.runner.run(harness.request())
      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
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
        if (phaseIdFromPrompt(prompt) !=
          "audit"
        ) {
          return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
        }
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
        ),
      )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(3, auditPrompts.size)
    assertFalse(auditPrompts[1].contains("Prior audit focus hint"))
    assertFalse(auditPrompts[2].contains("Prior audit focus hint"))
  }

  @Test
  fun `each completed audit round checkpoints before retry and review`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    var auditLaunches = 0
    var headBeforeGap = ""
    var repairCheckpoint = ""
    var reviewObserved = false
    val reviewRunner =
      object : PhaseRunner {
        override fun run(
          input: PhaseStepInput,
          state: PhaseLaunchState,
        ): PhaseStepOutput {
          assertTrue(repairCheckpoint.isNotBlank())
          assertTrue(git.headCommitShaValue != repairCheckpoint, "Review must see the committed repair.")
          reviewObserved = true
          return ApprovingReviewPhaseRunner.run(input, state)
        }
      }
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit_implement_fix") {
          assertTrue(git.headCommitShaValue.isNotBlank())
          assertTrue(git.headCommitShaValue != headBeforeGap, "Repair must start after its checkpoint.")
          repairCheckpoint = git.headCommitShaValue
        }
        if (phaseId == "implement" || phaseId == "audit_implement_fix") {
          git.worktreeStatusValue = " M src/AuditRepair.kt"
          git.ownedPathsValue = listOf("src/AuditRepair.kt")
        }
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            if (auditLaunches == 1) headBeforeGap = git.headCommitShaValue
            if (auditLaunches ==
              1
            ) {
              facts(auditRemainingAcOutput(remainingHint))
            } else {
              facts(auditSatisfiedOutput())
            }
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          branchSetup = BranchSetupTestConfig(gitOperations = git),
          reviewRunner = reviewRunner,
          launcher = launcher,
        ),
      )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertTrue(reviewObserved)
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
        if (phaseId == "implement" || phaseId == "audit_implement_fix") {
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
