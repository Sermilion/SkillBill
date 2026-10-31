package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class ArchitectureBaselineRecorder {
  @Test
  fun recordBaselinesWhenRequested() {
    if (System.getenv("RECORD_ARCHITECTURE_BASELINES") != "1") return
    val baselineDir =
      ArchitectureBaselineSupport.baselineRoot
    Files.createDirectories(baselineDir)

    recordPackageCycleBaselines(baselineDir)
    recordAmbientClockBaselines(baselineDir)
    recordAmbientEnvironmentBaselines(baselineDir)
    recordInjectDefaultBaselines(baselineDir)
    recordCustomThrowableBaseline(baselineDir)
  }

  private fun recordCustomThrowableBaseline(baselineDir: Path) {
    ArchitectureBaselineSupport.writeBaselineRows(
      baselineDir.resolve(PrincipleEnforcementInventory.CUSTOM_THROWABLE_BASELINE),
      ArchitectureScanSupport.customThrowableRows().sorted(),
    )
  }

  private fun recordPackageCycleBaselines(baselineDir: Path) {
    PrincipleEnforcementInventory.moduleArchitectureScanCases
      .filter { scanCase -> scanCase.moduleName in ArchitectureBaselineSupport.packageCycleCensusModules }
      .forEach { scanCase ->
        writePackageCycleBaseline(
          baselineDir.resolve(ArchitectureBaselineSupport.packageCycleCensusName(scanCase.moduleName)),
          scanCase.mainScanRoot,
          scanCase.packagePrefix,
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

  private fun writePackageCycleBaseline(
    target: Path,
    scanRoot: String,
    packagePrefix: String,
  ) {
    val cycles =
      ArchitectureScanSupport.packageCycles(scanRoot, packagePrefix)
        .map { cycle -> cycle.areas.sorted().joinToString("|") }
        .sorted()
    ArchitectureBaselineSupport.writeBaselineRows(target, cycles)
  }

  private fun writeAmbientBaseline(
    target: Path,
    sites: List<ArchitectureScanSupport.AmbientCallSite>,
  ) {
    ArchitectureBaselineSupport.writeBaselineRows(
      target,
      ArchitectureScanSupport.encodeAmbientSites(sites),
    )
  }

  private fun writeInjectDefaultBaseline(
    target: Path,
    scanRoot: String,
  ) {
    val sites =
      ArchitectureScanSupport.injectConstructorDefaultSites(scanRoot)
        .map { site -> "${site.relativePath}::${site.symbol}::${site.parameter}" }
    ArchitectureBaselineSupport.writeBaselineRows(target, sites)
  }
}
