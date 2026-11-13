package skillbill.install.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDirectiveCapabilityTest {
  @Test
  fun `opencode joins the model-directive capable agents`() {
    assertEquals(
      setOf(
        SupportedAgent.CLAUDE,
        SupportedAgent.CODEX,
        SupportedAgent.CURSOR,
        SupportedAgent.OPENCODE,
        SupportedAgent.DEVIN,
      ),
      MODEL_DIRECTIVE_CAPABLE_AGENTS,
    )
  }

  @Test
  fun `supportsModelDirective accepts cursor and rejects junie`() {
    assertTrue(supportsModelDirective("cursor"))
    assertTrue(supportsModelDirective("CURSOR"))
    assertTrue(supportsModelDirective("claude"))
    assertTrue(supportsModelDirective("codex"))
    assertTrue(supportsModelDirective("opencode"))
    assertTrue(supportsModelDirective("OPENCODE"))
    assertFalse(supportsModelDirective("junie"))
    assertFalse(supportsModelDirective("copilot"))
    assertFalse(supportsModelDirective(null))
    assertFalse(supportsModelDirective(" "))
  }
}
