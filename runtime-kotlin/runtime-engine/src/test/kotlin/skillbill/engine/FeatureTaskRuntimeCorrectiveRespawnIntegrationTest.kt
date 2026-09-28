
package skillbill.engine

import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimePhaseOutputTestValidator
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.install.model.SupportedAgent.CLAUDE
import skillbill.ports.agentrun.agentRunLaunchFacts
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun completedPhaseBody(
  contractVersion: String,
  phaseId: String,
  summary: String,
  producedOutputs: String,
  verdict: String? = null,
): String {
  val verdictField = verdict?.let { "\"verdict\":\"$it\"," }.orEmpty()
  return "{\"contract_version\":\"$contractVersion\",\"phase_id\":\"$phaseId\",\"status\":\"completed\"," +
    "\"summary\":\"$summary\",$verdictField\"produced_outputs\":$producedOutputs}"
}

class FeatureTaskRuntimeCorrectiveRespawnIntegrationTest {
  private val rawSpan = "SKILL187-CORRECTIVE-SENTINEL"
  private val payloadFreeConstraint = "status: does not have a value in the enumeration"

  @Test
  fun `schema-invalid result body and digest match the private diagnostic capture`() {
    val rejectedBody = completedPhaseBody("0.5", "audit", rawSpan, """{"gaps":[]}""")
    var auditAttempts = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
          RuntimeRecordingLauncher { request ->
            val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
            if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
            auditAttempts += 1
            facts(if (auditAttempts == 1) rejectedBody else defaultPhaseOutput(request))
          },
          validator = rejectingOnceValidator(rejectedBody),
        ),
      )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertContains(blocked.blockedReason, "does not participate in a fix loop")

    val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "audit" }
    assertEquals(rejectedBody.encodeToByteArray().toList(), diagnostic.payload?.toList())
    assertEquals(1, auditPrompts(harness).size, "schema-invalid audit must not relaunch")
    assertFalse(auditPrompts(harness).single().contains("REJECTED by the schema gate"))
  }

  @Test
  fun `audit schema rejection cannot launch a second attempt or deliver a repair context`() {
    val firstBody = completedPhaseBody("0.5", "audit", "SKILL187-ATTEMPT-1", """{"gaps":[]}""")
    val secondBody = completedPhaseBody("0.5", "audit", "SKILL187-ATTEMPT-2", """{"gaps":[]}""")
    var auditAttempts = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
          RuntimeRecordingLauncher { request ->
            val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
            if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
            auditAttempts += 1
            facts(
              when (auditAttempts) {
                1 -> firstBody
                2 -> secondBody
                else -> defaultPhaseOutput(request)
              },
            )
          },
          validator =
          object : FeatureTaskRuntimePhaseOutputTestValidator() {
            override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
              if (sourceLabel != "audit") return
              if (phaseOutputText.contains("SKILL187-ATTEMPT-1") || phaseOutputText.contains("SKILL187-ATTEMPT-2")) {
                throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
                  sourceLabel = sourceLabel,
                  reason = "status: does not have a value in the enumeration — offending value: bad",
                  payloadFreeReason = payloadFreeConstraint,
                )
              }
            }
          },
        ),
      )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertContains(blocked.blockedReason, "does not participate in a fix loop")

    val prompts = auditPrompts(harness)
    assertEquals(1, prompts.size, "audit must reject once then block")
    assertFalse(prompts[0].contains("Untrusted prior phase output"), "first launch must omit repair section")
    assertFalse(prompts[0].contains("REJECTED by the schema gate"), "first launch must omit schema directive")
  }

  @Test
  fun `a later phase retry cannot receive a stale repair body from an earlier phase`() {
    val planBody = completedPhaseBody("0.2", "plan", "SKILL187-PLAN-STALE", """{"mode":"direct","tasks":[]}""")
    val auditBody =
      completedPhaseBody("0.5", "audit", "SKILL187-AUDIT-CURRENT", """{"value":"{\"gaps\":[]}"}""", "satisfied")
    var planAttempts = 0
    var auditAttempts = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
          RuntimeRecordingLauncher { request ->
            val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
            when (phaseId) {
              "plan" -> {
                planAttempts += 1
                facts(if (planAttempts == 1) planBody else defaultPhaseOutput(request))
              }
              "audit" -> {
                auditAttempts += 1
                facts(if (auditAttempts == 1) auditBody else defaultPhaseOutput(request))
              }
              else -> facts(defaultPhaseOutput(request))
            }
          },
          validator =
          object : FeatureTaskRuntimePhaseOutputTestValidator() {
            override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
              if (sourceLabel == "plan" && phaseOutputText.contains("SKILL187-PLAN-STALE")) {
                throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
                  sourceLabel = sourceLabel,
                  reason = "plan rejected",
                  payloadFreeReason = payloadFreeConstraint,
                )
              }
              if (sourceLabel == "audit" && phaseOutputText.contains("SKILL187-AUDIT-CURRENT")) {
                throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
                  sourceLabel = sourceLabel,
                  reason = "audit rejected",
                  payloadFreeReason = "verdict: must be a top-level string",
                )
              }
            }
          },
        ),
      )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals("plan", blocked.lastIncompletePhase)
    assertTrue(
      harness.launcher.requests.none {
        phaseIdFromPrompt(requireNotNull(it.skillRunRequest.promptOverride)) == "audit"
      },
      "a schema-invalid plan must block before audit launches",
    )
  }

  @Test
  fun `retryable terminal audit blocks after one agent session`() {
    var auditLaunches = 0
    val retryableFailure =
      """
      {
        "contract_version":"0.2",
        "phase_id":"audit",
        "status":"failed",
        "failure_disposition":"retryable",
        "summary":"SKILL187-TERMINAL-BLOCK",
        "produced_outputs":{"blocking_reasons":["Temporary input unavailable."]}
      }
      """.trimIndent()
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
          RuntimeRecordingLauncher { request ->
            val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
            if (phaseId == "audit") auditLaunches += 1
            facts(if (phaseId == "audit" && auditLaunches == 1) retryableFailure else defaultPhaseOutput(request))
          },
        ),
      )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals(1, auditLaunches)
    assertEquals(1, auditPrompts(harness).size)
  }

  @Test
  fun `malformed simplify output blocks before audit and preserves the single-session boundary`() {
    val malformed =
      completedPhaseBody(
        FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        "simplify",
        "Missing simplification receipt.",
        "{}",
      )
    var simplifyLaunches = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
          RuntimeRecordingLauncher { request ->
            val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
            if (phaseId == "simplify") {
              simplifyLaunches += 1
              facts(malformed)
            } else {
              facts(defaultPhaseOutput(request))
            }
          },
          validator = realFeatureTaskRuntimePhaseOutputValidator,
        ),
      )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("simplify", blocked.lastIncompletePhase)
    assertEquals(1, simplifyLaunches)
    assertTrue("audit" !in harness.launchOrder())
    assertEquals(
      "invalid_output",
      harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("simplify")?.failureDisposition?.wireValue,
    )
  }

  @Test
  fun `invalid enum and compound artifact_ref rejections carry the captured body into the next launch`() {
    val invalidEnum =
      completedPhaseBody(
        "0.5",
        "audit",
        "SKILL187-ENUM",
        """{"value":"{\"gaps\":[{\"severity\":\"catastrophic\"}]}"}""",
        "gaps_found",
      )
    val compoundRef =
      completedPhaseBody(
        "0.5",
        "audit",
        "SKILL187-ARTIFACT",
        """{"value":"{\"gaps\":[{\"artifact_ref\":\"a.kt;b.kt;c.kt\"}]}"}""",
        "gaps_found",
      )
    listOf(invalidEnum to "SKILL187-ENUM", compoundRef to "SKILL187-ARTIFACT").forEach { (rejectedBody, sentinel) ->
      var auditAttempts = 0
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            launcher =
            RuntimeRecordingLauncher { request ->
              val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
              if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
              auditAttempts += 1
              facts(if (auditAttempts == 1) rejectedBody else defaultPhaseOutput(request))
            },
            validator =
            object : FeatureTaskRuntimePhaseOutputTestValidator() {
              override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
                if (sourceLabel != "audit") return
                if (phaseOutputText.contains(sentinel)) {
                  throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
                    sourceLabel = sourceLabel,
                    reason = "field rejected — offending value: $sentinel",
                    payloadFreeReason = payloadFreeConstraint,
                  )
                }
              }
            },
          ),
        )

      assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
      assertEquals(1, auditAttempts)
      val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "audit" }
      assertEquals(rejectedBody.encodeToByteArray().toList(), diagnostic.payload?.toList())
      assertFalse(auditPrompts(harness).single().contains("REJECTED by the schema gate"))
    }
  }

  @Test
  fun `truncated audit capture blocks without relaunching or exposing the body`() {
    val excerpt =
      completedPhaseBody(
        "0.5",
        "audit",
        "SKILL187-TRUNCATED-EXCERPT",
        """{"gaps":[]}""",
      )
    val fullStreamDigest = "a".repeat(64)
    val fullStreamBytes = 12_345L
    var auditAttempts = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
          RuntimeRecordingLauncher { request ->
            val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
            if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
            auditAttempts += 1
            if (auditAttempts == 1) {
              agentRunLaunchFacts(
                agent = CLAUDE,
                stdout = excerpt,
                stderr = "",
                stdoutTruncated = true,
                stdoutByteSize = fullStreamBytes,
                stdoutSha256 = fullStreamDigest,
              )
            } else {
              facts(defaultPhaseOutput(request))
            }
          },
          validator =
          object : FeatureTaskRuntimePhaseOutputTestValidator() {
            override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
              if (sourceLabel != "audit") return
              if (phaseOutputText.contains("SKILL187-TRUNCATED-EXCERPT")) {
                throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
                  sourceLabel = sourceLabel,
                  reason = "truncated rejection",
                  payloadFreeReason = payloadFreeConstraint,
                )
              }
            }
          },
        ),
      )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertContains(blocked.blockedReason, "does not participate in a fix loop")
    assertEquals(1, auditAttempts)
    assertFalse(auditPrompts(harness).single().contains("SKILL187-TRUNCATED-EXCERPT"))
  }

  @Test
  fun `a degraded audit diagnostic blocks without a fabricated locator or relaunch`() {
    val excerpt =
      completedPhaseBody(
        "0.5",
        "audit",
        "SKILL187-DEGRADED-EXCERPT",
        """{"gaps":[]}""",
      )
    val fullStreamDigest = "b".repeat(64)
    val fullStreamBytes = 9_001L
    var auditAttempts = 0
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(
          launcher =
          RuntimeRecordingLauncher { request ->
            val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
            if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
            auditAttempts += 1
            if (auditAttempts == 1) {
              agentRunLaunchFacts(
                agent = CLAUDE,
                stdout = excerpt,
                stderr = "",
                stdoutTruncated = true,
                stdoutByteSize = fullStreamBytes,
                stdoutSha256 = fullStreamDigest,
              )
            } else {
              facts(defaultPhaseOutput(request))
            }
          },
          validator =
          object : FeatureTaskRuntimePhaseOutputTestValidator() {
            override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
              if (sourceLabel != "audit") return
              if (phaseOutputText.contains("SKILL187-DEGRADED-EXCERPT")) {
                throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
                  sourceLabel = sourceLabel,
                  reason = "degraded rejection",
                  payloadFreeReason = payloadFreeConstraint,
                )
              }
            }
          },
          agentAssignment = phasePerAgentAssignment(),
        ),
      )
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordRejectedOutput(
      RejectedOutputDiagnosticRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "audit",
        attempt = 1,
        rule = "divergent-pre-record",
        path = "/",
        reason = "divergent-pre-record",
        agentId = phaseAgent("audit"),
        model = "unspecified",
        rawResponse = "divergent-pre-record".encodeToByteArray(),
      ),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertContains(blocked.blockedReason, "does not participate in a fix loop")
    assertFalse(blocked.blockedReason.contains("rod_"), "degraded write must not fabricate a resolvable locator")
    assertEquals(1, auditAttempts)
  }

  private fun auditPrompts(harness: RunnerHarness): List<String> = harness.launcher.requests
    .map { requireNotNull(it.skillRunRequest.promptOverride) }
    .filter { phaseIdFromPrompt(it) == "audit" }

  private fun rejectingOnceValidator(rejectedBody: String): FeatureTaskRuntimePhaseOutputTestValidator =
    object : FeatureTaskRuntimePhaseOutputTestValidator() {
      override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
        if (sourceLabel != "audit") return
        if (phaseOutputText.contains(rawSpan) || phaseOutputText == rejectedBody) {
          throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
            sourceLabel = sourceLabel,
            reason = "status: does not have a value in the enumeration — offending value: $rawSpan",
            payloadFreeReason = payloadFreeConstraint,
          )
        }
      }
    }
}
