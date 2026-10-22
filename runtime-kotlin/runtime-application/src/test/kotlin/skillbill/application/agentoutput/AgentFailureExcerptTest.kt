package skillbill.application.agentoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentFailureExcerptTest {
  @Test
  fun `stdin banner on stderr does not hide provider failure on stdout`() {
    assertEquals(
      "Selected model is at capacity.",
      agentFailureExcerpt(
        stderr = "Reading prompt from stdin...\n",
        stdout = "Selected model is at capacity.\n",
        maxChars = 500,
      ),
    )
  }

  @Test
  fun `a launch that only reports the stdin banner has no failure excerpt`() {
    assertNull(
      agentFailureExcerpt(
        stderr = "Reading prompt from stdin...\n",
        stdout = "  ",
        maxChars = 500,
      ),
    )
  }
}
