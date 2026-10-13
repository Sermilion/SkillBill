package skillbill.infrastructure.sqlite

import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import skillbill.error.core.DatabaseFailureCode
import skillbill.error.core.SkillBillRuntimeException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class SQLiteDatabaseSessionFactoryBusyTranslationTest {
  @Test
  fun `a busy failure inside a write transaction surfaces as the typed busy error`() {
    val tempDir = Files.createTempDirectory("skillbill-busy-transaction")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = tempDir,
        dbPathOverride = tempDir.resolve("metrics.db").toString(),
        environment = emptyMap(),
      )

    val raised = SQLiteException("[SQLITE_BUSY] private driver details", SQLiteErrorCode.SQLITE_BUSY)
    val thrown =
      assertFailsWith<SkillBillRuntimeException> {
        database.transaction { throw raised }
      }
    assertEquals(DatabaseFailureCode.BUSY, thrown.code)

    assertSame(raised, thrown.cause)
    assertFalse(thrown.message.orEmpty().contains("private driver details"))
  }

  @Test
  fun `a non-busy driver failure whose message mentions a locked database is not translated`() {
    val tempDir = Files.createTempDirectory("skillbill-locked-text-transaction")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = tempDir,
        dbPathOverride = tempDir.resolve("metrics.db").toString(),
        environment = emptyMap(),
      )
    val raised =
      SQLiteException(
        "constraint failed after a note about database is locked",
        SQLiteErrorCode.SQLITE_CONSTRAINT,
      )

    val thrown = assertFailsWith<SkillBillRuntimeException> { database.transaction { throw raised } }

    assertEquals(DatabaseFailureCode.ACCESS, thrown.code)
    assertSame(raised, thrown.cause)
  }

  @Test
  fun `a non-busy failure inside a write transaction is not translated to the busy error`() {
    val tempDir = Files.createTempDirectory("skillbill-non-busy-transaction")
    val database =
      sqliteDatabaseSessionFactory(
        userHome = tempDir,
        dbPathOverride = tempDir.resolve("metrics.db").toString(),
        environment = emptyMap(),
      )
    val raised = IllegalStateException("unrelated failure")

    val thrown = assertFailsWith<IllegalStateException> { database.transaction { throw raised } }

    assertSame(raised, thrown)
  }
}
