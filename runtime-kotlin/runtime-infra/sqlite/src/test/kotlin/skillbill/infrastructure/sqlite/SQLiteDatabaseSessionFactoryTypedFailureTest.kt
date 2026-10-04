package skillbill.infrastructure.sqlite

import org.sqlite.SQLiteException
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SQLiteDatabaseSessionFactoryTypedFailureTest {
  @Test
  fun `read surfaces the typed error rather than a jdbc exception at the ports boundary`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-read")
    val unopenable = unopenableDatabasePath(tempDir)
    val database = boundDatabase(tempDir, unopenable)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        database.read { it.workflowStates }
      }

    assertEquals(DatabaseFailureCode.ACCESS, error.code)
    assertTrue(
      error.message.orEmpty().startsWith(
        "Database ${DatabaseAccessOperation.READ.wireValue} failed for " +
          "'${unopenable.toAbsolutePath().normalize()}': ",
      ),
      error.message.orEmpty(),
    )
    assertFalse(error.message.orEmpty().contains("org.sqlite"), error.message.orEmpty())
  }

  @Test
  fun `a sqlite condition raised while executing the read converts to the typed error`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-read-statement")
    val schemaless = tempDir.resolve("schemaless.db")
    DriverManager.getConnection("jdbc:sqlite:$schemaless").use { connection ->
      connection.createStatement().use { it.execute("CREATE TABLE placeholder (id INTEGER PRIMARY KEY)") }
    }
    val database = boundDatabase(tempDir, schemaless)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        database.read { it.workflowStates.getFeatureTaskExecutionIdentity("missing") }
      }

    assertEquals(DatabaseFailureCode.ACCESS, error.code)
    assertTrue(
      error.message.orEmpty().startsWith(
        "Database ${DatabaseAccessOperation.READ.wireValue} failed for " +
          "'${schemaless.toAbsolutePath().normalize()}': ",
      ),
      error.message.orEmpty(),
    )
    assertFalse(error.message.orEmpty().contains("org.sqlite"), error.message.orEmpty())
    assertFalse(error.message.orEmpty().contains("\n"), error.message.orEmpty())
  }

  @Test
  fun `a non-sqlite failure raised inside the read block propagates unchanged`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-read-passthrough")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    database.transaction { }

    assertFailsWith<IllegalStateException> {
      database.read { error("unrelated failure") }
    }
  }

  @Test
  fun `a failing read block releases its snapshot instead of leaving a stuck transaction`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-read-snapshot-release")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)
    database.transaction { }

    assertFailsWith<IllegalStateException> {
      database.read { unitOfWork ->
        unitOfWork.workflowStates.getFeatureTaskExecutionIdentity("missing")
        error("boom")
      }
    }

    database.read { it.workflowStates.getFeatureTaskExecutionIdentity("missing") }
    database.transaction { }
  }

  @Test
  fun `transaction surfaces the typed error rather than a jdbc exception at the ports boundary`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-transaction")
    val unopenable = unopenableDatabasePath(tempDir)
    val database = boundDatabase(tempDir, unopenable)

    val thrown =
      runCatching {
        database.transaction { it.workflowStates }
      }.exceptionOrNull()

    assertFalse(thrown is SQLiteException, "raw JDBC exception crossed the ports boundary: $thrown")
    assertTrue(
      (thrown as? SkillBillRuntimeException)?.code == DatabaseFailureCode.ACCESS,
      "expected the typed error, got $thrown",
    )
  }

  @Test
  fun `a failure raised inside a transaction block still rolls back and propagates`() {
    val tempDir = Files.createTempDirectory("skillbill-typed-rollback")
    val dbPath = tempDir.resolve("metrics.db")
    val database = boundDatabase(tempDir, dbPath)

    assertFailsWith<IllegalStateException> {
      database.transaction { error("force rollback") }
    }

    database.transaction { }
  }

  private fun unopenableDatabasePath(tempDir: Path): Path =
    tempDir.resolve("unopenable.db").also { it.createDirectories() }

  private fun boundDatabase(
    tempDir: Path,
    dbPath: Path,
  ): SQLiteDatabaseSessionFactory =
    sqliteDatabaseSessionFactory(userHome = tempDir, dbPathOverride = dbPath.toString(), environment = emptyMap())
}
