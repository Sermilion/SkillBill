package skillbill.architecture

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val RUN_CATCHING_PENDING_MAIN_ROOTS: Set<String> =
  setOf(
    "runtime-kotlin/runtime-engine/src/main/kotlin",
  )

private val RUN_CATCHING_CALL = Regex("\\brunCatching\\s*(<[^>]*>)?\\s*[{(]")

class RunCatchingBanArchitectureTest {
  @Test
  fun `main sources outside pending roots contain no runCatching calls`() {
    val violations =
      PrincipleEnforcementInventory.moduleArchitectureScanCases
        .filterNot { it.mainScanRoot in RUN_CATCHING_PENDING_MAIN_ROOTS }
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
  fun `every pending main root still contains a runCatching call`() {
    val pendingRoots = PrincipleEnforcementInventory.moduleArchitectureScanCases.map { it.mainScanRoot }.toSet()
    val missingRoots = RUN_CATCHING_PENDING_MAIN_ROOTS - pendingRoots
    val emptyRoots =
      RUN_CATCHING_PENDING_MAIN_ROOTS
        .filter { root ->
          val sourceFiles =
            ArchitectureScanSupport.kotlinFilesUnder(ArchitectureScanSupport.runtimeRoot.resolve(root))
          assertTrue(sourceFiles.isNotEmpty(), "No Kotlin main sources found for pending root $root.")
          sourceFiles.none { sourceFile -> runCatchingCallLines(sourceFile.readText()).isNotEmpty() }
        }

    assertTrue(missingRoots.isEmpty(), "Pending roots are not catalog modules: ${missingRoots.sorted()}")
    assertTrue(emptyRoots.isEmpty(), "Pending roots no longer contain runCatching calls: ${emptyRoots.sorted()}")
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
