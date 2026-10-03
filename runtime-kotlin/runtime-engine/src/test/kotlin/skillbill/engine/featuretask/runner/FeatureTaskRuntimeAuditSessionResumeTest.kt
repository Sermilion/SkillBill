package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.phaseartifacts.asPendingForOperatorResume
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditSessionResumeTest {
  @Test
  fun `operator resume establishes one audit baseline and cannot authorize another automatic repair`() {
    var audits = 0
    var repairs = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> facts(auditRemainingAcOutput("AC-001: latest gap ${++audits}"))
          "audit_implement_fix" -> {
            repairs += 1
            facts(defaultPhaseOutput(request))
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val config = RuntimeHarnessConfig(launcher = launcher)
    val first = runnerHarness(config)
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(first.runner.run(first.request()))
    val blockedRecord = requireNotNull(first.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit"))
    assertContains(requireNotNull(blockedRecord.outputArtifact), "latest gap 4")
    reopen(first, "audit")

    val resumed = runnerHarness(config, repository = first.repository)
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(resumed.runner.run(resumed.request()))

    assertContains(blocked.blockedReason, "did not shrink")
    assertEquals(6, audits)
    assertEquals(4, repairs)
    val lastRepair =
      launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .last { phaseIdFromPrompt(it) == "audit_implement_fix" }
    assertContains(lastRepair, "latest gap 5")
    assertNull(resumed.recorder.loadOperatorBlockRetry(WORKFLOW_ID))
    assertTrue("review" !in resumed.launchOrder())
  }

  @Test
  fun `recreated repair carries saved work after retryable failures exhaust the budget`() {
    var repairs = 0
    var audits = 0
    val partial = "Guard implementation repaired. AC-001 capability closure remains."
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        when (phaseIdFromPrompt(prompt)) {
          "audit" -> {
            audits += 1
            facts(if (audits == 1) auditRemainingAcOutput("AC-001: missing closure") else auditSatisfiedOutput())
          }
          "audit_implement_fix" -> {
            repairs += 1
            if (repairs <= 3) {
              facts(
                """{
                |  "contract_version": "0.7",
                |  "phase_id": "audit_implement_fix",
                |  "status": "blocked",
                |  "summary": "Runner temporarily unavailable after saving repair progress.",
                |  "failure_disposition": "retryable",
                |  "produced_outputs": {"value": "$partial"}
                |}
                """.trimMargin(),
              )
            } else {
              assertContains(prompt, partial)
              assertContains(prompt, "Resume the saved audit repair")
              facts(defaultPhaseOutput(request))
            }
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val config = RuntimeHarnessConfig(launcher = launcher)
    val first = runnerHarness(config)
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(first.runner.run(first.request()))
    assertEquals("audit_implement_fix", blocked.lastIncompletePhase)
    assertEquals(3, repairs)
    val savedPlan = first.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit_plan_fix")?.outputArtifact
    reopen(first, "audit_implement_fix")
    val beforeResume = launcher.requests.size

    val resumed = runnerHarness(config, repository = first.repository)
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumed.runner.run(resumed.request()))

    assertEquals(4, repairs)
    assertEquals(2, audits)
    assertEquals(savedPlan, resumed.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit_plan_fix")?.outputArtifact)
    assertTrue("audit_plan_fix" !in resumed.launchedPromptPhaseOrder().drop(beforeResume))
    assertTrue("implement" !in resumed.launchedPromptPhaseOrder().drop(beforeResume))
  }

  @Test
  fun `legacy blocked repair plans before resuming and retains the saved repair attempt`() {
    var repairs = 0
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" ->
            facts(
              if (++audits == 1) auditRemainingAcOutput("AC-001: missing closure") else auditSatisfiedOutput(),
            )
          "audit_implement_fix" -> {
            repairs++
            if (repairs <= 3) {
              facts(
                """{
            |"contract_version":"0.7",
            |"phase_id":"audit_implement_fix",
            |"status":"blocked",
            |"summary":"Saved production edits.",
            |"failure_disposition":"retryable",
            |"produced_outputs":{"value":"Keep the repaired production path."}
            |}
                """.trimMargin(),
              )
            } else {
              facts(defaultPhaseOutput(request))
            }
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }
    val config = RuntimeHarnessConfig(launcher = launcher)
    val first = runnerHarness(config)
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(first.runner.run(first.request()))
    val originalRepair = first.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit_implement_fix")
    val artifacts = first.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    val records = requireNotNull(first.recorder.loadPhaseRecords(WORKFLOW_ID)) - "audit_plan_fix"
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()] =
      records.mapValues { it.value.asWorkflowArtifactEntry() }
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.label()] =
      first.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .filter { it.phaseId != "audit_plan_fix" || it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
        .map {
          if (it.phaseId == "audit_plan_fix") {
            it.copy(phaseId = "audit_implement_fix").asWorkflowArtifactEntry()
          } else {
            it.asWorkflowArtifactEntry()
          }
        }
    first.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
    reopen(first, "audit_implement_fix")
    val beforeResume = launcher.requests.size
    val resumed = runnerHarness(config, repository = first.repository)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumed.runner.run(resumed.request()))

    assertEquals(
      listOf("audit_plan_fix", "audit_implement_fix", "audit"),
      resumed.launchedPromptPhaseOrder().drop(beforeResume).filter { it.startsWith("audit") },
    )
    assertEquals(3, originalRepair?.attemptCount)
    assertEquals(4, resumed.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit_implement_fix")?.attemptCount)
    assertTrue("implement" !in resumed.launchedPromptPhaseOrder().drop(beforeResume))
  }

  @Test
  fun `operator resume still rejects unknown criteria before starting repair`() {
    var audits = 0
    val launcher =
      RuntimeRecordingLauncher { request ->
        if (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride)) == "audit") {
          facts(auditRemainingAcOutput(if (++audits > 4) "AC-099: invented criterion" else "AC-001: gap"))
        } else {
          facts(defaultPhaseOutput(request))
        }
      }
    val config = RuntimeHarnessConfig(launcher = launcher)
    val first = runnerHarness(config)
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(first.runner.run(first.request()))
    reopen(first, "audit")
    val beforeResume = launcher.requests.size
    val resumed = runnerHarness(config, repository = first.repository)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(resumed.runner.run(resumed.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertEquals(5, audits)
    assertEquals(listOf("audit"), resumed.launchedPromptPhaseOrder().drop(beforeResume))
    assertContains(blocked.blockedReason, "AC-099")
    assertTrue("audit_implement_fix" !in resumed.launchedPromptPhaseOrder().drop(beforeResume))
  }

  private fun reopen(
    harness: RunnerHarness,
    phaseId: String,
  ) {
    val records = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).toMutableMap()
    val blocked = requireNotNull(records[phaseId])
    records[phaseId] = blocked.asPendingForOperatorResume()
    val ledger = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
    val timestamp = Instant.parse("2026-09-29T21:00:00Z")
    val retry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
        sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: 0) + 1,
        timestamp = timestamp,
        phaseId = phaseId,
        attemptCount = blocked.attemptCount,
      )
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()] =
      records.mapValues { it.value.asWorkflowArtifactEntry() }
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.label()] =
      (ledger + retry).map { it.asWorkflowArtifactEntry() }
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.label()] =
      FeatureTaskRuntimeOperatorBlockRetry(phaseId, "Runtime repaired; retry explicitly.", timestamp.toString())
        .asWorkflowArtifactEntry(requireNotNull(blocked.blockedReason), listOf(phaseId))
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }
}
