package skillbill.infrastructure.skills.nativeagent

import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.infrastructure.skills.nativeagent.rendering.discoverRepoNativeAgentSourceFiles
import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.review.plan.ReviewLaunchPlanPolicy
import java.nio.file.Files
import java.nio.file.Path

internal fun validatePlannedNativeAgentWorkers(repoRoot: Path): List<String> {
  val root = repoRoot.toAbsolutePath().normalize()
  val packsRoot = root.resolve("platform-packs")
  if (!Files.isDirectory(packsRoot) || !Files.isDirectory(root.resolve("orchestration/contracts"))) {
    return emptyList()
  }
  val issues = mutableListOf<String>()
  val sources =
    discoverRepoNativeAgentSourceFiles(root).flatMap { path ->
      catchingNonCancellation { parseNativeAgentSourceFile(path) }
        .getOrElse { error ->
          issues += "${root.relativize(path)}: cannot parse native-agent source: ${error.message.orEmpty()}"
          emptyList()
        }
    }
  val manifests =
    catchingNonCancellation { discoverPlatformPackManifests(packsRoot) }
      .getOrElse { error ->
        return listOf("platform-packs: cannot derive native-agent worker set: ${error.message.orEmpty()}")
      }
  val plannedNames =
    manifests.flatMap { manifest ->
      val selectedAreas = ReviewLaunchPlanPolicy.composedAreas(manifest.slug, manifests)
      ReviewLaunchPlanPolicy.flatten(manifest.slug, manifests, selectedAreas).lanes.map { it.skillName }
    }.toSortedSet()
  val declarations = sources.groupBy { it.name }
  plannedNames.forEach { worker ->
    when (declarations[worker].orEmpty().size) {
      0 -> issues += "platform-packs: planned review worker '$worker' has no native-agent source declaration"
      1 -> Unit
      else -> issues += "platform-packs: planned review worker '$worker' has ambiguous native-agent declarations"
    }
  }
  return issues.sorted()
}
