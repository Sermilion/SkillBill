package skillbill.engine.featuretask.runner

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeVerifyFindingsFailureTest.Failure
import skillbill.engine.featuretask.slot.REVIEW_FIX_BLOCKER_FINDING_ID
import skillbill.engine.featuretask.slot.harnessPendingVerifyFindingIds
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeVerifyFindingsFailureTest {
  enum class Failure { EMPTY_OUTPUT, PROCESS_FAILURE, BLOCKED_OUTPUT, RUNTIME_ERROR, PERSISTED_BLOCK }

  @ParameterizedTest
  @EnumSource(Failure::class)
  fun `verification failures retain the finding and advance without recording a block`(failure: Failure) {
    val errors = mutableListOf<String>()
    val diagnostics =
      object : RuntimeDiagnostics {
        override fun info(message: String) = Unit

        override fun warning(
          message: String,
          error: Throwable?,
        ) = Unit

        override fun error(
          message: String,
          error: Throwable?,
        ) {
          errors += message
        }
      }
    val harness = failureHarness(failure, diagnostics)
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedPhase("simplify", "completed", 1, INVOKED_AGENT, SIMPLIFY_OUTPUT)
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, settledAuditSatisfiedRecord())
    harness.seedReviewPhase("completed", 1, REVIEW_OUTPUT, 1)
    harnessPendingVerifyFindingIds = listOf(REVIEW_FIX_BLOCKER_FINDING_ID)
    if (failure == Failure.PERSISTED_BLOCK) {
      harness.seedBlockedPhase(
        "verify_findings",
        2,
        INVOKED_AGENT,
        "persisted selected headings changed",
        FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
      )
    }
    val priorBlocks =
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty().count {
        it.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
      }

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertEquals(
      if (failure == Failure.PERSISTED_BLOCK) 0 else 1,
      harness.launchedPromptPhaseOrder().count {
        it == "verify_findings"
      },
    )
    assertContains(harness.launchedPromptPhaseOrder(), "implement_fix")
    assertTrue(
      errors.any {
        "phase=verify_findings" in it && "value_used=original_review_findings" in it
      },
      errors.toString(),
    )
    assertEquals(
      priorBlocks,
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty().count {
        it.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
      },
    )
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).getValue("verify_findings")
    assertEquals("completed", record.status.wireValue)
    assertEquals(null, record.blockedReason)
    val dispositions =
      FeatureTaskRuntimeOutputVerification.dispositionsFrom(
        NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
          requireNotNull(record.outputArtifact),
          "verify_findings",
        ).envelopeWireMap(),
      )
    assertEquals(listOf(REVIEW_FIX_BLOCKER_FINDING_ID), dispositions.map { it.findingId })
    assertTrue(dispositions.all { it.disposition == FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED })
    assertTrue(dispositions.all { it.reason?.contains("unavailable") == true })
  }
}

private val REVIEW_OUTPUT =
  """
  {
    "contract_version": "0.7",
    "phase_id": "review",
    "status": "completed",
    "summary": "Review found a defect.",
    "produced_outputs": {
      "value": "$REVIEW_FIX_BLOCKER_FINDING_ID [blocker] src/Foo.kt:1 $REVIEW_BLOCKER_MESSAGE",
      "findings": [{
        "severity": "blocker",
        "finding_id": "$REVIEW_FIX_BLOCKER_FINDING_ID",
        "message": "$REVIEW_BLOCKER_MESSAGE",
        "location": "src/Foo.kt:1"
      }],
      "blocker_dispositions": []
    }
  }
  """.trimIndent()

private fun failureHarness(
  failure: Failure,
  diagnostics: RuntimeDiagnostics,
): RunnerHarness {
  val harness =
    runnerHarness(
      RuntimeHarnessConfig(
        repoRoot = Files.createTempDirectory("skillbill-verify-failure"),
        branchSetup =
          BranchSetupTestConfig(
            gitOperations = RecordingWorkflowGitOperations().apply { headCommitShaValue = "f".repeat(40) },
          ),
        diagnostics = diagnostics,
        launcher =
          RuntimeRecordingLauncher { request ->
            val phase = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
            if (phase != "verify_findings") {
              facts(validJsonOutput(phase))
            } else {
              when (failure) {
                Failure.EMPTY_OUTPUT -> facts("")
                Failure.PROCESS_FAILURE -> spawnFailedFacts()
                Failure.BLOCKED_OUTPUT ->
                  facts(
                    validJsonOutput(phase).replace("\"status\": \"completed\"", "\"status\": \"blocked\""),
                  )
                Failure.RUNTIME_ERROR -> throw SkillBillRuntimeException(
                  "verification evidence unavailable",
                )
                Failure.PERSISTED_BLOCK ->
                  error(
                    "a persisted verification block must continue without relaunching",
                  )
              }
            }
          },
      ),
    )
  return harness
}
