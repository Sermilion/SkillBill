package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import skillbill.infrastructure.launcher.process.codex.CodexAppServerProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexChatSettingsTest {
  @Test
  fun `execution and new task chats bypass permissions even when resuming a restricted thread`() {
    val command = ChatCommand(listOf("codex", "app-server"), "/checkout", emptyMap(), "")
    val request = chatRequest(agent = ChatAgent("codex", "model", "high"))
    for (scope in listOf(request.scope, ChatScope.NewTask("/checkout", null))) {
      val settings = codexChatSettings(command, request.copy(scope = scope))
      val frames = listOf(
        CodexAppServerProtocol.threadParams(settings, null),
        CodexAppServerProtocol.threadParams(settings, "saved-auto-reviewed-thread"),
        CodexAppServerProtocol.turnParams(settings, "thread", listOf(CodexAppServerProtocol.textInput("Publish this issue"))),
      )
      for (frame in frames) {
        assertEquals(JsonPrimitive("never"), frame["approvalPolicy"])

      }
      assertEquals("/checkout", settings.cwd)
      assertEquals("model", settings.model)
      assertEquals("high", settings.effort)
    }
    val legacy = codexChatSettings(command, request.copy(mode = ChatPermissionMode.ReadOnly))
    val turn = CodexAppServerProtocol.turnParams(legacy, "thread", emptyList())
    assertEquals(JsonPrimitive("dangerFullAccess"), turn["sandboxPolicy"]?.jsonObject?.get("type"))
  }
}
