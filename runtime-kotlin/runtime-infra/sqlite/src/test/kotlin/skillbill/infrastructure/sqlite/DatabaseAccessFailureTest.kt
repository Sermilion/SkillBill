package skillbill.infrastructure.sqlite

import org.sqlite.SQLiteException
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.databaseAccessCondition
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseAccessFailureTest {
  @Test
  fun `unopenable database on the read path raises the typed error with the resolved path`() {
    val unopenable = unopenableDatabasePath()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        DatabaseRuntime.openReadDb(
          cliValue = unopenable.toString(),
          environment = emptyMap(),
          userHome = unopenable.parent,
        )
      }

    assertEquals(DatabaseFailureCode.ACCESS, error.code)
    assertTrue(
      error.message.orEmpty().startsWith(
        "Database ${DatabaseAccessOperation.READ.wireValue} failed for " +
          "'${unopenable.toAbsolutePath().normalize()}': ",
      ),
      error.message.orEmpty(),
    )
    val condition = databaseAccessCondition(error)
    assertTrue(condition.startsWith("sqlite result code "), condition)
  }

  @Test
  fun `no sqlite exception escapes the read path`() {
    val unopenable = unopenableDatabasePath()

    val thrown =
      runCatching {
        DatabaseRuntime.openReadDb(
          cliValue = unopenable.toString(),
          environment = emptyMap(),
          userHome = unopenable.parent,
        )
      }.exceptionOrNull()

    assertFalse(thrown is SQLiteException, "raw JDBC exception escaped: $thrown")
    assertTrue(
      (thrown as? SkillBillRuntimeException)?.code == DatabaseFailureCode.ACCESS,
      "expected the typed error, got $thrown",
    )
    val rendered = thrown.message.orEmpty()
    assertFalse(rendered.contains("org.sqlite"), rendered)
    assertFalse(rendered.contains("\n"), rendered)
  }

  @Test
  fun `a failing open leaves no connection holding the database file`() {
    val tempDir = createTempDirectory("skill-bill-db-access")
    val dbPath = tempDir.resolve("review-metrics.db")
    Files.write(dbPath, "this is not a sqlite database".toByteArray())

    runCatching {
      DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap(), userHome = tempDir).close()
    }

    assertTrue(Files.deleteIfExists(dbPath), "the temp database file could not be deleted after the failed open")
  }

  @Test
  fun `a healthy database still opens on the read path`() {
    val tempDir = createTempDirectory("skill-bill-db-access-ok")
    val dbPath = tempDir.resolve("review-metrics.db")
    DatabaseRuntime.openDb(cliValue = dbPath.toString(), environment = emptyMap(), userHome = tempDir).close()

    DatabaseRuntime.openReadDb(cliValue = dbPath.toString(), environment = emptyMap(), userHome = tempDir).use { open ->
      assertContains(open.dbPath.toString(), "review-metrics.db")
      assertFalse(open.connection.isClosed)
    }
  }

  private fun unopenableDatabasePath(): Path {
    val tempDir = createTempDirectory("skill-bill-db-unopenable")

    return tempDir.resolve("review-metrics.db").also { it.createDirectories() }
  }
}
