package skillbill.engine

import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.workflow.model.WorkflowStepStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeatureTaskRuntimeValidationGateDispatchTest {
  @Test
  fun `completed output completes validate without command evidence or a runtime rerun`() {
    val harness = validationHarness(validJsonOutput("validate"))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "validate" })
    val record = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("validate"))
    assertEquals(WorkflowStepStatus.COMPLETED, record.status)
    val envelope =
      assertNotNull(
        JsonCodec.parseObjectOrNull(assertNotNull(record.outputArtifact))
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap),
      )
    assertEquals(WorkflowStepStatus.COMPLETED.wireValue, envelope["status"])
    assertNull(harness.recorder.loadValidationGateProgress(WORKFLOW_ID))
  }

  @Test
  fun `completed uniform output without validation_passed completes validate`() {
    val output = validJsonOutput("validate").replace(",\"validation_passed\":true", "")
    val harness = validationHarness(output)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "validate" })
  }

  @Test
  fun `completed validation completes with or without a runtime platform pack`() {
    val output = validJsonOutput("validate")
    listOf(true, false).forEach { hasRuntimePack ->
      val harness = validationHarness(hasRuntimePack) { facts(output) }

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
      assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "validate" })
    }
  }

  @Test
  fun `unparseable results cannot advance beyond validate`() {
    val harness = validationHarness("finished")

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("validate", report.lastIncompletePhase)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "validate" })
    assertFalse("write_history" in harness.launchedPromptPhaseOrder())
    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    assertEquals(WorkflowStepStatus.BLOCKED, records["validate"]?.status)
    assertNull(records["commit_push"])
  }

  @Test
  fun `blocked phase result remains blocked without a runtime check to override it`() {
    val output =
      """
      {"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"validate","status":"blocked",
       "failure_disposition":"needs_user_action","summary":"Quality check could not run.",
       "produced_outputs":{"value":"Quality check could not run."}}
      """.trimIndent()
    val harness = validationHarness(output)

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("validate", report.lastIncompletePhase)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "validate" })
    assertFalse("write_history" in harness.launchedPromptPhaseOrder())
  }

  @Test
  fun `legacy partial validation reports retain failures without launching a salvage session`() {
    val remaining = "WidgetTest failed: expected 2 but got 3."
    listOf("progress", "no_progress", null, "shrinking").forEach { verdict ->
      val harness =
        validationHarness { attempt ->
          facts(if (attempt == 1) blockedValidateOutput(remaining, verdict) else validJsonOutput("validate"))
        }

      val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

      assertEquals("validate", report.lastIncompletePhase)
      assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "validate" })
      assertFalse("write_history" in harness.launchedPromptPhaseOrder())
      val record = assertNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("validate"))
      assertEquals(WorkflowStepStatus.BLOCKED, record.status)
      assertContains(assertNotNull(record.outputArtifact), remaining)
      assertFalse(report.blockedReason.contains("leftover set did not shrink"))
    }
  }

  @Test
  fun `failed validation process cannot complete using a successful-looking final response`() {
    val harness =
      validationHarness {
        agentRunLaunchFacts(
          agent = SupportedAgent.CLAUDE,
          termination = AgentRunTermination.Exited(1),
          stdout = validJsonOutput("validate"),
          stderr = "Validation process failed.",
        )
      }

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("validate", report.lastIncompletePhase)
    assertFalse("write_history" in harness.launchedPromptPhaseOrder())
  }

  @Test
  fun `provider limit leaves validate paused without recording a completed check`() {
    val harness =
      validationHarness {
        agentRunLaunchFacts(
          agent = SupportedAgent.CLAUDE,
          termination = AgentRunTermination.Exited(1),
          stdout = "",
          stderr = "You've hit your usage limit",
        )
      }

    assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))
    assertEquals(WorkflowStepStatus.PAUSED, harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("validate")?.status)
    assertFalse("write_history" in harness.launchedPromptPhaseOrder())
  }

  private fun blockedValidateOutput(
    remaining: String,
    verdict: String?,
  ): String {
    val verdictField = verdict?.let { ""","verdict":"$it"""" }.orEmpty()
    return """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"validate",""" +
      """"status":"blocked",""" +
      """"failure_disposition":"needs_user_action","summary":"Project checks still fail.",""" +
      """"produced_outputs":{"value":"$remaining"}$verdictField}"""
  }

  private fun validationHarness(output: String): RunnerHarness = validationHarness { facts(output) }

  private fun validationHarness(
    hasRuntimePack: Boolean = true,
    outcome: (Int) -> AgentRunLaunchOutcome,
  ): RunnerHarness {
    var validationAttempts = 0
    return runnerHarness(
      RuntimeHarnessConfig(
        validationGatePlatformManifests = if (hasRuntimePack) listOf(kotlinPackWithValidationGate()) else emptyList(),
        validationGateRunner =
          object : ValidationGateRunner {
            override fun run(request: ValidationGateRunRequest): ValidationGateRunResult =
              error("Runtime must not rerun the phase check: ${request.argv}")
          },
        validator = realFeatureTaskRuntimePhaseOutputValidator,
        launcher =
          RuntimeRecordingLauncher { request ->
            when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
              "validate" -> outcome(++validationAttempts)
              "audit" -> facts(auditSatisfiedOutput())
              else -> facts(defaultPhaseOutput(request))
            }
          },
      ),
    )
  }
}
