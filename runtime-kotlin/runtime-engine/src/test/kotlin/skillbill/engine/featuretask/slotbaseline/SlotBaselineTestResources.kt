package skillbill.engine.featuretask.slotbaseline

import java.nio.file.Path
import java.nio.file.Paths

internal object SlotBaselineTestResources {
  val resourcesRoot: Path = locateModuleRoot().resolve("src/test/resources")

  fun resolve(relativePath: String): Path = resourcesRoot.resolve(relativePath)

  private fun locateModuleRoot(): Path {
    val cwd = Paths.get("").toAbsolutePath().normalize()
    return generateSequence(cwd) { it.parent }
      .mapNotNull { candidate ->
        when {
          candidate.fileName?.toString() == "runtime-engine" -> candidate
          candidate.resolve("runtime-kotlin/runtime-engine/src/test/resources").toFile().isDirectory ->
            candidate.resolve("runtime-kotlin/runtime-engine")
          else -> null
        }
      }
      .firstOrNull()
      ?: error("could not locate the runtime-engine test module from $cwd")
  }
}
