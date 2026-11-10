package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import skillbill.infrastructure.launcher.process.codex.CodexAppServerProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexChatDefaultModelTest {
  @Test
  fun `resuming with agent default sends the profile configured model on resume and turn`() = runTest {
    val command = ChatCommand(listOf("codex", "app-server"), "/checkout", emptyMap(), "")
    val settings = codexResumeSettings(
      codexChatSettings(command, chatRequest(agent = ChatAgent("codex", null, null))), "existing-thread",
    ) { method, params ->
      assertEquals("config/read", method)
      assertEquals(JsonPrimitive("/checkout"), params["cwd"])
      Json.parseToJsonElement("""{"config":{"model":"profile-default"}}""").jsonObject
    }
    assertEquals(JsonPrimitive("profile-default"), CodexAppServerProtocol.threadParams(settings, "existing-thread")["model"])
    assertEquals(JsonPrimitive("profile-default"), CodexAppServerProtocol.turnParams(settings, "existing-thread", emptyList())["model"])
  }

  @Test
  fun `explicit models and fresh threads do not require default discovery`() = runTest {
    val command = ChatCommand(listOf("codex", "app-server"), "/checkout", emptyMap(), "")
    for ((model, thread) in listOf("chosen" to "existing", null to null)) {
      val settings = codexChatSettings(command, chatRequest(agent = ChatAgent("codex", model, null)))
      assertEquals(settings, codexResumeSettings(settings, thread) { _, _ -> error("unexpected discovery") })
    }
  }

  @Test
  fun `an unconfigured profile uses the catalog default across pages`() = runTest {
    val calls = mutableListOf<String>()
    val model = codexDefaultModel("/checkout") { method, params ->
      calls += method
      Json.parseToJsonElement(when {
        method == "config/read" -> """{"config":{"model":null}}"""
        params["cursor"] == null -> """{"data":[{"model":"other","isDefault":false}],"nextCursor":"next"}"""
        else -> """{"data":[{"model":"catalog-default","isDefault":true}],"nextCursor":null}"""
      }).jsonObject
    }
    assertEquals("catalog-default", model)
    assertEquals(listOf("config/read", "model/list", "model/list"), calls)
  }

  @Test
  fun `missing default refuses to resume with the saved model`() = runTest {
    assertFailsWith<IllegalStateException> {
      codexDefaultModel("/checkout") { method, _ ->
        Json.parseToJsonElement(if (method == "config/read") """{"config":{}}""" else """{"data":[],"nextCursor":null}""").jsonObject
      }
    }
  }
}
