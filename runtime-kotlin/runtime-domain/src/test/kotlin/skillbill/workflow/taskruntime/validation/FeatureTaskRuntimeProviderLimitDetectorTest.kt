package skillbill.workflow.taskruntime.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeProviderLimitDetectorTest {
  @Test
  fun `recognizes Codex usage_limit_exceeded without inventing a reset time`() {
    val signal = requireNotNull(FeatureTaskRuntimeProviderLimitDetector.detect("usage_limit_exceeded"))

    assertEquals("usage_limit_exceeded", signal.evidence)
    assertNull(signal.resetHint)
  }

  @Test
  fun `extracts try again at reset hint from later lines in the inspected tail`() {
    val stderr =
      "usage_limit_exceeded\n" +
        "You've hit your usage limit. Please try again at Nov 5th, 2026 2:05 AM"
    val signal = requireNotNull(FeatureTaskRuntimeProviderLimitDetector.detect(stderr))

    assertEquals("usage_limit_exceeded", signal.evidence)
    assertEquals("Nov 5th, 2026 2:05 AM", signal.resetHint)
  }

  @Test
  fun `a reset at the end of an earlier line does not steal a later try-again timestamp`() {
    val stderr =
      "please reset\n" +
        "usage_limit_exceeded\n" +
        "You've hit your usage limit. Please try again at Nov 5th, 2026 2:05 AM"
    val signal = requireNotNull(FeatureTaskRuntimeProviderLimitDetector.detect(stderr))

    assertEquals("usage_limit_exceeded", signal.evidence)
    assertEquals("Nov 5th, 2026 2:05 AM", signal.resetHint)
  }

  @Test
  fun `recognizes a session-limit refusal and keeps the provider's own reset statement`() {
    val signal =
      requireNotNull(
        FeatureTaskRuntimeProviderLimitDetector.detect(
          "You've hit your session limit · resets 3:40am (Europe/Berlin)",
        ),
      )

    assertEquals("You've hit your session limit · resets 3:40am (Europe/Berlin)", signal.evidence)
    assertEquals("3:40am (Europe/Berlin)", signal.resetHint)
  }

  @Test
  fun `recognizes a rate-limit refusal that states no reset time`() {
    val signal = requireNotNull(FeatureTaskRuntimeProviderLimitDetector.detect("API error: rate_limit_error"))

    assertNull(signal.resetHint, "the runtime must not invent a reset time the provider did not state")
  }

  @Test
  fun `inspects stderr before stdout`() {
    val signal =
      requireNotNull(
        FeatureTaskRuntimeProviderLimitDetector.detect(
          "Error: quota exceeded for this organization",
          "You've hit your usage limit",
        ),
      )

    assertEquals("Error: quota exceeded for this organization", signal.evidence)
  }

  @Test
  fun `an ordinary failure is not a provider limit`() {
    assertNull(
      FeatureTaskRuntimeProviderLimitDetector.detect(
        "e: RateLimiter.kt:429:11 unresolved reference: limit\nCompilation error",
      ),
    )
  }

  @Test
  fun `a limit phrase far above the failure tail does not classify the exit`() {
    val output =
      "You've hit your session limit\n" +
        "x".repeat(FeatureTaskRuntimeProviderLimitDetector.INSPECTED_TAIL_CHARS) +
        "\nError: the child process crashed"

    assertNull(
      FeatureTaskRuntimeProviderLimitDetector.detect(output),
      "only the tail of a failed launch states why it failed; earlier transcript text does not",
    )
  }

  @Test
  fun `evidence stays bounded`() {
    val signal =
      requireNotNull(
        FeatureTaskRuntimeProviderLimitDetector.detect("y".repeat(500) + " You've hit your session limit"),
      )

    assertTrue(signal.evidence.length <= 200, "evidence was ${signal.evidence.length} chars")
  }
}
