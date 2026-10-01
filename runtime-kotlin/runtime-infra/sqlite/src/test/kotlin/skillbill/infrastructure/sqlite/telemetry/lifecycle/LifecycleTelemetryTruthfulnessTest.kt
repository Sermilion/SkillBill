package skillbill.infrastructure.sqlite.telemetry.lifecycle

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleSessionCompletion
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.contracts.telemetry.TelemetryMeasurementAvailability
import skillbill.infrastructure.sqlite.SqliteTestDiagnostics
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.ensureDatabase
import skillbill.infrastructure.sqlite.reconcileStaleTelemetrySessions
import skillbill.infrastructure.sqlite.telemetry.LifecycleStaleReason
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRejectionViolationClass
import java.nio.file.Files
import java.sql.Connection
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ISSUE_KEY = "SKILL-236"
private const val RUNTIME_WORKFLOW_ID = "wftr-20260915-091657-8czi"

class LifecycleTelemetryTruthfulnessTest {
  @Test
  fun `a quality check the reconciler closed reports no failure count and a reconciler completion`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.qualityCheckStarted(startedQualityCheck(), "anonymous")
      ageQualityCheckStart(connection)

      reconcileStaleTelemetrySessions(connection, Clock.systemUTC(), "anonymous")

      val payload = payloadFor(connection, "skillbill_quality_check_finished")
      assertEquals(
        LifecycleSessionCompletion.RECONCILER_STALE.wireValue,
        payload[LifecycleTelemetryPayloadKeys.COMPLETION],
      )
      assertNull(
        payload[LifecycleTelemetryPayloadKeys.FINAL_FAILURE_COUNT],
        "a check that never reported back must not claim a failure count",
      )
      assertEquals(
        TelemetryMeasurementAvailability.UNAVAILABLE_INCOMPLETE.wireValue,
        payload[LifecycleTelemetryPayloadKeys.FINAL_FAILURE_COUNT_AVAILABILITY],
      )
      assertEquals(
        LifecycleStaleReason.NO_TERMINAL_BEFORE_THRESHOLD.wireValue,
        payload[LifecycleTelemetryPayloadKeys.STALE_REASON],
      )
    }
  }

  @Test
  fun `a check the operator finished with nothing failing still reports a measured clean zero`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.qualityCheckStarted(startedQualityCheck(), "anonymous")
      store.qualityCheckFinished(finishedQualityCheck(), "anonymous")

      val payload = payloadFor(connection, "skillbill_quality_check_finished")
      assertEquals(
        LifecycleSessionCompletion.OPERATOR_COMPLETED.wireValue,
        payload[LifecycleTelemetryPayloadKeys.COMPLETION],
      )
      assertEquals(
        TelemetryMeasurementAvailability.MEASURED.wireValue,
        payload[LifecycleTelemetryPayloadKeys.FINAL_FAILURE_COUNT_AVAILABILITY],
      )
      assertEquals(
        0,
        (payload[LifecycleTelemetryPayloadKeys.FINAL_FAILURE_COUNT] as Number).toInt(),
        "a real clean gate must stay countable as clean, not get nulled out with the stale ones",
      )
    }
  }

  @Test
  fun `a run with no durable budget state reports unavailable rather than an intact budget`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeStarted(startedRuntimeSession(), "anonymous")
      store.featureTaskRuntimeFinished(finishedRuntimeSession(), "anonymous")

      val payload = payloadFor(connection, "skillbill_feature_task_runtime_finished")
      assertEquals(
        TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE.wireValue,
        payload[LifecycleTelemetryPayloadKeys.REVIEW_FIX_CAP_EXHAUSTED_AVAILABILITY],
      )
      assertNull(
        payload[LifecycleTelemetryPayloadKeys.REVIEW_FIX_CAP_EXHAUSTED],
        "an unmeasured review-fix budget must not read as a budget measured intact",
      )
      assertEquals(
        TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE.wireValue,
        payload[LifecycleTelemetryPayloadKeys.AUDIT_GAP_AVAILABILITY],
      )
      assertNull(payload[LifecycleTelemetryPayloadKeys.AUDIT_GAP_ITERATION_COUNT])
      assertNull(payload[LifecycleTelemetryPayloadKeys.AUDIT_FIRST_PASS_CONVERGENCE])
    }
  }

  @Test
  fun `a measured budget reports its value and a measured zero stays distinguishable from absent`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeStarted(startedRuntimeSession(), "anonymous")
      store.featureTaskRuntimeFinished(
        finishedRuntimeSession().copy(reviewFixCapExhausted = false, auditGapIterationCount = 0),
        "anonymous",
      )

      val payload = payloadFor(connection, "skillbill_feature_task_runtime_finished")
      assertEquals(
        TelemetryMeasurementAvailability.MEASURED.wireValue,
        payload[LifecycleTelemetryPayloadKeys.REVIEW_FIX_CAP_EXHAUSTED_AVAILABILITY],
      )
      assertEquals(false, payload[LifecycleTelemetryPayloadKeys.REVIEW_FIX_CAP_EXHAUSTED])
      assertEquals(0, (payload[LifecycleTelemetryPayloadKeys.AUDIT_GAP_ITERATION_COUNT] as Number).toInt())
      assertEquals(true, payload[LifecycleTelemetryPayloadKeys.AUDIT_FIRST_PASS_CONVERGENCE])
    }
  }

  @Test
  fun `correlation identifiers join started to finished without exposing the raw issue key`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeStarted(startedRuntimeSession(), "anonymous")
      store.featureTaskRuntimeFinished(finishedRuntimeSession(), "anonymous")

      val started = payloadFor(connection, "skillbill_feature_task_runtime_started")
      val finished = payloadFor(connection, "skillbill_feature_task_runtime_finished")
      val redacted = started[LifecycleTelemetryPayloadKeys.REDACTED_WORKFLOW_ID] as String
      assertFalse(redacted.contains(ISSUE_KEY), "the workflow id must not carry the raw issue key at anonymous")
      assertEquals(
        redacted,
        finished[LifecycleTelemetryPayloadKeys.REDACTED_WORKFLOW_ID],
        "both events must redact the same workflow id to the same value, or the join breaks",
      )
      assertEquals(
        TelemetryMeasurementAvailability.MEASURED.wireValue,
        finished[LifecycleTelemetryPayloadKeys.CORRELATION_AVAILABILITY],
      )
      assertEquals(2, (finished[LifecycleTelemetryPayloadKeys.GOAL_SUBTASK_ID] as Number).toInt())
      assertTrue(
        (finished[LifecycleTelemetryPayloadKeys.GOAL_PARENT_WORKFLOW_ID] as String).isNotBlank(),
      )
    }
  }

  @Test
  fun `a run reports the agents and models its phases resolved and declares the ones it never learned`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeStarted(startedRuntimeSession(), "anonymous")
      store.featureTaskRuntimeFinished(
        finishedRuntimeSession().copy(resolvedAgentIds = listOf("claude", "codex"), launchedModels = null),
        "anonymous",
      )

      val payload = payloadFor(connection, "skillbill_feature_task_runtime_finished")
      assertEquals(
        TelemetryMeasurementAvailability.MEASURED.wireValue,
        payload[LifecycleTelemetryPayloadKeys.RESOLVED_AGENT_AVAILABILITY],
      )
      assertEquals(
        listOf("claude", "codex"),
        payload[LifecycleTelemetryPayloadKeys.RESOLVED_AGENT_IDS],
        "a run whose phases resolved two agents must report both, not let one stand for the run",
      )
      assertEquals(
        TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE.wireValue,
        payload[LifecycleTelemetryPayloadKeys.LAUNCHED_MODEL_AVAILABILITY],
        "no phase carried a model directive, which is unknown rather than a measured empty set",
      )
      assertNull(payload[LifecycleTelemetryPayloadKeys.LAUNCHED_MODELS])
      assertTrue(LifecycleTelemetryPayloadKeys.LAUNCHED_MODELS in payload)
    }
  }

  @Test
  fun `a run that resolved no agent reports unavailable rather than an empty agent set`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeStarted(startedRuntimeSession(), "anonymous")
      store.featureTaskRuntimeFinished(finishedRuntimeSession(), "anonymous")

      val payload = payloadFor(connection, "skillbill_feature_task_runtime_finished")
      assertEquals(
        TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE.wireValue,
        payload[LifecycleTelemetryPayloadKeys.RESOLVED_AGENT_AVAILABILITY],
      )
      assertNull(payload[LifecycleTelemetryPayloadKeys.RESOLVED_AGENT_IDS])
    }
  }

  @Test
  fun `corrupt agent id arrays report incomplete availability instead of absent durable state`() {
    val payload =
      featureTaskRuntimeFinishedPayload(
        mapOf(
          "session_id" to "ftr-truth",
          "workflow_id" to RUNTIME_WORKFLOW_ID,
          "issue_key" to ISSUE_KEY,
          LifecycleTelemetryPayloadKeys.RESOLVED_AGENT_IDS to "{not-an-array}",
          LifecycleTelemetryPayloadKeys.LAUNCHED_MODELS to "{not-an-array}",
        ),
        level = "full",
        salt = "salt",
        diagnostics = SqliteTestDiagnostics,
      )
    assertEquals(
      TelemetryMeasurementAvailability.UNAVAILABLE_INCOMPLETE.wireValue,
      payload[LifecycleTelemetryPayloadKeys.RESOLVED_AGENT_AVAILABILITY],
    )
    assertEquals(
      TelemetryMeasurementAvailability.UNAVAILABLE_INCOMPLETE.wireValue,
      payload[LifecycleTelemetryPayloadKeys.LAUNCHED_MODEL_AVAILABILITY],
    )
  }

  @Test
  fun `whitespace formatted empty agent and model arrays remain valid unavailable measurements`() {
    val payload =
      featureTaskRuntimeFinishedPayload(
        mapOf(
          "session_id" to "ftr-whitespace",
          "workflow_id" to RUNTIME_WORKFLOW_ID,
          "issue_key" to ISSUE_KEY,
          LifecycleTelemetryPayloadKeys.RESOLVED_AGENT_IDS to "[ ]",
          LifecycleTelemetryPayloadKeys.LAUNCHED_MODELS to "[ ]",
        ),
        level = "full",
        salt = "salt",
        diagnostics = SqliteTestDiagnostics,
      )

    assertEquals(
      TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE.wireValue,
      payload[LifecycleTelemetryPayloadKeys.RESOLVED_AGENT_AVAILABILITY],
    )
    assertEquals(
      TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE.wireValue,
      payload[LifecycleTelemetryPayloadKeys.LAUNCHED_MODEL_AVAILABILITY],
    )
  }

  @Test
  fun `a row written before the availability columns existed reports unknown and no value`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeStarted(startedRuntimeSession(), "anonymous")
      store.featureTaskRuntimeFinished(finishedRuntimeSession(), "anonymous")
      clearAvailabilityColumns(connection)

      val payload =
        featureTaskRuntimeFinishedPayload(
          sessionRow(connection, "ftr-truth"),
          level = "anonymous",
          salt = "salt",
          diagnostics = SqliteTestDiagnostics,
        )
      listOf(
        LifecycleTelemetryPayloadKeys.REVIEW_FIX_CAP_EXHAUSTED_AVAILABILITY,
        LifecycleTelemetryPayloadKeys.AUDIT_GAP_AVAILABILITY,
      ).forEach { key ->
        assertEquals(
          TelemetryMeasurementAvailability.UNKNOWN.wireValue,
          payload[key],
          "a migrated row must stay unknown rather than inherit a measurement nobody took",
        )
      }
      assertNull(payload[LifecycleTelemetryPayloadKeys.REVIEW_FIX_CAP_EXHAUSTED])
      assertNull(payload[LifecycleTelemetryPayloadKeys.AUDIT_GAP_ITERATION_COUNT])
      assertNull(payload[LifecycleTelemetryPayloadKeys.AUDIT_FIRST_PASS_CONVERGENCE])
    }
  }

  @Test
  fun `a verify run with no durable timestamps reports no duration rather than a measured zero`() {
    val payload =
      featureVerifyFinishedPayload(
        mapOf(
          LifecycleTelemetryPayloadKeys.SESSION_ID to "fv-truth",
          LifecycleTelemetryPayloadKeys.COMPLETION_STATUS to "completed",
        ),
        level = "anonymous",
        diagnostics = SqliteTestDiagnostics,
      )

    assertNull(
      payload[LifecycleTelemetryPayloadKeys.DURATION_SECONDS],
      "a run whose start and end were never recorded must not report a zero-second duration",
    )
    assertEquals(
      TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE.wireValue,
      payload[LifecycleTelemetryPayloadKeys.DURATION_SECONDS_AVAILABILITY],
    )
  }

  @Test
  fun `a rejection measurement joins its own lifecycle run on one identifier at anonymous consent`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeStarted(goalChildRuntimeSession(), "anonymous")
      store.featureTaskRuntimeFinished(finishedRuntimeSession(), "anonymous")
      store.featureTaskRuntimeRejection(
        FeatureTaskRuntimeRejectionMeasurement(
          workflowId = RUNTIME_WORKFLOW_ID,
          phaseId = "implement",
          iteration = 1,
          rule = "maxLength",
          pointerPath = "/produced_outputs/value",
          violationClass = FeatureTaskRuntimeRejectionViolationClass.LENGTH,
          exhaustedFixLoop = true,
        ),
      )

      val finished = payloadFor(connection, "skillbill_feature_task_runtime_finished")
      val rejection = payloadFor(connection, "skillbill_feature_task_runtime_rejection")
      assertEquals(
        finished[LifecycleTelemetryPayloadKeys.REDACTED_WORKFLOW_ID],
        rejection[SharedPayloadKeys.WORKFLOW_ID],
        "a rejection that cannot be joined back to its run is an unattributable count",
      )
      listOf(finished, rejection).forEach { payload ->
        assertFalse(
          payload.values.filterIsInstance<String>().any { it.contains(ISSUE_KEY) },
          "anonymous consent must keep the raw issue key off the wire in every event family: $payload",
        )
      }
    }
  }

  @Test
  fun `a standalone run reports goal linkage as explicitly unknown rather than as a blank join key`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeStarted(
        goalChildRuntimeSession().copy(goalParentWorkflowId = null, goalSubtaskId = null),
        "anonymous",
      )
      store.featureTaskRuntimeFinished(finishedRuntimeSession(), "anonymous")

      val finished = payloadFor(connection, "skillbill_feature_task_runtime_finished")
      assertEquals(
        TelemetryMeasurementAvailability.MEASURED.wireValue,
        finished[LifecycleTelemetryPayloadKeys.CORRELATION_AVAILABILITY],
        "the run's own workflow identity is known even when no goal owns it",
      )
      assertNull(
        finished[LifecycleTelemetryPayloadKeys.GOAL_PARENT_WORKFLOW_ID],
        "a standalone run has no goal parent, and a blank id would join every standalone run together",
      )
      assertNull(finished[LifecycleTelemetryPayloadKeys.GOAL_SUBTASK_ID])
      assertTrue(
        LifecycleTelemetryPayloadKeys.GOAL_PARENT_WORKFLOW_ID in finished,
        "the key must be present and null so a consumer reads unknown rather than missing-by-accident",
      )
    }
  }

  private fun startedQualityCheck(): QualityCheckStartedRecord =
    QualityCheckStartedRecord(
      sessionId = "qc-stale",
      routedSkill = "bill-kotlin-code-check",
      detectedStack = "kotlin",
      fallback = false,
      fallbackReason = null,
      scopeType = "branch_diff",
      initialFailureCount = 4,
    )

  private fun finishedQualityCheck(): QualityCheckFinishedRecord =
    QualityCheckFinishedRecord(
      sessionId = "qc-stale",
      routedSkill = "bill-kotlin-code-check",
      detectedStack = "kotlin",
      fallback = false,
      fallbackReason = null,
      scopeType = "branch_diff",
      initialFailureCount = 4,
      finalFailureCount = 0,
      iterations = 2,
      result = "pass",
      failingCheckNames = emptyList(),
      unsupportedReason = "",
    )

  private fun startedRuntimeSession(): FeatureTaskRuntimeStartedRecord =
    FeatureTaskRuntimeStartedRecord(
      sessionId = "ftr-truth",
      featureSize = "MEDIUM",
      issueKey = ISSUE_KEY,
      featureName = "telemetry truth",
      workflowId = "$ISSUE_KEY:subtask:2",
      goalParentWorkflowId = "$ISSUE_KEY:parent",
      goalSubtaskId = 2,
    )

  private fun goalChildRuntimeSession(): FeatureTaskRuntimeStartedRecord =
    startedRuntimeSession().copy(
      workflowId = RUNTIME_WORKFLOW_ID,
      goalParentWorkflowId = "wftr-20260915-084500-parent",
    )

  private fun finishedRuntimeSession(): FeatureTaskRuntimeFinishedRecord =
    FeatureTaskRuntimeFinishedRecord(
      sessionId = "ftr-truth",
      completionStatus = "completed",
      completedPhaseIds = listOf("implement"),
      phaseOutcomes = mapOf("implement" to "completed"),
      lastIncompletePhase = "completed",
      blockedReason = "",
      resolvedBranch = "codex/$ISSUE_KEY",
    )

  private fun clearAvailabilityColumns(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        """
        UPDATE feature_task_runtime_sessions SET
          review_fix_cap_exhausted_availability = NULL,
          audit_gap_availability = NULL,
          audit_gap_iteration_count = NULL
        WHERE session_id = 'ftr-truth'
        """.trimIndent(),
      )
    }
  }

  private fun sessionRow(
    connection: Connection,
    sessionId: String,
  ): Map<String, Any?> =
    connection.prepareStatement("SELECT * FROM feature_task_runtime_sessions WHERE session_id = ?").use { statement ->
      statement.bindAll(sessionId)
      statement.executeQuery().use { resultSet ->
        assertTrue(resultSet.next(), "expected a session row for $sessionId")
        val metaData = resultSet.metaData
        (1..metaData.columnCount).associate { index ->
          metaData.getColumnLabel(index) to resultSet.getObject(index)
        }
      }
    }

  private fun ageQualityCheckStart(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        "UPDATE quality_check_sessions SET started_at = datetime('now', '-30 days') WHERE session_id = 'qc-stale'",
      )
    }
  }

  private fun payloadFor(
    connection: Connection,
    eventName: String,
  ): Map<String, Any?> =
    connection.prepareStatement("SELECT payload_json FROM telemetry_outbox WHERE event_name = ? ORDER BY id DESC")
      .use { statement ->
        statement.bindAll(eventName)
        statement.executeQuery().use { resultSet ->
          assertTrue(resultSet.next(), "expected a queued $eventName row")
          JsonCodec.parseObjectOrNull(resultSet.getString("payload_json"))
            ?.let(JsonCodec::jsonElementToValue)
            ?.let(JsonCodec::anyToStringAnyMap)
            .orEmpty()
        }
      }

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("skillbill-telemetry-truth").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }
}
