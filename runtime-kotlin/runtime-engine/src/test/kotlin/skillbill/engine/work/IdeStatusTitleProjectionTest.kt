package skillbill.engine.work

import skillbill.engine.work.model.IdeStatusRequest
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.WorkBoardRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeStatusTitleProjectionTest {
  @Test
  fun `a goal is titled by its feature name and falls back to its issue key without a manifest`() {
    val fixture = gitRepoFixture("ide-status-goal-title")
    val identity = testGoalRepositoryIdentity(fixture)
    val request = IdeStatusRequest(repoRoot = fixture.toString(), observedAt = ideStatusObservedAt)

    val titled =
      ideStatusService(
        goalOnlyDatabase(),
        manifestStore = StubGoalManifestStore(goalManifestState(fixture, identity, childWorkflowId = "w-child")),
      ).status(request)
    val untitled = ideStatusService(goalOnlyDatabase()).status(request)

    assertEquals("ide-status", titled.snapshot.title)
    assertEquals("SKILL-148", untitled.snapshot.title)
  }

  @Test
  fun `a standalone phase is titled by its id and recorded target, never by its summary`() {
    establishTemporarySchemaReadiness("ide-status-phase-title").use { fixture ->
      val database =
        sqliteDatabaseSessionFactory(fixture.tempDir, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val identity = testGoalRepositoryIdentity(fixture.tempDir)
      database.transaction { unit ->
        listOf(
          Triple("plan-1", "plan", "SKILL-452"),
          Triple("review-1", "review", null),
          Triple("qa-1", "qa", null),
        ).forEach { (invocationId, phaseId, issueKey) ->
          unit.standalonePhaseStatuses.register(
            StandalonePhaseStatusRegistration(
              identity, if (phaseId == "qa") "HEAD" else "feat/SKILL-452-board", issueKey, null, invocationId,
              phaseId, "execution-$invocationId", "blocked", phaseId, ideStatusObservedAt.minusSeconds(30), "owner",
              1, ideStatusObservedAt.plusSeconds(30),
            ),
          )
        }
      }

      val items =
        ideStatusService(database).board(WorkBoardRequest(observedAt = ideStatusObservedAt, pageSize = 10)).items
      val titles = items.associate { it.snapshot.execution?.invocationId.orEmpty() to it.snapshot.title }

      assertEquals(
        mapOf("plan-1" to "plan SKILL-452", "review-1" to "review feat/SKILL-452-board", "qa-1" to "qa"),
        titles,
      )
      assertTrue(items.none { it.snapshot.title == it.snapshot.summary })
    }
  }

  @Test
  fun `a running plan is titled by the feature name of the bundle its intake seeded`() {
    val bundle = ideStatusProjectStoreRoot.resolve("specs/SKILL-9452-linear-progress-sync")
    Files.createDirectories(bundle)
    try {
      establishTemporarySchemaReadiness("ide-status-seeded-plan-title").use { fixture ->
        val database =
          sqliteDatabaseSessionFactory(fixture.tempDir, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
        val identity = testGoalRepositoryIdentity(fixture.tempDir)
        database.transaction { unit ->
          unit.standalonePhaseStatuses.register(
            StandalonePhaseStatusRegistration(
              identity, "feat/SKILL-9452-board", "SKILL-9452", null, "plan-1", "plan", "execution-plan-1", "running",
              "plan", ideStatusObservedAt.minusSeconds(30), "owner", 1, ideStatusObservedAt.plusSeconds(30),
            ),
          )
        }

        val items =
          ideStatusService(database).board(WorkBoardRequest(observedAt = ideStatusObservedAt, pageSize = 10)).items

        assertEquals(listOf("linear-progress-sync"), items.map { it.snapshot.title })
      }
    } finally {
      Files.delete(bundle)
    }
  }
}
