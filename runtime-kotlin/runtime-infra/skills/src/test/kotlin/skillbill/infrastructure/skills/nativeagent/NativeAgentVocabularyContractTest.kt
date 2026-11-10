package skillbill.infrastructure.skills.nativeagent

import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.SupportedAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NativeAgentVocabularyContractTest {
  @Test
  fun `domain declaration round trips every native provider without a second id vocabulary`() {
    val agents = NativeAgentProvider.entries.map(NativeAgentProvider::supportedAgent)

    assertEquals(listOf("claude", "codex", "junie", "cursor"), agents.map(SupportedAgent::wireValue))
    agents.forEach { agent ->
      assertEquals(agent, SupportedAgent.fromWire(agent.wireValue.uppercase()))
      assertEquals(agent, NativeAgentProvider.forSupportedAgent(agent).supportedAgent)
    }
    assertFalse(agents.contains(SupportedAgent.OPENCODE))
    assertFailsWith<NoSuchElementException> { NativeAgentProvider.forSupportedAgent(SupportedAgent.OPENCODE) }
    assertFailsWith<IllegalArgumentException> { SupportedAgent.fromWire("unsupported") }
  }
}
