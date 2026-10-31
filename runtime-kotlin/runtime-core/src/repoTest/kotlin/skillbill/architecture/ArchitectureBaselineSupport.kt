package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path

internal object ArchitectureBaselineSupport {
  internal val baselineRoot: Path =
    ArchitectureScanSupport.runtimeRoot.resolve(
      "runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines",
    )

  fun readBaseline(name: String): String = readBaselineIn(baselineRoot, name)

  internal fun readBaselineIn(
    directory: Path,
    name: String,
  ): String {
    val baseline = directory.resolve(name)
    return if (Files.isRegularFile(baseline)) Files.readString(baseline) else ""
  }

  internal fun writeBaselineRows(
    target: Path,
    rows: List<String>,
  ) {
    if (rows.isEmpty()) {
      Files.deleteIfExists(target)
    } else {
      Files.writeString(target, rows.joinToString("\n") + "\n")
    }
  }

  fun packageCycleCensusName(moduleName: String): String =
    "${RuntimeModuleCatalog.gradleModuleIdToBaselineStem(moduleName)}-package-cycle-baseline.txt"

  val packageCycleCensusModules: Set<String> = setOf("runtime-engine", "runtime-goalrunner")

  fun readPackageCycleCensus(moduleName: String): Set<ArchitectureScanSupport.PackageCycle> {
    if (moduleName !in packageCycleCensusModules) return emptySet()
    val census = baselineRoot.resolve(packageCycleCensusName(moduleName))
    if (!Files.exists(census)) return emptySet()
    return Files.readAllLines(census)
      .map(String::trim)
      .filter { line -> line.isNotBlank() && !line.startsWith("#") }
      .map { line -> ArchitectureScanSupport.PackageCycle(line.split('|').map(String::trim).sorted()) }
      .toSet()
  }
}
