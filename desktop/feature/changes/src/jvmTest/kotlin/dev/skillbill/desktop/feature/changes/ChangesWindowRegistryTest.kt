package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangesWindowRegistryTest {
  @Test
  fun `reopening an execution updates its scope and another execution replaces it`() {
    val registry = ChangesWindowRegistry()
    val first = workItem("A")
    val second = workItem("B")

    registry.open(first, ChangeScope.All)
    registry.open(first, ChangeScope.Subtasks(3, 3))
    assertEquals(ChangeScope.Subtasks(3, 3), registry.current.value?.scope)
    assertEquals(1, registry.current.value?.focusRequest)

    registry.open(second, ChangeScope.All)
    assertEquals(second.id, registry.current.value?.source?.id)
    assertEquals(0, registry.current.value?.focusRequest)

    registry.close()
    assertEquals(null, registry.current.value)
  }
}
