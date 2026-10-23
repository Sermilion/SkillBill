package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class ProductionFileLineCeilingArchitectureTest {
  @Test
  fun `production Kotlin files stay within the line ceiling`() {
    val violations =
      ArchitectureScanSupport.productionLineCeilingViolations(
        productionRoots = listOf("runtime-kotlin"),
        ceiling = PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING,
        exemptions = PrincipleEnforcementInventory.productionLineCeilingExemptions,
      )
    assertEquals(
      emptyList(),
      violations,
      "Production files must stay at or below ${PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING} lines unless" +
        "explicitly exempted.",
    )
  }

  @Test
  fun `production line ceiling scanner fires on synthetic oversized fixture`() {
    val oversizedLineCount = PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING + 1
    val fixtureLines = (1..oversizedLineCount).joinToString("\n") { index -> "fun line$index() = $index" }
    val violations =
      ArchitectureScanSupport.productionLineCeilingViolationsInSource(
        relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/Example.kt",
        source = fixtureLines,
        ceiling = PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING,
        exemptions = PrincipleEnforcementInventory.productionLineCeilingExemptions,
      )
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-example/src/main/kotlin/Example.kt has $oversizedLineCount lines; split it below the " +
          "${PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING}-line ceiling or add an explicit exemption with " +
          "reason.",
      ),
      violations,
      "Regression if an oversized production file no longer names its line count and split expectation.",
    )
  }
}
