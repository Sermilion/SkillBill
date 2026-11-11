package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.usage.UsageProfileResolver
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.model.ChatModelsRead
import dev.skillbill.desktop.core.domain.model.AdvertisedAgentChoices
import dev.skillbill.desktop.core.domain.model.AdvertisedEffortSupport
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatModelCatalogTest {
  @Test
  fun `codex lists every page using the selected profile and closes discovery without a prompt`() = runTest {
    val connection = ModelConnection(listOf(
      """{"id":1,"result":{}}""",
      """{"id":2,"result":{"data":[{"id":"entry-a","model":"model-a","displayName":"Model A"}],"nextCursor":"page2"}}""",
      """{"id":3,"result":{"data":[{"id":"entry-b","model":"model-b","displayName":"Model B"}],"nextCursor":null}}""",
    ))
    var command: ChatCommand? = null
    val read = catalog(connection, onStart = { command = it }).read(ChatAgent("codex", null, null, "/profiles/custom"), "/repo")
    assertEquals(listOf(ChatModel("model-a", "Model A"), ChatModel("model-b", "Model B")), assertIs<ChatModelsRead.Loaded>(read).models)
    assertEquals("/profiles/custom", command?.environment?.get("CODEX_HOME"))
    assertEquals("/repo", command?.workingDirectory)
    assertEquals(listOf("initialize", "initialized", "model/list", "model/list"), connection.sent.map { it.string("method") })
    assertEquals("page2", connection.sent.last().obj("params")?.string("cursor"))
    assertTrue(connection.closed)
  }

  @Test
  fun `claude exposes provider models without generating a chat turn`() = runTest {
    val connection = ModelConnection(listOf(
      """{"type":"control_response","response":{"request_id":"chat-models","subtype":"success","response":{"models":[{"value":"provider-new-model","displayName":"New model"}]}}}""",
    ))
    val read = catalog(connection).read(ChatAgent("claude", null, null), "/repo")
    assertEquals(listOf(ChatModel("provider-new-model", "New model")), assertIs<ChatModelsRead.Loaded>(read).models)
    assertEquals("initialize", connection.sent.single().obj("request")?.string("subtype"))
    assertTrue(connection.closed)
  }

  @Test
  fun `a malformed model page stays visible as a failed read with a diagnostic`() = runTest {
    val connection = ModelConnection(listOf("""{"id":1,"result":{}}""", """{"id":2,"result":{"data":[{}]}}"""))
    val diagnostics = RecordingDiagnostics()
    assertIs<ChatModelsRead.Failed>(catalog(connection, diagnostics).read(ChatAgent("codex", null, null), "/repo"))
    assertEquals(listOf("chat.model_discovery_failed"), diagnostics.categories)
    assertTrue(connection.closed)
  }

  @Test
  fun `cancelling discovery closes its process and propagates cancellation`() = runTest {
    val connection = ModelConnection(emptyList(), cancel = true)
    var cancelled = false
    try {
      catalog(connection).read(ChatAgent("codex", null, null), "/repo")
    } catch (_: CancellationException) {
      cancelled = true
    }
    assertTrue(cancelled)
    assertTrue(connection.closed)
  }

  @Test
  fun `cursor model rows retain parameter variants and ignore headings and tips`() {
    val output = "Available models\n\u001B[32mnew-model-high - New model high\u001B[0m\nnew-model-low - New model low\nTip: use --model <id>"
    assertEquals(listOf(ChatModel("new-model-high", "New model high"), ChatModel("new-model-low", "New model low")), cursorChatModels(output))
  }

  @Test
  fun `junie discovery reads grouped ACP model options`() = runTest {
    val connection = ModelConnection(listOf(
      """{"id":1,"result":{"protocolVersion":1}}""",
      """{"id":2,"result":{"sessionId":"discovery-only","configOptions":[{"category":"model","options":[{"group":"Provider","options":[{"value":"new-model","name":"New model"}]}]}]}}""",
    ))
    val read = catalog(connection).read(ChatAgent("junie", null, null), "/repo")
    assertEquals(listOf(ChatModel("new-model", "New model")), assertIs<ChatModelsRead.Loaded>(read).models)
    assertEquals(listOf("initialize", "session/new"), connection.sent.map { it.string("method") })
    assertTrue(connection.closed)
  }

  @Test
  fun `OpenCode selection waits for refreshed model effort choices without prompting`() = runTest {
    val connection = ModelConnection(listOf(
      """{"id":1,"result":{"protocolVersion":1}}""",
      """{"id":2,"result":{"sessionId":"temporary","configOptions":[{"id":"model","currentValue":"model-a","options":[{"value":"model-a","name":"Model A"},{"value":"model-b","name":"Model B"}]}]}}""",
      """{"id":3,"result":{"configOptions":[{"id":"model","currentValue":"model-b","options":[{"value":"model-a","name":"Model A"},{"value":"model-b","name":"Model B"}]},{"id":"effort","options":[{"value":"high","name":"High"},{"value":"low","name":"Low"}]}]}}""",
    ))
    val host = HostEnvironment(mapOf("OPENCODE_CONFIG_DIR" to "/profiles/open-code"), "/home/test", "/repo")
    val catalog = ChatModelCatalog(host, { BoundedProcessStart.Started(connection) },
      { flowOf(ChatProcessOutput.Exited(1, "")) }, UsageProfileResolver(host), FakeAppSettingsRepository(), RecordingDiagnostics())

    val result = assertIs<AdvertisedAgentChoices.Choices>(catalog.openCodeChoices("/repo", "model-b"))

    assertEquals("/profiles/open-code", result.binding.profile)
    assertEquals("model-b", result.selectedModel.id)
    assertEquals(listOf("high", "low"), assertIs<AdvertisedEffortSupport.Offered>(result.effort).values.map { it.id })
    assertEquals(listOf("initialize", "session/new", "session/set_config_option"), connection.sent.map { it.string("method") })
    assertEquals("model", connection.sent.last().obj("params")?.string("configId"))
    assertEquals("model-b", connection.sent.last().obj("params")?.string("value"))
    assertTrue(connection.closed)
  }

  @Test
  fun `OpenCode does not invent a selected model from malformed setup or acknowledgement`() = runTest {
    val models = """[{"value":"model-a","name":"Model A"}]"""
    val setups = listOf(
      """{"sessionId":"temporary","configOptions":[{"id":"model","options":$models}]}""",
      """{"sessionId":"temporary","configOptions":[{"id":"model","currentValue":"missing","options":$models}]}""",
      """{"configOptions":[{"id":"model","currentValue":"model-a","options":$models}]}""",
    )
    for (setup in setups) {
      val connection = ModelConnection(listOf("""{"id":1,"result":{"protocolVersion":1}}""", """{"id":2,"result":$setup}"""))
      assertEquals(
        AdvertisedAgentChoices.Failed(AdvertisedAgentChoices.Failed.Reason.PROTOCOL),
        catalog(connection).openCodeChoices("/repo", null),
      )
      assertTrue(connection.closed)
    }
    val connection = ModelConnection(listOf(
      """{"id":1,"result":{"protocolVersion":1}}""",
      """{"id":2,"result":{"sessionId":"temporary","configOptions":[{"id":"model","currentValue":"model-a","options":$models}]}}""",
      """{"id":3,"result":{"configOptions":[{"id":"model","currentValue":"model-a","options":[]}]}}""",
    ))
    assertEquals(
      AdvertisedAgentChoices.Failed(AdvertisedAgentChoices.Failed.Reason.PROTOCOL),
      catalog(connection).openCodeChoices("/repo", "model-a"),
    )
    assertTrue(connection.closed)
  }

  @Test
  fun `OpenCode acknowledges an unchanged explicit model and returns refreshed no-effort support`() = runTest {
    val options = """[{"id":"model","currentValue":"model-a","options":[{"value":"model-a","name":"Model A"}]}]"""
    val connection = ModelConnection(listOf(
      """{"id":1,"result":{"protocolVersion":1}}""",
      """{"id":2,"result":{"sessionId":"temporary","configOptions":$options}}""",
      """{"id":3,"result":{"configOptions":$options}}""",
    ))

    val result = assertIs<AdvertisedAgentChoices.Choices>(catalog(connection).openCodeChoices("/repo", "model-a"))

    assertEquals(AdvertisedEffortSupport.Unsupported, result.effort)
    assertEquals(listOf("initialize", "session/new", "session/set_config_option"), connection.sent.map { it.string("method") })
    assertTrue(connection.closed)
  }

  private fun catalog(connection: ModelConnection, diagnostics: RecordingDiagnostics = RecordingDiagnostics(), onStart: (ChatCommand) -> Unit = {}): ChatModelCatalog {
    val host = HostEnvironment(emptyMap(), "/home/test", "")
    return ChatModelCatalog(host, { command -> onStart(command); BoundedProcessStart.Started(connection) },
      { flowOf(ChatProcessOutput.Exited(1, "")) }, UsageProfileResolver(host), FakeAppSettingsRepository(), diagnostics)
  }

  private class ModelConnection(lines: List<String>, private val cancel: Boolean = false) : BoundedProtocolConnection {
    private val remaining = ArrayDeque(lines)
    val sent = mutableListOf<JsonObject>()
    var closed = false
    override suspend fun sendLine(line: String): Boolean { sent += checkNotNull(parseStreamLine(line)); return true }
    override suspend fun receive(): BoundedProtocolInput {
      if (cancel) throw CancellationException("picker dismissed")
      return remaining.removeFirstOrNull()?.let(BoundedProtocolInput::Line) ?: BoundedProtocolInput.Exited
    }
    override fun beginResponseBudget() = Unit
    override fun endResponseBudget() = Unit
    override suspend fun close() { closed = true }
  }
}
