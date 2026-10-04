package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditProgressRegressionTest {
  @Test
  fun `repair audit rejects reopening a satisfied criterion before another repair`() {
    var audits = 0
    var repairs = 0
    val prompts = mutableListOf<String>()
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        when (phaseIdFromPrompt(prompt)) {
          "audit" -> {
            prompts += prompt
            facts(
              auditRemainingAcOutput(
                when (++audits) {
                  1 -> "AC-001 remains"
                  2 -> "AC-002 remains"
                  else -> "No production criteria remain."
                },
              ),
            )
          }
          "audit_implement_fix" -> {
            repairs += 1
            facts(defaultPhaseOutput(request))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val harness = runnerHarness(RuntimeHarnessConfig(acceptanceCriteria = CRITERIA, launcher = launcher))
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals(2, audits)
    assertEquals(1, repairs)
    assertContains(prompts[1], "only these unresolved criterion IDs: AC-001")
    assertTrue("review" !in harness.launchOrder())
  }

  @Test
  fun `unchanged lists block after two non-shrinking rounds despite source labels and historical JSON`() {
    val cases =
      listOf(
        "AC-001 / S3-AC2: missing behavior" to "S3-AC2: same gap with different words",
        """[{"criterion_id":"AC-001"},{"criterion_id":"AC-002"},{"criterion_id":"AC-003"}]""" to
          """Remaining production acceptance criteria:
          |- S3-AC2. Transition ownership is incomplete.
          |- S3-AC3. Capability access is incomplete.
          |- S3-AC4. Review access is incomplete.
          |
          |AC4 has no remaining production gap. No tests were run.
          """.trimMargin(),
      )
    for ((before, after) in cases) {
      var audits = 0
      var repairs = 0
      val launcher =
        RuntimeRecordingLauncher { request ->
          when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
            "audit" -> facts(auditRemainingAcOutput(if (++audits == 1) before else after))
            "audit_implement_fix" -> {
              repairs += 1
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
      assertContains(report.blockedReason, "did not shrink")
      assertEquals(4, audits)
      assertEquals(3, repairs)
      assertTrue("review" !in harness.launchOrder())
      val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit"))
      assertEquals(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION, record.failureDisposition)
    }
  }

  @Test
  fun `unidentified and unknown findings never start repair`() {
    for (text in listOf(
      "Missing production behavior.",
      "- AC-099: unknown requirement",
      "- Missing another behavior",
    )) {
      val launcher =
        RuntimeRecordingLauncher { request ->
          val phase = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          facts(if (phase == "audit") auditRemainingAcOutput(text) else defaultPhaseOutput(request))
        }
      val harness =
        runnerHarness(RuntimeHarnessConfig(launcher = launcher))
      val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
      assertEquals("audit", report.lastIncompletePhase)
      assertTrue("audit_implement_fix" !in harness.launchedPromptPhaseOrder())
      assertTrue("review" !in harness.launchOrder())
    }
  }

  @Test
  fun `recreated runtime compares accepted audit before interrupted repair`() {
    assertRecreatedComparison(missingBaseline = false)
  }

  @Test
  fun `recreated repair cannot reset a missing audit baseline`() {
    assertRecreatedComparison(missingBaseline = true)
  }

  @Test
  fun `missing baseline restarts repair once and its second loss blocks after recreation`() {
    var audits = 0
    var recreated = false
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        val output =
          facts(if (phase == "audit") auditRemainingAcOutput("AC-001: missing") else defaultPhaseOutput(request))
        if (phase == "audit") audits += 1
        if (!recreated && phase == "audit" && audits > 1) {
          (output as AgentRunLaunchFacts).copy(termination = AgentRunTermination.Exited(1))
        } else {
          output
        }
      }
    val config = RuntimeHarnessConfig(acceptanceCriteria = CRITERIA, launcher = launcher)
    val first = runnerHarness(config)
    first.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    first.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "audit_implement_fix",
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = INVOKED_AGENT,
        finished = true,
        outputArtifact = validJsonOutput("audit_implement_fix"),
      ),
    )
    val interrupted = assertIs<FeatureTaskRuntimeRunReport.Blocked>(first.runner.run(first.request()))
    assertEquals("audit", interrupted.lastIncompletePhase)
    val auditsBeforeResume = audits
    assertTrue("audit_plan_fix" in first.launchedPromptPhaseOrder())
    assertEquals(
      1,
      first.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty().count {
        it.blockedReason == "continuation:audit_missing_baseline"
      },
    )

    val key = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()
    val artifacts = first.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    val records = requireNotNull(JsonCodec.anyToStringAnyMap(artifacts[key])).toMutableMap()
    records.remove("audit")
    artifacts[key] = records
    first.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
    recreated = true
    val restarted = runnerHarness(config, repository = first.repository)
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(restarted.runner.run(restarted.request()))
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "second time")
    assertEquals(auditsBeforeResume + 1, audits)
    assertEquals(
      2,
      restarted.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty().count {
        it.blockedReason == "continuation:audit_missing_baseline"
      },
    )
  }

  private fun assertRecreatedComparison(missingBaseline: Boolean) {
    var resumed = false
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            audits += 1
            facts(auditRemainingAcOutput(if (resumed) "S3-AC2: still missing" else "AC-001: missing"))
          }
          "audit_implement_fix" -> {
            val output = facts(defaultPhaseOutput(request)) as AgentRunLaunchFacts
            if (resumed) output else output.copy(termination = AgentRunTermination.Exited(1))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val config =
      RuntimeHarnessConfig(
        acceptanceCriteria = CRITERIA,
        launcher = launcher,
      )
    val first = runnerHarness(config)
    val interrupted = assertIs<FeatureTaskRuntimeRunReport.Blocked>(first.runner.run(first.request()))
    assertEquals("audit_implement_fix", interrupted.lastIncompletePhase)
    val oldAttempt =
      requireNotNull(
        first.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit_implement_fix"),
      ).attemptCount
    val checkpoints = first.recorder.loadCheckpointIdentities(WORKFLOW_ID).orEmpty()
    if (missingBaseline) {
      val key = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()
      val artifacts = first.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
      val records = requireNotNull(JsonCodec.anyToStringAnyMap(artifacts[key])).toMutableMap()
      records.remove("audit")
      artifacts[key] = records
      first.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
    }
    val launchesBeforeResume = launcher.requests.size
    resumed = true
    val restarted = runnerHarness(config, repository = first.repository)
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(restarted.runner.run(restarted.request()))
    if (missingBaseline) {
      assertEquals("audit_implement_fix", blocked.lastIncompletePhase)
      assertContains(blocked.blockedReason, "audit")
    } else {
      assertEquals("audit", blocked.lastIncompletePhase)
      assertContains(blocked.blockedReason, "1 remaining production criteria")
      assertContains(blocked.blockedReason, "AC-001")
      assertContains(blocked.blockedReason, "did not shrink")
    }
    assertEquals(if (missingBaseline) 1 else 4, audits)
    val newAttempt =
      requireNotNull(
        restarted.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit_implement_fix"),
      ).attemptCount
    assertTrue(if (missingBaseline) newAttempt >= oldAttempt else newAttempt > oldAttempt)
    assertTrue(restarted.recorder.loadCheckpointIdentities(WORKFLOW_ID).orEmpty().containsAll(checkpoints))
    assertTrue("implement" !in restarted.launchedPromptPhaseOrder().drop(launchesBeforeResume))
    assertTrue("review" !in restarted.launchOrder())
  }

  private companion object {
    val CRITERIA =
      listOf(
        "S3-AC2. Transition ownership is exclusive.",
        "S3-AC3. Capabilities are scoped.",
        "S3-AC4. Review access is confined.",
        "S3-AC8. Documentation matches.",
      )
  }
}
