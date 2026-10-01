
package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.infrastructure.skills.scaffold.rendering.renderContentBody
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestContentRenderRequest
import java.nio.file.Path
import skillbill.scaffold.policy.platformpack.renderPlatformPackManifestContent as renderPackManifest

internal fun renderPlatformPackManifestContent(
  plan: ScaffoldPlan,
  repoRoot: Path,
  baselineSkillPath: Path,
): String {
  val packRoot = plan.manifestPath?.parent ?: repoRoot.resolve("platform-packs").resolve(plan.platform)
  return renderPackManifest(
    PlatformPackManifestContentRenderRequest(
      platform = plan.platform,
      displayName = plan.displayName,
      routingSignals = plan.routingSignals,
      tieBreakers = plan.tieBreakers,
      specialistAreas = plan.specialistAreas,
      specialistAreaMetadata = plan.specialistAreaMetadata,
      baselineLayers = plan.baselineLayers,
      packRoot = packRoot.toFileLocation(),
      baselineSkillPath = baselineSkillPath.toFileLocation(),
      specialistSkillPaths = plan.specialistSkillPaths.mapValues { (_, entry) -> entry.toFileLocation() },
    ),
  )
}

internal fun stagePlatformPackSkills(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  baselineSkillPath: Path,
): List<Path> {
  val symlinks = mutableListOf<Path>()
  val baselineContext =
    TemplateContext(plan.baselineSkillName, "code-review", plan.platform, "", plan.displayName)
  val baselineDescription =
    if (plan.description.isNotBlank()) {
      plan.description
    } else {
      "Use when reviewing changes in ${plan.displayName} codebases."
    }
  stageFile(
    txn,
    baselineSkillPath.resolve("content.md"),
    renderContentBody(baselineContext, baselineDescription, internalFor = PACK_SIDECAR_PARENT_SKILL),
  )

  plan.specialistAreas.forEach { area ->
    symlinks.addAll(stagePlatformPackArea(txn, plan, area))
  }
  return symlinks
}

internal fun stagePlatformPackArea(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  area: String,
): List<Path> {
  val areaPath = plan.specialistSkillPaths.getValue(area)
  val areaName = plan.specialistSkillNames.getValue(area)
  val areaContext = TemplateContext(areaName, "code-review", plan.platform, area, plan.displayName)
  val areaDescription = "Use when reviewing ${plan.displayName} changes for $area risks."
  stageFile(
    txn,
    areaPath.resolve("content.md"),
    renderContentBody(areaContext, areaDescription, internalFor = PACK_SIDECAR_PARENT_SKILL),
  )
  return emptyList()
}

internal fun previewPlatformPackCreatedFiles(plan: ScaffoldPlan): List<Path> =
  buildList {
    plan.manifestPath?.let(::add)
    plan.baselineSkillPath?.let {
      add(it.resolve("content.md"))
    }
    plan.specialistSkillPaths.values.forEach { path ->
      add(path.resolve("content.md"))
    }
  }

internal fun previewSubagentStubFiles(plan: ScaffoldPlan): List<Path> {
  if (!plan.shouldEmitSubagents()) {
    return emptyList()
  }
  val stubDir =
    if (plan.kind == SKILL_KIND_PLATFORM_PACK) {
      plan.baselineSkillPath ?: return emptyList()
    } else {
      plan.skillPath
    }
  return listOf(stubDir.resolve("native-agents").resolve("agents.yaml"))
}

internal fun subagentEmissionNotes(plan: ScaffoldPlan): List<String> {
  if (!plan.shouldEmitSubagents()) {
    return emptyList()
  }
  val stubDir =
    if (plan.kind == SKILL_KIND_PLATFORM_PACK) {
      plan.baselineSkillPath ?: plan.skillPath
    } else {
      plan.skillPath
    }
  if (plan.kind == SKILL_KIND_PLATFORM_PACK && plan.bodyBasedSubagents.isEmpty()) {
    return listOf(
      "Subagent bundle emitted: ${plan.subagentSpecialists.size} entries. " +
        "Native agents compose from the generated code-review content.md files; " +
        "fill in those content.md files before shipping.",
    )
  }
  return listOf(
    "Subagent bundle emitted: ${plan.subagentSpecialists.size} entries. " +
      "Fill in the TODO placeholders in $stubDir/native-agents/agents.yaml before shipping; " +
      "install renders provider artifacts.",
  )
}

internal fun ScaffoldPlan.shouldEmitSubagents(): Boolean = subagentSpecialists.isNotEmpty() && !subagentsSuppressed

internal fun ScaffoldPlan.isExternalAddon(): Boolean = kind == SKILL_KIND_ADD_ON && externalAddonLocationPath != null

internal fun ScaffoldPlan.externalAddonManifestPath(): Path =
  externalAddonLocationPath?.resolve("addon-manifest.yaml")
    ?: error("External add-on plan is missing addon_location_path.")
