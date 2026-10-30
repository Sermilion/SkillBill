package skillbill.architecture

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationPackageAcyclicityArchitectureTest {
  @ParameterizedTest(name = "{0} main sources have no exact-package cycle")
  @MethodSource("moduleNames")
  fun `module main sources have no exact-package cycle`(moduleName: String) {
    val scanCase =
      PrincipleEnforcementInventory.moduleArchitectureScanCases
        .single { scanCase -> scanCase.moduleName == moduleName }
    assertTrue(
      ArchitectureScanSupport.scannedPackages(scanCase.mainScanRoot, scanCase.packagePrefix).isNotEmpty(),
      "The $moduleName scan found no packages; check ${scanCase.mainScanRoot} and ${scanCase.packagePrefix}.",
    )
    val cycles = ArchitectureScanSupport.packageCycles(scanCase.mainScanRoot, scanCase.packagePrefix)
    val expected = ArchitectureBaselineSupport.readPackageCycleCensus(moduleName)
    assertEquals(
      expected,
      cycles,
      cycleDifference(expected, cycles) +
        if (moduleName in ArchitectureBaselineSupport.packageCycleCensusModules) {
          "\nRe-record the census with RECORD_ARCHITECTURE_BASELINES=1 only if the change is intended."
        } else {
          ""
        },
    )
  }

  private fun cycleDifference(
    expected: Set<ArchitectureScanSupport.PackageCycle>,
    actual: Set<ArchitectureScanSupport.PackageCycle>,
  ): String =
    buildList {
      (actual - expected).forEach { cycle -> add("New package cycle: ${cycle.areas.joinToString(" <-> ")}") }
      (expected - actual).forEach { cycle -> add("Census cycle gone: ${cycle.areas.joinToString(" <-> ")}") }
    }.joinToString("\n")

  @Test
  fun `runtime-contracts cycle scan sees error and contracts subpackage back edges`() {
    val scanCase =
      PrincipleEnforcementInventory.moduleArchitectureScanCases
        .single { scanCase -> scanCase.moduleName == "runtime-contracts" }
    val root = Files.createTempDirectory("architecture-contracts-subpackage-cycle")
    try {
      writeContractsSubpackageFixture(root, errorCoreImportsShellContent = false)
      assertEquals(
        emptySet(),
        ArchitectureScanSupport.packageCycles(root.toString(), scanCase.packagePrefix),
      )
      writeContractsSubpackageFixture(root, errorCoreImportsShellContent = true)
      assertEquals(
        setOf(
          ArchitectureScanSupport.PackageCycle(
            listOf("skillbill.contracts.review", "skillbill.contracts.workflow.featuretask"),
          ),
          ArchitectureScanSupport.PackageCycle(listOf("skillbill.error.core", "skillbill.error.shellcontent")),
        ),
        ArchitectureScanSupport.packageCycles(root.toString(), scanCase.packagePrefix),
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
  fun `exact package scan resolves member imports and reports arbitrary length cycles`() {
    assertExactPackageCycleScan()
    assertRootPackageCycleScan()
    assertAcyclicExactPackageScan()
  }

  private fun assertRootPackageCycleScan() {
    val root = Files.createTempDirectory("architecture-root-package-cycle")
    try {
      writeFixtureFile(
        root,
        "skillbill/synthetic/Root.kt",
        listOf("package skillbill.synthetic", "import skillbill.synthetic.zeta.Zeta", "class Root"),
      )
      writeFixtureFile(
        root,
        "skillbill/synthetic/zeta/Zeta.kt",
        listOf("package skillbill.synthetic.zeta", "import skillbill.synthetic.Root", "class Zeta"),
      )
      assertEquals(
        setOf(ArchitectureScanSupport.PackageCycle(listOf("skillbill.synthetic", "skillbill.synthetic.zeta"))),
        ArchitectureScanSupport.packageCycles(scanRoot = root.toString(), packagePrefix = "skillbill.synthetic."),
      )
    } finally {
      root.toFile().deleteRecursively()
    }
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
        ArchitectureScanSupport.packageCycles(scanRoot = root.toString(), packagePrefix = "skillbill.synthetic."),
      )
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun assertAcyclicExactPackageScan() {
    val root = Files.createTempDirectory("architecture-acyclic-package")
    try {
      writeFixtureFile(
        root,
        "skillbill/synthetic/alpha/Alpha.kt",
        listOf("package skillbill.synthetic.alpha", "import skillbill.synthetic.beta.Beta", "class Alpha"),
      )
      writeFixtureFile(
        root,
        "skillbill/synthetic/beta/Beta.kt",
        listOf("package skillbill.synthetic.beta", "import skillbill.synthetic.gamma.Gamma", "class Beta"),
      )
      writeFixtureFile(
        root,
        "skillbill/synthetic/gamma/Gamma.kt",
        listOf("package skillbill.synthetic.gamma", "class Gamma"),
      )
      assertTrue(
        ArchitectureScanSupport.packageImportEdges(root.toString(), "skillbill.synthetic.").isNotEmpty(),
        "The acyclic fixture must contain import edges.",
      )
      assertEquals(
        emptySet(),
        ArchitectureScanSupport.packageCycles(scanRoot = root.toString(), packagePrefix = "skillbill.synthetic."),
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

  companion object {
    @JvmStatic
    fun moduleNames(): List<String> = PrincipleEnforcementInventory.moduleArchitectureScanCases.map { it.moduleName }
  }
}
