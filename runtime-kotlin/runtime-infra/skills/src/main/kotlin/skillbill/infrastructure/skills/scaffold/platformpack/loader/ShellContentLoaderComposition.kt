
package skillbill.infrastructure.skills.scaffold.platformpack.loader

import skillbill.model.toPath
import skillbill.review.model.CodeReviewBaselineLayer
import skillbill.review.model.CodeReviewCompositionMode
import skillbill.review.plan.ReviewFallbackResolver
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files

internal fun validatePlatformPackCompositions(
  packs: List<PlatformManifest>,
  packsBySlug: Map<String, PlatformManifest> = packs.associateBy { it.slug },
) {
  packs
    .filter { it.codeReviewComposition != null }
    .forEach { pack -> validateCompositionReferences(pack, packsBySlug) }
  validateNoCompositionCycles(packs)
  packs
    .filter { it.codeReviewComposition != null }
    .forEach(::validateCompositionModeSupported)
  packs
    .filter { it.codeReviewComposition != null }
    .forEach { root ->
      ReviewLaunchPlanPolicy.flatten(
        routedSlug = root.slug,
        manifests = packs,
        selectedAreas = ReviewLaunchPlanPolicy.composedAreas(root.slug, packs),
      )
    }
}

internal fun loadCompositionClosure(
  rootPack: PlatformManifest,
  packsBySlug: Map<String, PlatformManifest> = emptyMap(),
): List<PlatformManifest> {
  val loaded = linkedMapOf(rootPack.slug to rootPack)
  val catalog = packsBySlug

  fun collect(pack: PlatformManifest) {
    pack.codeReviewComposition?.baselineLayers.orEmpty().forEach { layer ->
      if (layer.platform in loaded) {
        return@forEach
      }
      val targetPack = catalog[layer.platform] ?: siblingPack(rootPack, layer.platform) ?: return@forEach
      loaded[targetPack.slug] = targetPack
      collect(targetPack)
    }
  }

  collect(rootPack)
  return loaded.values.toList()
}

private fun siblingPack(
  rootPack: PlatformManifest,
  slug: String,
): PlatformManifest? {
  val packParent = rootPack.packRoot.toPath().parent
  return if (packParent == null || !Files.isDirectory(packParent)) {
    null
  } else {
    val targetRoot = packParent.resolve(slug)
    if (Files.isRegularFile(targetRoot.resolve("platform.yaml"))) {
      loadPlatformManifest(targetRoot)
    } else {
      null
    }
  }
}

internal fun validateCompositionReferences(
  pack: PlatformManifest,
  packsBySlug: Map<String, PlatformManifest>,
) {
  val seenTargets = mutableSetOf<Pair<String, String>>()
  pack.codeReviewComposition?.baselineLayers.orEmpty().forEachIndexed { index, layer ->
    val targetLabel = "${layer.platform}/${layer.skill}"
    if (layer.platform == pack.slug) {
      invalidManifestSchema(
        "Platform pack '${pack.slug}': code_review_composition.baseline_layers[$index] self-references " +
          "the same platform pack '$targetLabel'.",
      )
    }
    if (!seenTargets.add(layer.platform to layer.skill)) {
      invalidManifestSchema(
        "Platform pack '${pack.slug}': duplicate code_review_composition baseline layer '$targetLabel'.",
      )
    }
    val targetPack = packsBySlug[layer.platform]
    if (targetPack == null) {
      if (ReviewFallbackResolver.resolveOptional(packsBySlug.values.toList()) == null) {
        invalidManifestSchema(
          "Platform pack '${pack.slug}': code_review_composition.baseline_layers[$index] references " +
            "missing platform pack '${layer.platform}'.",
        )
      }
      return@forEachIndexed
    }
    if (layer.skill !in targetPack.declaredCodeReviewSkillNames()) {
      invalidManifestSchema(
        "Platform pack '${pack.slug}': code_review_composition.baseline_layers[$index] references " +
          "missing code-review skill '${layer.skill}' in platform pack '${layer.platform}'.",
      )
    }
  }
}

internal fun validateCompositionModeSupported(pack: PlatformManifest) {
  pack.codeReviewComposition?.baselineLayers.orEmpty().forEachIndexed { index, layer ->
    validateCompositionModeSupported(pack.slug, index, layer)
  }
}

internal fun validateCompositionModeSupported(
  sourceSlug: String,
  index: Int,
  layer: CodeReviewBaselineLayer,
) {
  val unsupportedReason = unsupportedCompositionModeReason(layer)
  if (unsupportedReason != null) {
    invalidManifestSchema(
      "Platform pack '$sourceSlug': code_review_composition.baseline_layers[$index] uses mode " +
        "'${layer.mode.wireValue}' with unsupported referenced skill '${layer.platform}/${layer.skill}'. " +
        unsupportedReason,
    )
  }
}

internal fun unsupportedCompositionModeReason(layer: CodeReviewBaselineLayer): String? =
  when (layer.mode) {
    CodeReviewCompositionMode.KmpBaseline ->
      if (layer.skill == "bill-${layer.platform}-code-review") {
        null
      } else {
        "Mode '${layer.mode.wireValue}' requires the referenced pack's baseline code-review skill."
      }
  }

internal fun validateNoCompositionCycles(packs: List<PlatformManifest>) {
  val graph: Map<String, List<String>> =
    packs.associate { pack ->
      pack.slug to pack.codeReviewComposition?.baselineLayers.orEmpty().map { layer -> layer.platform }
    }
  val visited = mutableSetOf<String>()
  val visiting = mutableSetOf<String>()
  val stack = mutableListOf<String>()

  fun visit(slug: String) {
    if (slug in visited) return
    if (slug in visiting) {
      val cycleStart = stack.indexOf(slug).coerceAtLeast(0)
      val cycle = (stack.drop(cycleStart) + slug).joinToString(" -> ")
      invalidManifestSchema(
        "Platform pack '$slug': code_review_composition contains a composition cycle: $cycle.",
      )
    }

    visiting += slug
    stack += slug
    graph.getValue(slug)
      .filter { target -> target in graph }
      .forEach(::visit)
    stack.removeAt(stack.lastIndex)
    visiting -= slug
    visited += slug
  }

  graph.keys.sorted().forEach(::visit)
}

fun PlatformManifest.declaredCodeReviewSkillNames(): Set<String> {
  val names = linkedSetOf<String>()
  routedSkillName?.let(names::add)
  declaredFiles.baseline?.toPath()?.parent?.fileName?.toString()
    ?.takeIf { it != "code-review" }
    ?.let(names::add)
  declaredCodeReviewAreas.forEach { area ->
    names += "bill-$slug-code-review-$area"
    declaredFiles.areas[area]?.toPath()?.parent?.fileName?.toString()
      ?.takeIf { it != "code-review" }
      ?.let(names::add)
  }
  return names
}
