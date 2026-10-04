package skillbill.engine.featuretask.runner

import skillbill.contracts.time.JvmSystemClock
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.engine.featuretask.slot.REVIEW_FIX_BLOCKER_FINDING_ID
import skillbill.engine.featuretask.slot.harnessPendingVerifyFindingIds
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.infrastructure.workflow.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeVerifyFindingsBodyDeliveryTest {
  @Test
  fun `verify_findings heading selection continues for body delivery without burning the output-gate cap`() {
    val repoRoot = Files.createTempDirectory("skillbill-verify-findings-body-delivery")
    val findingPath = "runtime-kotlin/runtime-application/src/Foo.kt"
    val sourcePath = "runtime-kotlin/runtime-application/agent/history.md"
    val agent = Files.createDirectories(repoRoot.resolve("runtime-kotlin/runtime-application/agent"))
    Files.writeString(
      agent.resolve("history.md"),
      "# Boundary History\n\n## [${LocalDate.now(ZoneOffset.UTC)}] selected-title\n\nselected body sentence\n",
    )
    val headingId =
      FileSystemGoalPlanningContextDiscovery(JvmSystemClock)
        .discoverForFindingPaths(repoRoot, listOf(findingPath), loudFailOnCapExceeded = true)
        .boundaryCatalog
        .single()
        .headingId

    var verifyLaunches = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(repoRoot = repoRoot).copy(
          launcher =
            RuntimeRecordingLauncher { request ->
              val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
              if (phaseId == "verify_findings") {
                verifyLaunches += 1
                val prompt = requireNotNull(request.skillRunRequest.promptOverride)
                if (verifyLaunches == 1) {
                  assertTrue(prompt.contains("selected-title"), "first pass catalogs titles")
                  assertFalse(prompt.contains("selected body sentence"), "first pass withholds bodies")
                  assertFalse(prompt.contains("REJECTED by the schema gate"), "handshake is not a schema rejection")
                } else {
                  assertTrue(prompt.contains("Selected boundary memory"), "second pass delivers bodies")
                  assertTrue(prompt.contains("selected body sentence"), "second pass includes selected body")
                  assertFalse(prompt.contains("REJECTED by the schema gate"), "continuation is not a schema rejection")
                }
                facts(verifyFindingsSelectingBoundary(headingId, sourcePath))
              } else {
                facts(validJsonOutput(phaseId))
              }
            },
        ),
      )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedPhase("simplify", "completed", 1, INVOKED_AGENT, SIMPLIFY_OUTPUT)
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, settledAuditSatisfiedRecord())
    harness.seedReviewPhase("completed", 1, reviewFindingWithLocation(findingPath), 1)
    harnessPendingVerifyFindingIds = listOf(REVIEW_FIX_BLOCKER_FINDING_ID)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(2, verifyLaunches, "heading selection must continue once for body delivery")
    assertTrue(
      harness.io.database.rejectedDiagnostics().none { it.metadata.phaseId == "verify_findings" },
      "body delivery must not record a rejected-output diagnostic",
    )
    val bodyDeliveryContinuations =
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION }
        .mapNotNull { FeatureTaskRuntimeContinuationKind.fromLedgerDetail(it.blockedReason) }
    assertContains(bodyDeliveryContinuations, FeatureTaskRuntimeContinuationKind.VERIFICATION_BODY_DELIVERY)
  }

  @Test
  fun `census-only verify_findings without selected_boundary_headings settles without body-delivery continue`() {
    val findingPath = "runtime-kotlin/runtime-application/src/Foo.kt"
    var verifyLaunches = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(repoRoot = Files.createTempDirectory("skillbill-verify-census-only")).copy(
          launcher =
            RuntimeRecordingLauncher { request ->
              val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
              if (phaseId == "verify_findings") {
                verifyLaunches += 1
                facts(verifyFindingsCensusOnlyOutput(listOf(REVIEW_FIX_BLOCKER_FINDING_ID)))
              } else {
                facts(validJsonOutput(phaseId))
              }
            },
        ),
      )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedPhase("simplify", "completed", 1, INVOKED_AGENT, SIMPLIFY_OUTPUT)
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, settledAuditSatisfiedRecord())
    harness.seedReviewPhase("completed", 1, reviewFindingWithLocation(findingPath), 1)
    harnessPendingVerifyFindingIds = listOf(REVIEW_FIX_BLOCKER_FINDING_ID)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, verifyLaunches, "census-only verify must settle in one launch")
    val bodyDeliveryContinuations =
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION }
        .mapNotNull { FeatureTaskRuntimeContinuationKind.fromLedgerDetail(it.blockedReason) }
    assertFalse(
      bodyDeliveryContinuations.contains(FeatureTaskRuntimeContinuationKind.VERIFICATION_BODY_DELIVERY),
      "census-only verify must not trigger body-delivery continue",
    )
  }
}

private fun reviewFindingWithLocation(locationPath: String): String =
  """
  {
    "contract_version": "0.7",
    "phase_id": "review",
    "status": "completed",
    "summary": "Review produced a validated output.",
    "produced_outputs": {
      "value": "$REVIEW_FIX_BLOCKER_FINDING_ID [blocker] $locationPath:1 $REVIEW_BLOCKER_MESSAGE",
      "findings": [{
        "severity": "blocker",
        "finding_id": "$REVIEW_FIX_BLOCKER_FINDING_ID",
        "message": "$REVIEW_BLOCKER_MESSAGE",
        "location": "$locationPath:1"
      }],
      "blocker_dispositions": []
    }
  }
  """.trimIndent()

private fun verifyFindingsSelectingBoundary(
  headingId: String,
  sourcePath: String,
): String =
  """
  {
    "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
    "phase_id": "verify_findings",
    "status": "completed",
    "summary": "Verified the finding against scoped boundary memory.",
    "verdict": "findings_verified",
    "produced_outputs": {
      "finding_dispositions": [{
        "finding_id": "$REVIEW_FIX_BLOCKER_FINDING_ID",
        "disposition": "verified",
        "selected_boundary_headings": [{
          "heading_id": "$headingId",
          "source_path": "$sourcePath"
        }]
      }]
    }
  }
  """.trimIndent()

private fun verifyFindingsCensusOnlyOutput(verifiedFindingIds: List<String>): String {
  val dispositions =
    verifiedFindingIds.joinToString(",") { findingId ->
      """{"finding_id":"$findingId","disposition":"verified","boundary_context_unavailable":true}"""
    }
  return """
    {
      "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
      "phase_id": "verify_findings",
      "status": "completed",
      "summary": "Verified the finding without boundary heading selection.",
      "verdict": "findings_verified",
      "produced_outputs": {"finding_dispositions": [$dispositions]}
    }
    """.trimIndent()
}
