package skillbill.cli

import com.github.ajalt.clikt.core.UsageError
import skillbill.cli.kernel.agent.SKILL_BILL_AGENT_ENV
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.cli.kernel.agent.requireSupportedOptionalAgentId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InvokingAgentResolutionTest {
  @Test
  fun `unknown explicit agent is refused with the supported list`() {
    val error =
      assertFailsWith<UsageError> {
        requireInvokingAgentId("claude-code", emptyMap(), "--agent")
      }

    assertContains(error.message.orEmpty(), "Unknown agent 'claude-code'")
    assertContains(error.message.orEmpty(), "claude, codex, cursor")
    assertContains(error.message.orEmpty(), "--agent")
  }

  @Test
  fun `unknown agent from the environment names the environment variable`() {
    val error =
      assertFailsWith<UsageError> {
        requireInvokingAgentId(null, mapOf(SKILL_BILL_AGENT_ENV to "claude-code"), "--agent")
      }

    assertContains(error.message.orEmpty(), SKILL_BILL_AGENT_ENV)
  }

  @Test
  fun `supported agent resolves`() {
    assertEquals("claude", requireInvokingAgentId("claude", emptyMap(), "--agent"))
  }

  @Test
  fun `supported agent resolves regardless of casing or padding`() {
    assertEquals("codex", requireInvokingAgentId("  Codex ", emptyMap(), "--agent"))
  }

  @Test
  fun `detected execution context still resolves`() {
    assertEquals("claude", requireInvokingAgentId(null, mapOf("CLAUDECODE" to "1"), "--agent"))
  }

  @Test
  fun `unknown agent override is refused`() {
    val error =
      assertFailsWith<UsageError> {
        requireSupportedOptionalAgentId("claude-code", "--agent-override")
      }

    assertContains(error.message.orEmpty(), "--agent-override")
  }

  @Test
  fun `absent agent override is allowed`() {
    assertNull(requireSupportedOptionalAgentId(null, "--agent-override"))
  }
}
