package skillbill.agent.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentPhaseModelsTest {
  @Test
  fun `phase models keep string handoff separate from typed launch plumbing`() {
    val input =
      AgentPhaseInput(
        input = "review prose",
        requestedAction = "verify claims",
      )
    val phaseOutput = PhaseOutput(value = "preplan prose", prompt = "optional directive")

    assertEquals("review prose", input.input)
    assertEquals("verify claims", input.requestedAction)
    assertEquals("preplan prose", phaseOutput.value)
    assertEquals("optional directive", phaseOutput.prompt)
    assertEquals(String::class.java, AgentPhaseInput::class.java.getDeclaredField("input").type)
    assertEquals(String::class.java, AgentPhaseInput::class.java.getDeclaredField("requestedAction").type)
    assertEquals(String::class.java, PhaseOutput::class.java.getDeclaredField("value").type)
    assertFalse(
      (
        AgentPhaseInput::class.java.declaredFields +
          PhaseOutput::class.java.declaredFields
      )
        .any { it.name in setOf("repoRoot", "agentId", "evidenceBroker", "budget", "budgets") },
    )
  }
}
