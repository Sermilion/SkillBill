package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.telemetry.StaleSessionReconciliationPolicy
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.infrastructure.sqlite.telemetry.reconcileStaleFeatureTaskRuntimeSessions
import skillbill.infrastructure.sqlite.telemetry.reconcileStaleTelemetrySessions
import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import java.nio.file.Files
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StaleSessionReconcilerTest {
  @Test
  fun `ordinary duplicate finishes enqueue one terminal event for every lifecycle family`() {
    val dbPath = Files.createTempDirectory("duplicate-lifecycle-finish").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedStaleLifecycleSessions(connection)
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)

      repeat(2) {
        store.featureTaskRuntimeFinished(featureTaskRuntimeFinishedRecord("ftr-stale"), level = "full")
        store.featureVerifyFinished(featureVerifyFinishedRecord("fvs-stale"), level = "full")
        store.qualityCheckFinished(qualityCheckFinishedRecord("qcs-stale"), level = "full")
      }

      assertEquals(1, eventCount(connection, "skillbill_feature_task_runtime_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_verify_finished"))
      assertEquals(1, eventCount(connection, "skillbill_quality_check_finished"))
      val verifyFinished = payload(connection, "skillbill_feature_verify_finished")
      assertEquals("measured", verifyFinished["duration_seconds_availability"])
      assertIs<Number>(verifyFinished["duration_seconds"])
      listOf(
        "feature_task_runtime_sessions" to "ftr-stale",
        "feature_verify_sessions" to "fvs-stale",
        "quality_check_sessions" to "qcs-stale",
      ).forEach { (tableName, sessionId) ->
        assertEquals(1, columnValue(connection, tableName, sessionId, "duplicate_terminal_finished_events"))
      }
    }
  }

  @Test
  fun `durable cadence and global batch budget drain backlog across eligible runs`() {
    val dbPath = Files.createTempDirectory("bounded-reconciliation").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedStaleLifecycleSessions(connection)
      val now = Instant.parse("2030-01-01T00:00:00Z")
      val request =
        TelemetryReconciliationRequest(
          level = "full",
          cadenceSeconds = 300,
          maximumBatchSize = 2,
          now = now,
        )

      assertEquals(2, reconcileStaleTelemetrySessions(connection, request).processedCandidates)
      assertTrue(reconcileStaleTelemetrySessions(connection, request).skippedByCadence)
      assertEquals(
        1,
        reconcileStaleTelemetrySessions(connection, request.copy(now = now.plusSeconds(301))).processedCandidates,
      )
      assertEquals(3, terminalLifecycleEventCount(connection))
      assertEquals(
        0,
        reconcileStaleTelemetrySessions(connection, request.copy(now = now.plusSeconds(602))).processedCandidates,
      )
      assertEquals(3, terminalLifecycleEventCount(connection))
      assertEquals(
        0,
        reconcileStaleTelemetrySessions(connection, request.copy(now = now.plusSeconds(903))).processedCandidates,
      )
    }
  }

  @Test
  fun `zero cadence forces reconciliation on every run while the batch budget stays bounded`() {
    val dbPath = Files.createTempDirectory("forced-reconciliation").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedStaleLifecycleSessions(connection)
      val now = Instant.parse("2030-01-02T00:00:00Z")
      val request =
        TelemetryReconciliationRequest(
          level = "full",
          cadenceSeconds = 0,
          maximumBatchSize = 2,
          now = now,
        )

      val first = reconcileStaleTelemetrySessions(connection, request)
      assertEquals(2, first.processedCandidates)
      assertTrue(!first.skippedByCadence)

      val second = reconcileStaleTelemetrySessions(connection, request)
      assertEquals(1, second.processedCandidates)
      assertTrue(!second.skippedByCadence)

      val third = reconcileStaleTelemetrySessions(connection, request)
      assertEquals(0, third.processedCandidates)
      assertTrue(!third.skippedByCadence)
      assertEquals(3, terminalLifecycleEventCount(connection))
    }
  }

  @Test
  fun `stale lifecycle sessions are closed and emit exactly one terminal event`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-test").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedStaleLifecycleSessions(connection)

      val reconciled = reconcileStaleTelemetrySessions(connection, Clock.systemUTC(), level = "full")

      assertEquals(0, reconciled.featureImplementSessions)
      assertEquals(1, reconciled.featureTaskRuntimeSessions)
      assertEquals(1, reconciled.featureVerifySessions)
      assertEquals(1, reconciled.qualityCheckSessions)
      assertEquals(3, reconciled.emittedTerminalEvents)
      assertEquals(null, columnValue(connection, "feature_implement_sessions", "fis-stale", "finished_at"))
      assertEquals("stale", columnValue(connection, "feature_task_runtime_sessions", "ftr-stale", "completion_status"))
      assertEquals("stale", columnValue(connection, "feature_verify_sessions", "fvs-stale", "completion_status"))
      assertEquals("stale", columnValue(connection, "quality_check_sessions", "qcs-stale", "result"))
      assertNotNull(columnValue(connection, "feature_verify_sessions", "fvs-stale", "finished_at"))
      assertEquals(1, eventCount(connection, "skillbill_feature_task_runtime_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_verify_finished"))
      assertEquals(1, eventCount(connection, "skillbill_quality_check_finished"))
      assertEquals("stale", payload(connection, "skillbill_feature_task_runtime_finished")["completion_status"])
      assertEquals("stale", payload(connection, "skillbill_feature_verify_finished")["completion_status"])
      assertEquals(
        null,
        payload(connection, "skillbill_feature_verify_finished")["duration_seconds"],
        "A reconciler-closed verify run never observed its end, so its age is not a duration.",
      )
      assertEquals(
        "unavailable_incomplete",
        payload(connection, "skillbill_feature_verify_finished")["duration_seconds_availability"],
      )
      assertEquals("stale", payload(connection, "skillbill_quality_check_finished")["result"])

      val repeated = reconcileStaleTelemetrySessions(connection, Clock.systemUTC(), level = "full")

      assertEquals(0, repeated.emittedTerminalEvents)
      assertEquals(1, eventCount(connection, "skillbill_feature_task_runtime_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_verify_finished"))
      assertEquals(1, eventCount(connection, "skillbill_quality_check_finished"))
    }
  }

  @Test
  fun `late normal finish after stale reconciliation does not duplicate terminal events`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-late-finish").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedStaleLifecycleSessions(connection)
      reconcileStaleTelemetrySessions(connection, Clock.systemUTC(), level = "full")

      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.featureTaskRuntimeFinished(featureTaskRuntimeFinishedRecord("ftr-stale"), level = "full")
      store.featureVerifyFinished(featureVerifyFinishedRecord("fvs-stale"), level = "full")
      store.qualityCheckFinished(qualityCheckFinishedRecord("qcs-stale"), level = "full")

      assertEquals(1, eventCount(connection, "skillbill_feature_task_runtime_finished"))
      assertEquals(1, eventCount(connection, "skillbill_feature_verify_finished"))
      assertEquals(1, eventCount(connection, "skillbill_quality_check_finished"))
      assertEquals(
        "stale",
        columnValue(connection, "feature_task_runtime_sessions", "ftr-stale", "completion_status"),
      )
      assertEquals("stale", columnValue(connection, "feature_verify_sessions", "fvs-stale", "completion_status"))
      assertEquals("stale", columnValue(connection, "quality_check_sessions", "qcs-stale", "result"))
      assertEquals("stale", payload(connection, "skillbill_feature_task_runtime_finished")["completion_status"])
      assertEquals("stale", payload(connection, "skillbill_feature_verify_finished")["completion_status"])
      assertEquals("stale", payload(connection, "skillbill_quality_check_finished")["result"])
      listOf(
        "feature_task_runtime_sessions" to "ftr-stale",
        "feature_verify_sessions" to "fvs-stale",
        "quality_check_sessions" to "qcs-stale",
      ).forEach { (tableName, sessionId) ->
        assertEquals(
          1,
          columnValue(connection, tableName, sessionId, "duplicate_terminal_finished_events"),
          "$tableName.$sessionId must record the duplicate terminal finish",
        )
      }
    }
  }

  @Test
  fun `old blocked goal issue is abandoned and emitted exactly once`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-goal").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedAbandonedGoalIssueCandidates(connection)

      val reconciled =
        reconcileStaleTelemetrySessions(
          connection,
          Clock.systemUTC(),
          level = "full",
          policy = StaleSessionReconciliationPolicy(goalIssueAbandonmentDays = 14),
        )

      assertEquals(1, reconciled.goalIssueAbandonedSessions)
      assertEquals(1, reconciled.emittedTerminalEvents)
      assertEquals(1, eventCount(connection, "skillbill_goal_issue_finished"))
      val payload = payload(connection, "skillbill_goal_issue_finished", issueKey = "SKILL-109")
      assertEquals("abandoned", payload["status"])
      assertEquals(1, payload["subtasks_complete"])
      assertEquals(1, payload["subtasks_blocked"])
      assertEquals(1, payload["subtasks_skipped"])
      assertTrue((payload["duration_seconds"] as Number).toLong() > 0)
      Instant.parse(assertIs<String>(payload["first_started_at"]).also { assertTrue(it.isNotBlank()) })
      Instant.parse(assertIs<String>(payload["finished_at"]).also { assertTrue(it.isNotBlank()) })
      assertEquals("abandoned", goalColumnValue(connection, "SKILL-109", "status"))
      Instant.parse(assertIs<String>(goalColumnValue(connection, "SKILL-109", "state_entered_at")))
      assertEquals(0, goalColumnValue(connection, "SKILL-109", "state_entered_at_estimated"))
      assertEquals(null, goalColumnValue(connection, "SKILL-111", "status"))
      assertEquals(null, goalColumnValue(connection, "SKILL-110", "status"))

      val repeated =
        reconcileStaleTelemetrySessions(
          connection,
          Clock.systemUTC(),
          level = "full",
          policy = StaleSessionReconciliationPolicy(goalIssueAbandonmentDays = 14),
        )

      assertEquals(0, repeated.goalIssueAbandonedSessions)
      assertEquals(1, eventCount(connection, "skillbill_goal_issue_finished"))
    }
  }

  @Test
  fun `stale goal abandonment advances state entry beyond a future value`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-goal-state-entry").resolve("metrics.db")
    val futureStateEntry = "2999-05-01T12:00:00.123456789Z"
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      seedAbandonedGoalIssueCandidates(connection)
      connection.prepareStatement(
        "UPDATE goal_issue_progress SET state_entered_at = ?, state_entered_at_estimated = 1 WHERE issue_key = ?",
      ).use { statement ->
        statement.setString(1, futureStateEntry)
        statement.setString(2, "SKILL-109")
        statement.executeUpdate()
      }

      reconcileStaleTelemetrySessions(
        connection,
        Clock.systemUTC(),
        level = "full",
        policy = StaleSessionReconciliationPolicy(goalIssueAbandonmentDays = 14),
      )

      assertEquals("abandoned", goalColumnValue(connection, "SKILL-109", "status"))
      assertTrue(
        Instant.parse(goalColumnValue(connection, "SKILL-109", "state_entered_at") as String)
          .isAfter(Instant.parse(futureStateEntry)),
      )
      assertEquals(0, goalColumnValue(connection, "SKILL-109", "state_entered_at_estimated"))
    }
  }

  private fun seedAbandonedGoalIssueCandidates(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        """
        INSERT INTO goal_issue_progress (
          parent_workflow_id, issue_key, total_invocations, total_blocks,
          total_resumes, first_started_at, last_activity_at, last_blocked_at,
          latest_segment_workflow_id, last_blocked_segment_workflow_id, mode
        ) VALUES (
          'goal-parent', 'SKILL-109', 2, 6, 1,
          strftime('%Y-%m-%dT%H:%M:%SZ', 'now', '-20 days'),
          datetime('now', '-20 days'), datetime('now', '-20 days'),
          'goal-parent:seg:2', 'goal-parent:seg:2', 'runtime'
        )
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO goal_issue_progress (
          parent_workflow_id, issue_key, total_invocations, total_blocks,
          total_resumes, first_started_at, last_activity_at, last_blocked_at,
          latest_segment_workflow_id, last_blocked_segment_workflow_id, mode
        ) VALUES (
          'goal-parent', 'SKILL-110', 1, 1, 0,
          datetime('now', '-20 days'), datetime('now', '-1 days'), datetime('now', '-20 days'),
          'goal-parent:seg:active', 'goal-parent:seg:old-block', 'runtime'
        )
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO goal_issue_progress (
          parent_workflow_id, issue_key, total_invocations, total_blocks,
          total_resumes, first_started_at, last_activity_at, latest_segment_workflow_id, mode
        ) VALUES (
          'goal-parent', 'SKILL-111', 1, 0, 0,
          datetime('now', '-20 days'), datetime('now', '-20 days'), 'goal-parent:seg:started-only', 'runtime'
        )
        """.trimIndent(),
      )
      statement.executeUpdate(abandonedGoalRunSegmentsSql())
    }
  }

  private fun abandonedGoalRunSegmentsSql(): String =
    """
    INSERT INTO goal_run_sessions (
      workflow_id, issue_key, subtask_total, resumed, started_at, status,
      finished_at, finished_duration_ms, subtasks_complete, subtasks_blocked, subtasks_skipped, mode
    ) VALUES
      (
        'goal-parent:seg:1', 'SKILL-109', 3, 0, datetime('now', '-21 days'), 'blocked',
        datetime('now', '-21 days'), 1000, 0, 2, 0, 'runtime'
      ),
      (
        'goal-parent:seg:2', 'SKILL-109', 3, 1, datetime('now', '-20 days'), 'blocked',
        datetime('now', '-20 days'), 1000, 1, 1, 1, 'runtime'
      ),
      (
        'other-parent:seg:1', 'SKILL-109', 3, 0, datetime('now', '-1 days'), 'completed',
        datetime('now', '-1 days'), 1000, 3, 0, 0, 'runtime'
      )
    """.trimIndent()

  @Test
  fun `recent workflow activity prevents age-only stale reconciliation`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-liveness").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_runtime_sessions (session_id, started_at, finished_at, completion_status)
          VALUES ('ftr-active', datetime('now', '-40000 seconds'), NULL, '')
          """.trimIndent(),
        )
        statement.executeUpdate(
          """
          INSERT INTO feature_task_workflows (
            workflow_id, session_id, mode, contract_version, workflow_status, updated_at
          ) VALUES ('wfl-active', 'ftr-active', 'runtime', '1.1.0', 'running', CURRENT_TIMESTAMP)
          """.trimIndent(),
        )
      }

      val reconciled = reconcileStaleTelemetrySessions(connection, Clock.systemUTC(), level = "full")

      assertEquals(0, reconciled.featureTaskRuntimeSessions)
      assertEquals(null, columnValue(connection, "feature_task_runtime_sessions", "ftr-active", "finished_at"))
      assertEquals(0, eventCount(connection, "skillbill_feature_task_runtime_finished"))
    }
  }

  @Test
  fun `legacy entry points still reconcile their lifecycle family`() {
    val dbPath = Files.createTempDirectory("stale-reconciler-legacy-entry").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO feature_task_runtime_sessions (session_id, started_at, finished_at, completion_status)
          VALUES ('ftr-legacy', datetime('now', '-40000 seconds'), NULL, '')
          """.trimIndent(),
        )
      }

      assertEquals(1, reconcileStaleFeatureTaskRuntimeSessions(connection, thresholdSeconds = 28_800L))
      assertEquals(1, eventCount(connection, "skillbill_feature_task_runtime_finished"))
    }
  }

  private fun seedStaleLifecycleSessions(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        """
        INSERT INTO feature_implement_sessions (session_id, started_at, finished_at, completion_status)
        VALUES ('fis-stale', datetime('now', '-40000 seconds'), NULL, '')
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO feature_task_runtime_sessions (session_id, started_at, finished_at, completion_status)
        VALUES ('ftr-stale', datetime('now', '-40000 seconds'), NULL, '')
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO feature_verify_sessions (session_id, started_at, finished_at, completion_status)
        VALUES ('fvs-stale', datetime('now', '-40000 seconds'), NULL, '')
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO quality_check_sessions (session_id, started_at, finished_at, result)
        VALUES ('qcs-stale', datetime('now', '-40000 seconds'), NULL, NULL)
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO feature_implement_sessions (session_id, started_at, finished_at, completion_status)
        VALUES ('fis-fresh', datetime('now', '-1000 seconds'), NULL, '')
        """.trimIndent(),
      )
    }
  }

  private fun featureTaskRuntimeFinishedRecord(sessionId: String): FeatureTaskRuntimeFinishedRecord =
    FeatureTaskRuntimeFinishedRecord(
      sessionId = sessionId,
      completionStatus = "completed",
      completedPhaseIds = listOf("implement", "review"),
      phaseOutcomes = mapOf("implement" to "completed", "review" to "completed"),
      lastIncompletePhase = "completed",
      blockedReason = "",
      resolvedBranch = "feat/SKILL-109",
      reviewFixIterationCount = 0,
      estimatedPhaseTokenBreakdownJson = null,
      estimatedTotalTokens = null,
    )

  private fun featureVerifyFinishedRecord(sessionId: String): FeatureVerifyFinishedRecord =
    FeatureVerifyFinishedRecord(
      sessionId = sessionId,
      featureFlagAuditPerformed = true,
      reviewIterations = 1,
      auditResult = "all_pass",
      completionStatus = "completed",
      historyRelevance = "medium",
      historyHelpfulness = "medium",
      gapsFound = emptyList(),
    )

  private fun qualityCheckFinishedRecord(sessionId: String): QualityCheckFinishedRecord =
    QualityCheckFinishedRecord(
      sessionId = sessionId,
      routedSkill = "bill-code-check",
      detectedStack = "kotlin",
      fallback = false,
      fallbackReason = null,
      scopeType = "branch_diff",
      initialFailureCount = 0,
      finalFailureCount = 0,
      iterations = 1,
      result = "pass",
      failingCheckNames = emptyList(),
      unsupportedReason = "",
    )

  private fun eventCount(
    connection: Connection,
    eventName: String,
  ): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM telemetry_outbox WHERE event_name = ?").use { statement ->
      statement.setString(1, eventName)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getInt(1)
      }
    }

  private fun terminalLifecycleEventCount(connection: Connection): Int =
    listOf(
      "skillbill_feature_task_runtime_finished",
      "skillbill_feature_verify_finished",
      "skillbill_quality_check_finished",
    ).sumOf { eventCount(connection, it) }

  private fun columnValue(
    connection: Connection,
    tableName: String,
    sessionId: String,
    columnName: String,
  ): Any? =
    connection.prepareStatement("SELECT $columnName FROM $tableName WHERE session_id = ?").use { statement ->
      statement.setString(1, sessionId)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getObject(1)
      }
    }

  private fun goalColumnValue(
    connection: Connection,
    issueKey: String,
    columnName: String,
  ): Any? =
    connection.prepareStatement("SELECT $columnName FROM goal_issue_progress WHERE issue_key = ?").use { statement ->
      statement.setString(1, issueKey)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getObject(1)
      }
    }

  private fun payload(
    connection: Connection,
    eventName: String,
    issueKey: String? = null,
  ): Map<String, Any?> {
    val issueFilter = if (issueKey == null) "" else "AND json_extract(payload_json, '$.issue_key') = ?"
    return connection.prepareStatement(
      """
      SELECT payload_json
      FROM telemetry_outbox
      WHERE event_name = ?
      $issueFilter
      ORDER BY id DESC
      LIMIT 1
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, eventName)
      issueKey?.let { statement.setString(2, it) }
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        val element = requireNotNull(JsonCodec.parseObjectOrNull(resultSet.getString("payload_json")))
        requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(element)))
      }
    }
  }
}
