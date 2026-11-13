package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.usage.UsageProfileResolver
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.model.ChatModelsRead
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
  fun `devin lists exact variant uids from its finite model command without a prompt`() = runTest {
    val json = """{"families":[{"slug":"swe-2","family_uid":"fam-swe","variants":[{"model_uid":"swe-2-high","label":"SWE-2 High"}]}]}"""
    var command: ChatCommand? = null
    val read = catalog(ModelConnection(emptyList()), devin = { command = it; flowOf(ChatProcessOutput.Line(json), ChatProcessOutput.Exited(0, "")) })
      .read(ChatAgent("devin", null, null), "/repo")
    assertEquals(listOf(ChatModel("swe-2-high", "SWE-2 High")), assertIs<ChatModelsRead.Loaded>(read).models)
    val launched = checkNotNull(command)
    assertEquals(listOf("devin", "models", "list", "--format", "json"), launched.argv)
    assertEquals("", launched.stdin)
    assertEquals("/repo", launched.workingDirectory)
    assertTrue(launched.stdoutLimitBytes != null)
  }

  @Test
  fun `devin discovery failures stay visible instead of inventing models`() = runTest {
    val valid = """{"families":[{"variants":[{"model_uid":"swe-2-high","label":"SWE-2 High"}]}]}"""
    val cases = listOf(
      listOf(ChatProcessOutput.Line("not json"), ChatProcessOutput.Exited(0, "")),
      listOf(ChatProcessOutput.Line("""{"families":[]}"""), ChatProcessOutput.Exited(0, "")),
      listOf(ChatProcessOutput.Line("""{"families":[{"variants":[{"label":"No uid"}]}]}"""), ChatProcessOutput.Exited(0, "")),
      listOf(ChatProcessOutput.Line(valid), ChatProcessOutput.Exited(1, "not authenticated")),
      listOf(ChatProcessOutput.StdoutEnded("stdout exceeded limit", ""), ChatProcessOutput.Exited(0, "")),
      listOf(ChatProcessOutput.StartFailed("missing")),
    )
    for (events in cases) {
      val diagnostics = RecordingDiagnostics()
      val read = catalog(ModelConnection(emptyList()), diagnostics, devin = { flowOf(*events.toTypedArray()) })
        .read(ChatAgent("devin", null, null), "/repo")
      assertIs<ChatModelsRead.Failed>(read, events.toString())
      assertEquals(listOf("chat.model_discovery_failed"), diagnostics.categories)
    }
  }

  @Test
  fun `cancelling devin discovery propagates cancellation`() = runTest {
    val models = catalog(ModelConnection(emptyList()), devin = { flow { throw CancellationException("picker dismissed") } })
    assertFailsWith<CancellationException> { models.read(ChatAgent("devin", null, null), "/repo") }
  }

  private fun catalog(
    connection: ModelConnection,
    diagnostics: RecordingDiagnostics = RecordingDiagnostics(),
    onStart: (ChatCommand) -> Unit = {},
    devin: (ChatCommand) -> kotlinx.coroutines.flow.Flow<ChatProcessOutput> = { flowOf(ChatProcessOutput.Exited(1, "")) },
  ): ChatModelCatalog {
    val host = HostEnvironment(emptyMap(), "/home/test", "")
    return ChatModelCatalog(host, { command -> onStart(command); BoundedProcessStart.Started(connection) },
      { devin(it) }, UsageProfileResolver(host), FakeAppSettingsRepository(), diagnostics)
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
