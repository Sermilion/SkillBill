package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class TypedParseBoundaryArchitectureTest {
  @Test
  fun `named untrusted-input parse boundaries do not use forbidden malformed-input reporters`() {
    val violations =
      ArchitectureScanSupport.parseBoundaryViolations(
        PrincipleEnforcementInventory.parseBoundarySites,
      )
    assertEquals(
      emptyList(),
      violations,
      "Named parse boundaries must surface malformed external input through a result or a " +
        "SkillBillRuntimeException code, not error, require, or bare throw.",
    )
  }

  @Test
  fun `typed parse boundary scanner fires on synthetic boundary fixture`() {
    val fixture =
      """
      private fun decodeBad(raw: String): String {
        val value = raw as? Map<*, *> ?: error("durable record must be an object.")
        return value.toString()
      }
      """.trimIndent()
    val violations =
      ArchitectureScanSupport.parseBoundaryViolationsInSource(
        source = fixture,
        site =
          ArchitectureScanSupport.ParseBoundarySite(
            relativePath = "Synthetic.kt",
            functionNames = setOf("decodeBad"),
          ),
      )
    assertEquals(
      listOf(
        "Synthetic.kt::decodeBad reports malformed external input via error(); " +
          "use a result or a SkillBillRuntimeException code instead.",
      ),
      violations,
      "Regression if a named parse boundary can report malformed external input via error, require, or bare throw.",
    )
  }

  @Test
  fun `typed parse boundary scanner reports a selected name absent from the source`() {
    val fixture =
      """
      private fun decodeGood(raw: String): String = raw.trim()
      """.trimIndent()
    val violations =
      ArchitectureScanSupport.parseBoundaryViolationsInSource(
        source = fixture,
        site =
          ArchitectureScanSupport.ParseBoundarySite(
            relativePath = "Synthetic.kt",
            functionNames = setOf("decodeGood", "decodeMissing"),
          ),
      )
    assertEquals(
      listOf("Synthetic.kt::decodeMissing is selected but could not be located or inspected"),
      violations,
      "Regression if a selected parse boundary name can silently go uninspected.",
    )
  }

  @Test
  fun `typed parse boundary scanner inspects extension functions with expression bodies`() {
    val fixture =
      """
      private fun Map<String, Any?>.decodeBad(): Int =
        this["value"] as? Int ?: error("value must be an integer.")

      private fun decodeGood(raw: String): String = raw.trim()
      """.trimIndent()
    val violations =
      ArchitectureScanSupport.parseBoundaryViolationsInSource(
        source = fixture,
        site =
          ArchitectureScanSupport.ParseBoundarySite(
            relativePath = "Synthetic.kt",
            functionNames = setOf("decodeBad", "decodeGood"),
          ),
      )
    assertEquals(
      listOf(
        "Synthetic.kt::decodeBad reports malformed external input via error(); " +
          "use a result or a SkillBillRuntimeException code instead.",
      ),
      violations,
      "Regression if an extension expression body escapes the scan or absorbs the following function.",
    )
  }
}
