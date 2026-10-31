package skillbill.engine.goalrunner.execution.core

import skillbill.application.agentoutput.agentFailureExcerpt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalRunnerStderrExcerptTest {
  @Test
  fun `agentFailureExcerpt skips the Codex stdin status banner`() {
    val excerpt =
      agentFailureExcerpt(
        stderr = "",
        stdout = "Reading prompt from stdin...\nerror: model refused the request\n",
        maxChars = 500,
      )

    assertEquals("error: model refused the request", excerpt)
  }

  @Test
  fun `agentFailureExcerpt prefers stderr over stdout`() {
    val excerpt =
      agentFailureExcerpt(
        stderr = "spawn failure: binary missing\n",
        stdout = "Reading prompt from stdin...\n",
        maxChars = 500,
      )

    assertEquals("spawn failure: binary missing", excerpt)
  }

  @Test
  fun `agentFailureExcerpt returns null when both streams are blank`() {
    assertNull(agentFailureExcerpt(stderr = "  ", stdout = "", maxChars = 500))
  }
}
