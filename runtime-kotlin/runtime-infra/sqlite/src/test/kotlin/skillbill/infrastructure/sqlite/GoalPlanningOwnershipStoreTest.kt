package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.workflow.goalrunner.GoalPlanningOwnershipStore
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.goalrunner.model.GoalPlanningOwnershipWriteResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoalPlanningOwnershipStoreTest {
  @Test
  fun `reservation is durable and transitions are conditional and repeatable`() {
    val root = Files.createTempDirectory("goal-planning-ownership")
    try {
      val databasePath = root.resolve("runtime.db")
      var expected: GoalPlanningOwnership? = null
      DatabaseRuntime.ensureDatabase(databasePath).use { connection ->
        val store = GoalPlanningOwnershipStore(connection)
        val reservation =
          GoalPlanningOwnership(
            repositoryIdentity = "/repo/.git",
            normalizedIssueKey = "SONA-8",
            workflowId = "plan-reserved-1",
            reservationId = "launch-1",
            ownedPath = root.resolve("checkout").toAbsolutePath(),
            initialCommit = "revision-1",
            state = GoalPlanningOwnership.State.RESERVED,
          )

        assertIs<GoalPlanningOwnershipWriteResult.Stored>(store.reserve(reservation))
        assertIs<GoalPlanningOwnershipWriteResult.Stored>(store.reserve(reservation))
        val competing =
          store.reserve(
            reservation.copy(
              workflowId = "plan-reserved-2",
              reservationId = "launch-2",
              ownedPath = root.resolve("other").toAbsolutePath(),
            ),
          )
        assertIs<GoalPlanningOwnershipWriteResult.Conflict>(competing)
        assertEquals(reservation, store.findByWorkflow(reservation.workflowId))

        val initialized =
          assertIs<GoalPlanningOwnershipWriteResult.Stored>(
            store.markInitialized(reservation.reservationId, reservation.ownedPath, root.resolve("gitdir").toString()),
          ).ownership
        assertEquals(GoalPlanningOwnership.State.INITIALIZED, initialized.state)
        val transferred =
          assertIs<GoalPlanningOwnershipWriteResult.Stored>(
            store.transfer(reservation.reservationId, "goal-parent-1", reservation.ownedPath),
          ).ownership
        assertEquals("goal-parent-1", transferred.parentWorkflowId)
        assertEquals(
          transferred,
          assertIs<GoalPlanningOwnershipWriteResult.Stored>(
            store.transfer(reservation.reservationId, "goal-parent-1", reservation.ownedPath),
          ).ownership,
        )
        assertIs<GoalPlanningOwnershipWriteResult.Conflict>(
          store.transfer(reservation.reservationId, "goal-parent-2", reservation.ownedPath),
        )
        assertEquals(transferred, store.findByWorkflow(reservation.workflowId))

        assertSupersededReservation(store, reservation, root)
        expected = transferred
      }
      DatabaseRuntime.ensureDatabase(databasePath).use { connection ->
        assertEquals(expected, GoalPlanningOwnershipStore(connection).findByWorkflow("plan-reserved-1"))
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun assertSupersededReservation(
    store: GoalPlanningOwnershipStore,
    reservation: GoalPlanningOwnership,
    root: Path,
  ) {
    val superseded =
      reservation.copy(
        normalizedIssueKey = "SONA-9",
        workflowId = "plan-superseded-1",
        reservationId = "launch-superseded-1",
        ownedPath = root.resolve("superseded").toAbsolutePath(),
      )
    assertIs<GoalPlanningOwnershipWriteResult.Stored>(store.reserve(superseded))
    assertIs<GoalPlanningOwnershipWriteResult.Stored>(
      store.supersede(superseded.reservationId, superseded.workflowId, superseded.ownedPath),
    )
    val replacement =
      superseded.copy(
        workflowId = "plan-replacement-1",
        reservationId = "launch-replacement-1",
        ownedPath = root.resolve("replacement").toAbsolutePath(),
      )
    assertIs<GoalPlanningOwnershipWriteResult.Stored>(store.reserve(replacement))
    assertEquals(GoalPlanningOwnership.State.SUPERSEDED, store.findByWorkflow(superseded.workflowId)?.state)
  }
}
