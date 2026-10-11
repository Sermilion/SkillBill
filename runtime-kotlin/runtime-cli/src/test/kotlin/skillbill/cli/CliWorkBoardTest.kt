package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import java.nio.file.Files
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
    assertEquals("1.1", payload["contract_version"])
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
}
