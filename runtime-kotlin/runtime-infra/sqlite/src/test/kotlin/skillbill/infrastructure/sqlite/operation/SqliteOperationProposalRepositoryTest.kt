package skillbill.infrastructure.sqlite.operation

import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.ensureDatabase
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.operation.model.OperationAnchors
import skillbill.ports.operation.model.OperationProposal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteOperationProposalRepositoryTest {
  @Test
  fun `migration v47 adds operation proposals to a pre-change database and keeps its rows`() {
    val dbPath = Files.createTempDirectory("operation-proposals-migration").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'add-operation-proposals'")
        statement.executeUpdate("DROP TABLE operation_proposals")
        statement.executeUpdate(
          "INSERT INTO feature_task_phase_settlements " +
            "(workflow_id, phase_id, attempt, kind, envelope_json, recorded_at) " +
            "VALUES ('wftr-kept', 'implement', 1, 'complete', '{}', '2026-09-27T10:00:00Z')",
        )
      }
      assertFalse(tableExists(connection, "operation_proposals"))
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(tableExists(connection, "operation_proposals"))
      assertEquals(1, scalarInt(connection, "SELECT COUNT(*) FROM feature_task_phase_settlements"))
      assertNotNull(ledgerNames(connection).singleOrNull { it == "add-operation-proposals" })
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DatabaseMigrations.migrations.size, ledgerNames(connection).size)
    }
  }

  @Test
  fun `a new proposal supersedes only the prior open proposal for the same operation and repo`() {
    val repo = repository()
    repo.createSupersedingPrior(proposal("tok-1", operationId = "release", repoRoot = "/repo-a"))
    repo.createSupersedingPrior(proposal("tok-other-repo", operationId = "release", repoRoot = "/repo-b"))
    repo.createSupersedingPrior(proposal("tok-other-op", operationId = "other", repoRoot = "/repo-a"))

    repo.createSupersedingPrior(proposal("tok-2", operationId = "release", repoRoot = "/repo-a"))

    assertNotNull(repo.find("tok-1")?.supersededAt)
    assertNull(repo.find("tok-2")?.supersededAt)
    assertNull(repo.find("tok-other-repo")?.supersededAt)
    assertNull(repo.find("tok-other-op")?.supersededAt)
  }

  @Test
  fun `a proposal is consumed once and a superseded proposal cannot be consumed`() {
    val repo = repository()
    repo.createSupersedingPrior(proposal("tok-1"))
    repo.createSupersedingPrior(proposal("tok-2"))

    assertTrue(repo.markConsumed("tok-2", "2026-09-27T10:01:00Z"))
    assertFalse(repo.markConsumed("tok-2", "2026-09-27T10:02:00Z"))
    assertFalse(repo.markConsumed("tok-1", "2026-09-27T10:02:00Z"))
    assertEquals("2026-09-27T10:01:00Z", repo.find("tok-2")?.consumedAt)
  }

  @Test
  fun `stored anchors and proposal value round trip exactly`() {
    val repo = repository()
    val stored =
      proposal("tok-1").copy(
        anchors = OperationAnchors("abc123", "main", mapOf("last_release_tag" to "v1.2.3", "remote_head" to "def")),
        proposalValue = "## What's New in v1.3.0\n\n### New Features\n- Operations\n",
      )
    repo.createSupersedingPrior(stored)

    assertEquals(stored, repo.find("tok-1"))
    assertNull(repo.find("tok-missing"))
  }

  private fun repository(): SqliteOperationProposalRepository {
    val tempDir: Path = Files.createTempDirectory("operation-proposals")
    val dbPath = tempDir.resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()
    return SqliteOperationProposalRepository(
      sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap()),
    )
  }

  private fun proposal(
    token: String,
    operationId: String = "release",
    repoRoot: String = "/repo",
  ): OperationProposal =
    OperationProposal(
      token = token,
      operationId = operationId,
      repoRoot = repoRoot,
      anchors = OperationAnchors(headSha = "abc123", branch = "main"),
      proposalValue = "proposal $token",
      createdAt = "2026-09-27T10:00:00Z",
    )

  private fun tableExists(
    connection: Connection,
    name: String,
  ): Boolean =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name'").use { it.next() }
    }

  private fun scalarInt(
    connection: Connection,
    sql: String,
  ): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use {
        it.next()
        it.getInt(1)
      }
    }

  private fun ledgerNames(connection: Connection): List<String> =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT name FROM schema_migrations").use { rows ->
        buildList { while (rows.next()) add(rows.getString("name")) }
      }
    }
}
