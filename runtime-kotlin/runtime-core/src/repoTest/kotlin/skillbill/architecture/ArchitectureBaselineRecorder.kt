package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class ArchitectureBaselineRecorder {
  @Test
  fun recordBaselinesWhenRequested() {
    if (System.getenv("RECORD_ARCHITECTURE_BASELINES") != "1") return
    val baselineDir =
      ArchitectureScanSupport.runtimeRoot.resolve(
        "runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines",
      )
    Files.createDirectories(baselineDir)

    recordLogicalTypeLineCeilingBaseline(baselineDir)
    recordPackageCycleBaselines(baselineDir)
    recordAmbientClockBaselines(baselineDir)
    recordAmbientEnvironmentBaselines(baselineDir)
    recordInjectDefaultBaselines(baselineDir)
    recordSpilloverFileNameBaseline(baselineDir)
    recordCustomThrowableBaseline(baselineDir)
  }

  private fun recordCustomThrowableBaseline(baselineDir: Path) {
    Files.writeString(
      baselineDir.resolve(PrincipleEnforcementInventory.CUSTOM_THROWABLE_BASELINE),
      ArchitectureScanSupport.customThrowableRows().sorted().joinToString("\n") + "\n",
    )
  }

  private fun recordLogicalTypeLineCeilingBaseline(baselineDir: Path) {
    val logicalCounts =
      ArchitectureScanSupport.logicalTypeLineCounts(
        productionRoots = listOf("runtime-kotlin", "intellij-plugin"),
      ).filter { (_, count) -> count > PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING }
    Files.writeString(
      baselineDir.resolve("logical-type-line-ceiling-baseline.txt"),
      logicalCounts.entries.sortedBy { it.key }.joinToString("\n") { (fqn, count) -> "$fqn $count" } + "\n",
    )
  }

  private fun recordPackageCycleBaselines(baselineDir: Path) {
    PrincipleEnforcementInventory.moduleArchitectureScanCases.forEach { scanCase ->
      writePackageCycleBaseline(
        baselineDir.resolve(scanCase.packageCycleBaseline),
        scanCase.mainScanRoot,
        scanCase.packagePrefix,
        scanCase.packageCycleGranularity,
      )
    }
  }

  private fun recordAmbientClockBaselines(baselineDir: Path) {
    PrincipleEnforcementInventory.moduleArchitectureScanCases.forEach { scanCase ->
      writeAmbientBaseline(
        baselineDir.resolve(scanCase.ambientClockBaseline),
        ArchitectureScanSupport.ambientClockCallSites(scanCase.mainScanRoot),
      )
    }
  }

  private fun recordAmbientEnvironmentBaselines(baselineDir: Path) {
    PrincipleEnforcementInventory.moduleArchitectureScanCases.forEach { scanCase ->
      writeAmbientBaseline(
        baselineDir.resolve(scanCase.ambientEnvironmentBaseline),
        ArchitectureScanSupport.ambientEnvironmentCallSites(scanCase.mainScanRoot),
      )
    }
  }

  private fun recordInjectDefaultBaselines(baselineDir: Path) {
    PrincipleEnforcementInventory.moduleArchitectureScanCases
      .filterNot { scanCase -> scanCase.moduleName == "runtime-application" }
      .forEach { scanCase ->
        writeInjectDefaultBaseline(
          baselineDir.resolve(scanCase.injectDefaultsBaseline),
          scanCase.mainScanRoot,
        )
      }
  }

  private fun recordSpilloverFileNameBaseline(baselineDir: Path) {
    val scanRoots =
      PrincipleEnforcementInventory.moduleArchitectureScanCases.map { scanCase ->
        scanCase.moduleSourceRoot
      }
    val paths =
      ArchitectureScanSupport.spilloverFileNamePaths(
        scanRoots = scanRoots,
        exemptPaths = PrincipleEnforcementInventory.spilloverFileNameExemptions,
      )
    Files.writeString(
      baselineDir.resolve(PrincipleEnforcementInventory.SPILLOVER_FILE_NAME_BASELINE),
      paths.sorted().joinToString("\n") + "\n",
    )
  }

  private fun writePackageCycleBaseline(
    target: Path,
    scanRoot: String,
    packagePrefix: String,
    granularity: ArchitectureScanSupport.PackageCycleGranularity,
  ) {
    val cycles =
      ArchitectureScanSupport.packageCycles(scanRoot, packagePrefix, granularity)
        .map { cycle -> cycle.areas.sorted().joinToString("|") }
        .sorted()
    Files.writeString(target, cycles.joinToString("\n") + "\n")
  }

  private fun writeAmbientBaseline(
    target: Path,
    sites: List<ArchitectureScanSupport.AmbientCallSite>,
  ) {
    Files.writeString(
      target,
      ArchitectureScanSupport.encodeAmbientSites(sites).joinToString("\n") + "\n",
    )
  }

  private fun writeInjectDefaultBaseline(
    target: Path,
    scanRoot: String,
  ) {
    val sites =
      ArchitectureScanSupport.injectConstructorDefaultSites(scanRoot)
        .map { site -> "${site.relativePath}::${site.symbol}::${site.parameter}" }
    Files.writeString(target, sites.joinToString("\n") + "\n")
  }
}
