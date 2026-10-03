package skillbill.infrastructure.skills.scaffold.adapters

import java.nio.file.Files
import java.nio.file.Path
import skillbill.error.shellcontent.InvalidScaffoldPayloadError
import skillbill.error.shellcontent.missingRequiredSection
import skillbill.infrastructure.skills.agentaddon.discoverAgentAddons
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringTarget
import skillbill.infrastructure.skills.scaffold.authoring.validateTarget
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.declaredCodeReviewSkillNames
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.platformpack.loader.unsupportedCompositionModeReason
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldPlan
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.CONTENT_BODY_FILENAME
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.displayNameFromSlug
import skillbill.model.EnvironmentContext
import skillbill.ports.scaffold.repo.model.ScaffoldAuthoringValidationRequest
import skillbill.ports.scaffold.repo.model.ScaffoldAuthoringValidationResult
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.policy.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.parseBaselineLayerPayload as policyParseBaselineLayerPayload

class FileSystemScaffoldRepoValidation(
  private val environmentContext: EnvironmentContext? = null,
  private val catalogLoader: PlatformPackCatalogLoader? = null,
) {
  fun validateAuthoringTarget(request: ScaffoldAuthoringValidationRequest): ScaffoldAuthoringValidationResult {
    val target =
      AuthoringTarget(
        skillName = request.skillName,
        packageName = request.packageName,
        platform = request.platform,
        displayName = request.displayName,
        family = request.family,
        area = request.area,
        skillFile = request.skillFile,
        contentFile = request.contentFile,
      )
    val issues = validateTarget(target, request.repoRoot)
    return ScaffoldAuthoringValidationResult(issues = issues)
  }

  internal fun optionalBaselineLayers(
    payload: Map<String, Any?>,
    repoRoot: Path,
    newPlatform: String,
  ): List<CodeReviewBaselineLayer> {
    val raw = payload["baseline_layers"] ?: return emptyList()
    if (raw !is List<*>) {
      failBaselineLayersNotAList()
    }
    if (raw.isEmpty()) {
      failBaselineLayersEmpty()
    }
    val layers = raw.mapIndexed { index, entry -> policyParseBaselineLayerPayload(index, entry) }
    validateBaselineLayerPayloadReferences(layers, repoRoot, newPlatform)
    return layers
  }

  internal fun validateBaselineLayerPayloadReferences(
    layers: List<CodeReviewBaselineLayer>,
    repoRoot: Path,
    newPlatform: String,
  ) {
    val seenTargets = mutableSetOf<Pair<String, String>>()
    layers.forEachIndexed { index, layer ->
      val targetLabel = "${layer.platform}/${layer.skill}"
      if (layer.platform == newPlatform) {
        failBaselineSelfReference(index, targetLabel)
      }
      if (!seenTargets.add(layer.platform to layer.skill)) {
        failBaselineDuplicate(targetLabel)
      }
      val targetRoot = repoRoot.resolve("platform-packs").resolve(layer.platform)
      if (!Files.isDirectory(targetRoot) || !Files.isRegularFile(targetRoot.resolve("platform.yaml"))) {
        failBaselineMissingPack(index, layer.platform)
      }
      val targetPack = loadPlatformPack(targetRoot)
      if (layer.skill !in targetPack.declaredCodeReviewSkillNames()) {
        failBaselineMissingSkill(index, layer.platform, layer.skill)
      }
      validateBaselineLayerModeSupported(index, layer)
    }
  }

  internal fun validateScaffold(
    plan: ScaffoldPlan,
    repoRoot: Path,
  ) {
    if (plan.kind == SKILL_KIND_AGENT_ADDON) {
      discoverAgentAddons(repoRoot)
      return
    }
    if (plan.kind == SKILL_KIND_HORIZONTAL) {
      val issues = validateTarget(plannedAuthoringTarget(plan), repoRoot)
      if (issues.isNotEmpty()) {
        failMissingRequiredSection(plan.skillName, issues.first())
      }
      return
    }
    val packRoot =
      plan.manifestPath?.parent
        ?: repoRoot.resolve("platform-packs").resolve(plan.platform)
    val catalog =
      if (plan.externalPackRoot != null && environmentContext != null && catalogLoader != null) {
        catalogLoader.loadEffectiveCatalog(
          PlatformPackDiscoveryContext(
            repoRoot = repoRoot,
            userHome = environmentContext.userHome,
            environment = environmentContext.environment,
            catalogLoader = catalogLoader,
          ),
        ).manifestsBySlug
      } else {
        emptyMap()
      }
    loadPlatformPack(packRoot, catalog)
  }

  internal fun plannedAuthoringTarget(plan: ScaffoldPlan): AuthoringTarget =
    AuthoringTarget(
      skillName = plan.skillName,
      packageName = plan.platform.ifBlank { "base" },
      platform = plan.platform,
      displayName = plan.displayName.ifBlank { displayNameFromSlug(plan.skillName.removePrefix("bill-")) },
      family = plan.family,
      area = plan.area,
      skillFile = plan.skillFile,
      contentFile = plan.contentFile ?: plan.skillPath.resolve(CONTENT_BODY_FILENAME),
    )

  private fun validateBaselineLayerModeSupported(
    index: Int,
    layer: CodeReviewBaselineLayer,
  ) {
    val unsupportedReason = unsupportedCompositionModeReason(layer)
    if (unsupportedReason != null) {
      failBaselineUnsupportedMode(index, layer, unsupportedReason)
    }
  }
}

private fun failBaselineLayersNotAList(): Nothing =
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'baseline_layers' must be a list of baseline layer objects.",
  )

private fun failBaselineLayersEmpty(): Nothing =
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'baseline_layers' must contain at least one layer when provided.",
  )

private fun failBaselineSelfReference(
  index: Int,
  targetLabel: String,
): Nothing =
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'baseline_layers[$index]' self-references the new platform pack '$targetLabel'.",
  )

private fun failBaselineDuplicate(targetLabel: String): Nothing =
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'baseline_layers' contains duplicate layer '$targetLabel'.",
  )

private fun failBaselineMissingPack(
  index: Int,
  platform: String,
): Nothing =
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'baseline_layers[$index]' references missing platform pack '$platform'.",
  )

private fun failBaselineMissingSkill(
  index: Int,
  platform: String,
  skill: String,
): Nothing =
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'baseline_layers[$index]' references missing code-review skill " +
      "'$skill' in platform pack '$platform'.",
  )

private fun failBaselineUnsupportedMode(
  index: Int,
  layer: CodeReviewBaselineLayer,
  unsupportedReason: String,
): Nothing =
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'baseline_layers[$index].mode' uses mode '${layer.mode.wireValue}' with " +
      "unsupported referenced skill '${layer.platform}/${layer.skill}'. $unsupportedReason",
  )

private fun failMissingRequiredSection(
  skillName: String,
  firstIssue: String,
): Nothing = throw missingRequiredSection("Horizontal skill '$skillName': $firstIssue")
