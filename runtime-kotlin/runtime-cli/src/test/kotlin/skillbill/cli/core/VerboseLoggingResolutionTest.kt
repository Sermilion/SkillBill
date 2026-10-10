package skillbill.cli.core

import skillbill.di.core.ProcessLoggingEnvironmentKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerboseLoggingResolutionTest {
  @Test
  fun `verbose logging is requested by the flag or the env var and otherwise not`() {
    val hostPath = mapOf("PATH" to "/usr/bin")
    assertTrue(resolveVerboseLogging(listOf("--verbose", "APP-1"), hostPath))
    assertTrue(
      resolveVerboseLogging(
        listOf("APP-1"),
        mapOf(ProcessLoggingEnvironmentKeys.SKILL_BILL_VERBOSE to "TRUE"),
      ),
    )
    assertFalse(resolveVerboseLogging(listOf("APP-1"), hostPath))
  }

  @Test
  fun `routeIntake keeps verbose as a root option when inserting goal`() {
    assertEquals(
      listOf("--verbose", "goal", "APP-1", "Add CSV export"),
      routeIntakeTokens(
        listOf("--verbose", "APP-1", "Add CSV export"),
        isCommand = { token -> token in setOf("goal", "phase") },
      ),
    )
  }
}
