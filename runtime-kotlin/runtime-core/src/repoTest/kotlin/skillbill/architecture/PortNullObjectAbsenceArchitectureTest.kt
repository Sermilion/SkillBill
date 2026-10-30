package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object PortNullObjectCensus {
  private val declaration =
    Regex(
      """(?<!(?:data|enum|sealed|value) )\b(?:object|class)\s+((?:Unavailable|Noop|Empty|Unconfigured)\w*)""",
    )
  private val interfaceDeclaration = Regex("""^(?:public\s+|internal\s+)?(?:fun\s+)?interface\s+(\w+)""")
  private val companionNullObjectVal = Regex("""^\s*val\s+(NONE|IDLE|NOOP|DISABLED)\s*:""")

  val COMPANION_VAL_MODULES: List<String> = listOf("runtime-kotlin/runtime-ports", "runtime-kotlin/runtime-engine")

  fun interfaceNullObjectBodiesIn(source: String): List<String> = InterfaceNullObjectBodyScanner.scan(source)

  fun namesIn(source: String): Set<String> = declaration.findAll(source).map { it.groupValues[1] }.toSet()

  fun companionNullObjectsIn(source: String): Set<String> {
    var enclosingInterface: String? = null
    var inCompanion = false
    val found = mutableSetOf<String>()
    source.lines().forEach { line ->
      interfaceDeclaration.find(line)?.let { match ->
        enclosingInterface = match.groupValues[1]
        inCompanion = false
      }
      if (enclosingInterface == null) return@forEach
      if ("companion object" in line) inCompanion = true
      if (inCompanion) {
        companionNullObjectVal.find(line)?.let { match ->
          found += "$enclosingInterface.${match.groupValues[1]}"
        }
      }
      if (line == "}") {
        enclosingInterface = null
        inCompanion = false
      }
    }
    return found
  }

  fun testOnlyCompanionNullObjectsIn(
    declaringSources: Map<String, String>,
    mainSources: Collection<String>,
  ): List<String> =
    declaringSources
      .flatMap { (label, source) -> companionNullObjectsIn(source).map { qualifiedName -> qualifiedName to label } }
      .filterNot { (qualifiedName, _) -> mainSources.any { source -> qualifiedName in source } }
      .map { (qualifiedName, label) -> "$qualifiedName ($label)" }
      .sorted()
}

class PortNullObjectAbsenceArchitectureTest {
  private val runtimeRoot: Path = ArchitectureScanSupport.runtimeRoot

  @Test
  fun `no runtime module declares a null-object substitute in main source`() {
    val moduleMainRoots =
      RuntimeModuleCatalog.declaredGradleModules
        .filter { moduleName -> moduleName != "runtime-infra" }
        .map { moduleName -> moduleMainKotlinRoot(moduleName) }
    val infraMainRoots =
      RuntimeModuleCatalog.declaredGradleModules
        .filter { moduleName -> moduleName.startsWith("runtime-infra:") }
        .map { moduleName -> moduleMainKotlinRoot(moduleName) }
    assertEquals(
      7,
      infraMainRoots.size,
      "Every runtime-infra module must contribute a scanned main source root.",
    )
    val declarations =
      moduleMainRoots
        .flatMap { root ->
          val sourceFiles = kotlinFilesUnderWithArchitectureAsserts(root)
          sourceFiles.flatMap { path ->
            PortNullObjectCensus.namesIn(Files.readString(path)).map { "$it (${path.fileName})" }
          }
        }
        .sorted()

    assertEquals(
      emptyList(),
      declarations,
      "A Noop, Unavailable, Empty, or Unconfigured substitute reached production. Make the port " +
        "nullable at the reached call site and move the substitute into that module's testFixtures.",
    )
  }

  @Test
  fun `the census flags object and class substitutes and ignores data object and enum cases`() {
    assertEquals(
      setOf("NoopWorkflowGitOperations"),
      PortNullObjectCensus.namesIn("object NoopWorkflowGitOperations : WorkflowGitOperations"),
    )
    assertEquals(
      setOf("NoopWorkflowGitOperations"),
      PortNullObjectCensus.namesIn("class NoopWorkflowGitOperations : WorkflowGitOperations"),
    )
    assertEquals(
      emptySet(),
      PortNullObjectCensus.namesIn("  data object Empty : ValidationGateTriageResult"),
    )
    assertEquals(
      emptySet(),
      PortNullObjectCensus.namesIn("  enum class Unavailable : ReviewCheckpointFileIdentity"),
    )
  }

  @Test
  fun `no ports or engine interface member has a null-object default body`() {
    val reports =
      PortNullObjectCensus.COMPANION_VAL_MODULES.flatMap { module ->
        val sourceFiles = kotlinFilesUnderWithArchitectureAsserts(runtimeRoot.resolve("$module/src/main"))
        assertTrue(sourceFiles.isNotEmpty(), "$module must contribute scanned main source files.")
        sourceFiles.flatMap { path ->
          PortNullObjectCensus.interfaceNullObjectBodiesIn(Files.readString(path))
            .map { report -> "${runtimeRoot.relativize(path)}: $report" }
        }
      }.sorted()

    assertEquals(
      emptyList(),
      reports,
      "An interface member body that only returns null, a literal, an empty collection, Unit or a throw lets a " +
        "fake or a new implementation silently report nothing. Declare the member without a body, split the " +
        "interface so only the implementations that need it carry it, and implement it explicitly.",
    )
  }

  @Test
  fun `the interface census reports null, throw and getter bodies but not forwarding or default parameters`() {
    val source =
      """
      interface Sample {
        fun a(): String? = null

        fun wrapped(
          id: String,
        ): Boolean =
          throw IllegalStateException("x")

        val b: Int get() = 0

        fun c(u: Int): String? = a()

        fun d(x: String? = null): Int

        fun e(): Int {
          return 1
        }

        fun f(): List<String> {
        }

        fun g(): Int = compute { 0 }
      }
      """.trimIndent()

    assertEquals(
      listOf(
        "Sample.a (NULL)",
        "Sample.wrapped (THROW)",
        "Sample.b (LITERAL)",
        "Sample.e (LITERAL)",
        "Sample.f (UNIT_OR_EMPTY_BLOCK)",
      ),
      PortNullObjectCensus.interfaceNullObjectBodiesIn(source),
    )
  }

  @Test
  fun `no scanned module keeps a test-only companion null object in main source`() {
    assertEquals(emptyList(), testOnlyCompanionNullObjects())
  }

  @Test
  fun `the companion census flags a test-only null object and accepts a main-referenced one`() {
    val testOnly =
      """
      fun interface PlanningSweep {
        fun prepare(): Int

        companion object {
          val NONE: PlanningSweep = PlanningSweep { 0 }
        }
      }
      """.trimIndent()
    val mainReferenced =
      """
      fun interface GoalRunnerEventSink {
        fun emit(event: String)

        companion object {
          val NONE: GoalRunnerEventSink = GoalRunnerEventSink {}
        }
      }
      """.trimIndent()
    val emptyDataValue =
      "data class Findings(val entries: List<String>) { companion object { val EMPTY = Findings(emptyList()) } }"
    val declaringSources =
      mapOf("Sweep.kt" to testOnly, "Sink.kt" to mainReferenced, "Findings.kt" to emptyDataValue)
    val mainSources =
      declaringSources.values + "class Runner(private val sink: GoalRunnerEventSink = GoalRunnerEventSink.NONE)"

    assertEquals(
      listOf("PlanningSweep.NONE (Sweep.kt)"),
      PortNullObjectCensus.testOnlyCompanionNullObjectsIn(declaringSources, mainSources),
    )
  }

  private fun testOnlyCompanionNullObjects(): List<String> =
    PortNullObjectCensus.testOnlyCompanionNullObjectsIn(
      declaringSources =
        PortNullObjectCensus.COMPANION_VAL_MODULES
          .flatMap { module ->
            kotlinFilesUnderWithArchitectureAsserts(runtimeRoot.resolve("$module/src/main"))
          }
          .associate { path -> "${runtimeRoot.relativize(path)}" to Files.readString(path) },
      mainSources =
        ArchitectureScanSupport.kotlinFilesUnder(runtimeRoot.resolve("runtime-kotlin"))
          .filter { path -> "${Path.of("src", "main")}" in path.toString() }
          .map(Files::readString),
    )

  @Test
  fun `every runtime-ports test fixture object has an outside test or fixture reference`() {
    val fixtureRoot =
      runtimeRoot.resolve(
        "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-ports")}/src/testFixtures/kotlin",
      )
    val fixtureFiles = kotlinFilesUnderWithArchitectureAsserts(fixtureRoot)
    val declarations =
      fixtureFiles.flatMap { path ->
        Regex(
          """(?m)^\s*(?:internal\s+)?(?:object|class)\s+""" +
            """((?:Unavailable|Noop|Empty|Unconfigured)[A-Za-z]\w*)\b""",
        )
          .findAll(Files.readString(path))
          .map { match -> match.groupValues[1] to path }
          .toList()
      }
    val runtimeKotlinRoot = runtimeRoot.resolve("runtime-kotlin")
    val referenceFiles =
      kotlinFilesUnderWithArchitectureAsserts(runtimeKotlinRoot)
        .filterNot { path -> path.fileName.toString() == "PortNullObjectClassification.kt" }

    val missingReferences =
      declarations
        .filter { (name, declarationPath) ->
          referenceFiles
            .none { path ->
              Files.readString(path).lineSequence().any { line ->
                Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(line) &&
                  !Regex(
                    """^\s*(?:internal\s+)?(?:object|class)\s+${Regex.escape(name)}\b""",
                  ).containsMatchIn(line)
              }
            }
        }
        .map { (name, path) -> "$name (${runtimeRoot.relativize(path)})" }
        .sorted()

    assertEquals(emptyList(), missingReferences)
  }
}
