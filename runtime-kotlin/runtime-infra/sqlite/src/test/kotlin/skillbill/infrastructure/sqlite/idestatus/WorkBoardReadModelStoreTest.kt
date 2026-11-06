package skillbill.infrastructure.sqlite.idestatus

import skillbill.contracts.persistence.WorkBoardReadModelKeys
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.infrastructure.sqlite.SqliteTestDiagnostics
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.ports.idestatus.model.GoalMainCheckoutOutcome
import skillbill.ports.idestatus.model.GoalWorktreeState
import skillbill.ports.idestatus.model.GoalWorktreeView
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import skillbill.ports.idestatus.model.WorkBoardReadModelRow
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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

  @Test
  fun `a row keeps its title and goal worktree and a row written before titles existed is undecodable`() {
    val at = Instant.parse("2026-10-28T10:00:00Z")
    val row =
      WorkBoardReadModelRow(
        "plan-1",
        WorkBoardItem(
          IdeStatusSnapshot(
            repositoryIdentity = "repo",
            lifecycleState = IdeStatusLifecycleState.BLOCKED,
            currentStep = IdeStatusStep("plan", "plan"),
            updatedAt = at,
            freshness = IdeStatusFreshness.FRESH,
            summary = "The plan is blocked: the spec names no acceptance criteria.",
            title = "plan SKILL-452",
          ),
          repositoryRoot = "/repo",
          goalWorktree = GoalWorktreeView("/goal", GoalWorktreeState.RETAINED, "unpublished_commits", "keep edits"),
        ),
        at,
        "plan",
        null,
        at,
        null,
      )
    val metadata = WorkBoardRowMetadata(row.workId, at, row.searchText, null, at, null)
    val payload = WorkBoardReadModelPayloadCodec.encode(row)

    assertEquals("plan SKILL-452", WorkBoardReadModelPayloadCodec.decode(payload, metadata)?.item?.snapshot?.title)
    assertEquals(
      GoalWorktreeView("/goal", GoalWorktreeState.RETAINED, "unpublished_commits", "keep edits"),
      WorkBoardReadModelPayloadCodec.decode(payload, metadata)?.item?.goalWorktree,
    )
    assertNull(WorkBoardReadModelPayloadCodec.decode(payload.replace("\"title\":\"plan SKILL-452\",", ""), metadata))
  }

  @Test
  fun `a removed worktree keeps its main checkout outcome and a row without the keys still decodes`() {
    val at = Instant.parse("2026-10-28T10:00:00Z")

    fun rowWith(worktree: GoalWorktreeView) =
      WorkBoardReadModelRow(
        "goal-1",
        WorkBoardItem(
          IdeStatusSnapshot(
            repositoryIdentity = "repo",
            lifecycleState = IdeStatusLifecycleState.BLOCKED,
            currentStep = IdeStatusStep("plan", "plan"),
            updatedAt = at,
            freshness = IdeStatusFreshness.FRESH,
            summary = "The goal finished.",
            title = "goal SKILL-512",
          ),
          repositoryRoot = "/repo",
          goalWorktree = worktree,
        ),
        at,
        "goal",
        null,
        at,
        null,
      )
    val metadata = WorkBoardRowMetadata("goal-1", at, "goal", null, at, null)
    val outcome =
      GoalWorktreeView(
        "/goal",
        GoalWorktreeState.REMOVED,
        mainCheckoutOutcome = GoalMainCheckoutOutcome.SKIPPED_UNCOMMITTED_CHANGES,
        mainCheckoutDetail = "app/build.gradle",
      )

    assertEquals(
      outcome,
      WorkBoardReadModelPayloadCodec.decode(WorkBoardReadModelPayloadCodec.encode(rowWith(outcome)), metadata)
        ?.item
        ?.goalWorktree,
    )

    val legacy =
      WorkBoardReadModelPayloadCodec.encode(rowWith(GoalWorktreeView("/goal", GoalWorktreeState.REMOVED)))
        .replace(",\"main_checkout_outcome\":null", "")
        .replace(",\"main_checkout_detail\":null", "")
    assertFalse(legacy.contains("main_checkout"), legacy)
    assertEquals(
      GoalWorktreeView("/goal", GoalWorktreeState.REMOVED),
      WorkBoardReadModelPayloadCodec.decode(legacy, metadata)?.item?.goalWorktree,
    )
  }
}
