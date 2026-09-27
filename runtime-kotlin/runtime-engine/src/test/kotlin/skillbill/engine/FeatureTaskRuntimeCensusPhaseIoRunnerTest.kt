package skillbill.engine

import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.slot.scriptedReviewPhaseRunner
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeCensusPhaseIoRunnerTest {
  @Test
  fun `census verify with extra keys and census fix with finding_ref alias complete the loop`() {
    val harness =
      goalCensusHarness(
        findings = listOf(blockerFinding(REVIEW_FIX_BLOCKER_FINDING_ID)),
        verifyOutput = fatVerifiedCensus(REVIEW_FIX_BLOCKER_FINDING_ID),
        implementFixOutput = validJsonOutput("implement_fix").replace("\"finding_id\"", "\"finding_ref\""),
      )

    val report = runInline(harness)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "verify_findings" })
    assertEquals(1, launched.count { it == "implement_fix" })
    assertTrue(launched.indexOf("implement_fix") < launched.indexOf("validate"))
    assertTrue(
      harness.io.database.rejectedDiagnostics().none {
        it.metadata.phaseId == "verify_findings" || it.metadata.phaseId == "implement_fix"
      },
    )
  }

  @Test
  fun `findings_verified with zero verified rows launches implement_fix covered by empty entries`() {
    val harness =
      goalCensusHarness(
        findings = listOf(blockerFinding(REVIEW_FIX_BLOCKER_FINDING_ID)),
        verifyOutput =
          verifyCensus(
            verdict = "findings_verified",
            dispositions = listOf(disposition(REVIEW_FIX_BLOCKER_FINDING_ID, "rejected")),
          ),
        implementFixOutput = emptyCensusFix(),
      )

    val report = runInline(harness)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "implement_fix" })
    assertTrue(launched.contains("validate"))
  }

  @Test
  fun `no_findings_verified skips implement_fix even when the census has verified rows`() {
    val harness =
      seededVerifyHarness(
        verifyOutput =
          verifyCensus(
            verdict = "no_findings_verified",
            dispositions = listOf(disposition(REVIEW_FIX_BLOCKER_FINDING_ID, "verified")),
          ),
      )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "verify_findings" })
    assertFalse(launched.contains("implement_fix"))
    assertTrue(launched.contains("validate"))
  }

  @Test
  fun `omitted review finding id blocks verify without launching implement_fix`() {
    val harness =
      seededVerifyHarness(
        verifyOutput =
          verifyCensus(
            verdict = "findings_verified",
            dispositions = emptyList(),
          ),
      )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("verify_findings", blocked.lastIncompletePhase)
    assertTrue(
      blocked.blockedReason.contains("omitted") ||
        harness.io.database.rejectedDiagnostics().any {
          it.metadata.phaseId == "verify_findings" && it.metadata.reason.contains("omitted")
        },
    )
    assertFalse(harness.launchedPromptPhaseOrder().contains("implement_fix"))
  }

  @Test
  fun `legacy 0_2 repair receipt blocks implement_fix`() {
    val harness =
      seededVerifyHarness(
        verifyOutput =
          verifyCensus(
            verdict = "findings_verified",
            dispositions = listOf(disposition(REVIEW_FIX_BLOCKER_FINDING_ID, "verified")),
          ),
        implementFixOutput =
          censusFix(
            findingId = REVIEW_FIX_BLOCKER_FINDING_ID,
            receiptContractVersion = "0.2",
          ),
      )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("implement_fix", blocked.lastIncompletePhase, blocked.blockedReason)
    assertContains(blocked.blockedReason, "cap=1")
    assertTrue(
      harness.io.database.rejectedDiagnostics().any { it.metadata.phaseId == "implement_fix" },
    )
    assertFalse(harness.launchedPromptPhaseOrder().contains("validate"))
  }

  @Test
  fun `omitted carried finding blocks implement_fix coverage`() {
    val harness =
      goalCensusHarness(
        findings = listOf(blockerFinding(REVIEW_FIX_BLOCKER_FINDING_ID)),
        verifyOutput =
          verifyCensus(
            verdict = "findings_verified",
            dispositions = listOf(disposition(REVIEW_FIX_BLOCKER_FINDING_ID, "verified")),
          ),
        implementFixOutput = emptyCensusFix(),
      )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(runInline(harness))

    assertEquals("implement_fix", blocked.lastIncompletePhase, blocked.blockedReason)
    assertTrue(
      blocked.blockedReason.contains("repair-receipt") ||
        blocked.blockedReason.contains("omitted") ||
        harness.io.database.rejectedDiagnostics().any { it.metadata.phaseId == "implement_fix" },
    )
    assertFalse(harness.launchedPromptPhaseOrder().contains("validate"))
  }

  @Test
  fun `refuted finding is not owed on the repair receipt and lands on the ledger from review identity`() {
    val refutedId = "F-002"
    val harness =
      goalCensusHarness(
        findings =
          listOf(
            blockerFinding(REVIEW_FIX_BLOCKER_FINDING_ID),
            nitFinding(refutedId),
          ),
        verifyOutput =
          verifyCensus(
            verdict = "findings_verified",
            dispositions =
              listOf(
                disposition(REVIEW_FIX_BLOCKER_FINDING_ID, "verified"),
                disposition(refutedId, "rejected", reason = "False positive against spec intent."),
              ),
          ),
        implementFixOutput = censusFix(REVIEW_FIX_BLOCKER_FINDING_ID),
      )

    val report = runInline(harness)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement_fix" })
    val rejected = harness.ledgerRows.single { it.findingId == refutedId }
    assertEquals(UNADDRESSED_FINDING_REJECTED_DISPOSITION, rejected.verificationDisposition)
    assertEquals("nit", rejected.severity)
    assertEquals("Bar.kt:1", rejected.location)
    assertEquals(NIT_MESSAGE, rejected.summary)
    assertEquals("False positive against spec intent.", rejected.verificationReason)
  }

  private fun runInline(harness: RunnerHarness): FeatureTaskRuntimeRunReport =
    harness.runner.run(harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE))

  private fun seededVerifyHarness(
    verifyOutput: String,
    implementFixOutput: String? = null,
  ): RunnerHarness {
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "before-fix" }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          branchSetup = BranchSetupTestConfig(gitOperations = git),
          repoRoot = Files.createTempDirectory("skillbill-census-seeded"),
        ).copy(
          launcher =
            RuntimeRecordingLauncher { request ->
              val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
              when (phaseId) {
                "verify_findings" -> facts(verifyOutput)
                "implement_fix" -> {
                  git.repositoryFingerprintValue = "after-fix"
                  facts(implementFixOutput ?: validJsonOutput(phaseId))
                }
                else -> facts(validJsonOutput(phaseId))
              }
            },
          validator = realFeatureTaskRuntimePhaseOutputValidator,
        ),
      )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("simplify", "completed", 1, INVOKED_AGENT, SIMPLIFY_OUTPUT)
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, auditSatisfiedOutput())
    harness.seedReviewPhase("completed", 1, seededReviewFinding(), 1)
    harnessPendingVerifyFindingIds = listOf(REVIEW_FIX_BLOCKER_FINDING_ID)
    return harness
  }

  private fun goalCensusHarness(
    findings: List<String>,
    verifyOutput: String,
    implementFixOutput: String,
  ): RunnerHarness {
    val repoRoot = Files.createTempDirectory("skillbill-census-goal")
    val git =
      RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
        .also { it.headCommitShaValue = "f".repeat(40) }
        .also { it.repositoryFingerprintValue = "before-fix" }
    return runnerHarness(
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        goalContinuation =
          FeatureTaskRuntimeGoalContinuationContext(
            parentIssueKey = "SKILL-65",
            subtaskId = 5,
            goalBranch = "feat/existing-runtime-branch",
            suppressPr = true,
            parentWorkflowId = "wfl-parent",
            reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
          ),
        reviewRunner = scriptedReviewPhaseRunner { findings.joinToString("\n") },
      ).copy(
        launcher =
          RuntimeRecordingLauncher { request ->
            val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
            when (phaseId) {
              "verify_findings" -> facts(verifyOutput)
              "implement_fix" -> {
                git.repositoryFingerprintValue = "after-fix"
                git.goalReviewTrackedDelta = "census-fix\n"
                facts(implementFixOutput)
              }
              else -> facts(validJsonOutput(phaseId))
            }
          },
        validator = realFeatureTaskRuntimePhaseOutputValidator,
        agentAssignment = phasePerAgentAssignment(),
      ),
    ).also { harness ->
      harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
      harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
      harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
      harness.seedPhase("simplify", "completed", 1, INVOKED_AGENT, SIMPLIFY_OUTPUT)
      harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, auditSatisfiedOutput())
    }
  }
}

private const val NIT_MESSAGE = "Hourly selection is never read"

private fun blockerFinding(findingId: String) = "- [$findingId] Blocker | High | Foo.kt:1 | $REVIEW_BLOCKER_MESSAGE"

private fun nitFinding(findingId: String) = "- [$findingId] Nit | High | Bar.kt:1 | $NIT_MESSAGE"

private fun seededReviewFinding(): String =
  """
  {
    "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
    "phase_id": "review",
    "status": "completed",
    "summary": "Review produced a validated output.",
    "produced_outputs": {
      "findings": [{
        "severity": "blocker",
        "finding_id": "$REVIEW_FIX_BLOCKER_FINDING_ID",
        "message": "$REVIEW_BLOCKER_MESSAGE",
        "location": "Foo.kt:1"
      }],
      "blocker_dispositions": []
    }
  }
  """.trimIndent()

internal fun disposition(
  findingId: String,
  disposition: String,
  reason: String? = null,
): String {
  val reasonField = reason?.let { ""","reason":"$it"""" }.orEmpty()
  return """{"finding_id":"$findingId","disposition":"$disposition","boundary_context_unavailable":true$reasonField}"""
}

private fun verifyCensus(
  verdict: String,
  dispositions: List<String>,
  extraProduced: String = "",
): String {
  val extra = if (extraProduced.isEmpty()) "" else ",$extraProduced"
  return """
    {
      "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
      "phase_id": "verify_findings",
      "status": "completed",
      "summary": "Verified findings.",
      "verdict": "$verdict",
      "produced_outputs": {
        "finding_dispositions": [${dispositions.joinToString(",")}]
        $extra
      }
    }
    """.trimIndent()
}

private fun fatVerifiedCensus(findingId: String): String =
  verifyCensus(
    verdict = "findings_verified",
    dispositions =
      listOf(
        """{"finding_id":"$findingId","disposition":"verified","boundary_context_unavailable":true,""" +
          """"reason":"ignored","severity":"major","location":"ignored.kt","message":"ignored"}""",
      ),
    extraProduced = """"legacy_sibling":"ignored"""",
  )

private fun censusFix(
  findingId: String,
  outcome: String = "addressed",
  receiptContractVersion: String = "0.3",
): String =
  """
  {
    "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
    "phase_id": "implement_fix",
    "status": "completed",
    "summary": "Fixed findings.",
    "produced_outputs": {
      "repair_receipt": {
        "contract_version": "$receiptContractVersion",
        "entries": [{
          "finding_id": "$findingId",
          "outcome": "$outcome"
        }]
      },
      "reconciled_state": {"reconciled": true, "evidence": "Fixture tree at target state."}
    }
  }
  """.trimIndent()

private fun emptyCensusFix(): String =
  """
  {
    "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
    "phase_id": "implement_fix",
    "status": "completed",
    "summary": "No carried findings to repair.",
    "produced_outputs": {
      "repair_receipt": {"contract_version": "0.3", "entries": []},
      "reconciled_state": {"reconciled": true, "evidence": "Fixture tree at target state."}
    }
  }
  """.trimIndent()
