package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.usage.UsageProfileResolver
import dev.skillbill.desktop.core.domain.model.AdvertisedAgentChoices
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.model.ChatModelsRead
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.io.path.createTempDirectory
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
  fun `unsupported agent model lookup returns empty without launching either process path`() = runTest {
    val connection = ModelConnection(emptyList())
    val diagnostics = RecordingDiagnostics()
    var processStarted = false
    val read = catalog(connection, diagnostics, onStart = { processStarted = true }, processes = {
      processStarted = true
      flowOf(ChatProcessOutput.Exited(0, ""))
    }).read(ChatAgent("retired-agent", null, null), "/repo")

    assertEquals(ChatModelsRead.Loaded(emptyList()), read)
    assertEquals(false, processStarted)
    assertEquals(emptyList(), diagnostics.categories)
    assertEquals(emptyList(), connection.sent)
    assertEquals(false, connection.closed)
  }

  @Test
  fun `devin lists ordered variant UIDs with label fallback and exact deduplication`() = runTest {
    val json = """{"families":[
      {"family_uid":"swe-2","slug":"swe-2","variants":[
        {"model_uid":"swe-2-high","label":"SWE-2 High"},
        {"model_uid":"swe-2-medium-lightning","label":""},
        {"model_uid":"swe-2-high","label":"Duplicate"}
      ]},
      {"family_uid":"adaptive","variants":[{"model_uid":"adaptive"}]}
    ]}"""
    var command: ChatCommand? = null
    val read = catalog(ModelConnection(emptyList()), processes = {
      command = it
      flowOf(
        ChatProcessOutput.Line(json),
        ChatProcessOutput.StdoutEnded(null, ""),
        ChatProcessOutput.Exited(0, ""),
      )
    }).read(ChatAgent("devin", null, null), "/repo")
    assertEquals(
      listOf(
        ChatModel("swe-2-high", "SWE-2 High"),
        ChatModel("swe-2-medium-lightning", "swe-2-medium-lightning"),
        ChatModel("adaptive", "adaptive"),
      ),
      assertIs<ChatModelsRead.Loaded>(read).models,
    )
    assertEquals(listOf("models", "list", "--format", "json"), command?.argv?.takeLast(4))
    assertEquals("/home/test", command?.environment?.get("HOME"))
    assertEquals("/repo", command?.workingDirectory)
  }

  @Test
  fun `a malformed devin catalog fails the read instead of skipping bad variants`() = runTest {
    val read = catalog(ModelConnection(emptyList()), processes = {
      flowOf(
        ChatProcessOutput.Line("""{"families":[{"variants":[{"model_uid":7,"label":"ok"}]}]}"""),
        ChatProcessOutput.StdoutEnded(null, ""),
        ChatProcessOutput.Exited(0, ""),
      )
    }).read(ChatAgent("devin", null, null), "/repo")
    assertIs<ChatModelsRead.Failed>(read)
  }

  @Test
  fun `an empty devin catalog and a nonzero exit surface as failed reads`() = runTest {
    val empty = catalog(ModelConnection(emptyList()), processes = {
      flowOf(
        ChatProcessOutput.Line("""{"families":[]}"""),
        ChatProcessOutput.StdoutEnded(null, ""),
        ChatProcessOutput.Exited(0, ""),
      )
    }).read(ChatAgent("devin", null, null), "/repo")
    assertIs<ChatModelsRead.Failed>(empty)
    val failed = catalog(ModelConnection(emptyList()), processes = {
      flowOf(ChatProcessOutput.Exited(1, "not signed in"))
    }).read(ChatAgent("devin", null, null), "/repo")
    val failure = assertIs<ChatModelsRead.Failed>(failed)
    assertTrue("devin auth login" in failure.message)
  }

  @Test
  fun `a devin profile refusal launches no process`() = runTest {
    var started = false
    val read = catalog(ModelConnection(emptyList()), processes = {
      started = true
      flowOf()
    }).read(ChatAgent("devin", null, null, "/profiles/d"), "/repo")
    val failure = assertIs<ChatModelsRead.Failed>(read)
    assertTrue("default CLI environment" in failure.message)
    assertEquals(false, started)
  }

  @Test
  fun `oversized devin output stops capture and fails visibly`() = runTest {
    var released = false
    val read = catalog(ModelConnection(emptyList()), processes = {
      flow<ChatProcessOutput> {
        emit(ChatProcessOutput.Line("x".repeat(1_000_001)))
        try {
          awaitCancellation()
        } finally {
          released = true
        }
      }
    }).read(ChatAgent("devin", null, null), "/repo")
    assertIs<ChatModelsRead.Failed>(read)
    assertTrue(released)
  }

  @Test
  fun `cancelling devin discovery releases the process stream`() = runTest {
    var released = false
    val job = launch {
      runCatching {
        catalog(ModelConnection(emptyList()), processes = {
          flow<ChatProcessOutput> {
            emit(ChatProcessOutput.Line("{\"families\""))
            try {
              awaitCancellation()
            } finally {
              released = true
            }
          }
        }).read(ChatAgent("devin", null, null), "/repo")
      }
    }
    job.cancelAndJoin()
    assertTrue(released)
  }

  @Test
  fun `opencode discovery flattens advertised models under the selected profile and checkout without a prompt`() = runTest {
    val root = createTempDirectory("oc-root").toString()
    val connection = ModelConnection(listOf(
      """{"id":1,"result":{"protocolVersion":1}}""",
      """{"id":2,"result":{"sessionId":"s1","configOptions":[{"id":"model","currentValue":"open-a","options":[{"value":"open-a","name":"Open A"},{"value":"group","options":[{"value":"open-b","name":"Open B"}]}]}]}}""",
    ))
    var command: ChatCommand? = null
    val read = catalog(connection, onStart = { command = it })
      .read(ChatAgent("opencode", null, null, "/profiles/oc"), root)
    assertEquals(
      listOf(ChatModel("open-a", "Open A"), ChatModel("open-b", "Open B")),
      assertIs<ChatModelsRead.Loaded>(read).models,
    )
    assertEquals("/profiles/oc", command?.environment?.get("OPENCODE_CONFIG_DIR"))
    assertEquals(java.nio.file.Path.of(root).toRealPath().toString(), command?.workingDirectory)
    assertEquals(listOf("initialize", "session/new"), connection.sent.map { it.string("method") })
    assertTrue(connection.closed)
  }

  @Test
  fun `opencode choices without a selected profile keep host defaults`() = runTest {
    val root = createTempDirectory("oc-root").toString()
    val connection = ModelConnection(listOf(
      """{"id":1,"result":{"protocolVersion":1}}""",
      """{"id":2,"result":{"sessionId":"s1","configOptions":[{"id":"model","currentValue":"open-a","options":[{"value":"open-a","name":"Open A"}]}]}}""",
    ))
    var command: ChatCommand? = null
    val choices = catalog(connection, onStart = { command = it }).openCodeChoices(root, null)
    val selected = assertIs<AdvertisedAgentChoices.Choices>(choices)
    assertEquals(null, selected.binding.profile)
    assertEquals(root, selected.binding.workingDirectory)
    assertEquals(null, command?.environment?.get("OPENCODE_CONFIG_DIR"))
  }

  @Test
  fun `opencode authentication refusal and an unadvertised selection stay failed and close the connection`() = runTest {
    val root = createTempDirectory("oc-root").toString()
    val auth = catalog(ModelConnection(listOf(
      """{"id":1,"error":{"code":-32000,"message":"auth required"}}""",
    )))
    val authRead = auth.read(ChatAgent("opencode", null, null), root)
    assertTrue("opencode auth login" in assertIs<ChatModelsRead.Failed>(authRead).message)

    val connection = ModelConnection(listOf(
      """{"id":1,"result":{"protocolVersion":1}}""",
      """{"id":2,"result":{"sessionId":"s1","configOptions":[{"id":"model","currentValue":"open-a","options":[{"value":"open-a","name":"Open A"}]}]}}""",
    ))
    val read = catalog(connection).read(ChatAgent("opencode", "missing-model", null), root)
    val failure = assertIs<ChatModelsRead.Failed>(read)
    assertTrue("missing-model" in failure.message)
    assertTrue(connection.closed)
  }

  private fun catalog(
    connection: ModelConnection,
    diagnostics: RecordingDiagnostics = RecordingDiagnostics(),
    onStart: (ChatCommand) -> Unit = {},
    processes: (ChatCommand) -> kotlinx.coroutines.flow.Flow<ChatProcessOutput> = { flowOf(ChatProcessOutput.Exited(1, "")) },
  ): ChatModelCatalog {
    val host = HostEnvironment(emptyMap(), "/home/test", "")
    return ChatModelCatalog(host, { command -> onStart(command); BoundedProcessStart.Started(connection) },
      { processes(it) }, UsageProfileResolver(host), FakeAppSettingsRepository(), diagnostics)
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
