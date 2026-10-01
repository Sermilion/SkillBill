package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class InlineFqnArchitectureTest {
  @Test
  fun `production and test Kotlin avoid inline fully-qualified references outside the keep-list`() {
    val violations =
      ArchitectureScanSupport.inlineFqnViolations(
        scanRoots = PrincipleEnforcementInventory.inlineFqnScanRoots,
        prefixes = PrincipleEnforcementInventory.inlineFqnPrefixes,
      )
    assertEquals(
      emptyList(),
      violations,
      "Inline fully-qualified references such as java.time.Instant.now() must be replaced with imports or documented" +
        "aliases.",
    )
  }

  @Test
  fun `inline FQN scanner fires on synthetic java time fixture and ignores keep-list controls`() {
    val violatingFixture =
      """

      class Clock {
        fun now() = java.time.Instant.now()
      }
      """.trimIndent()
    assertEquals(
      listOf("java.time.Instant.now"),
      ArchitectureScanSupport.inlineFqnReferences(violatingFixture, PrincipleEnforcementInventory.inlineFqnPrefixes),
      "Regression if inline java.time.Instant.now() without an import stops failing the general inline-FQN guard.",
    )

    val keepListFixture =
      """

      import java.time.Instant

      interface Clean {
        /**
         * Doc mentions java.time.Instant.now() in KDoc.
         */
        fun now(): Instant
        val hint: String
          get() = "java.time.Instant.now()"
      }
      """.trimIndent()
    assertEquals(
      emptyList(),
      ArchitectureScanSupport.inlineFqnReferences(keepListFixture, PrincipleEnforcementInventory.inlineFqnPrefixes),
      "Regression if string literals, KDoc, or import lines false-positive the inline-FQN scanner.",
    )
  }
}
