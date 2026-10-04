package skillbill.infrastructure.sqlite.telemetry.redaction

import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.ensureDatabase
import skillbill.infrastructure.sqlite.tableExists
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TelemetryRedactionTest {
  @Test
  fun `opening the database twice returns the same salt`() {
    val dbPath = tempDbPath()
    val first = DatabaseRuntime.ensureDatabase(dbPath).use(::telemetryRedactionSalt)
    val second = DatabaseRuntime.ensureDatabase(dbPath).use(::telemetryRedactionSalt)

    assertTrue(first.isNotBlank(), "a salt must be generated on first use")
    assertEquals(first, second)
  }

  @Test
  fun `a database created before this change gains the secrets table on open and yields a salt`() {
    val dbPath = tempDbPath()
    DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("CREATE TABLE IF NOT EXISTS legacy_marker (id INTEGER PRIMARY KEY)")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertTrue(tableExists(connection, "telemetry_local_secrets"), "secrets table must be created on open")
      assertTrue(telemetryRedactionSalt(connection).isNotBlank())
    }
  }

  @Test
  fun `the same issue key and salt produce the same substitute across two calls`() {
    assertEquals(
      redactIssueKey("SKILL-163", "anonymous", "salt-a"),
      redactIssueKey("SKILL-163", "anonymous", "salt-a"),
    )
  }

  @Test
  fun `two different issue keys under one salt produce different substitutes`() {
    assertNotEquals(
      redactIssueKey("SKILL-163", "anonymous", "salt-a"),
      redactIssueKey("SKILL-164", "anonymous", "salt-a"),
    )
  }

  @Test
  fun `the substitute never contains the raw key`() {
    val substitute = redactIssueKey("SKILL-163", "anonymous", "salt-a")

    assertFalse(substitute.contains("SKILL-163"), "substitute must not embed the raw key")
    assertTrue(substitute.startsWith(REDACTED_ISSUE_KEY_PREFIX), "substitute must be visibly not a tracker key")
  }

  @Test
  fun `full level returns the raw key byte for byte`() {
    assertEquals("SKILL-163", redactIssueKey("SKILL-163", "full", "salt-a"))
  }

  @Test
  fun `an unknown level redacts and a blank key stays blank at every level`() {
    assertFalse(redactIssueKey("SKILL-163", "", "salt-a").contains("SKILL-163"))
    assertEquals("", redactIssueKey("", "anonymous", "salt-a"))
    assertEquals("", redactIssueKey("", "full", "salt-a"))
  }

  private fun tableExists(
    connection: Connection,
    tableName: String,
  ): Boolean =
    connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
      statement.setString(1, tableName)
      statement.executeQuery().use { resultSet -> resultSet.next() }
    }

  private fun tempDbPath(): Path = Files.createTempDirectory("skillbill-telemetry-redaction").resolve("metrics.db")
}
