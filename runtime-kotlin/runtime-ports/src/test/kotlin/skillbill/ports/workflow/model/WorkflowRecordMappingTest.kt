package skillbill.ports.workflow.model

import org.junit.jupiter.api.Test
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_PROGRESS_EVENT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.ledger.decodeDeclaredGoalProgressEvent
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePhaseLedgerEntryFromArtifact
import skillbill.workflow.taskruntime.artifact.decodePhaseRecordFromArtifact
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WorkflowRecordMappingTest {
  @Test
  fun `malformed workflow columns and invalid roots fail during mapping`() {
    val invalidRows =
      listOf(
        row().copy(stepsJson = "{"),
        row().copy(stepsJson = "{}"),
        row().copy(stepsJson = "[null]"),
        row().copy(stepsJson = "[1]"),
        row().copy(stepsJson = "null"),
        row().copy(artifactsJson = "null"),
        row().copy(stepsJson = """[{"step_id":"implement","status":"running","attempt_count":1,"unknown":true}]"""),
        row().copy(artifactsJson = "{"),
        row().copy(artifactsJson = "[]"),
      )
    invalidRows.forEach { invalid ->
      assertFailsWith<InvalidWorkflowStateSchemaError> { invalid.toSnapshot() }
    }
  }

  @Test
  fun `unchanged snapshot round trip retains persisted json and timestamp spellings`() {
    val source =
      row(
        stepsJson = "[{\"step_id\":\"implement\",\"status\":\"running\",\"attempt_count\":1}]",
        artifactsJson = "{\"amount\":1.00,\"note\":\"kept\"}",
        startedAt = "2026-06-02 10:00:00",
        updatedAt = "2026-06-02T10:00:01.000Z",
      )
    val snapshot = source.toSnapshot()

    assertEquals(Instant.parse("2026-06-02T10:00:00Z"), snapshot.startedAt)
    assertEquals(source, snapshot.mapToRecord(source))
  }

  @Test
  fun `malformed timestamps fail as typed workflow schema errors`() {
    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        row().copy(startedAt = "not-a-time").toSnapshot()
      }

    assertNotNull(error.message)
  }

  @Test
  fun `row mapping retains every supported timestamp spelling and nullable terminal columns`() {
    val spellings =
      listOf(
        "2026-06-02 10:00:00",
        "2026-06-02T10:00:00Z",
        "2026-06-02T10:00:00.000Z",
        "2026-06-02T10:00:00.123456789Z",
        "2026-06-02T12:00:00.000+02:00",
        "2026-06-02T10:00:00+00:00",
      )
    spellings.forEach { spelling ->
      val source = row().copy(startedAt = spelling, updatedAt = spelling, finishedAt = spelling)
      assertEquals(source, source.toSnapshot().mapToRecord(source))
    }
    assertEquals(row(), row().toSnapshot().mapToRecord())
  }

  @Test
  fun `encoding cannot replace a corrupt source row with an empty aggregate`() {
    val snapshot = row().toSnapshot()
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      snapshot.mapToRecord(row().copy(artifactsJson = "{"))
    }
  }

  @Test
  fun `phase ledger and progress codecs retain stored timestamp bytes at the row boundary`() {
    val phase =
      """{"contract_version":"$FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION",""" +
        """"record_kind":"private_phase_record","phase_id":"plan","status":"running","attempt_count":1,""" +
        """"started_at":"2026-06-02 10:00:00","first_started_at":"2026-06-02T12:00:00.000+02:00",""" +
        """"resolved_agent_id":"codex","execution_origin":"agent-executed"}"""
    val ledger =
      """{"action":"start","sequence_number":0,"timestamp":"2026-06-02T10:00:00.000Z","phase_id":"plan",""" +
        """"attempt_count":1,"execution_origin":"agent-executed"}"""
    val progress =
      """{"contract_version":"$GOAL_PROGRESS_EVENT_CONTRACT_VERSION","event_kind":"phase_started",""" +
        """"workflow_id":"wf-1","workflow_phase":"plan","process_alive":true,"sequence_number":0,""" +
        """"timestamp":"2026-06-02T12:00:00.000+02:00"}"""
    val source =
      row(
        artifactsJson =
          """{"feature_task_runtime_phase_records":{"plan":$phase},"feature_task_runtime_phase_ledger":[$ledger],""" +
            """"goal_progress_latest_event":$progress}""",
      )
    val phaseRecord = assertNotNull(decodePhaseRecordFromArtifact(JsonCodec.parseValue(phase)))
    val phaseLedger = assertNotNull(decodePhaseLedgerEntryFromArtifact(JsonCodec.parseValue(ledger)))
    val progressEvent =
      assertNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(progress)))
        .decodeDeclaredGoalProgressEvent("test")
    val updated =
      source.toSnapshot().copy(
        artifacts =
          DurableWorkflowArtifacts.fromMap(
            linkedMapOf(
              "feature_task_runtime_phase_records" to mapOf("plan" to phaseRecord.asWorkflowArtifactEntry()),
              "feature_task_runtime_phase_ledger" to listOf(phaseLedger.asWorkflowArtifactEntry()),
              "goal_progress_latest_event" to progressEvent.toPersistenceWire(),
            ),
          ),
      )
    assertEquals(Instant.parse("2026-06-02T10:00:00Z"), phaseRecord.startedAt)
    assertEquals(phaseRecord.startedAt, phaseRecord.firstStartedAt)
    assertEquals(source.artifactsJson, updated.mapToRecord(source).artifactsJson)
  }

  @Test
  fun `attempt ledger encoding retains legacy timestamp text through a row rewrite`() {
    val source =
      row(
        artifactsJson =
          """{"goal_attempt_ledger":[{"action":"resume","sequence_number":1,"timestamp":"2026-06-02 10:00:00"}]}""",
      )
    val entry = GoalAttemptLedgerEntry(GoalAttemptLedgerAction.RESUME, 1, "2026-06-02 10:00:00")
    val snapshot =
      source.toSnapshot().copy(
        artifacts =
          DurableWorkflowArtifacts.fromMap(
            mapOf("goal_attempt_ledger" to listOf(entry.toPersistenceWire())),
          ),
      )
    assertEquals(Instant.parse("2026-06-02T10:00:00Z"), entry.timestamp)
    assertEquals(source.artifactsJson, snapshot.mapToRecord(source).artifactsJson)
  }

  @Test
  fun `goal observability timestamp spellings survive an unrelated artifact update`() {
    val source =
      row(
        artifactsJson =
          """{"goal_observability_latest_event":{"timestamp":"2026-06-02T12:00:00.000+02:00","sequence_number":1},""" +
            """"goal_observability_run_history":[{"timestamp":"2026-06-02 10:00:00","sequence_number":0}]}""",
      )
    val updated =
      source.toSnapshot().copy(
        artifacts =
          DurableWorkflowArtifacts.fromMap(
            source.toSnapshot().artifacts.toMap() + ("unrelated" to true),
          ),
      )

    val persisted = updated.mapToRecord(source)

    assertEquals(
      """{"goal_observability_latest_event":{"timestamp":"2026-06-02T12:00:00.000+02:00","sequence_number":1},""" +
        """"goal_observability_run_history":[{"timestamp":"2026-06-02 10:00:00",""" +
        """"sequence_number":0}],"unrelated":true}""",
      persisted.artifactsJson,
    )
  }

  @Test
  fun `mapping keeps supported integer coercion and original step field order`() {
    listOf("1", "1.00", "\"1\"").forEach { attempts ->
      val source = row(stepsJson = """[{"attempt_count":$attempts,"status":"running","step_id":"implement"}]""")
      val snapshot = source.toSnapshot()
      assertEquals(1, snapshot.steps.single().attemptCount)
      assertEquals(source.stepsJson, snapshot.mapToRecord(source).stepsJson)
    }
  }

  private fun row(
    stepsJson: String = "[]",
    artifactsJson: String = "{}",
    startedAt: String? = null,
    updatedAt: String? = null,
  ) = WorkflowStateRecord(
    workflowId = "wf-1",
    sessionId = "session-1",
    workflowName = "bill-feature-task",
    contractVersion = "0.3",
    workflowStatus = WorkflowStatus.RUNNING.wireValue,
    currentStepId = "implement",
    stepsJson = stepsJson,
    artifactsJson = artifactsJson,
    startedAt = startedAt,
    updatedAt = updatedAt,
    finishedAt = null,
  )
}
