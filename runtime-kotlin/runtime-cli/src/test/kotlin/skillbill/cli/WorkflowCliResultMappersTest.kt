package skillbill.cli

import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.persist.WorkflowWireProjections
import skillbill.cli.goal.run.toGoalDiffStatCliMap
import skillbill.cli.goal.run.toGoalSelectedDiffHunksCliMap
import skillbill.cli.kernel.payload.toPayload
import skillbill.cli.workflow.toCliMap
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.error.shellcontent.InvalidGoalObservabilityEventSchemaError
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalobservability.GoalObservabilityDiffStat
import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunks
import skillbill.workflow.model.goalobservability.goalObservabilityLatestEventFromArtifacts
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowCliResultMappersTest {
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
      ).toPayload()

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
  fun `workflow acknowledgement wire bytes remain identical to the captured baseline`() {
    val acknowledgement =
      WorkflowUpdateAcknowledgementView(
        status = "ok",
        workflowId = "wf-1",
        workflowName = "bill-feature",
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "implement",
        updatedStepIds = listOf("implement"),
        updatedArtifactKeys = emptyList(),
        readOnlyFullStateGuidance = "guidance",
      )
    val actual =
      JsonCodec.mapToJsonString(
        WorkflowWireProjections.updateAcknowledgementMap(acknowledgement).toPayload(),
      ) + "\n"
    val expected =
      Files.readAllBytes(
        cliCompatibilityRepositoryRoot().resolve(
          ".feature-specs/done/SKILL-351-runtime-domain-boundaries-and-simplicity/" +
            "baselines/workflow-update-ack-wire.json",
        ),
      )

    assertEquals(expected.toList(), actual.toByteArray().toList())
  }

  @Test
  fun `runtime continue mapper returns honest ok for a recoverable run instead of false missing artifacts error`() {
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    val engine = WorkflowEngine()
    val record =
      WorkflowStateSnapshot(
        workflowId = "wftr-crashed",
        sessionId = "ftr-001",
        workflowName = definition.workflowName,
        contractVersion = definition.contractVersion,
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "plan",
        steps =
          listOf(
            WorkflowStepState("preplan", WorkflowStepStatus.COMPLETED, 1),
            WorkflowStepState("plan", WorkflowStepStatus.COMPLETED, 1),
            WorkflowStepState("implement", WorkflowStepStatus.PENDING, 0),
          ),
        artifacts =
          DurableWorkflowArtifacts.fromMap(
            requireNotNull(
              JsonCodec.anyToStringAnyMap(
                JsonCodec.parseValue(
                  """{"feature_task_runtime_phase_records":{""" +
                    """"preplan":{"contract_version":"0.2","record_kind":"private_phase_record",""" +
                    """"phase_id":"preplan","status":"completed","attempt_count":1,""" +
                    """"started_at":"2026-06-18T10:00:00Z","first_started_at":"2026-06-18T10:00:00Z",""" +
                    """"finished_at":"2026-06-18T10:01:00Z","resolved_agent_id":"agent-preplan",""" +
                    """"execution_origin":"agent-executed"},""" +
                    """"plan":{"contract_version":"0.2","record_kind":"private_phase_record",""" +
                    """"phase_id":"plan","status":"completed","attempt_count":1,""" +
                    """"started_at":"2026-06-18T10:02:00Z","first_started_at":"2026-06-18T10:02:00Z",""" +
                    """"finished_at":"2026-06-18T10:03:00Z","resolved_agent_id":"agent-plan",""" +
                    """"execution_origin":"agent-executed"}}}""",
                ),
              ),
            ),
          ),
        startedAt = Instant.parse("2026-06-18T10:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T10:03:00Z"),
        finishedAt = null,
        mode = definition.workflowMode?.let(FeatureTaskWorkflowMode::fromWireValue),
      )
    val decision =
      engine.continueDecision(
        definition,
        record,
        WorkflowContinueSessionSummary(
          acceptanceCriteriaCount = 3,
          rolloutRelevant = true,
          specSummary = "typed summary",
        ),
      )

    val mapped = WorkflowContinueResult.Standard(dbPath = "/tmp/metrics.db", view = decision.view).toCliMap()

    assertEquals("ok", mapped["status"])
    assertFalse(mapped.containsKey("error"))
    assertEquals("implement", mapped["continue_step_id"])
    assertEquals(emptyList<String>(), mapped["missing_artifacts"])
    assertEquals(
      mapOf(
        "acceptance_criteria_count" to 3,
        "rollout_relevant" to true,
        "spec_summary" to "typed summary",
      ),
      mapped["session_summary"],
    )
  }

  @Test
  fun `goal diff mapper exposes bounded selected hunk wire shape`() {
    val diffStat = GoalObservabilityDiffStat(filesChanged = 1, insertions = 2, deletions = 1).toGoalDiffStatCliMap()
    val hunks =
      GoalObservabilitySelectedDiffHunks(
        hunks =
          listOf(
            GoalObservabilitySelectedDiffHunk(
              path = "runtime.txt",
              staged = false,
              header = "@@ -1 +1 @@",
              lines = listOf("-old", "+new"),
              truncated = false,
            ),
          ),
        truncated = false,
      ).toGoalSelectedDiffHunksCliMap()

    assertEquals(1, diffStat["files_changed"])
    assertEquals(2, diffStat["insertions"])
    assertEquals(1, diffStat["deletions"])
    assertEquals(false, hunks["truncated"])
    val firstHunk = (hunks["hunks"] as List<*>).single() as Map<*, *>
    assertEquals("runtime.txt", firstHunk["path"])
    assertEquals(listOf("-old", "+new"), firstHunk["lines"])
  }

  @Test
  fun `workflow mapper exposes compact goal observability summary without heavy fields`() {
    val mapped =
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(),
      ).withDecodedGoalObservability().toCliMap()

    val observability = mapped["goal_observability"] as Map<*, *>
    assertEquals("implement", observability["workflow_phase"])
    assertEquals("phase_subagent", observability["worker_role"])
    assertEquals("durable_progress", observability["liveness_class"])
    assertFalse(observability.containsKey("changed_files"))
  }

  @Test
  fun `workflow mapper loud-fails malformed goal observability latest event`() {
    val error =
      assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot =
            snapshotWithObservability(
              event =
                mapOf(
                  "contract_version" to "0.2",
                  "subtask_id" to 1,
                  "workflow_phase" to "implement",
                  "worker_role" to "phase_subagent",
                  "liveness_class" to "durable_progress",
                  "activity_summary" to "editing",
                  "sequence_number" to 1,
                  "timestamp" to "2026-06-01T00:00:00Z",
                ),
            ),
        ).withDecodedGoalObservability().toCliMap()
      }

    assertEquals("", error.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails schema-invalid extra goal observability field`() {
    val error =
      assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("unknown" to true)),
        ).withDecodedGoalObservability().toCliMap()
      }

    assertEquals("", error.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails malformed optional goal observability summary`() {
    val error =
      assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot =
            snapshotWithObservability(
              event = snapshotWithObservabilityEvent() + ("changed_file_summary" to "not-an-object"),
            ),
        ).withDecodedGoalObservability().toCliMap()
      }

    assertEquals("changed_file_summary", error.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails malformed optional goal observability arrays`() {
    val changedFilesError =
      assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot =
            snapshotWithObservability(
              event = snapshotWithObservabilityEvent() + ("changed_files" to listOf(123)),
            ),
        ).withDecodedGoalObservability().toCliMap()
      }
    assertEquals("changed_files[0]", changedFilesError.fieldPath)

    val samplePathsError =
      assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
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
        ).withDecodedGoalObservability().toCliMap()
      }
    assertEquals("changed_file_summary.sample_paths", samplePathsError.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails schema-invalid scalar coercion`() {
    val issueKeyError =
      assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("issue_key" to 61)),
        ).withDecodedGoalObservability().toCliMap()
      }
    assertEquals("issue_key", issueKeyError.fieldPath)

    val subtaskIdError =
      assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("subtask_id" to "1")),
        ).withDecodedGoalObservability().toCliMap()
      }
    assertEquals("subtask_id", subtaskIdError.fieldPath)

    val timestampError =
      assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("timestamp" to 20260601)),
        ).withDecodedGoalObservability().toCliMap()
      }
    assertEquals("timestamp", timestampError.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails schema-only invalid heavy goal observability fields`() {
    val error =
      assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
        WorkflowGetResult.Ok(
          workflowId = "wfl-1",
          dbPath = "/tmp/metrics.db",
          snapshot =
            snapshotWithObservability(
              event = snapshotWithObservabilityEvent() + ("changed_files" to List(501) { "file-$it.kt" }),
            ),
        ).withDecodedGoalObservability().toCliMap()
      }

    assertEquals("changed_files", error.fieldPath)
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

private fun cliCompatibilityRepositoryRoot(): Path {
  var current: Path? = Path.of("").toAbsolutePath().normalize()
  while (current != null) {
    if (Files.exists(current.resolve(".git"))) return current
    current = current.parent
  }
  error("Repository root is not available from the test working directory.")
}
