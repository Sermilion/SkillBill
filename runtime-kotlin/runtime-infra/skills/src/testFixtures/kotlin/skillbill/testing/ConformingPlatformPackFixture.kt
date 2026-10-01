package skillbill.testing

import skillbill.infrastructure.skills.scaffold.platformpack.loader.CODE_REVIEW_FALLBACK_CAPABILITY
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.rendering.areaReviewContent
import skillbill.infrastructure.skills.scaffold.rendering.baselineReviewContent
import skillbill.infrastructure.skills.scaffold.rendering.renderFrontmatter
import skillbill.install.model.InstallPlan
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

internal fun assertConcreteAndManifestFallbackSelected(
  plan: InstallPlan,
  concreteSlug: String,
  additionalSlugs: Set<String> = emptySet(),
) {
  val fallbackSlugs =
    plan.discoveredPlatformPacks
      .filter { pack ->
        CODE_REVIEW_FALLBACK_CAPABILITY in loadPlatformPack(pack.packRoot.toPath()).fallbackCapabilities
      }
      .map { it.slug }
  assertEquals(1, fallbackSlugs.size)
  assertEquals((fallbackSlugs + concreteSlug + additionalSlugs).sorted(), plan.selectedPlatformSlugs)
}

fun seedConformingPlatformPack(
  repoRoot: Path,
  slug: String,
  areaNames: List<String> = listOf("architecture"),
  baselinePointerTarget: String? = null,
) {
  require(areaNames.isNotEmpty()) { "A conforming review pack must declare at least one specialist area." }
  val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
  val baselineName = "bill-$slug-code-review"
  val areaSkillNames = areaNames.associateWith { area -> "bill-$slug-code-review-$area" }
  val baselineDir = packRoot.resolve("code-review").resolve(baselineName)
  Files.createDirectories(baselineDir.resolve("native-agents"))
  areaSkillNames.values.forEach { skillName -> Files.createDirectories(packRoot.resolve("code-review/$skillName")) }

  val focuses = areaNames.associateWith { area -> "$slug $area boundary APIs and failure modes" }
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    conformingManifest(slug, areaSkillNames, focuses, baselinePointerTarget),
  )

  Files.writeString(
    baselineDir.resolve("content.md"),
    governedContent(
      baselineName,
      "Test $slug baseline review.",
      PACK_SIDECAR_PARENT_SKILL,
      baselineReviewContent("Review $slug changes."),
    ),
  )
  areaSkillNames.forEach { (area, skillName) ->
    Files.writeString(
      packRoot.resolve("code-review/$skillName/content.md"),
      governedContent(
        skillName,
        "Test $slug $area review.",
        PACK_SIDECAR_PARENT_SKILL,
        areaReviewContent("Review ${focuses.getValue(area)}.", area, slug),
      ),
    )
  }
  Files.writeString(
    baselineDir.resolve("native-agents/agents.yaml"),
    conformingNativeAgents(slug, areaNames, focuses),
  )
}

private fun conformingManifest(
  slug: String,
  areaSkillNames: Map<String, String>,
  focuses: Map<String, String>,
  baselinePointerTarget: String?,
): String =
  buildString {
    val baselineName = "bill-$slug-code-review"
    appendLine("platform: \"$slug\"")
    appendLine("contract_version: \"1.8\"")
    appendLine("routing_signals:")
    appendLine("  strong: [\".$slug\", \"*.$slug\"]")
    appendLine("  tie_breakers:")
    appendLine("    - \"Prefer $slug when $slug source signals dominate the changed product surface.\"")
    appendLine("    - \"Do not prefer $slug when an adjacent pack's declared signals dominate.\"")
    appendLine("    - \"Exclude generated and vendored files from dominance scoring.\"")
    appendLine("declared_code_review_areas:")
    areaSkillNames.keys.forEach { area -> appendLine("  - $area") }
    appendLine("declared_files:")
    appendLine("  baseline: \"code-review/$baselineName/content.md\"")
    appendLine("  areas:")
    areaSkillNames.forEach { (area, skillName) -> appendLine("    $area: \"code-review/$skillName/content.md\"") }
    appendLine("area_metadata:")
    focuses.forEach { (area, focus) ->
      appendLine("  $area:")
      appendLine("    focus: \"$focus\"")
    }
    appendLine("display_name: \"$slug\"")
    appendLine("pointers:")
    if (baselinePointerTarget == null) {
      appendLine("  code-review/$baselineName: []")
    } else {
      appendLine("  code-review/$baselineName:")
      appendLine("    - name: review-orchestrator.md")
      appendLine("      target: $baselinePointerTarget")
    }
    areaSkillNames.values.forEach { skillName -> appendLine("  code-review/$skillName: []") }
  }

private fun conformingNativeAgents(
  slug: String,
  areaNames: List<String>,
  focuses: Map<String, String>,
): String =
  buildString {
    appendLine("contract_version: \"0.1\"")
    appendLine("agents:")
    areaNames.forEach { area ->
      appendLine("  - name: bill-$slug-code-review-$area")
      appendLine(
        "    description: \"$slug ${area.replace('-', ' ')} specialist — ${focuses.getValue(area)}.\"",
      )
      appendLine("    compose: governed-content")
    }
  }

private fun governedContent(
  name: String,
  description: String,
  internalFor: String,
  body: String,
): String =
  buildString {
    append(renderFrontmatter(name, description, internalFor))
    appendLine()
    appendLine("# $name")
    appendLine()
    append(body)
  }
