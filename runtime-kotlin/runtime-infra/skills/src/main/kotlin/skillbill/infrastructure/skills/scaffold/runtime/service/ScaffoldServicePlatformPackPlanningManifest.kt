package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.review.model.CodeReviewBaselineLayer
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_PACK
import java.nio.file.Path
import skillbill.infrastructure.skills.scaffold.payload.requireStringOrDefaultMap as requireStringOrDefault
import skillbill.scaffold.policy.platformpack.buildPlatformPackInstallPaths as policyBuildPlatformPackInstallPaths

internal data class PlatformPackScaffoldPlanBodyArgs(
  val scaffold: PlatformPackScaffoldPlanArgs,
  val baselineName: String,
  val baselineLayers: List<CodeReviewBaselineLayer>,
  val specialistPlan: PlatformPackSpecialistPlan,
  val notes: List<String>,
)

internal data class PlatformPackSpecialistPlan(
  val names: Map<String, String>,
  val paths: Map<String, Path>,
  val metadata: Map<String, String>,
  val subagents: List<String>,
  val subagentDescriptions: Map<String, String>,
)

internal fun platformPackSpecialistPlan(
  args: PlatformPackScaffoldPlanArgs,
  selectedAreas: List<String>,
): PlatformPackSpecialistPlan {
  val specialistNames = selectedAreas.associateWith { area -> "bill-${args.platform}-code-review-$area" }
  val specialistPaths =
    selectedAreas.associateWith { area ->
      args.packRoot.resolve("code-review").resolve(specialistNames.getValue(area))
    }
  val specialistMetadata =
    selectedAreas.associateWith { area ->
      specialistFocus(args.defaults.displayName, area, args.defaults.strongSignals)
    }
  val platformPackSubagents = selectedAreas.map { area -> specialistNames.getValue(area) }
  val platformPackSubagentDescriptions =
    selectedAreas.associate { area ->
      val specialistName = specialistNames.getValue(area)
      val description =
        "${args.defaults.displayName} ${area.replace('-', ' ')} specialist — " +
          "${specialistMetadata.getValue(area)}."
      specialistName to description
    }
  return PlatformPackSpecialistPlan(
    names = specialistNames,
    paths = specialistPaths,
    metadata = specialistMetadata,
    subagents = platformPackSubagents,
    subagentDescriptions = platformPackSubagentDescriptions,
  )
}

internal fun platformPackScaffoldPlanBody(args: PlatformPackScaffoldPlanBodyArgs): ScaffoldPlan =
  ScaffoldPlan(
    kind = SKILL_KIND_PLATFORM_PACK,
    skillName = args.baselineName,
    skillPath = args.scaffold.packRoot,
    skillFile = args.scaffold.packRoot.resolve("code-review").resolve(args.baselineName).resolve("SKILL.md"),
    contentFile = args.scaffold.packRoot.resolve("code-review").resolve(args.baselineName).resolve("content.md"),
    family = "code-review",
    platform = args.scaffold.platform,
    area = "",
    isShelled = true,
    notes = args.notes,
    displayName = args.scaffold.defaults.displayName,
    description = requireStringOrDefault(args.scaffold.payload, "description", ""),
    manifestPath = args.scaffold.packRoot.resolve("platform.yaml"),
    routingSignals = args.scaffold.defaults.strongSignals,
    tieBreakers = args.scaffold.defaults.tieBreakers,
    specialistAreas = args.specialistPlan.names.keys.toList(),
    specialistAreaMetadata = args.specialistPlan.metadata,
    specialistSkillNames = args.specialistPlan.names,
    specialistSkillPaths = args.specialistPlan.paths,
    baselineSkillName = args.baselineName,
    baselineSkillPath = args.scaffold.packRoot.resolve("code-review").resolve(args.baselineName),
    installPaths =
      policyBuildPlatformPackInstallPaths(
        packRoot = args.scaffold.packRoot.toFileLocation(),
        baselineName = args.baselineName,
        specialistPaths = args.specialistPlan.paths.mapValues { (_, entry) -> entry.toFileLocation() },
        selectedAreas = args.specialistPlan.names.keys.toList(),
      ).map { entry -> entry.toPath() },
    contentBody = args.scaffold.payload["content_body"] as? String,
    baselineLayers = args.baselineLayers,
    subagentSpecialists = args.specialistPlan.subagents,
    subagentDescriptions = args.specialistPlan.subagentDescriptions,
    subagentsSuppressed = false,
  )
