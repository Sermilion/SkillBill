package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

class ProductionLogicalTypeLineCeilingArchitectureTest {
  @Test
  fun `production logical types stay within baseline or ceiling`() {
    val baseline =
      ArchitectureScanSupport.parseIntBaseline(
        ArchitectureBaselineSupport.readBaseline("logical-type-line-ceiling-baseline.txt"),
      )
    val violations =
      ArchitectureScanSupport.logicalTypeLineCeilingViolations(
        productionRoots = listOf("runtime-kotlin"),
        ceiling = PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING,
        baseline = baseline,
      )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `logical type ceiling scanner fires on synthetic split-file fixture`() {
    val partLineCount = PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING / 2 + 10
    val partOne =
      """

      class SplitLogicalTypeFixture
      """.trimIndent() + "\n" + (1..partLineCount).joinToString("\n") { index -> "fun partOne$index() = $index" }
    val partTwo =
      """

      fun SplitLogicalTypeFixture.partTwo() = Unit
      """.trimIndent() + "\n" + (1..partLineCount).joinToString("\n") { index -> "fun partTwo$index() = $index" }
    val combinedLineCount = listOf(partOne, partTwo).sumOf { source -> source.lineSequence().count() }
    val violations =
      ArchitectureScanSupport.logicalTypeLineCeilingViolationsInSources(
        sourceFiles =
          listOf(
            syntheticSourceFile("fixture/logicaltype/SplitLogicalTypeFixture.kt", partOne),
            syntheticSourceFile("fixture/logicaltype/SplitLogicalTypeFixtureExtensions.kt", partTwo),
          ),
        ceiling = PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING,
        baseline = emptyMap(),
      )
    assertEquals(
      listOf(
        "skillbill.fixture.logicaltype.SplitLogicalTypeFixture has $combinedLineCount lines; exceeds the " +
          "${PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING}-line ceiling without a baseline entry.",
      ),
      violations,
    )
  }
}
