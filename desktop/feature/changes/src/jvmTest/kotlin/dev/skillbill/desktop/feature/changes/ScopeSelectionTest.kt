package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopeSelectionTest {
  @Test
  fun `picker search matches a whole number or a name in any case`() {
    val subtasks = listOf(9 to "Parser", 19 to "Renderer", 29 to "Loader", 30 to "Public api client").map { (id, name) ->
      ScopeSubtask(id, name, ScopeSubtaskState.DONE, "e", listOf("e"), false, emptyList())
    }

    assertEquals(listOf(9), filterSubtasks(subtasks, "9").map { it.id })
    assertEquals(listOf(30), filterSubtasks(subtasks, " API ").map { it.id })
  }
}
