package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatSteeringPolicy
import dev.skillbill.desktop.core.domain.model.ChatSteeringRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatSteeringUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.first
import kotlin.test.assertTrue
import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatStopSettlement
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.repository.ConfigRead
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class AgentExecutionChatRepositoryTest {
  @Test
  fun `repository routes retained deliveries by session and retirement removes only the consumed identity`() = runTest {
    val retained = MutableStateFlow<Map<Pair<String, String>, ChatSteeringUpdate>>(emptyMap())
    var ended = false
    val claude = object : ChatTransport {
      override val agentId = "claude"
      override val steeringPolicy = ChatSteeringPolicy.LiveWhenAvailable
      override fun run(request: ChatTurnRequest) = flowOf<ChatEvent>(ChatEvent.Completed)
      override suspend fun steer(request: ChatSteeringRequest): ChatSteeringResult {
        val result = ChatSteeringResult.Accepted(request.deliveryId)
        retained.value = retained.value + ((request.sessionId to request.deliveryId) to ChatSteeringUpdate(request.sessionId, result))
        return result
      }
      override fun steeringUpdates(sessionId: String): Flow<ChatSteeringUpdate> = retained.transform { snapshot ->
        snapshot.values.filter { it.sessionId == sessionId }.forEach { emit(it) }
      }
      override fun retireSteering(sessionId: String, deliveryId: String) {
        retained.value = retained.value - (sessionId to deliveryId)
      }
      override fun end(sessionId: String) { ended = true }
    }
    val diagnostics = RecordingDiagnostics()
    val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")
    val store = ChatStore(host, diagnostics, Clock.System)
    val settings = FakeAppSettingsRepository()
    val config = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RuntimeConfigRepository::class.java)) { _, _, _ -> error("unused") } as RuntimeConfigRepository
    val summarizer = ChatSummarizer(host, settings, { error("unused") }, store, diagnostics, ApplicationCoroutineScope(backgroundScope))
    val catalog = AgentSessionCatalog(host, settings, store, diagnostics)
    val modelCatalog = ChatModelCatalog(
      host, { error("unused") }, { error("unused") }, dev.skillbill.desktop.core.data.usage.UsageProfileResolver(host), settings, diagnostics,
    )
    val repository = AgentExecutionChatRepository(setOf(claude), config, store, catalog, diagnostics, summarizer, modelCatalog)
    assertEquals(ChatSteeringPolicy.LiveWhenAvailable, repository.steeringPolicy("claude"))
    for ((session, id) in listOf("one" to "a", "one" to "b", "two" to "c")) {
      repository.steer(ChatSteeringRequest(session, "claude", id, "guidance"))
    }
    assertEquals(listOf("a", "b"), repository.steeringUpdates("claude", "one").take(2).toList().map { it.result.deliveryId })
    repository.retireSteering("claude", "one", "a")
    assertEquals("b", repository.steeringUpdates("claude", "one").first().result.deliveryId)
    assertEquals("c", repository.steeringUpdates("claude", "two").first().result.deliveryId)
    assertEquals(ChatSteeringResult.Unsupported("missing", "Live delivery is unavailable"), repository.steer(ChatSteeringRequest("one", "absent", "missing", "guidance")))
    assertTrue("chat.steering_unsupported" in diagnostics.categories)
    assertEquals(false, ended)
  }

  // A registered provider must appear sorted in agents() and route its own turn, not just be listed.
  @Test
  fun `registered providers expose sorted identities and route their own turns`() = runTest {
    val runs = mutableListOf<String>()
    fun transport(id: String) = object : ChatTransport {
      override val agentId = id
      override fun run(request: ChatTurnRequest): Flow<ChatEvent> {
        runs += request.agent.agentId
        return flowOf(ChatEvent.Completed)
      }
    }
    val diagnostics = RecordingDiagnostics()
    val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")
    val store = ChatStore(host, diagnostics, Clock.System)
    val settings = FakeAppSettingsRepository()
    val unusedConfig = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RuntimeConfigRepository::class.java)) { _, method, _ ->
      if (method.name == "machineConfig") ConfigRead.Failed(ReadProblem.Runtime("unused"))
      else error("unused")
    } as RuntimeConfigRepository
    val summarizer = ChatSummarizer(host, settings, { error("unused") }, store, diagnostics, ApplicationCoroutineScope(backgroundScope))
    val catalog = AgentSessionCatalog(host, settings, store, diagnostics)
    val repository = AgentExecutionChatRepository(
      setOf(transport("claude"), transport("devin"), transport("opencode")),
      unusedConfig, store, catalog, diagnostics, summarizer,
      ChatModelCatalog(host, { error("unused") }, { error("unused") },
        dev.skillbill.desktop.core.data.usage.UsageProfileResolver(host), settings, diagnostics),
    )

    assertEquals(listOf("claude", "devin", "opencode"), repository.agents())
    assertTrue(repository.supports("devin") && repository.supports("opencode"))
    assertEquals(ChatAgent("devin", null, null), repository.agentFor("devin"))
    assertEquals(listOf<ChatEvent>(ChatEvent.Completed),
      repository.send(chatRequest(agent = ChatAgent("opencode", null, null))).toList())
    assertEquals(listOf("opencode"), runs)
    assertEquals(ChatStopSettlement.AlreadyFinished, repository.stopTurn("session-1"))
  }

  // An unsupported agent must not silently fall through to the Claude process.
  @Test
  fun `an agent without a transport fails the turn without running one`() = runTest {
    var runs = 0
    val claude = object : ChatTransport {
      override val agentId = "claude"

      override fun run(request: ChatTurnRequest): Flow<ChatEvent> {
        runs++
        return flowOf(ChatEvent.Completed)
      }
    }
    val unusedConfig = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RuntimeConfigRepository::class.java)) { _, _, _ ->
      error("unused")
    } as RuntimeConfigRepository
    val diagnostics = RecordingDiagnostics()
    val repository = run {
      val host = HostEnvironment(emptyMap(), createTempDirectory("chat-home").toString(), "")
      val store = ChatStore(host, diagnostics, Clock.System)
      val settings = FakeAppSettingsRepository()
      val summarizer = ChatSummarizer(host, settings, { error("unused") }, store, diagnostics, ApplicationCoroutineScope(backgroundScope))
      val catalog = AgentSessionCatalog(host, settings, store, diagnostics)
      AgentExecutionChatRepository(setOf(claude), unusedConfig, store, catalog, diagnostics, summarizer,
        ChatModelCatalog(host, { error("unused") }, { error("unused") },
          dev.skillbill.desktop.core.data.usage.UsageProfileResolver(host), settings, diagnostics))
    }

    val events = repository.send(chatRequest(agent = ChatAgent("codex", null, null))).toList()

    assertEquals(listOf<ChatEvent>(ChatEvent.Failed(ChatFailure.AgentUnsupported, "codex")), events)
    assertEquals(0, runs)
    assertEquals(listOf("chat.agent_unsupported"), diagnostics.categories)
  }
}
