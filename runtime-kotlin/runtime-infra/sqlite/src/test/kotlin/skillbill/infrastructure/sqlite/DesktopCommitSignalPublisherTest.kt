package skillbill.infrastructure.sqlite

import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopCommitSignalPublisherTest {
  @Test
  fun `work commits publish one complete revision snapshot while rollback and unrelated writes stay silent`() {
    establishTemporarySchemaReadiness("desktop-commit-signal-publisher").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      val signal = desktopCommitSignalPath(fixture.dbPath)
      assertFalse(Files.exists(signal))

      database.transaction { unit ->
        unit.goalRunnerControls.persistControlState("parent", GoalRunnerControlState(pauseRequested = true))
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            "repo-root-realpath-v1:${fixture.tempDir}",
            "main",
            "SKILL-423",
            null,
            "phase-work",
            "review",
            "execution-phase-work",
            "active",
            "review",
            Instant.parse("2026-10-13T00:00:00Z"),
            "test-owner",
            1,
            Instant.parse("2026-10-13T00:05:00Z"),
          ),
        )
      }

      val committed = Files.readString(signal)
      assertTrue(committed.contains("generation="))
      assertTrue(committed.contains("goals=1"))
      assertTrue(committed.contains("phases=1"))

      assertFailsWith<IllegalStateException> {
        database.transaction { unit ->
          unit.goalRunnerControls.persistControlState(
            "parent",
            GoalRunnerControlState(paused = true, pauseReason = "test", pausedAt = "2026-10-13T00:01:00Z"),
          )
          error("reject write")
        }
      }
      assertEquals(committed, Files.readString(signal))

      database.transaction { unit ->
        val connection =
          unit::class.java.getDeclaredField("connection").apply { isAccessible = true }
            .get(unit) as Connection
        connection.createStatement().use { statement ->
          statement.execute("CREATE TABLE unrelated_desktop_signal_probe (id TEXT PRIMARY KEY)")
          statement.execute("INSERT INTO unrelated_desktop_signal_probe VALUES ('committed')")
        }
      }
      assertEquals(committed, Files.readString(signal))
    }
  }

  @Test
  fun `agent activity commits publish the activity revision without changing the work observation`() {
    establishTemporarySchemaReadiness("desktop-commit-signal-activity").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      val signal = desktopCommitSignalPath(fixture.dbPath)
      val before = database.read { unit -> unit.workList.observation() }

      database.selfManagedWrite { unit ->
        unit.agentActivityStamps.record(
          "wfl-activity",
          AgentActivityStamp(Instant.parse("2026-10-13T00:00:00Z"), AgentActivityLabel.STDOUT),
        )
      }

      val committed = Files.readString(signal)
      assertTrue(committed.contains("goals=0"))
      assertTrue(committed.contains("phases=0"))
      assertTrue(committed.contains("activity=1"))
      assertEquals(before, database.read { unit -> unit.workList.observation() })
    }
  }

  @Test
  fun `worktree probes and heartbeats advance only activity and list the goal of the child`() {
    establishTemporarySchemaReadiness("desktop-commit-signal-heartbeat").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.transaction { unit -> seedGoalWithLeasedChild(unit.rawConnection()) }
      val before = signalValues(fixture)

      database.transaction { unit ->
        unit.rawConnection().createStatement().use { statement ->
          statement.executeUpdate(
            "INSERT INTO worktree_edit_journal (workflow_id, recorded_at, path, lines_added, lines_removed, source) " +
              "VALUES ('$CHILD', '2026-10-28T00:01:00Z', 'a.kt', 1, 0, 'worktree_probe')",
          )
          statement.executeUpdate(
            "UPDATE feature_task_runtime_worker_leases " +
              "SET heartbeat_at = '2026-10-28T00:02:00Z', expires_at = '2026-10-28T00:07:00Z'",
          )
        }
      }

      val after = signalValues(fixture)
      assertEquals(before.getValue("goals"), after.getValue("goals"))
      assertEquals(before.getValue("phases"), after.getValue("phases"))
      assertEquals(before.getValue("activity").toInt() + 2, after.getValue("activity").toInt())
      assertEquals("0:$GOAL", after.getValue("activity.changed"))
    }
  }

  @Test
  fun `goal control updates advance goals and specs and list the goal id`() {
    establishTemporarySchemaReadiness("desktop-commit-signal-goal").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.transaction { unit -> seedGoalWithLeasedChild(unit.rawConnection()) }
      val before = signalValues(fixture)

      database.transaction { unit ->
        unit.goalRunnerControls.persistControlState(GOAL, GoalRunnerControlState(pauseRequested = true))
      }

      val after = signalValues(fixture)
      assertTrue(after.getValue("goals").toInt() > before.getValue("goals").toInt())
      assertTrue(after.getValue("specs").toInt() > before.getValue("specs").toInt())
      assertTrue(GOAL in after.getValue("goals.changed").substringAfter(':').split(','))
    }
  }

  @Test
  fun `bursts of more than sixteen workflows publish a bounded list that forces a full reread`() {
    establishTemporarySchemaReadiness("desktop-commit-signal-burst").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.transaction { unit -> seedGoalWithLeasedChild(unit.rawConnection()) }
      val before = signalValues(fixture).getValue("goals").toLong()

      database.transaction { unit ->
        unit.rawConnection().createStatement().use { statement ->
          repeat(20) { index ->
            statement.executeUpdate(
              "INSERT INTO feature_task_workflows (workflow_id, mode, contract_version) " +
                "VALUES ('wftr-burst-$index', 'runtime', '1.0')",
            )
          }
        }
      }

      val signal = signalValues(fixture)
      val current = signal.getValue("goals").toLong()
      val (base, ids) = signal.getValue("goals.changed").split(':', limit = 2)
      assertEquals((4 until 20).map { "wftr-burst-$it" }.toSet(), ids.split(',').toSet())
      assertEquals(current - 16, base.toLong())
      assertTrue(base.toLong() > before)
    }
  }

  @Test
  fun `generation stays stable across commits and changes when a migration applies`() {
    establishTemporarySchemaReadiness("desktop-commit-signal-generation").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      database.transaction { unit -> seedGoalWithLeasedChild(unit.rawConnection()) }
      val first = signalValues(fixture).getValue("generation")
      database.transaction { unit ->
        unit.goalRunnerControls.persistControlState(GOAL, GoalRunnerControlState(pauseRequested = true))
      }
      assertEquals(first, signalValues(fixture).getValue("generation"))

      DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
        connection.createStatement().use { statement ->
          statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'separate-desktop-change-signals'")
        }
      }
      DatabaseRuntime.ensureDatabase(fixture.dbPath).close()
      database.transaction { unit ->
        unit.goalRunnerControls.persistControlState(
          GOAL,
          GoalRunnerControlState(paused = true, pauseReason = "x", pausedAt = "2026-10-28T00:00:00Z"),
        )
      }

      assertNotEquals(first, signalValues(fixture).getValue("generation"))
    }
  }

  private fun signalValues(fixture: TemporarySchemaDatabase): Map<String, String> =
    Files.readAllLines(desktopCommitSignalPath(fixture.dbPath)).associate { line ->
      line.substringBefore('=') to line.substringAfter('=')
    }

  private fun UnitOfWork.rawConnection(): Connection =
    this::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(this) as Connection

  private fun seedGoalWithLeasedChild(connection: Connection) {
    val continuation =
      "{\"${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()}\":" +
        "{\"${FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARENT_WORKFLOW_ID}\":\"$GOAL\"}}"
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        "INSERT INTO feature_task_workflows (workflow_id, mode, contract_version) VALUES ('$GOAL', 'runtime', '1.0')",
      )
      statement.executeUpdate(
        "INSERT INTO goal_issue_progress (parent_workflow_id, issue_key) VALUES ('$GOAL', 'SKILL-447')",
      )
      statement.executeUpdate(
        "INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, artifacts_json) " +
          "VALUES ('$CHILD', 'runtime', '1.0', '$continuation')",
      )
      statement.executeUpdate(
        "INSERT INTO feature_task_execution_identities (workflow_id, contract_version, normalized_issue_key, " +
          "repository_identity, governed_spec_path, mode, route_scope) " +
          "VALUES ('$CHILD', '0.1', 'SKILL-447', 'repo', 'spec.md', 'runtime', 'goal_child')",
      )
      statement.executeUpdate(
        "INSERT INTO feature_task_runtime_worker_leases (workflow_id, contract_version, generation, owner_token, " +
          "host_identity, boot_identity, pid, process_birth_token, lease_state, heartbeat_at, expires_at, " +
          "phase_id, phase_attempt) VALUES ('$CHILD', '0.1', 1, 'owner', 'host', 'boot', 1, 'birth', 'active', " +
          "'2026-10-28T00:00:00Z', '2026-10-28T00:05:00Z', 'implement', 1)",
      )
    }
  }

  private companion object {
    const val GOAL = "wftr-goal-parent"
    const val CHILD = "wftr-goal-child"
  }
}
