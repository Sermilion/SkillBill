package skillbill.error.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseFailureCodeTest {
  @Test
  fun `message carries the resolved path and the sqlite result code`() {
    val error =
      databaseAccessFailure(
        dbPath = "/tmp/skill-bill/review-metrics.db",
        operation = DatabaseAccessOperation.READ,
        condition = "sqlite result code 5: database is locked",
      )

    assertContains(error.message.orEmpty(), "/tmp/skill-bill/review-metrics.db")
    assertContains(error.message.orEmpty(), "sqlite result code 5")
    assertContains(error.message.orEmpty(), "read")
  }

  @Test
  fun `bounded condition collapses a multi line condition and drops stack frames`() {
    val error =
      databaseAccessFailure(
        dbPath = "/tmp/metrics.db",
        operation = DatabaseAccessOperation.OPEN,
        condition =
          buildString {
            appendLine("org.sqlite.SQLiteException: [SQLITE_BUSY] database is locked")
            appendLine("\tat org.sqlite.core.DB.newSQLException(DB.java:1010)")
            appendLine("\tat org.sqlite.core.DB.throwex(DB.java:977)")
            appendLine("Caused by: org.sqlite.SQLiteException: inner")
            appendLine("\t... 12 more")
          },
      )

    val message = error.message.orEmpty()
    assertEquals(1, message.lines().size, message)
    assertFalse(message.contains("org.sqlite"), message)
    assertFalse(message.contains("at org."), message)
    assertFalse(message.contains("Caused by:"), message)
    assertContains(message, "database is locked")
  }

  @Test
  fun `condition is length bounded`() {
    val error =
      databaseAccessFailure(
        dbPath = "/tmp/metrics.db",
        operation = DatabaseAccessOperation.READ,
        condition = "x".repeat(5_000),
      )

    val condition = databaseAccessCondition(error)
    assertTrue(condition.length <= 201, "condition length was ${condition.length}")
    assertEquals(1, error.message.orEmpty().lines().size)
  }

  @Test
  fun `the access failure carries the database access code`() {
    val error =
      databaseAccessFailure(
        dbPath = "/tmp/metrics.db",
        operation = DatabaseAccessOperation.OPEN,
        condition = "sqlite result code 5: database is locked",
      )

    assertEquals(DatabaseFailureCode.ACCESS, error.code)
  }

  @Test
  fun `blank condition falls back to a bounded placeholder`() {
    val error =
      databaseAccessFailure(
        dbPath = "/tmp/metrics.db",
        operation = DatabaseAccessOperation.OPEN,
        condition = "   \n  ",
      )

    assertEquals("unknown sqlite condition", databaseAccessCondition(error))
  }

  @Test
  fun `condition extractor returns the bounded condition and not the path or prefix`() {
    val error =
      databaseAccessFailure(
        dbPath = "/tmp/metrics.db",
        operation = DatabaseAccessOperation.READ,
        condition = "sqlite result code 14: unable to open database file",
      )

    assertEquals("sqlite result code 14: unable to open database file", databaseAccessCondition(error))
  }
}
