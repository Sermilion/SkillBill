package skillbill.architecture

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val RUN_CATCHING_CALL = Regex("\\brunCatching\\s*(<[^>]*>)?\\s*[{(]")

class RunCatchingBanArchitectureTest {
  @Test
  fun `main sources contain no runCatching calls`() {
    val violations =
      PrincipleEnforcementInventory.moduleArchitectureScanCases
        .flatMap { module ->
          val sourceFiles =
            ArchitectureScanSupport.kotlinFilesUnder(ArchitectureScanSupport.runtimeRoot.resolve(module.mainScanRoot))
          assertTrue(sourceFiles.isNotEmpty(), "No Kotlin main sources found for ${module.moduleName}.")
          sourceFiles
            .flatMap { sourceFile ->
              runCatchingCallLines(sourceFile.readText()).map { line ->
                "${ArchitectureScanSupport.runtimeRoot.relativize(sourceFile)}:$line"
              }
            }
        }

    assertTrue(violations.isEmpty(), "Forbidden runCatching calls:\n${violations.sorted().joinToString("\n")}")
  }

  @Test
  fun `call scanner ignores the replacement helper`() {
    val source =
      """
      fun old() = runCatching { 1 }
      fun replacement() = catchingNonCancellation { 1 }
      """.trimIndent()

    assertEquals(listOf(1), runCatchingCallLines(source))
  }
}

private fun runCatchingCallLines(source: String): List<Int> =
  source.lineSequence().mapIndexedNotNull { index, line ->
    if (RUN_CATCHING_CALL.containsMatchIn(line.substringBefore("//"))) index + 1 else null
  }.toList()
