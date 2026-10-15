package dev.skillbill.tray.navigation

import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import dev.skillbill.tray.feature.detail.HistoryDetailRoute
import dev.skillbill.tray.feature.detail.WorkDetailRoute
import dev.skillbill.tray.feature.history.HistoryRoute
import dev.skillbill.tray.feature.settings.SettingsRoute
import dev.skillbill.tray.feature.work.WorkRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowNavigatorTest {
  private val ref = DatabaseGenerationRef(1)
  private val first = WorkIdentity("goal-1", "repo-1")
  private val second = WorkIdentity("goal-2", "repo-2")
  private var generation: DatabaseGenerationRef? = ref

  private fun navigator() = WindowNavigator({ generation }, listOf(WorkRoute(ref)))

  @Test
  fun `opening an item shows its detail above the list and choosing another replaces it`() {
    val navigator = navigator()

    assertTrue(navigator.openDetail(first))
    assertEquals(listOf(WorkRoute(ref), WorkDetailRoute("goal-1", "repo-1", ref)), navigator.stack.entries)
    assertEquals(first, navigator.selected)

    navigator.openDetail(second)
    assertEquals(2, navigator.stack.entries.size)
    assertEquals(second, navigator.selected)
  }

  @Test
  fun `back leaves the detail for the list and never leaves the root`() {
    val navigator = navigator()
    navigator.openDetail(first)

    assertTrue(navigator.back())
    assertNull(navigator.selected)
    assertFalse(navigator.back())
    assertEquals(listOf(WorkRoute(ref)), navigator.stack.entries)
  }

  @Test
  fun `switching section and back restores the filter, the selection and the scroll anchor`() {
    val navigator = navigator()
    navigator.memory(Section.WORK).filter = "SKILL-4"
    navigator.memory(Section.WORK).scrollIndex = 7
    navigator.memory(Section.WORK).scrollOffset = 12
    navigator.openDetail(first)

    navigator.switchTo(Section.HISTORY)
    assertEquals(Section.HISTORY, navigator.section)
    assertEquals(listOf(HistoryRoute(ref)), navigator.stack.entries)
    navigator.openDetail(second)
    assertTrue(navigator.stack.top is HistoryDetailRoute)

    navigator.switchTo(Section.WORK)
    assertEquals(Section.WORK, navigator.section)
    assertEquals(first, navigator.selected)
    assertEquals("SKILL-4", navigator.memory(Section.WORK).filter)
    assertEquals(7, navigator.memory(Section.WORK).scrollIndex)
    assertEquals(12, navigator.memory(Section.WORK).scrollOffset)

    navigator.switchTo(Section.HISTORY)
    assertEquals(second, navigator.selected)
  }

  @Test
  fun `a remembered selection from another generation is not brought back`() {
    val navigator = navigator()
    navigator.openDetail(first)
    navigator.switchTo(Section.HISTORY)
    generation = DatabaseGenerationRef(2)

    navigator.switchTo(Section.WORK)

    assertEquals(listOf(WorkRoute(DatabaseGenerationRef(2))), navigator.stack.entries)
  }

  @Test
  fun `nothing that names work opens while a database switch is under way`() {
    val navigator = navigator()
    generation = null

    assertFalse(navigator.openDetail(first))
    navigator.switchTo(Section.HISTORY)

    assertEquals(listOf(WorkRoute(ref)), navigator.stack.entries)
  }

  @Test
  fun `a settings window opens no detail and has no section`() {
    val navigator = WindowNavigator({ generation }, listOf(SettingsRoute))

    assertNull(navigator.section)
    assertFalse(navigator.openDetail(first))
  }

  @Test
  fun `reset drops the stack, every section's memory and the list status`() {
    val navigator = navigator()
    navigator.memory(Section.WORK).filter = "x"
    navigator.memory(Section.WORK).scrollIndex = 4
    navigator.openDetail(first)
    navigator.status = ListStatus(loading = false, failed = true)

    navigator.reset(null)
    assertEquals(listOf(WorkRoute(null)), navigator.stack.entries)
    assertNull(navigator.selected)
    assertEquals("", navigator.memory(Section.WORK).filter)
    assertEquals(0, navigator.memory(Section.WORK).scrollIndex)
    assertEquals(ListStatus().loading, navigator.status.loading)
    assertFalse(navigator.status.failed)

    navigator.reset(DatabaseGenerationRef(2))
    assertEquals(listOf(WorkRoute(DatabaseGenerationRef(2))), navigator.stack.entries)
  }

  @Test
  fun `a successor replaces the selection only while the launching item is still shown`() {
    val navigator = navigator()
    navigator.openDetail(first)

    navigator.selectSuccessor(from = first, successor = second)
    assertEquals(second, navigator.selected)

    navigator.selectSuccessor(from = first, successor = WorkIdentity("goal-3", "repo-3"))
    assertEquals(second, navigator.selected)
  }

  @Test
  fun `the stack saves and restores into a new window`() {
    val navigator = navigator()
    navigator.openDetail(first)

    val restored = RouteSnapshots.restore(navigator.snapshot(), ref, WorkRoute(ref))

    assertEquals(navigator.stack.entries, restored.routes)
  }
}
