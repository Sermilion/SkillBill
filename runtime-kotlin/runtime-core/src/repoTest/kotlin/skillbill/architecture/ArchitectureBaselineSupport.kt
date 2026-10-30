package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path

internal object ArchitectureBaselineSupport {
  private val baselineRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { start ->
      var dir: Path? = start
      while (dir != null) {
        val candidate =
          dir.resolve(
            "runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines",
          )
        if (Files.isDirectory(candidate)) return@let candidate
        dir = dir.parent
      }
      start.resolve(
        "runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines",
      )
    }

  fun readBaseline(name: String): String = Files.readString(baselineRoot.resolve(name))

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
