package skillbill.infrastructure.skills.scaffold.validation.review

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import skillbill.error.shellcontent.invalidManifestSchema
import skillbill.error.shellcontent.invalidReviewSkillStructure
import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_BUNDLE_FILE
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentBundle
import skillbill.infrastructure.skills.scaffold.platformpack.packRootsBySlug
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest

internal object ReviewSkillStructureValidator {
  fun validate(
    pack: Path,
    packRootsBySlug: Map<String, Path> = emptyMap(),
  ) {
    val violations = violations(pack, packRootsBySlug)
    if (violations.isNotEmpty()) {
      throw invalidReviewSkillStructure(
        "Platform pack '${pack.fileName}' violates the governed review-skill structure: " +
          violations.joinToString("; ") { violation ->
            val display = displayPath(pack, violation.path)
            "$display: ${violation.rule}"
          },
      )
    }
  }

  fun violations(
    pack: Path,
    packRootsBySlug: Map<String, Path> = emptyMap(),
  ): List<ReviewSkillStructureViolation> {
    if (pack.name == "platform-packs") {
      return Files.list(pack).use { packDirectories ->
        packDirectories.filter(Files::isDirectory).toList().flatMap { child -> violations(child, packRootsBySlug) }
      }
    }
    val manifest =
      manifest(pack) ?: return listOf(
        ReviewSkillStructureViolation(pack.resolve("platform.yaml"), "platform manifest mapping"),
      )
    val reviewFiles = contentFiles(pack)
    val hasReviewSurface =
      declaredBaseline(manifest) != null ||
        declaredAreas(manifest).isNotEmpty() ||
        reviewFiles.isNotEmpty()
    return buildList {
      if (hasReviewSurface) {
        addAll(ReviewSkillStructureValidatorManifest.manifestViolations(pack, manifest))
        addAll(
          reviewFiles.flatMap { file ->
            ReviewSkillStructureValidatorContent.contentViolations(pack, manifest, file, packRootsBySlug)
          },
        )
        addAll(ReviewSkillStructureValidatorContent.nativeAgentViolations(pack, manifest))
        addAll(
          ReviewSkillStructureValidatorContent.authoredSidecarViolations(reviewFiles, manifest),
        )
      }
      addAll(
        allContentFiles(pack).flatMap(::severityViolations),
      )
    }
  }
}

internal data class ReviewSkillStructureViolation(val path: Path, val rule: String) {
  override fun toString(): String = "$path: $rule"
}

internal fun validateReviewSkillStructure(pack: PlatformManifest) {
  val baseline = pack.declaredFiles.baseline ?: return
  val bundle = baseline.toPath().parent.resolve("native-agents").resolve(NATIVE_AGENT_BUNDLE_FILE)
  if (!Files.isRegularFile(bundle)) return

  val actualAgents = parseNativeAgentBundle(bundle)
  val actualNames = actualAgents.map { it.name }
  val specialistNames =
    pack.declaredCodeReviewAreas
      .map { area -> pack.declaredFiles.areas.getValue(area).toPath().parent.fileName.toString() }
      .toSet()
  val baselineName = baseline.toPath().parent.fileName.toString()
  val expectedNames = specialistNames + baselineName
  val actualNameSet = actualNames.toSet()
  val governedNameSet = actualAgents.filter { it.composition != null }.map { it.name }.toSet()
  val unknown = governedNameSet - expectedNames
  if (actualNames.size != actualNameSet.size || unknown.isNotEmpty()) {
    throw invalidManifestSchema(
      "Platform pack '${pack.slug}': native-agent bundle may not declare duplicate agents or unknown " +
        "governed-content agents; unknown=${unknown.sorted()}.",
    )
  }
}
