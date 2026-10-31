package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
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
      AgentExecutionChatRepository(setOf(claude), unusedConfig, store, AgentSessionCatalog(host, FakeAppSettingsRepository(), store, diagnostics), diagnostics)
    }

    val events = repository.send(chatRequest(agent = ChatAgent("codex", null, null))).toList()

    assertEquals(listOf<ChatEvent>(ChatEvent.Failed(ChatFailure.AgentUnsupported, "codex")), events)
    assertEquals(0, runs)
    assertEquals(listOf("chat.agent_unsupported"), diagnostics.categories)
  }
}
