package dev.skillbill.tray.navigation

import androidx.savedstate.savedState
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.core.ui.navigation.EntryArgsOutcome
import dev.skillbill.tray.feature.detail.DetailArgs
import dev.skillbill.tray.feature.detail.HistoryDetailRoute
import dev.skillbill.tray.feature.detail.WorkDetailRoute
import dev.skillbill.tray.feature.detail.workDetailRoute
import dev.skillbill.tray.feature.history.HistoryRoute
import dev.skillbill.tray.feature.settings.SettingsRoute
import dev.skillbill.tray.feature.work.WorkRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteRestorationTest {
  private val now = DatabaseGenerationRef(3)
  private val safeRoot = WorkRoute(now)
  private val item = WorkIdentity("goal-1", "repo-1")

  @Test
  fun `a saved stack of this generation comes back as it was`() {
    val saved = RouteSnapshots.encode(listOf(HistoryRoute(now), HistoryDetailRoute("done-1", "repo-1", now)))

    val outcome = assertIs<RouteRestoreOutcome.Restored>(RouteSnapshots.restore(saved, now, safeRoot))

    assertEquals(listOf(HistoryRoute(now), HistoryDetailRoute("done-1", "repo-1", now)), outcome.routes)
    assertTrue(outcome.dropped.isEmpty())
  }

  @Test
  fun `every route type survives the saved form`() {
    val routes = listOf(WorkRoute(now), HistoryRoute(now), SettingsRoute, workDetailRoute(item, now))

    routes.forEach { route -> assertEquals(listOf(route), RouteSnapshots.decode(RouteSnapshots.encode(listOf(route)))) }
  }

  @Test
  fun `a stack saved under an earlier database is obsolete and gives the safe root`() {
    val saved = RouteSnapshots.encode(listOf(WorkRoute(DatabaseGenerationRef(2)), workDetailRoute(item, DatabaseGenerationRef(2))))

    val outcome = assertIs<RouteRestoreOutcome.Reset>(RouteSnapshots.restore(saved, now, safeRoot))

    assertEquals(listOf(safeRoot), outcome.routes)
    assertEquals("WorkRoute", outcome.diagnostic.routeType)
  }

  @Test
  fun `an obsolete detail is dropped and the current root stays`() {
    val saved = RouteSnapshots.encode(listOf(WorkRoute(now), workDetailRoute(item, DatabaseGenerationRef(1))))

    val outcome = assertIs<RouteRestoreOutcome.Restored>(RouteSnapshots.restore(saved, now, safeRoot))

    assertEquals(listOf(WorkRoute(now)), outcome.routes)
    assertEquals(listOf("WorkDetailRoute"), outcome.dropped.map { it.routeType })
  }

  @Test
  fun `a detail that names no item is invalid and is dropped with its reason`() {
    val saved = RouteSnapshots.encode(listOf(WorkRoute(now), WorkDetailRoute(workId = " ", repositoryIdentity = "repo-1", generation = now)))

    val outcome = assertIs<RouteRestoreOutcome.Restored>(RouteSnapshots.restore(saved, now, safeRoot))

    assertEquals(listOf(WorkRoute(now)), outcome.routes)
    assertEquals("A detail needs a work item", outcome.dropped.single().reason)
  }

  @Test
  fun `a stack that does not start at a section root gives the safe root`() {
    val saved = RouteSnapshots.encode(listOf(workDetailRoute(item, now)))

    val outcome = assertIs<RouteRestoreOutcome.Reset>(RouteSnapshots.restore(saved, now, safeRoot))

    assertEquals(listOf(safeRoot), outcome.routes)
    assertEquals("A stack starts at a section root", outcome.diagnostic.reason)
  }

  @Test
  fun `a stack deeper than a root and one detail keeps only that much`() {
    val saved = RouteSnapshots.encode(listOf(WorkRoute(now), workDetailRoute(item, now), workDetailRoute(WorkIdentity("goal-2", "repo-2"), now)))

    val outcome = assertIs<RouteRestoreOutcome.Restored>(RouteSnapshots.restore(saved, now, safeRoot))

    assertEquals(listOf(WorkRoute(now), workDetailRoute(item, now)), outcome.routes)
  }

  @Test
  fun `saved state that is not a route snapshot is unreadable and gives the safe root`() {
    val saved = savedState()

    assertNull(RouteSnapshots.decode(saved))
    val outcome = assertIs<RouteRestoreOutcome.Reset>(RouteSnapshots.restore(saved, now, safeRoot))
    assertEquals(listOf(safeRoot), outcome.routes)
  }

  @Test
  fun `while a database switch is under way no route made under a generation is current`() {
    val saved = RouteSnapshots.encode(listOf(WorkRoute(now)))

    val outcome = assertIs<RouteRestoreOutcome.Reset>(RouteSnapshots.restore(saved, null, WorkRoute(null)))

    assertEquals(listOf(WorkRoute(null)), outcome.routes)
  }

  @Test
  fun `detail arguments are validated into a typed outcome, never cast`() {
    val valid = assertIs<EntryArgsOutcome.Valid<DetailArgs>>(DetailArgs.from(workDetailRoute(item, now)))
    assertEquals(item, valid.args.identity)

    val blank = assertIs<EntryArgsOutcome.Rejected>(DetailArgs.from(HistoryDetailRoute("", "repo-1", now)))
    assertEquals("HistoryDetailRoute", blank.routeType)

    val wrongRoute = assertIs<EntryArgsOutcome.Rejected>(DetailArgs.from(WorkRoute(now)))
    assertEquals("WorkRoute", wrongRoute.routeType)
  }

  @Test
  fun `an entry made for one generation never shares a key with the next`() {
    assertNotEquals(WorkRoute(DatabaseGenerationRef(1)).entryKey, WorkRoute(DatabaseGenerationRef(2)).entryKey)
    assertNotEquals(workDetailRoute(item, DatabaseGenerationRef(1)).entryKey, workDetailRoute(item, DatabaseGenerationRef(2)).entryKey)
  }
}
