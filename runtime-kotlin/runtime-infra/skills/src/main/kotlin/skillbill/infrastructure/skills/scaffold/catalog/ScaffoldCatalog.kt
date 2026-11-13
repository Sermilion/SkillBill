package skillbill.infrastructure.skills.scaffold.catalog
import skillbill.infrastructure.skills.scaffold.platformpack.loader.declaredCodeReviewSkillNames
import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.infrastructure.skills.scaffold.platformpack.loader.unsupportedCompositionModeReason
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.PRE_SHELL_FAMILIES
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELLED_FAMILIES
import skillbill.review.model.CodeReviewBaselineLayer
import skillbill.review.model.CodeReviewCompositionMode
import skillbill.review.model.CodeReviewCompositionScope
import skillbill.scaffold.model.BaselineReviewCatalog
import skillbill.scaffold.model.BaselineReviewCompositionEdge
import skillbill.scaffold.model.BaselineReviewLayerSuggestion
import skillbill.scaffold.model.BaselineReviewPackEntry
import skillbill.scaffold.model.BaselineReviewSkillEntry
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.PLATFORM_PACK_PRESETS
import skillbill.scaffold.policy.SCAFFOLD_PAYLOAD_VERSION
import java.nio.file.Path

object ScaffoldCatalog {
  val approvedCodeReviewAreas: Set<String>
    get() = APPROVED_CODE_REVIEW_AREAS

  val preShellFamilies: Set<String>
    get() = PRE_SHELL_FAMILIES

  val shelledFamilies: Set<String>
    get() = SHELLED_FAMILIES

  val platformPackPresets: Map<String, String>
    get() = PLATFORM_PACK_PRESETS

  val scaffoldPayloadVersion: String
    get() = SCAFFOLD_PAYLOAD_VERSION

  fun discoverPilotedPlatformPacks(packsRoot: Path): List<PlatformManifest> = discoverPlatformPackManifests(packsRoot)

  fun discoverBaselineReviewCatalog(packsRoot: Path): BaselineReviewCatalog {
    val packs = discoverPlatformPackManifests(packsRoot)
    return BaselineReviewCatalog(
      packs =
        packs
          .filter { pack -> pack.declaredFiles.baseline != null }
          .map { pack ->
            BaselineReviewPackEntry(
              platform = pack.slug,
              displayName = pack.displayName ?: pack.slug,
              strongRoutingSignals = pack.routingSignals.strong,
              skills =
                pack.declaredCodeReviewSkillNames()
                  .sorted()
                  .map { skill -> baselineSkillEntry(pack.slug, skill) },
            )
          }
          .sortedBy { pack -> pack.platform },
      compositionEdges =
        packs.flatMap { pack ->
          pack.codeReviewComposition?.baselineLayers.orEmpty().map { layer ->
            BaselineReviewCompositionEdge(
              sourcePlatform = pack.slug,
              targetPlatform = layer.platform,
              targetSkill = layer.skill,
            )
          }
        }.sortedWith(compareBy({ it.sourcePlatform }, { it.targetPlatform }, { it.targetSkill })),
      layerSuggestions = baselineLayerSuggestions(packs),
    )
  }
}

private const val KOTLIN_BASELINE_PLATFORM = "kotlin"
private const val KOTLIN_BASELINE_SKILL = "bill-kotlin-code-review"
private const val KMP_BASELINE_MODE = "kmp-baseline"
private const val SAME_REVIEW_SCOPE = "same-review-scope"

private val KMP_ANDROID_SUGGESTION_SIGNALS =
  listOf(
    "kmp",
    "android",
    "com.android",
    "kotlin-multiplatform",
    "multiplatform",
  )

private fun baselineLayerSuggestions(packs: List<PlatformManifest>): List<BaselineReviewLayerSuggestion> {
  val kotlinPack =
    packs.firstOrNull { pack ->
      pack.slug == KOTLIN_BASELINE_PLATFORM &&
        pack.declaredFiles.baseline != null &&
        KOTLIN_BASELINE_SKILL in pack.declaredCodeReviewSkillNames()
    }
  val kotlinSkill = kotlinPack?.let { pack -> baselineSkillEntry(pack.slug, KOTLIN_BASELINE_SKILL) }
  val supportsKmpBaseline =
    kotlinSkill != null &&
      KMP_BASELINE_MODE in kotlinSkill.supportedModes &&
      SAME_REVIEW_SCOPE in kotlinSkill.supportedScopes
  return if (supportsKmpBaseline) {
    listOf(
      BaselineReviewLayerSuggestion(
        label = "Kotlin baseline",
        triggerSignals = KMP_ANDROID_SUGGESTION_SIGNALS,
        platform = KOTLIN_BASELINE_PLATFORM,
        skill = KOTLIN_BASELINE_SKILL,
        scope = SAME_REVIEW_SCOPE,
        required = true,
        mode = KMP_BASELINE_MODE,
      ),
    )
  } else {
    emptyList()
  }
}

private fun baselineSkillEntry(
  platform: String,
  skill: String,
): BaselineReviewSkillEntry {
  val supportedModes =
    CodeReviewCompositionMode.entries
      .filter { mode ->
        unsupportedCompositionModeReason(
          CodeReviewBaselineLayer(
            platform = platform,
            skill = skill,
            scope = CodeReviewCompositionScope.SameReviewScope,
            required = true,
            mode = mode,
          ),
        ) == null
      }
      .map { mode -> mode.wireValue }
  val supportedScopes =
    if (supportedModes.isEmpty()) {
      emptyList()
    } else {
      CodeReviewCompositionScope.entries.map { scope -> scope.wireValue }
    }
  return BaselineReviewSkillEntry(
    name = skill,
    supportedModes = supportedModes,
    supportedScopes = supportedScopes,
  )
}
