package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals

class CursorAcpArgumentsTest {
  @Test
  fun `act chat runs Cursor auto-review and read-only chat does not`() {
    assertEquals(listOf("--auto-review", "acp"), cursorAcpArguments(ChatPermissionMode.Act))
    assertEquals(listOf("acp"), cursorAcpArguments(ChatPermissionMode.ReadOnly))
  }
}
