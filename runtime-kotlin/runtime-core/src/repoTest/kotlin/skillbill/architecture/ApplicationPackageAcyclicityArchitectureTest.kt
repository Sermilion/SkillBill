package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationPackageAcyclicityArchitectureTest {
  @Test
  fun `application package cycles match the recorded baseline`() {
    val violations =
      ArchitectureScanSupport.packageCycleViolations(
        baselineCycles = baselineCycles("application-package-cycle-baseline.txt"),
        scanRoot = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
        packagePrefix = PrincipleEnforcementInventory.APPLICATION_PACKAGE_PREFIX,
      )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `runtime-cli package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-cli")
  }

  @Test
  fun `runtime-contracts package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-contracts")
  }

  @Test
  fun `runtime-contracts cycle scan sees error and contracts subpackage back edges`() {
    val scanCase =
      PrincipleEnforcementInventory.moduleArchitectureScanCases
        .single { scanCase -> scanCase.moduleName == "runtime-contracts" }
    val root = Files.createTempDirectory("architecture-contracts-subpackage-cycle")
    try {
      writeContractsSubpackageFixture(root, errorCoreImportsShellContent = false)
      assertEquals(
        emptyList(),
        ArchitectureScanSupport.packageCycleViolations(
          baselineCycles = emptySet(),
          scanRoot = root.toString(),
          packagePrefix = scanCase.packagePrefix,
          granularity = scanCase.packageCycleGranularity,
        ),
      )
      writeContractsSubpackageFixture(root, errorCoreImportsShellContent = true)
      assertEquals(
        listOf(
          "New package cycle not in baseline: " +
            "skillbill.contracts.review <-> skillbill.contracts.workflow.featuretask",
          "New package cycle not in baseline: skillbill.error.core <-> skillbill.error.shellcontent",
        ),
        ArchitectureScanSupport.packageCycleViolations(
          baselineCycles = emptySet(),
          scanRoot = root.toString(),
          packagePrefix = scanCase.packagePrefix,
          granularity = scanCase.packageCycleGranularity,
        ),
      )
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun writeContractsSubpackageFixture(
    root: Path,
    errorCoreImportsShellContent: Boolean,
  ) {
    val errorBackEdge =
      if (errorCoreImportsShellContent) "import skillbill.error.shellcontent.ShellContentFailure" else ""
    val contractsBackEdge =
      if (errorCoreImportsShellContent) "import skillbill.contracts.workflow.featuretask.FeatureTaskKeys" else ""
    writeFixtureFile(
      root,
      "skillbill/error/core/CoreFailure.kt",
      listOf("package skillbill.error.core", errorBackEdge, "open class CoreFailure"),
    )
    writeFixtureFile(
      root,
      "skillbill/error/shellcontent/ShellContentFailure.kt",
      listOf(
        "package skillbill.error.shellcontent",
        "import skillbill.error.core.CoreFailure",
        "class ShellContentFailure : CoreFailure()",
      ),
    )
    writeFixtureFile(
      root,
      "skillbill/contracts/review/ReviewKeys.kt",
      listOf("package skillbill.contracts.review", contractsBackEdge, "object ReviewKeys"),
    )
    writeFixtureFile(
      root,
      "skillbill/contracts/workflow/featuretask/FeatureTaskKeys.kt",
      listOf(
        "package skillbill.contracts.workflow.featuretask",
        "import skillbill.contracts.review.ReviewKeys",
        "object FeatureTaskKeys",
      ),
    )
  }

  private fun writeFixtureFile(
    root: Path,
    relativePath: String,
    lines: List<String>,
  ) {
    val file = root.resolve(relativePath)
    Files.createDirectories(file.parent)
    file.writeText(lines.joinToString("\n"))
  }

  @Test
  fun `runtime-core package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-core")
  }

  @Test
  fun `runtime-domain package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-domain")
  }

  @Test
  fun `runtime-domain model packages import only model packages`() {
    val violations =
      ArchitectureScanSupport.modelPackageImportViolations(
        scanRoot = "runtime-kotlin/runtime-domain/src/main/kotlin",
        packagePrefix = "skillbill.",
      )
    assertEquals(
      emptyList(),
      violations,
      "A runtime-domain model package must depend on an owning model package, not an internal non-model package.",
    )
  }

  @Test
  fun `model packages may import a leaf package but not a package that imports another package`() {
    val root = Files.createTempDirectory("architecture-model-leaf-rule")
    try {
      writeFixtureFile(root, "skillbill/text/Hashing.kt", listOf("package skillbill.text", "fun hash() = Unit"))
      writeFixtureFile(
        root,
        "skillbill/helper/Helper.kt",
        listOf("package skillbill.helper", "import skillbill.text.hash", "fun help() = hash()"),
      )
      writeFixtureFile(
        root,
        "skillbill/widget/model/Widget.kt",
        listOf("package skillbill.widget.model", "import skillbill.text.hash", "fun widget() = hash()"),
      )
      assertEquals(emptyList(), ArchitectureScanSupport.modelPackageImportViolations(root.toString(), "skillbill."))
      writeFixtureFile(
        root,
        "skillbill/widget/model/Widget.kt",
        listOf("package skillbill.widget.model", "import skillbill.helper.help", "fun widget() = help()"),
      )
      val violations = ArchitectureScanSupport.modelPackageImportViolations(root.toString(), "skillbill.")
      assertTrue(
        violations.single().endsWith("skillbill.widget.model imports non-model package skillbill.helper"),
        violations.joinToString("\n"),
      )
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `runtime-domain public declarations have cross-file consumers`() {
    val violations =
      ArchitectureScanSupport.publicDomainDeclarationViolations(
        scanRoot = "runtime-kotlin/runtime-domain/src/main/kotlin",
        referenceRoot = "runtime-kotlin",
      )
    assertEquals(
      emptyList(),
      violations,
      "Public runtime-domain declarations must be consumed outside their declaring file and module.",
    )
  }

  @Test
  fun `runtime-infra host package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-infra:host")
  }

  @Test
  fun `runtime-infra contracts package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-infra:contracts")
  }

  @Test
  fun `runtime-infra skills package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-infra:skills")
  }

  @Test
  fun `runtime-infra launcher package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-infra:launcher")
  }

  @Test
  fun `runtime-infra workflow package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-infra:workflow")
  }

  @Test
  fun `runtime-infra http package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-infra:http")
  }

  @Test
  fun `runtime-infra sqlite package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-infra:sqlite")
  }

  @Test
  fun `runtime-mcp package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-mcp")
  }

  @Test
  fun `runtime-ports package cycles equal the recorded census`() {
    assertPackageCyclesMatchBaseline("runtime-ports")
  }

  @Test
  fun `package cycle scanner fires on synthetic cycle absent from baseline`() {
    val violations =
      ArchitectureScanSupport.packageCycleViolationsForEdges(
        edges =
          mapOf(
            "alpha" to setOf("beta"),
            "beta" to setOf("alpha"),
          ),
        baselineCycles = emptySet(),
      )
    assertEquals(
      listOf("New package cycle not in baseline: alpha <-> beta"),
      violations,
    )
    assertTrue(violations.single().contains("alpha <-> beta"))
  }

  @Test
  fun `exact package scanner reports arbitrary length cycles and resolves imported members`() {
    val violations =
      ArchitectureScanSupport.packageCycleViolationsForEdges(
        edges =
          mapOf(
            "skillbill.synthetic.alpha" to setOf("skillbill.synthetic.beta"),
            "skillbill.synthetic.beta" to setOf("skillbill.synthetic.gamma"),
            "skillbill.synthetic.gamma" to setOf("skillbill.synthetic.alpha"),
          ),
        baselineCycles = emptySet(),
        granularity = ArchitectureScanSupport.PackageCycleGranularity.EXACT_PACKAGE_SCC,
      )
    assertEquals(
      listOf(
        "New package cycle not in baseline: " +
          "skillbill.synthetic.alpha <-> skillbill.synthetic.beta <-> skillbill.synthetic.gamma",
      ),
      violations,
    )
  }

  @Test
  fun `exact package source scan resolves nested and member imports without changing default scan`() {
    assertExactPackageCycleScan()
    assertAcyclicExactPackageScan()
  }

  private fun assertExactPackageCycleScan() {
    val root = Files.createTempDirectory("architecture-exact-package-cycle")
    try {
      val packages =
        listOf("alpha", "beta", "gamma", "delta", "epsilon").associateWith { area ->
          root.resolve("skillbill/synthetic/$area").also { path -> Files.createDirectories(path) }
        }
      writeSyntheticPackageFiles(packages)
      val exactEdges =
        ArchitectureScanSupport.packageImportEdges(
          scanRoot = root.toString(),
          packagePrefix = "skillbill.synthetic.",
          granularity = ArchitectureScanSupport.PackageCycleGranularity.EXACT_PACKAGE_SCC,
        )
      assertEquals(
        mapOf(
          "skillbill.synthetic.alpha" to setOf("skillbill.synthetic.beta"),
          "skillbill.synthetic.beta" to setOf("skillbill.synthetic.gamma"),
          "skillbill.synthetic.gamma" to setOf("skillbill.synthetic.alpha"),
          "skillbill.synthetic.delta" to setOf("skillbill.synthetic.epsilon"),
          "skillbill.synthetic.epsilon" to setOf("skillbill.synthetic.delta"),
        ),
        exactEdges,
      )
      assertEquals(
        setOf(
          ArchitectureScanSupport.PackageCycle(
            listOf(
              "skillbill.synthetic.alpha",
              "skillbill.synthetic.beta",
              "skillbill.synthetic.gamma",
            ),
          ),
          ArchitectureScanSupport.PackageCycle(
            listOf(
              "skillbill.synthetic.delta",
              "skillbill.synthetic.epsilon",
            ),
          ),
        ),
        ArchitectureScanSupport.packageCycles(
          scanRoot = root.toString(),
          packagePrefix = "skillbill.synthetic.",
          granularity = ArchitectureScanSupport.PackageCycleGranularity.EXACT_PACKAGE_SCC,
        ),
      )
      assertEquals(
        setOf(ArchitectureScanSupport.PackageCycle(listOf("delta", "epsilon"))),
        ArchitectureScanSupport.packageCycles(
          scanRoot = root.toString(),
          packagePrefix = "skillbill.synthetic.",
        ),
      )
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun assertAcyclicExactPackageScan() {
    val root = Files.createTempDirectory("architecture-acyclic-package")
    try {
      val packages =
        listOf("alpha", "beta", "gamma").associateWith { area ->
          root.resolve("skillbill/synthetic/$area").also { path -> Files.createDirectories(path) }
        }
      packages.forEach { (name, path) ->
        path.resolve("${name.replaceFirstChar(Char::uppercase)}.kt").writeText(
          """


          class ${name.replaceFirstChar(Char::uppercase)}
          """.trimIndent(),
        )
      }
      assertEquals(
        emptySet(),
        ArchitectureScanSupport.packageCycles(
          scanRoot = root.toString(),
          packagePrefix = "skillbill.synthetic.",
          granularity = ArchitectureScanSupport.PackageCycleGranularity.EXACT_PACKAGE_SCC,
        ),
      )
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun writeSyntheticPackageFiles(packages: Map<String, Path>) {
    packages.getValue("alpha").resolve("Alpha.kt").writeText(
      """
      package skillbill.synthetic.alpha
      import skillbill.synthetic.beta.Beta
      class Alpha
      """.trimIndent(),
    )
    packages.getValue("beta").resolve("Beta.kt").writeText(
      """
      package skillbill.synthetic.beta
      import skillbill.synthetic.gamma.Gamma
      class Beta {
        object Nested
      }
      """.trimIndent(),
    )
    packages.getValue("gamma").resolve("Gamma.kt").writeText(
      """
      package skillbill.synthetic.gamma
      import skillbill.synthetic.alpha.Alpha.*

      class Gamma
      """.trimIndent(),
    )
    packages.getValue("delta").resolve("Delta.kt").writeText(
      """
      package skillbill.synthetic.delta
      import skillbill.synthetic.epsilon.Epsilon
      class Delta
      """.trimIndent(),
    )
    packages.getValue("epsilon").resolve("Epsilon.kt").writeText(
      """
      package skillbill.synthetic.epsilon
      import skillbill.synthetic.delta.Delta
      class Epsilon
      """.trimIndent(),
    )
  }

  @Test
  fun `engine model subareas have an empty parent import baseline`() {
    val forbiddenEdges =
      listOf(
        "skillbill.engine.featuretask.model" to "skillbill.engine.featuretask",
        "skillbill.engine.goalrunner.model" to "skillbill.engine.goalrunner",
        "skillbill.engine.goalrunner.planning.model" to "skillbill.engine.goalrunner.planning",
      )
    val engineRoot =
      ArchitectureScanSupport.runtimeRoot.resolve(
        "runtime-kotlin/runtime-engine/src/main/kotlin",
      )
    val violations =
      ArchitectureScanSupport.kotlinFilesUnder(engineRoot).flatMap { sourceFile ->
        val source = sourceFile.readText()
        val declaredPackage = ArchitectureScanSupport.declaredPackage(source)
        if (declaredPackage == null) {
          emptyList()
        } else {
          forbiddenEdges.flatMap { (modelPackage, parentPackage) ->
            if (declaredPackage != modelPackage) {
              emptyList()
            } else {
              ArchitectureScanSupport.declaredImports(source)
                .filter { imported -> imported == parentPackage || imported.startsWith("$parentPackage.") }
                .map { imported -> "${engineRoot.relativize(sourceFile)}: $modelPackage -> $imported" }
            }
          }
        }
      }
    assertEquals(emptyList(), violations)
  }

  @Test
  fun `engine subareas have an empty package cycle baseline`() {
    val engineRoot = "runtime-kotlin/runtime-engine/src/main/kotlin"
    val subareas =
      listOf(
        "skillbill.engine.featuretask",
        "skillbill.engine.goalrunner",
        "skillbill.engine.goalrunner.planning",
      )
    val violations =
      subareas.flatMap { packagePrefix ->
        ArchitectureScanSupport.packageCycleViolations(
          baselineCycles = emptySet(),
          scanRoot = engineRoot,
          packagePrefix = packagePrefix,
        ).map { violation -> "$packagePrefix: $violation" }
      }
    assertEquals(emptyList(), violations)
  }

  @Test
  fun `engine model packages contain no injected data classes`() {
    val engineRoot =
      ArchitectureScanSupport.runtimeRoot.resolve(
        "runtime-kotlin/runtime-engine/src/main/kotlin",
      )
    val violations =
      ArchitectureScanSupport.kotlinFilesUnder(engineRoot).flatMap { sourceFile ->
        val source = sourceFile.readText()
        val packageName = ArchitectureScanSupport.declaredPackage(source).orEmpty()
        buildList {
          if (packageName.endsWith(".model") &&
            Regex("""@Inject\s+data\s+class""").containsMatchIn(source)
          ) {
            add("${engineRoot.relativize(sourceFile)} declares an injected model data class")
          }
        }
      }
    assertEquals(emptyList(), violations)
  }

  private fun assertPackageCyclesMatchBaseline(moduleName: String) {
    val scanCase =
      PrincipleEnforcementInventory.moduleArchitectureScanCases
        .single { scanCase -> scanCase.moduleName == moduleName }
    val current =
      ArchitectureScanSupport.packageCycles(
        scanRoot = scanCase.mainScanRoot,
        packagePrefix = scanCase.packagePrefix,
        granularity = scanCase.packageCycleGranularity,
      )
    assertEquals(
      baselineCycles(scanCase.packageCycleBaseline),
      current,
      "Re-record ${scanCase.packageCycleBaseline} with RECORD_ARCHITECTURE_BASELINES=1.",
    )
  }

  private fun baselineCycles(name: String): Set<ArchitectureScanSupport.PackageCycle> =
    ArchitectureScanSupport.parsePackageCycleBaseline(ArchitectureBaselineSupport.readBaseline(name))
}
