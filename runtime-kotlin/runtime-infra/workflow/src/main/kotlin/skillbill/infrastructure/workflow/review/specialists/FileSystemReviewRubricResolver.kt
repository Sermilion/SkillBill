package skillbill.infrastructure.workflow.review.specialists

import java.nio.file.Files
import java.nio.file.Path
import me.tatarka.inject.annotations.Inject
import skillbill.error.shellcontent.missingContentFile
import skillbill.model.toPath
import skillbill.ports.review.model.ResolvedReviewRubric
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.ports.review.preparation.ReviewRubricResolver
import skillbill.review.plan.ReviewAddonSelectionPolicy
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.PlatformManifest

@Inject
class FileSystemReviewRubricResolver : ReviewRubricResolver {
  override fun resolve(manifest: PlatformManifest?): ResolvedReviewRubric {
    if (manifest == null) return ResolvedReviewRubric(GENERIC_RUBRIC_ID, GENERIC_RUBRIC)
    val baseline =
      requireNotNull(manifest.declaredFiles.baseline) {
        "Platform pack '${manifest.slug}' does not declare a code-review baseline."
      }.toPath().toRealPath()
    val packRoot = manifest.packRoot.toPath().toRealPath()
    require(baseline.startsWith(packRoot) && Files.isRegularFile(baseline) && !Files.isSymbolicLink(baseline)) {
      "Platform pack '${manifest.slug}' declares an unreadable code-review baseline."
    }
    val specialists =
      manifest.declaredCodeReviewAreas.map { area ->
        val file =
          requireNotNull(manifest.declaredFiles.areas[area]) {
            "Platform pack '${manifest.slug}' does not declare its '$area' specialist file."
          }.toPath().toRealPath()
        require(file.startsWith(packRoot) && Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
          "Platform pack '${manifest.slug}' declares an unreadable '$area' code-review rubric."
        }
        val body = Files.readString(file)
        require(body.toByteArray().size <= MAX_RUBRIC_BYTES) {
          "Platform pack '${manifest.slug}' declares a '$area' rubric larger than $MAX_RUBRIC_BYTES bytes."
        }
        ResolvedReviewRubric(
          rubricId = "bill-${manifest.slug}-code-review-$area",
          body = body,
          area = area,
        )
      }
    val baselineBody = Files.readString(baseline)
    require(baselineBody.toByteArray().size <= MAX_RUBRIC_BYTES) {
      "Platform pack '${manifest.slug}' declares a code-review baseline larger than $MAX_RUBRIC_BYTES bytes."
    }
    return ResolvedReviewRubric(
      requireNotNull(manifest.routedSkillName),
      baselineBody,
      specialists = specialists,
    )
  }

  override fun resolve(
    manifest: PlatformManifest?,
    evidence: List<ReviewOwnedFileEvidence>,
    specialistSkillName: String,
  ): ResolvedReviewRubric {
    val resolved = resolve(manifest)
    if (manifest == null) return resolved
    val specialist = resolved.specialists.singleOrNull { it.rubricId == specialistSkillName } ?: resolved
    val consumer = "code-review/$specialistSkillName"
    val selected =
      ReviewAddonSelectionPolicy.select(manifest, specialistSkillName)
        .filter { selection -> governedAddonSelectionMatches(selection, evidence) }
    if (selected.isEmpty()) return specialist
    val appended = linkedSetOf<Path>()
    val guidance =
      selected.flatMap { selection ->
        val slots =
          listOf(ENTRYPOINT_SLOT to selection.entrypoint) +
            selection.companionPointers.map { pointer -> pointer to pointer }
        slots.mapNotNull { (slot, pointer) ->
          val path = resolveAddonFile(manifest, consumer, selection, slot, pointer)
          path.takeIf { appended.add(it) }?.let(::readBounded)
        }
      }.joinToString("\n\n")
    require(guidance.toByteArray().size <= MAX_ADDON_BYTES) {
      "Selected add-on guidance for '$specialistSkillName' is larger than $MAX_ADDON_BYTES bytes."
    }
    return specialist.copy(
      body = specialist.body + "\n\n## Selected governed add-on guidance\n\n" + guidance,
      selectedAddOns = selected.map { it.slug },
    )
  }

  private fun resolveAddonFile(
    manifest: PlatformManifest,
    skillRelativeDir: String,
    selection: GovernedAddonSelection,
    slot: String,
    pointerName: String,
  ): Path {
    val pointer =
      manifest.pointers.firstOrNull { spec ->
        spec.skillRelativeDir == skillRelativeDir && spec.name == pointerName
      } ?: throw missingContentFile(
        "pack '${manifest.slug}' add-on '${selection.slug}' slot '$slot': '$pointerName' is not declared in " +
          "platform.yaml pointers for '$skillRelativeDir'",
      )
    val packRoot = manifest.packRoot.toPath().toAbsolutePath().normalize()
    val repoRoot =
      packRoot.parent?.takeIf { parent -> parent.fileName.toString() == "platform-packs" }?.parent
        ?: packRoot
    val candidate = repoRoot.resolve(pointer.target).normalize()
    require(!Files.isSymbolicLink(candidate)) {
      "Platform pack '${manifest.slug}' declares a symbolic add-on '$pointerName'."
    }
    return candidate.toRealPath().also { path ->
      val addonsRoot = packRoot.resolve("addons").toRealPath()
      require(path.startsWith(addonsRoot) && Files.isRegularFile(path)) {
        "Platform pack '${manifest.slug}' declares an unreadable or escaping add-on '$pointerName'."
      }
    }
  }

  private fun readBounded(path: Path): String =
    Files.readString(path).also { body ->
      require(body.toByteArray().size <= MAX_ADDON_FILE_BYTES) {
        "Selected add-on '${path.fileName}' is larger than $MAX_ADDON_FILE_BYTES bytes."
      }
    }

  private companion object {
    const val ENTRYPOINT_SLOT = "entrypoint"
    const val MAX_RUBRIC_BYTES = 256 * 1024L
    const val MAX_ADDON_FILE_BYTES = 128 * 1024L
    const val MAX_ADDON_BYTES = 256 * 1024L
    const val GENERIC_RUBRIC_ID = "parallel-code-review"
    const val GENERIC_RUBRIC =
      "Find concrete correctness, security, reliability, performance, and test gaps in the assigned change."
  }
}
