package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.workflow.identity.status.WORK_BOARD_CONTRACT_VERSION
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliWorkBoardTest {
  @Test
  fun `board pages keep their observation time and return every repository once`() {
    val home = Files.createTempDirectory("cli-work-board-pages")
    val dbPath = home.resolve("metrics.db")
    val database = sqliteSessionFactoryForTests(home, dbPath.toString(), emptyMap())
    val now = Instant.now()
    val roots = List(3) { Files.createTempDirectory("board-page-repo-$it") }
    database.transaction { unit ->
      roots.forEachIndexed { index, root ->
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            repositoryIdentity = "repo-root-realpath-v1:${root.toRealPath()}",
            branchCorrelation = "feature/pages",
            issueKey = null,
            workflowId = null,
            invocationId = "page-$index",
            phaseId = "review",
            executionId = "page-execution-$index",
            lifecycleState = "active",
            currentStep = "review",
            startedAt = now.minusSeconds(index.toLong()),
            leaseOwner = "page-owner-$index",
            leaseGeneration = 1,
            leaseExpiresAt = now.plusSeconds(600),
          ),
        )
      }
    }
    val received = mutableListOf<String>()
    var cursor: String? = null
    var observedAt: String? = null
    do {
      val result =
        CliRuntime.run(
          listOf("--db", dbPath.toString(), "work", "board", "--page-size", "1") +
            (cursor?.let { listOf("--cursor", it) } ?: emptyList()),
          CliRuntimeContext(environment = emptyMap(), userHome = home),
        )
      assertEquals(0, result.exitCode, result.stdout)
      val payload = decodeJsonObject(result.stdout)
      val page = payload["items"] as List<*>
      assertEquals(1, page.size)
      assertEquals(1, payload["page_size"])
      received += (page.single() as Map<*, *>)["repository_root"] as String
      if (observedAt == null) observedAt = payload["observed_at"] as String
      assertEquals(observedAt, payload["observed_at"])
      cursor = payload["next_cursor"] as String?
      assertTrue(received.size <= roots.size)
      if (received.size < roots.size) assertNotNull(cursor)
    } while (cursor != null)
    assertEquals(roots.map { it.toRealPath().toString() }, received)

    val invalid =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "work", "board", "--cursor", "invalid!"),
        CliRuntimeContext(environment = emptyMap(), userHome = home),
      )
    assertEquals(1, invalid.exitCode)
    assertTrue(
      invalid.stderr.contains(
        "Invalid board continuation cursor",
      ) || invalid.stdout.contains("Invalid board continuation cursor"),
    )
  }

  @Test
  fun `a goal plan phase stands in until its goal is listable`() {
    val home = Files.createTempDirectory("cli-work-board-goal-plan")
    val repo = Files.createTempDirectory("cli-work-board-goal-plan-repo")
    val dbPath = home.resolve("metrics.db")
    val database = sqliteSessionFactoryForTests(home, dbPath.toString(), emptyMap())
    val identity = "repo-root-realpath-v1:${repo.toRealPath()}"
    val now = Instant.now()
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = identity,
          branchCorrelation = "feature/goal",
          issueKey = "SKILL-9",
          workflowId = null,
          invocationId = "goal-plan",
          phaseId = "plan",
          executionId = "goal-plan-execution",
          lifecycleState = "active",
          currentStep = "preplan",
          startedAt = now,
          leaseOwner = "goal-plan-owner",
          leaseGeneration = 1,
          leaseExpiresAt = now.plusSeconds(600),
          launchScope = IdeStatusLaunchScope.GOAL,
        ),
      )
    }
    val boardSnapshots = {
      val result =
        CliRuntime.run(
          listOf("--db", dbPath.toString(), "work", "board", "--format", "json"),
          CliRuntimeContext(environment = emptyMap(), userHome = home),
        )
      assertEquals(0, result.exitCode, result.stderr)
      (decodeJsonObject(result.stdout)["items"] as List<*>).map { (it as Map<*, *>)["snapshot"] as Map<*, *> }
    }
    val planListed = { boardSnapshots().any { it["invocation_id"] == "goal-plan" } }
    assertTrue(planListed(), "Before the goal has a row, its plan stands in for it.")

    insertBlockedGoalParent(dbPath, identity)
    assertTrue(planListed(), "A goal that blocked before it could be listed must not hide its plan.")

    insertGoalProgress(dbPath, now)
    val snapshots = boardSnapshots()
    assertTrue(snapshots.none { it["invocation_id"] == "goal-plan" }, "The goal row shows its planning progress.")
    assertTrue(snapshots.any { it["workflow_id"] == "goal-parent" }, "The goal row replaces the plan row.")
  }

  @Test
  fun `expired standalone monitor does not remain running on the work board`() {
    val home = Files.createTempDirectory("cli-work-board-expired-monitor")
    val repo = Files.createTempDirectory("cli-work-board-monitor-repo")
    val dbPath = home.resolve("metrics.db")
    val database = sqliteSessionFactoryForTests(home, dbPath.toString(), emptyMap())
    val now = Instant.now()
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo-root-realpath-v1:${repo.toRealPath()}",
          branchCorrelation = "feature/monitor",
          issueKey = null,
          workflowId = null,
          invocationId = "expired-monitor",
          phaseId = "monitor",
          executionId = "expired-monitor-execution",
          lifecycleState = "active",
          currentStep = "monitor",
          startedAt = now.minusSeconds(3600),
          leaseOwner = "dead-owner",
          leaseGeneration = 1,
          leaseExpiresAt = now.minusSeconds(1800),
        ),
      )
    }
    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "work", "board", "--format", "json"),
        CliRuntimeContext(environment = emptyMap(), userHome = home),
      )
    assertEquals(0, result.exitCode, result.stdout)
    val item = (decodeJsonObject(result.stdout)["items"] as List<*>).single() as Map<*, *>
    val snapshot = item["snapshot"] as Map<*, *>
    assertEquals("paused", snapshot["lifecycle_state"])
    assertEquals("runner_interrupted", snapshot["current_step"].let { it as Map<*, *> }["id"])
  }

  @Test
  fun `board includes live standalone work from more than one repository`() {
    val home = Files.createTempDirectory("cli-work-board-home")
    val repoOne = Files.createTempDirectory("cli-work-board-repo-one")
    val repoTwo = Files.createTempDirectory("cli-work-board-repo-two")
    val dbPath = home.resolve("metrics.db")
    val database = sqliteSessionFactoryForTests(home, dbPath.toString(), emptyMap())
    val now = Instant.now()
    database.transaction {
      listOf(repoOne, repoTwo).forEachIndexed { index, root ->
        it.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            repositoryIdentity = "repo-root-realpath-v1:${root.toRealPath()}",
            branchCorrelation = "feature/$index",
            issueKey = null,
            workflowId = null,
            invocationId = "invocation-$index",
            phaseId = "review",
            executionId = "execution-$index",
            lifecycleState = "active",
            currentStep = "review",
            startedAt = now,
            leaseOwner = "owner-$index",
            leaseGeneration = 1,
            leaseExpiresAt = now.plusSeconds(600),
          ),
        )
      }
    }

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "work", "board", "--format", "json"),
        CliRuntimeContext(environment = emptyMap(), userHome = home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    val items = payload["items"] as List<*>
    assertEquals(2, items.size)
    assertEquals(
      setOf(repoOne.toRealPath().toString(), repoTwo.toRealPath().toString()),
      items.map { (it as Map<*, *>)["repository_root"] }.toSet(),
    )
    assertEquals(WORK_BOARD_CONTRACT_VERSION, payload["contract_version"])
  }

  @Test
  fun `board reports an absent database as a structured problem`() {
    val home = Files.createTempDirectory("cli-work-board-absent")
    val missingDb = home.resolve("missing.db")
    val result =
      CliRuntime.run(
        listOf("--db", missingDb.toString(), "work", "board", "--format", "json"),
        CliRuntimeContext(environment = emptyMap(), userHome = home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("absent_database", ((decodeJsonObject(result.stdout)["problem"] as Map<*, *>)["code"]))
  }

  @Test
  fun `board retention uses completion time instead of a later heartbeat`() {
    val home = Files.createTempDirectory("cli-work-board-retention")
    val repoRoot = Files.createTempDirectory("cli-work-board-retention-repo")
    val dbPath = home.resolve("metrics.db")
    val database = sqliteSessionFactoryForTests(home, dbPath.toString(), emptyMap())
    val now = Instant.now()
    database.transaction {
      listOf("stale-terminal" to now.minusSeconds(7 * 60 * 60), "recent-terminal" to now.minusSeconds(2 * 60 * 60))
        .forEach { (id, finishedAt) ->
          val invocationId = "$id-invocation"
          val executionId = "$id-execution"
          it.standalonePhaseStatuses.register(
            StandalonePhaseStatusRegistration(
              repositoryIdentity = "repo-root-realpath-v1:${repoRoot.toRealPath()}",
              branchCorrelation = "feature/retention",
              issueKey = null,
              workflowId = null,
              invocationId = invocationId,
              phaseId = "review",
              executionId = executionId,
              lifecycleState = "active",
              currentStep = "review",
              startedAt = now.minusSeconds(100_000),
              leaseOwner = id,
              leaseGeneration = 1,
              leaseExpiresAt = now.plusSeconds(86_400),
            ),
          )
          it.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              executionId = executionId,
              expectedRevision = "1",
              leaseOwner = id,
              leaseGeneration = 1,
              lifecycleState = "terminal",
              currentStep = "review",
              currentActivity = null,
              updatedAt = now,
              finishedAt = finishedAt,
              terminalResult = "done",
            ),
          )
        }
    }

    val result =
      CliRuntime.run(
        listOf("--db", dbPath.toString(), "work", "board", "--finished-within", "6h"),
        CliRuntimeContext(environment = emptyMap(), userHome = home),
      )

    assertEquals(0, result.exitCode, result.stdout)
    val ids =
      (decodeJsonObject(result.stdout)["items"] as List<*>)
        .map { (it as Map<*, *>)["snapshot"] as Map<*, *> }
        .map { it["invocation_id"] }
    assertEquals(listOf("recent-terminal-invocation"), ids)
  }

  @Test
  fun `installation migrates an older goal and restores a board cache that omitted it`() {
    val home = Files.createTempDirectory("cli-upgrade-omitted-goal")
    val repo = Files.createTempDirectory("cli-upgrade-omitted-goal-repo")
    val dbPath = home.resolve("metrics.db")
    val identity = "repo-root-realpath-v1:${repo.toRealPath()}"
    val now = Instant.parse("2026-11-07T18:00:00Z")
    insertBlockedGoalParent(dbPath, identity)
    insertGoalProgress(dbPath, now)
    val before =
      ensureTestDatabase(dbPath).use { connection ->
        connection.createStatement().use { statement ->
          statement.executeUpdate("UPDATE feature_task_workflows SET contract_version = '0.3'")
          statement.executeUpdate("DELETE FROM work_board_items")
          statement.executeUpdate("UPDATE work_board_read_model_state SET complete = 1")
          statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 108")
          statement.execute("PRAGMA user_version = 107")
          statement.executeQuery(
            "SELECT steps_json, artifacts_json FROM feature_task_workflows WHERE workflow_id = 'goal-parent'",
          )
            .use { rows ->
              assertTrue(rows.next())
              rows.getString(1) to rows.getString(2)
            }
        }
      }
    val args = listOf("--db", dbPath.toString(), "install", "migrate-data")
    val context = CliRuntimeContext(environment = emptyMap(), userHome = home)
    repeat(2) {
      val migrated = CliRuntime.run(args, context)
      assertEquals(0, migrated.exitCode, migrated.stderr + migrated.stdout)
    }
    val board = CliRuntime.run(listOf("--db", dbPath.toString(), "work", "board"), context)
    assertEquals(0, board.exitCode, board.stderr)
    val items = decodeJsonObject(board.stdout)["items"] as List<*>
    assertTrue(items.any { ((it as Map<*, *>)["snapshot"] as Map<*, *>)["workflow_id"] == "goal-parent" })
    ensureTestDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT contract_version, steps_json, artifacts_json " +
            "FROM feature_task_workflows WHERE workflow_id = 'goal-parent'",
        )
          .use { rows ->
            assertTrue(rows.next())
            assertEquals("0.4", rows.getString(1))
            assertEquals(before, rows.getString(2) to rows.getString(3))
          }
      }
    }
  }

  @Test
  fun `unreadable saved goals stay visible with a typed problem without changing their data`() {
    listOf("99.0" to "[]", "0.4" to "invalid JSON").forEach { (version, steps) ->
      val home = Files.createTempDirectory("cli-board-incompatible-goal")
      val repo = Files.createTempDirectory("cli-board-incompatible-goal-repo")
      val dbPath = home.resolve("metrics.db")
      insertBlockedGoalParent(dbPath, "repo-root-realpath-v1:${repo.toRealPath()}")
      insertGoalProgress(dbPath, Instant.parse("2026-11-07T18:00:00Z"))
      ensureTestDatabase(dbPath).use { connection ->
        connection.prepareStatement("UPDATE feature_task_workflows SET contract_version = ?, steps_json = ?").use {
          it.setString(1, version)
          it.setString(2, steps)
          it.executeUpdate()
        }
        connection.createStatement().use {
          it.executeUpdate("UPDATE work_board_read_model_state SET complete = 0")
        }
      }
      val result =
        CliRuntime.run(
          listOf("--db", dbPath.toString(), "work", "board"),
          CliRuntimeContext(environment = emptyMap(), userHome = home),
        )
      assertEquals(0, result.exitCode, result.stderr)
      val item = (decodeJsonObject(result.stdout)["items"] as List<*>).single() as Map<*, *>
      val snapshot = item["snapshot"] as Map<*, *>
      assertEquals("goal-parent", snapshot["workflow_id"])
      assertEquals("SKILL-9", snapshot["issue_key"])
      assertEquals("blocked", snapshot["lifecycle_state"])
      assertEquals("incompatible_record", (snapshot["problem"] as Map<*, *>)["code"])
      ensureTestDatabase(dbPath).use { connection ->
        connection.createStatement().use { statement ->
          statement.executeQuery(
            "SELECT contract_version, steps_json FROM feature_task_workflows WHERE workflow_id = 'goal-parent'",
          ).use { rows ->
            assertTrue(rows.next())
            assertEquals(version, rows.getString(1))
            assertEquals(steps, rows.getString(2))
          }
        }
      }
    }
  }

  private fun insertBlockedGoalParent(
    dbPath: Path,
    identity: String,
  ) {
    ensureTestDatabase(dbPath).use { connection ->
      connection.prepareStatement("INSERT INTO feature_task_runtime_sessions (session_id) VALUES ('goal-session')")
        .use { it.executeUpdate() }
      connection.prepareStatement(
        """
        INSERT INTO feature_task_workflows (
          workflow_id, session_id, mode, contract_version, workflow_status, current_step_id, steps_json,
          artifacts_json, issue_key
        )
        VALUES (
          'goal-parent', 'goal-session', 'runtime', '0.1', 'blocked', 'plan_subtasks',
          '[{"step_id":"plan_subtasks","status":"blocked","attempt_count":0}]', ?, 'SKILL-9'
        )
        """.trimIndent(),
      ).use {
        it.setString(
          1,
          """
          {"decomposition_runtime":{"contract_version":"0.5","issue_key":"SKILL-9","feature_name":"goal",
          "parent_spec_path":"specs/SKILL-9-goal/spec.md","status":"pending",
          "execution_model":"same_branch_commit_per_subtask","base_branch":"main","feature_branch":"feature/goal",
          "stack_branches":[],"current_subtask_intent":{"subtask_id":1,"action":"start"},
          "subtasks":[{"id":1,"name":"One","spec_path":"specs/SKILL-9-goal/spec_subtask_1_one.md","status":"pending",
          "dependencies":[]}]}}
          """.trimIndent(),
        )
        it.executeUpdate()
      }
      connection.prepareStatement(
        "INSERT INTO goal_runner_controls (parent_workflow_id, control_state_json) VALUES ('goal-parent', ?)",
      ).use {
        it.setString(1, """{"repository_identity":"$identity"}""")
        it.executeUpdate()
      }
    }
  }

  private fun insertGoalProgress(
    dbPath: Path,
    startedAt: Instant,
  ) {
    ensureTestDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO goal_issue_progress (parent_workflow_id, issue_key, first_started_at, status)
        VALUES ('goal-parent', 'SKILL-9', ?, 'blocked')
        """.trimIndent(),
      ).use {
        it.setString(1, startedAt.toString())
        it.executeUpdate()
      }
    }
  }
}
