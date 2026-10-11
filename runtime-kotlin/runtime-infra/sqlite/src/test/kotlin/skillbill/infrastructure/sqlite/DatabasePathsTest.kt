package skillbill.infrastructure.sqlite

import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.schema.DatabasePaths
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DatabasePathsTest {
  @Test
  fun `cli path overrides environment and default path`() {
    val resolved =
      DatabaseRuntime.resolveDbPath(
        cliValue = "./custom/metrics.db",
        environment = mapOf(DatabasePaths.DB_ENVIRONMENT_KEY to "/tmp/env-metrics.db"),
        userHome = Path.of("/tmp/home"),
      )

    assertEquals(Path.of("./custom/metrics.db").toAbsolutePath().normalize(), resolved)
  }

  @Test
  fun `environment path overrides default path`() {
    val resolved =
      DatabaseRuntime.resolveDbPath(
        cliValue = null,
        environment = mapOf(DatabasePaths.DB_ENVIRONMENT_KEY to "~/metrics.db"),
        userHome = Path.of("/tmp/home"),
      )

    assertEquals(Path.of("/tmp/home/metrics.db"), resolved)
  }

  @Test
  fun `default path resolves under skill bill home`() {
    val resolved =
      DatabaseRuntime.resolveDbPath(
        cliValue = null,
        environment = emptyMap(),
        userHome = Path.of("/tmp/home"),
      )

    assertEquals(Path.of("/tmp/home/.skill-bill/review-metrics.db"), resolved)
  }

  @Test
  fun `strict existing reads refuse zero-byte stores without installing a schema`() {
    val home = Files.createTempDirectory("runtime-zero-byte-refusal")
    val dbPath = Files.createFile(home.resolve("metrics.db"))
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        DatabaseRuntime.openExistingReadDbAt(dbPath, SqliteTestDiagnostics).close()
      }
    assertEquals(DatabaseFailureCode.INCOMPATIBLE_SCHEMA, error.code)
    assertEquals(0L, Files.size(dbPath))
  }

  @Test
  fun `strict existing reads refuse missing stores without creating a file`() {
    val home = Files.createTempDirectory("runtime-missing-refusal")
    val dbPath = home.resolve("metrics.db")
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        DatabaseRuntime.openExistingReadDbAt(dbPath, SqliteTestDiagnostics).close()
      }
    assertEquals(DatabaseFailureCode.MISSING, error.code)
    assertFalse(Files.exists(dbPath))
  }

  @Test
  fun `openReadDb keeps a schema-complete database read-only`() {
    val workingDir = Files.createTempDirectory("runtime-kotlin-readonly-store")
    val dbPath = workingDir.resolve("review-metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).close()

    DatabaseRuntime.openReadDb(
      cliValue = dbPath.toString(),
      environment = emptyMap(),
      userHome = workingDir,
    ).use { open ->
      assertFailsWith<SQLException>("An already-complete store must not gain write capability.") {
        open.connection.createStatement().use { statement ->
          statement.executeUpdate("INSERT INTO review_runs (review_run_id, routed_skill) VALUES ('rvw-x', 's')")
        }
      }
    }
  }
}
