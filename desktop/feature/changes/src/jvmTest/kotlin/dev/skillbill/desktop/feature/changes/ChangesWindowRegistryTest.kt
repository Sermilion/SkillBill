package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangesWindowRegistryTest {
  @Test
  fun `reopening an execution updates its scope and focus request`() {
    val registry = ChangesWindowRegistry()
    val first = workItem("A")
    val second = workItem("B")

    registry.open(first, ChangeScope.All)
    registry.open(first, ChangeScope.Subtasks(3, 3))
    registry.open(second, ChangeScope.All)

    assertEquals(2, registry.windows.value.size)
    assertEquals(ChangeScope.Subtasks(3, 3), registry.windows.value.first().scope)
    assertEquals(1, registry.windows.value.first().focusRequest)
    registry.close(first.id)
    assertEquals(listOf(second.id), registry.windows.value.map { it.item.id })
  }
}
