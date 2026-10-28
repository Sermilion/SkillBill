package skillbill.infrastructure.sqlite.idestatus

import skillbill.contracts.persistence.WorkBoardReadModelKeys
import skillbill.infrastructure.sqlite.SqliteTestDiagnostics
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkBoardReadModelStoreTest {
  @Test
  fun `page applies search filter before limit`() {
    val dbPath = Files.createTempDirectory("work-board-read-model-page").resolve("runtime.db")
    ensureTestDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO ${WorkBoardReadModelKeys.ITEMS_TABLE} " +
            "(${WorkBoardReadModelKeys.WORK_ID}, ${WorkBoardReadModelKeys.DISPLAY_PRIORITY}, " +
            "${WorkBoardReadModelKeys.REQUIRES_ATTENTION}, ${WorkBoardReadModelKeys.UPDATED_AT}, " +
            "${WorkBoardReadModelKeys.SEARCH_TEXT}, ${WorkBoardReadModelKeys.PAYLOAD}, " +
            "${WorkBoardReadModelKeys.PROJECTED_AT}) VALUES " +
            "('first', 1, 0, '2026-10-28T11:00:00.000000000Z', 'other issue', '{bad', " +
            "'2026-10-28T11:00:00.000000000Z'), " +
            "('match', 2, 0, '2026-10-28T10:00:00.000000000Z', 'issue-447 title', '{bad', " +
            "'2026-10-28T10:00:00.000000000Z')",
        )
      }

      val page =
        SqliteWorkBoardReadModelStore(connection, SqliteTestDiagnostics).page(
          WorkBoardReadModelPageQuery(
            finishedFrom = Instant.parse("2026-10-21T12:00:00Z"),
            filter = "issue-447",
            afterKey = null,
            limit = 1,
          ),
        )

      assertEquals(listOf("match"), page.entries.map { it.workId })
    }
  }
}
