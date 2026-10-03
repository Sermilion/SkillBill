package skillbill.mcp.workflow

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalobservability.goalObservabilityLatestEventFromArtifacts

class WorkflowMcpResultMappersTest {
  @Test
  fun `workflow update mapper emits compact acknowledgement without full state`() {
    val mapped =
      WorkflowUpdateResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        acknowledgement =
          WorkflowUpdateAcknowledgementView(
            status = "ok",
            workflowId = "wfl-1",
            workflowName = "bill-feature-task",
            workflowStatus = WorkflowStatus.RUNNING,
            currentStepId = "implement",
            updatedStepIds = listOf("implement"),
            updatedArtifactKeys = listOf("implementation_summary"),
            readOnlyFullStateGuidance = "Use workflow show.",
          ),
      ).toMcpMap()

    assertEquals("ok", mapped["status"])
    assertEquals("wfl-1", mapped["workflow_id"])
    assertEquals("bill-feature-task", mapped["workflow_name"])
    assertEquals("running", mapped["workflow_status"])
    assertEquals("implement", mapped["current_step_id"])
    assertEquals(listOf("implement"), mapped["updated_step_ids"])
    assertEquals(listOf("implementation_summary"), mapped["updated_artifact_keys"])
    assertEquals("/tmp/metrics.db", mapped["db_path"])
    assertTrue(mapped.containsKey("read_only_full_state_command"))
    assertFalse(mapped.containsKey("steps"))
    assertFalse(mapped.containsKey("artifacts"))
  }

  @Test
  fun `workflow mapper exposes compact goal observability summary without heavy fields`() {
    val mapped =
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(),
      ).withDecodedGoalObservability().toMcpMap()

    val observability = mapped["goal_observability"] as Map<*, *>
    assertEquals("implement", observability["workflow_phase"])
    assertEquals("phase_subagent", observability["worker_role"])
    assertEquals("durable_progress", observability["liveness_class"])
    assertFalse(observability.containsKey("changed_files"))
  }

  @Test
  fun `workflow mapper tolerates legacy goal session artifact without projecting it`() {
    val mapped =
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot =
          snapshotWithObservability().copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                mapOf(
                  "goal_session_accounting" to listOf(mapOf("sequence_number" to 1)),
                ),
              ),
          ),
      ).withDecodedGoalObservability().toMcpMap()

    assertFalse(mapped.containsKey("goal_session_accounting_latest"))
  }

  @Test
  fun `workflow mapper loud-fails malformed goal observability latest event`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot =
            snapshotWithObservability(
              event =
                mapOf(
                  "contract_version" to "0.1",
                  "subtask_id" to 1,
                  "workflow_phase" to "implement",
                  "worker_role" to "phase_subagent",
                  "liveness_class" to "durable_progress",
                  "activity_summary" to "editing",
                  "sequence_number" to 1,
                  "timestamp" to "2026-06-01T00:00:00Z",
                ),
            ),
        ).withDecodedGoalObservability().toMcpMap()
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "contract_version")
  }

  @Test
  fun `workflow mapper loud-fails schema-invalid extra goal observability field`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("unknown" to true)),
        ).withDecodedGoalObservability().toMcpMap()
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "at '<root>'")
  }

  @Test
  fun `workflow mapper loud-fails malformed optional goal observability summary`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot =
            snapshotWithObservability(
              event = snapshotWithObservabilityEvent() + ("changed_file_summary" to "not-an-object"),
            ),
        ).withDecodedGoalObservability().toMcpMap()
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "changed_file_summary")
  }

  @Test
  fun `workflow mapper loud-fails malformed optional goal observability arrays`() {
    val changedFilesError =
      assertFailsWith<SkillBillRuntimeException> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot =
            snapshotWithObservability(
              event = snapshotWithObservabilityEvent() + ("changed_files" to listOf(123)),
            ),
        ).withDecodedGoalObservability().toMcpMap()
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }
    assertContains(changedFilesError.message.orEmpty(), "changed_files[0]")

    val samplePathsError =
      assertFailsWith<SkillBillRuntimeException> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot =
            snapshotWithObservability(
              event =
                snapshotWithObservabilityEvent() + (
                  "changed_file_summary" to
                    mapOf(
                      "total" to 1,
                      "added" to 0,
                      "modified" to 1,
                      "deleted" to 0,
                      "renamed" to 0,
                      "untracked" to 0,
                      "sample_paths" to "not-an-array",
                    )
                ),
            ),
        ).withDecodedGoalObservability().toMcpMap()
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }
    assertContains(samplePathsError.message.orEmpty(), "changed_file_summary.sample_paths")
  }

  @Test
  fun `workflow mapper loud-fails schema-invalid scalar coercion`() {
    val issueKeyError =
      assertFailsWith<SkillBillRuntimeException> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("issue_key" to 61)),
        ).withDecodedGoalObservability().toMcpMap()
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }
    assertContains(issueKeyError.message.orEmpty(), "issue_key")

    val subtaskIdError =
      assertFailsWith<SkillBillRuntimeException> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("subtask_id" to "1")),
        ).withDecodedGoalObservability().toMcpMap()
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }
    assertContains(subtaskIdError.message.orEmpty(), "subtask_id")

    val timestampError =
      assertFailsWith<SkillBillRuntimeException> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("timestamp" to 20260601)),
        ).withDecodedGoalObservability().toMcpMap()
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }
    assertContains(timestampError.message.orEmpty(), "timestamp")
  }

  @Test
  fun `workflow mapper loud-fails schema-only invalid heavy goal observability fields`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot =
            snapshotWithObservability(
              event = snapshotWithObservabilityEvent() + ("changed_files" to List(501) { "file-$it.kt" }),
            ),
        ).withDecodedGoalObservability().toMcpMap()
      }.also { assertEquals(InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA, it.code) }

    assertContains(error.message.orEmpty(), "changed_files")
  }

  @Test
  fun `continue mapper rejects decomposition results with an unsupported operation`() {
    val results =
      listOf(
        WorkflowContinueResult.DecompositionDone(
          dbPath = "/tmp/metrics.db",
          workflowId = "wfl-1",
          issueKey = "SKILL-1",
          decompositionStatus = "done",
        ),
        WorkflowContinueResult.DecompositionBlockedGit(
          dbPath = "/tmp/metrics.db",
          workflowId = "wfl-1",
          issueKey = "SKILL-1",
          blockedReason = "dirty tree",
        ),
      )

    val messages =
      results.map { result ->
        assertFailsWith<UnsupportedOperationException> { result.toMcpMap() }.message
      }

    assertEquals(
      listOf(
        "DecompositionDone is not produced for verify workflows",
        "DecompositionBlockedGit is not produced for verify workflows",
      ),
      messages,
    )
  }

  private fun snapshotWithObservability(
    event: Map<String, Any?> = snapshotWithObservabilityEvent(),
  ): WorkflowSnapshotView =
    WorkflowSnapshotView(
      workflowId = "wfl-1",
      sessionId = "fis-1",
      workflowName = "bill-feature-task",
      contractVersion = "0.1",
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "implement",
      steps = listOf(WorkflowStepState("implement", WorkflowStepStatus.RUNNING, 1)),
      artifacts =
        DurableWorkflowArtifacts.fromMap(
          mapOf(
            "goal_observability_latest_event" to event,
          ),
        ),
      startedAt = "2026-06-01 00:00:00",
      updatedAt = "2026-06-01 00:00:00",
      finishedAt = "",
    )

  private fun snapshotWithObservabilityEvent(): Map<String, Any?> =
    mapOf(
      "contract_version" to "0.2",
      "issue_key" to "SKILL-61",
      "subtask_id" to 1,
      "workflow_phase" to "implement",
      "worker_role" to "phase_subagent",
      "liveness_class" to "durable_progress",
      "activity_summary" to "editing",
      "sequence_number" to 1,
      "timestamp" to "2026-06-01T00:00:00Z",
      "changed_files" to listOf("heavy.kt"),
    )

  private fun WorkflowGetResult.Ok.withDecodedGoalObservability(): WorkflowGetResult.Ok =
    copy(
      goalObservability =
        goalObservabilityLatestEventFromArtifacts(snapshot.artifacts),
    )
}
