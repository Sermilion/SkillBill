package dev.skillbill.desktop.app

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import kotlinx.coroutines.cancel
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ChatAgentWiringTest {
  @Test
  fun `production bindings expose OpenCode in the chat agent selector`() {
    val home = createTempDirectory("chat-agent-graph")
    val component = ChatAgentTestComponent::class.create(HostEnvironment(emptyMap(), home.toString(), ""), DiagnosticsSwitch(false))
    try {
      assertContains(component.chat.agents(), "opencode")
      assertTrue(component.chat.supports("opencode"))
    } finally {
      component.applicationCoroutineScope.scope.cancel()
      home.toFile().deleteRecursively()
    }
  }
}

@MergeComponent(AppScope::class)
@SingleIn(AppScope::class)
abstract class ChatAgentTestComponent(
  @get:Provides val host: HostEnvironment,
  @get:Provides val diagnosticsSwitch: DiagnosticsSwitch,
) {
  abstract val chat: ExecutionChatRepository
  abstract val applicationCoroutineScope: ApplicationCoroutineScope
}
