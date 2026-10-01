package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_STOP
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.GoalRunnerControlStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.LEGACY_UNKNOWN_PAUSED_AT
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalRunnerControlStoreTest {
  @Test
  fun `lease read and unrelated control update preserve offset and fractional timestamp spellings`() {
    val dbPath = Files.createTempDirectory("lease-spelling").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      store.persistControlState("parent-spelling", GoalRunnerControlState())
      val heartbeat = "2026-08-02T12:00:10.000+02:00"
      val expiry = "2026-08-02T10:00:40.123456789Z"
      writeRawControlState(
        connection,
        "parent-spelling",
        """
        {"execution_lease":{"generation":1,"owner_token":"owner-token-123456","host_identity":"host",
        "boot_identity":"boot","pid":42,"process_birth_token":"birth",
        "heartbeat_at":"$heartbeat","expires_at":"$expiry"}}
        """.trimIndent(),
      )
      val decoded = store.controlState("parent-spelling")
      assertEquals(Instant.parse("2026-08-02T10:00:10Z"), decoded.executionLease?.heartbeatAt)
      assertEquals(Instant.parse(expiry), decoded.executionLease?.expiresAt)
      store.persistControlState("parent-spelling", decoded.copy(pauseRequested = true))
      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT control_state_json FROM goal_runner_controls WHERE parent_workflow_id = 'parent-spelling'",
        ).use { rows ->
          assertTrue(rows.next())
          val raw = requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(rows.getString(1))))
          val lease = requireNotNull(JsonCodec.anyToStringAnyMap(raw["execution_lease"]))
          assertEquals(heartbeat, lease["heartbeat_at"])
          assertEquals(expiry, lease["expires_at"])
        }
      }
    }
  }

  @Test
  fun `review policy and operator acceptance remain durable outside workflow projection`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-controls").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      val policy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.INLINE)
      val acceptance =
        GoalRunnerOutOfBandAcceptance(
          subtaskId = 2,
          commitSha = "abc123",
          reason = "work was completed on the feature branch",
          acceptedAt = "2026-08-01T10:00:00Z",
        )

      store.persistReviewPolicy("parent-1", policy)
      store.persistOutOfBandAcceptance("parent-1", acceptance)

      assertEquals(policy, store.reviewPolicy("parent-1"))
      assertEquals(mapOf(2 to acceptance), store.outOfBandAcceptances("parent-1"))
      assertEquals(GoalRunnerControlState(), store.controlState("parent-1"))
      connection.prepareStatement(
        "SELECT review_policy_json, out_of_band_acceptances_json " +
          "FROM goal_runner_controls WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, "parent-1")
        statement.executeQuery().use { rows ->
          check(rows.next())
          check(rows.getString("review_policy_json").contains("code_review_mode"))
          check(rows.getString("out_of_band_acceptances_json").contains("commit_sha"))
        }
      }
    }
  }

  @Test
  fun `runner interrupted pause fields clear to defaults`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-runner-interrupted-clear").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      val interrupted =
        GoalRunnerControlState(
          pauseRequested = true,
          pauseConsumed = true,
          paused = true,
          pauseReason = GOAL_PAUSE_REASON_RUNNER_INTERRUPTED,
          pausedAt = "2026-08-02T09:59:00Z",
        )
      store.persistControlState("parent-interrupted", interrupted)
      val cleared = store.clearRunnerInterruptedPause("parent-interrupted")

      assertEquals(GoalRunnerControlState(), cleared)
      assertEquals(GoalRunnerControlState(), store.controlState("parent-interrupted"))
    }
  }

  @Test
  fun `operator stop pause rows remain untouched when only runner interrupted would be cleared`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-operator-stop-preserved").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      val operatorStop =
        GoalRunnerControlState(
          pauseRequested = true,
          pauseConsumed = true,
          paused = true,
          pauseReason = GOAL_PAUSE_REASON_OPERATOR_STOP,
          pausedAt = "2026-08-02T09:00:00Z",
        )
      store.persistControlState("parent-stop", operatorStop)

      assertEquals(operatorStop, store.clearRunnerInterruptedPause("parent-stop"))
      assertEquals(operatorStop, store.controlState("parent-stop"))
    }
  }

  @Test
  fun `missing control state is legacy compatible and malformed state fails loudly`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-control-state").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      assertEquals(GoalRunnerControlState(), store.controlState("missing-parent"))

      val state =
        GoalRunnerControlState(
          stopAfterSubtaskId = 2,
          pauseRequested = true,
          pauseConsumed = true,
          paused = true,
          pauseReason = "operator_request",
          pausedAt = "2026-08-02T09:00:00Z",
        )
      store.persistControlState("parent-1", state)
      assertEquals(state, store.controlState("parent-1"))

      connection.prepareStatement(
        "UPDATE goal_runner_controls SET control_state_json = ? WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, "{\"paused\":true,\"unsupported\":true}")
        statement.setString(2, "parent-1")
        statement.executeUpdate()
      }
      assertFailsWith<InvalidWorkflowStateSchemaError> { store.controlState("parent-1") }
    }
  }

  @Test
  fun `malformed control-state JSON returns a typed schema error not IllegalArgumentException`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-control-malformed-json").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      store.persistControlState("parent-malformed", GoalRunnerControlState())
      writeRawControlState(connection, "parent-malformed", "{not valid json")

      assertFailsWith<InvalidWorkflowStateSchemaError> { store.controlState("parent-malformed") }
    }
  }

  @Test
  fun `control state survives a reopened database and duplicate writes remain stable`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-control-restart").resolve("metrics.db")
    val state =
      GoalRunnerControlState(
        stopAfterSubtaskId = 4,
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = "operator_request",
        pausedAt = "2026-08-02T09:00:00Z",
      )

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      assertEquals(state, store.persistControlState("parent-restart", state))
      assertEquals(state, store.persistControlState("parent-restart", state))
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(state, GoalRunnerControlStore(connection).controlState("parent-restart"))
    }
  }

  @Test
  fun `a paused record written before paused_at existed decodes from the lease heartbeat`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-legacy-paused").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      store.persistControlState("parent-legacy", GoalRunnerControlState())
      writeRawControlState(
        connection,
        "parent-legacy",
        """
        {"paused":true,"pause_requested":true,"pause_consumed":true,"pause_reason":"operator_request",
         "execution_lease":{"generation":1,"owner_token":"owner-token-123456","host_identity":"host",
         "boot_identity":"boot","pid":42,"process_birth_token":"birth",
         "heartbeat_at":"2026-08-02T10:00:10Z","expires_at":"2026-08-02T10:00:40Z"}}
        """.trimIndent(),
      )

      val decoded = store.controlState("parent-legacy")
      assertTrue(decoded.paused)
      assertEquals("2026-08-02T10:00:10Z", decoded.pausedAt)
    }
  }

  @Test
  fun `a paused legacy record with no lease decodes to the unknown-time sentinel rather than failing`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-legacy-sentinel").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      store.persistControlState("parent-legacy", GoalRunnerControlState())
      writeRawControlState(
        connection,
        "parent-legacy",
        """{"paused":true,"pause_requested":true,"pause_consumed":true,"pause_reason":"operator_request"}""",
      )

      assertEquals(LEGACY_UNKNOWN_PAUSED_AT, store.controlState("parent-legacy").pausedAt)
    }
  }

  private fun writeRawControlState(
    connection: Connection,
    parentWorkflowId: String,
    json: String,
  ) {
    connection.prepareStatement(
      "UPDATE goal_runner_controls SET control_state_json = ? WHERE parent_workflow_id = ?",
    ).use { statement ->
      statement.setString(1, json)
      statement.setString(2, parentWorkflowId)
      statement.executeUpdate()
    }
  }

  @Test
  fun `malformed durable acceptance identity and review policy fail typed and never authorize another subtask`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-malformed-controls").resolve("metrics.db")
    val malformedIdentities = listOf("2.5", "2147483648", "0", "-1", "\"2\"")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalRunnerControlStore(connection)
      malformedIdentities.forEachIndexed { index, identity ->
        val parent = "parent-malformed-$index"
        store.persistControlState(parent, GoalRunnerControlState())
        writeRawAcceptances(connection, parent, rawAcceptanceList(identity))
        assertFailsWith<InvalidWorkflowStateSchemaError>("subtask_id $identity must be rejected.") {
          store.outOfBandAcceptances(parent)
        }
      }
      store.persistControlState("parent-blank-commit", GoalRunnerControlState())
      writeRawAcceptances(connection, "parent-blank-commit", rawAcceptanceList("2", " "))
      assertFailsWith<InvalidWorkflowStateSchemaError> { store.outOfBandAcceptances("parent-blank-commit") }

      store.persistControlState("parent-unknown-mode", GoalRunnerControlState())
      connection.prepareStatement(
        "UPDATE goal_runner_controls SET review_policy_json = ? WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, """{"code_review_mode":"not-a-mode"}""")
        statement.setString(2, "parent-unknown-mode")
        statement.executeUpdate()
      }
      assertFailsWith<InvalidWorkflowStateSchemaError> { store.reviewPolicy("parent-unknown-mode") }
    }
  }

  private fun rawAcceptanceList(
    subtaskId: String,
    commitSha: String = "abc123",
  ): String =
    """
    [{"subtask_id":$subtaskId,"commit_sha":"$commitSha","reason":"accepted","accepted_at":"2026-08-01T10:00:00Z"}]
    """.trimIndent()

  private fun writeRawAcceptances(
    connection: Connection,
    parentWorkflowId: String,
    json: String,
  ) {
    connection.prepareStatement(
      "UPDATE goal_runner_controls SET out_of_band_acceptances_json = ? WHERE parent_workflow_id = ?",
    ).use { statement ->
      statement.setString(1, json)
      statement.setString(2, parentWorkflowId)
      statement.executeUpdate()
    }
  }

  @Test
  fun `switching current subtask id zeros only the subtask accumulator`() {
    val state =
      GoalRunnerControlState(
        currentSubtaskId = 1,
        subtaskActiveDurationMs = 30_000,
        subtaskActiveDurationAsOf = "2026-08-02T10:00:10Z",
        activeDurationMs = 60_000,
        activeDurationAsOf = "2026-08-02T10:00:10Z",
      )

    val switched = state.reconciledForCurrentSubtask(2)
    assertEquals(2, switched.currentSubtaskId)
    assertEquals(0, switched.subtaskActiveDurationMs)
    assertEquals(null, switched.subtaskActiveDurationAsOf)
    assertEquals(60_000, switched.activeDurationMs)
    assertEquals(state, state.reconciledForCurrentSubtask(1))
  }
}
