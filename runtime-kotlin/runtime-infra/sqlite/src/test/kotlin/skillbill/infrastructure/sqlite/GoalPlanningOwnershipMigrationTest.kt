package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.workflow.goalrunner.GoalPlanningOwnershipStore
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoalPlanningOwnershipMigrationTest {
  @Test
  fun `version 124 planning ownership survives interrupted and repeated upgrade`() {
    val root = Files.createTempDirectory("planning-ownership-upgrade")
    val database = root.resolve("runtime.db")
    try {
      val owner =
        GoalPlanningOwnership(
          repositoryIdentity = "/repo/.git",
          normalizedIssueKey = "SONA-8",
          workflowId = "original-plan",
          reservationId = "original-reservation",
          ownedPath = root.resolve("checkout"),
          initialCommit = "original-commit",
          state = GoalPlanningOwnership.State.RESERVED,
        )
      DatabaseRuntime.ensureDatabase(database).use { connection ->
        GoalPlanningOwnershipStore(connection).reserve(owner)
        connection.createStatement().use { statement ->
          statement.execute("ALTER TABLE goal_planning_ownership DROP COLUMN legacy_bundle_pending")
          statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'track-legacy-plan-bundle-transfer'")
          statement.execute("PRAGMA user_version = 124")
        }
        connection.autoCommit = false
        DatabaseMigrations.migrations.single { it.version == 125 }.apply(connection, SqliteTestDiagnostics)
        connection.rollback()
        connection.autoCommit = true
      }
      repeat(2) {
        DatabaseRuntime.ensureDatabase(database).use { connection ->
          val restored = assertNotNull(GoalPlanningOwnershipStore(connection).findByWorkflow(owner.workflowId))
          assertEquals(owner, restored)
          assertNull(restored.legacyBundlePending)
          connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
              rows.next()
              assertEquals(DesktopReadSchema.VERSION, rows.getInt(1))
            }
          }
        }
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
