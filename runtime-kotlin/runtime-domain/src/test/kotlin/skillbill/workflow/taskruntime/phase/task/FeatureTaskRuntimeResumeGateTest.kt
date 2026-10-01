package skillbill.workflow.taskruntime.phase.task

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeatureTaskRuntimeResumeGateTest {
  private val engine = WorkflowEngine()
  private val runtimeDefinition = FeatureTaskRuntimePhaseWorkflowDefinition.definition

  @Test
  fun `runtime resume gate clears missing artifacts when upstream phase records are completed`() {
    val record =
      runtimeSnapshot(
        currentStepId = "implement",
        stepsJson =
          stepsJson(
            "preplan" to "completed",
            "plan" to "completed",
            "implement" to "pending",
          ),
        phaseRecordStatuses = mapOf("preplan" to "completed", "plan" to "completed"),
      )

    val resume = engine.resumeView(runtimeDefinition, record)

    assertEquals(emptyList(), resume.missingArtifacts)
    assertTrue(resume.canResume)
    assertEquals("implement", resume.resumeStepId)
    assertEquals("plan", resume.lastCompletedStepId)
  }

  @Test
  fun `runtime compact continuation exposes completed upstream phase output under logical phase key`() {
    val record =
      runtimeSnapshot(
        currentStepId = "plan",
        stepsJson = stepsJson("preplan" to "completed", "plan" to "running"),
        phaseRecordStatuses = mapOf("preplan" to "completed"),
        phaseRecordOutputs = mapOf("preplan" to """{"preplan_digest":"bounded"}"""),
      )

    val decision = engine.continueDecision(runtimeDefinition, record)

    assertEquals(emptyList(), decision.view.resume.missingArtifacts)
    assertEquals(listOf("preplan"), decision.view.stepArtifactKeys)
    assertEquals("""{"preplan_digest":"bounded"}""", decision.view.stepArtifacts["preplan"])
    val compactPreplan = decision.view.compact.currentStepArtifacts.single { it.key == "preplan" }
    assertTrue(compactPreplan.present)
    assertFalse(compactPreplan.omitted)
    assertEquals("""{"preplan_digest":"bounded"}""", compactPreplan.value.raw)
    assertEquals(null, compactPreplan.omissionReason)
  }

  @Test
  fun `runtime resume gate reports missing upstream when its phase record is not completed`() {
    val record =
      runtimeSnapshot(
        currentStepId = "plan",
        stepsJson = stepsJson("preplan" to "running", "plan" to "pending"),
        phaseRecordStatuses = mapOf("preplan" to "running"),
      )

    val resume = engine.resumeView(runtimeDefinition, record)

    assertEquals(listOf("preplan"), resume.missingArtifacts)
    assertFalse(resume.canResume)
  }

  @Test
  fun `crashed runtime run with completed preplan and plan resumes at implement not preplan`() {
    val record =
      runtimeSnapshot(
        currentStepId = "plan",
        workflowStatus = WorkflowStatus.RUNNING,
        stepsJson =
          stepsJson(
            "preplan" to "completed",
            "plan" to "completed",
            "implement" to "pending",
          ),
        phaseRecordStatuses = mapOf("preplan" to "completed", "plan" to "completed"),
      )

    val resume = engine.resumeView(runtimeDefinition, record)

    assertTrue(resume.canResume)
    assertEquals("implement", resume.resumeStepId)
    assertEquals(emptyList(), resume.missingArtifacts)

    val decision = engine.continueDecision(runtimeDefinition, record)
    assertEquals("reopened", decision.view.continueStatus.wireValue)
    assertEquals("implement", decision.resumeStepId)
  }

  @Test
  fun `completed run done next-action dereferences a terminal-summary artifact present in the snapshot`() {
    val record =
      runtimeSnapshot(
        currentStepId = "pr",
        workflowStatus = WorkflowStatus.COMPLETED,
        stepsJson =
          stepsJson(
            "preplan" to "completed",
            "plan" to "completed",
            "implement" to "completed",
            "pr" to "completed",
          ),
        phaseRecordStatuses =
          mapOf(
            "preplan" to "completed",
            "plan" to "completed",
            "implement" to "completed",
            "pr" to "completed",
          ),
      )

    val resume = engine.resumeView(runtimeDefinition, record)

    assertEquals("done", resume.resumeMode.wireValue)
    val terminalSummaryKey = runtimeDefinition.completedTerminalSummaryArtifact
    assertTrue(
      resume.nextAction.contains(terminalSummaryKey),
      "done next-action must reference the terminal-summary artifact key",
    )
    assertTrue(
      resume.availableArtifacts.contains(terminalSummaryKey),
      "terminal-summary artifact key must dereference a present artifact for a completed run",
    )
  }

  @Test
  fun `non-runtime family keeps default top-level key presence rule`() {
    val verify = FeatureVerifyWorkflowDefinition.definition
    val firstRequiredStep =
      verify.stepIds.first { stepId ->
        verify.requiredArtifactsByStep[stepId].orEmpty().isNotEmpty()
      }
    val requiredKeys = verify.requiredArtifactsByStep.getValue(firstRequiredStep)

    val record =
      implementSnapshot(
        definition = verify,
        currentStepId = firstRequiredStep,
        stepsJson = stepsJson(firstRequiredStep to "pending"),
      )

    val resume = engine.resumeView(verify, record)
    assertEquals(requiredKeys, resume.missingArtifacts)
    assertFalse(resume.canResume)
  }

  @Test
  fun `runtime definition binds the family-aware presence resolver`() {
    assertSame(
      FeatureTaskRuntimeRequiredArtifactPresenceResolver,
      runtimeDefinition.requiredArtifactPresenceResolver,
    )
  }

  @Test
  fun `runtime resume gate loud-fails on malformed quality gate selection`() {
    val snapshot =
      WorkflowSnapshotView(
        workflowId = "wftr-test",
        sessionId = "ftr-test",
        workflowName = runtimeDefinition.workflowName,
        contractVersion = runtimeDefinition.contractVersion,
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        steps = emptyList(),
        artifacts =
          DurableWorkflowArtifacts.fromMap(
            mapOf(
              FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to
                mapOf(
                  "issue_key" to "SKILL-351",
                  "subtask_id" to 1,
                  "suppress_pr" to true,
                  "goal_branch" to "feat/SKILL-351",
                  "code_review_mode" to "inline",
                  "quality_gate_selection" to false,
                ),
            ),
          ),
        startedAt = "2026-06-18T10:00:00Z",
        updatedAt = "2026-06-18T10:05:00Z",
        finishedAt = "",
      )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeRequiredArtifactPresenceResolver.missingRequiredArtifacts(
        snapshot = snapshot,
        resumeStepId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        requiredArtifacts = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      )
    }
  }

  private fun runtimeSnapshot(
    currentStepId: String,
    stepsJson: String,
    phaseRecordStatuses: Map<String, String>,
    phaseRecordOutputs: Map<String, String> = emptyMap(),
    workflowStatus: WorkflowStatus = WorkflowStatus.RUNNING,
  ): WorkflowStateSnapshot =
    WorkflowStateSnapshot(
      workflowId = "wftr-test",
      sessionId = "ftr-test",
      workflowName = runtimeDefinition.workflowName,
      contractVersion = runtimeDefinition.contractVersion,
      workflowStatus = workflowStatus,
      currentStepId = currentStepId,
      steps = decodeSteps(stepsJson),
      artifacts =
        DurableWorkflowArtifacts.fromMap(
          decodeArtifacts(phaseRecordsArtifactsJson(phaseRecordStatuses, phaseRecordOutputs)),
        ),
      startedAt = Instant.parse("2026-06-18T10:00:00Z"),
      updatedAt = Instant.parse("2026-06-18T10:05:00Z"),
      finishedAt = null,
      mode = runtimeDefinition.workflowMode?.let(FeatureTaskWorkflowMode::fromWireValue),
    )

  private fun implementSnapshot(
    definition: WorkflowDefinition,
    currentStepId: String,
    stepsJson: String,
  ): WorkflowStateSnapshot =
    WorkflowStateSnapshot(
      workflowId = "wfi-test",
      sessionId = "impl-test",
      workflowName = definition.workflowName,
      contractVersion = definition.contractVersion,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = currentStepId,
      steps = decodeSteps(stepsJson),
      artifacts = DurableWorkflowArtifacts.EMPTY,
      startedAt = Instant.parse("2026-06-18T10:00:00Z"),
      updatedAt = Instant.parse("2026-06-18T10:05:00Z"),
      finishedAt = null,
      mode = definition.workflowMode?.let(FeatureTaskWorkflowMode::fromWireValue),
    )

  private fun decodeSteps(raw: String): List<WorkflowStepState> =
    (JsonCodec.parseValue(raw) as List<*>).map { value ->
      val entry = requireNotNull(JsonCodec.anyToStringAnyMap(value))
      WorkflowStepState(
        stepId = entry.getValue("step_id") as String,
        status = WorkflowStepStatus.fromWire(entry.getValue("status") as String)!!,
        attemptCount = (entry.getValue("attempt_count") as Number).toInt(),
      )
    }

  private fun decodeArtifacts(raw: String): Map<String, Any?> =
    requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(raw)))

  private fun stepsJson(vararg stepStatuses: Pair<String, String>): String =
    stepStatuses.joinToString(prefix = "[", postfix = "]") { (stepId, status) ->
      """{"step_id":"$stepId","status":"$status","attempt_count":1}"""
    }

  private fun phaseRecordsArtifactsJson(
    phaseRecordStatuses: Map<String, String>,
    phaseRecordOutputs: Map<String, String> = emptyMap(),
  ): String {
    val records =
      phaseRecordStatuses.entries.joinToString(",") { (phaseId, status) ->
        val finishedAt = if (status == "completed") ""","finished_at":"2026-06-18T10:04:00Z"""" else ""
        val outputArtifact =
          phaseRecordOutputs[phaseId]
            ?.let { ""","output_artifact":${jsonStringLiteral(it)}""" }
            .orEmpty()
        """"$phaseId":{"contract_version":"0.2","record_kind":"private_phase_record",""" +
          """"phase_id":"$phaseId","status":"$status","attempt_count":1,""" +
          """"started_at":"2026-06-18T10:00:00Z","first_started_at":"2026-06-18T10:00:00Z",""" +
          """"resolved_agent_id":"agent-$phaseId","execution_origin":"agent-executed"$finishedAt$outputArtifact}"""
      }
    return """{"feature_task_runtime_phase_records":{$records}}"""
  }

  private fun jsonStringLiteral(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").let { """"$it"""" }
}
