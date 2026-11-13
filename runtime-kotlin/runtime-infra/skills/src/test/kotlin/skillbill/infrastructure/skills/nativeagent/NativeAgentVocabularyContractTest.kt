package skillbill.infrastructure.skills.nativeagent

import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.SupportedAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeAgentVocabularyContractTest {
  @Test
  fun `domain declaration round trips every native provider without a second id vocabulary`() {
    val agents = SupportedAgent.entries

    assertEquals(listOf("claude", "codex", "cursor"), agents.map(SupportedAgent::wireValue))
    agents.forEach { agent ->
      assertEquals(agent, SupportedAgent.fromWire(agent.wireValue.uppercase()))
      assertEquals(agent, NativeAgentProvider.forSupportedAgent(agent).supportedAgent)
    }
    assertFailsWith<IllegalArgumentException> { SupportedAgent.fromWire("unsupported") }
  }
}
