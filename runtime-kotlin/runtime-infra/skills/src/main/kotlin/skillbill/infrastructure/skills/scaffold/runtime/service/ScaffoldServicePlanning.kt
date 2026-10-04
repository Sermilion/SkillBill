package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.error.shellcontent.invalidScaffoldPayloadError
import skillbill.error.shellcontent.missingPlatformPackError
import skillbill.error.shellcontent.skillAlreadyExistsError
import skillbill.error.shellcontent.unknownSkillKindError
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.externalplatformpack.resolveExternalPlatformPackSourcePath
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.rendering.defaultAreaFocus
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.APPROVED_CODE_REVIEW_AREAS
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELLED_FAMILIES
import skillbill.infrastructure.skills.scaffold.runtime.service.externalpack.registerPlannedExternalPlatformPack
import skillbill.ports.system.HostPlatformPort
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.policy.sharedContractNote
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import skillbill.infrastructure.skills.scaffold.payload.optionalSpecialistSubagents as policyOptionalSpecialistSubagents
import skillbill.infrastructure.skills.scaffold.payload.rejectBaselineLayersForNonPlatformPack as policyRejectBaselineLayersForNonPlatformPack
import skillbill.infrastructure.skills.scaffold.payload.rejectLeafSubagentSpecialists as policyRejectLeafSubagentSpecialists
import skillbill.infrastructure.skills.scaffold.payload.requireStringMap as requireString
import skillbill.infrastructure.skills.scaffold.payload.requireStringOrDefaultMap as requireStringOrDefault
import skillbill.infrastructure.skills.scaffold.payload.resolvePlatformPackDefaults as policyResolvePlatformPackDefaults

internal fun executeScaffold(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
  runtime: ScaffoldRuntimeContext = ScaffoldRuntimeContext(resolveUserHome(null)),
): ScaffoldExecutionResult {
  val execution =
    when {
      plan.kind == SKILL_KIND_PLATFORM_PACK && plan.externalPackRegistrationMode == PACK_REGISTRATION_REGISTER ->
        ScaffoldExecutionResult(
          createdFiles = emptyList(),
          manifestEdits = emptyList(),
          symlinks = emptyList(),
          installTargets = emptyList(),
          notes = listOf("Registered existing external platform pack without rewriting pack files."),
        )
      plan.kind == SKILL_KIND_PLATFORM_PACK -> createPlatformPack(txn, plan, repoRoot)
      else -> stageSingleScaffold(txn, plan, repoRoot)
    }
  registerPlannedExternalPlatformPack(plan, txn, repoRoot, runtime)
  adapters.validateScaffold(plan, repoRoot)
  val (installTargets, installNotes) = performInstall(txn, plan, repoRoot, adapters)
  return execution.copy(
    installTargets = installTargets,
    notes = execution.notes + installNotes + subagentEmissionNotes(plan),
  )
}

internal fun resolveRepoRoot(
  payload: Map<String, Any?>,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
): Path {
  val repoRootRaw = payload["repo_root"] as? String ?: return defaultRepoRoot(hostPlatform)
  if (repoRootRaw.isBlank()) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field 'repo_root' must be a non-empty string when provided.",
    )
  }
  return Path.of(repoRootRaw).toAbsolutePath().normalize()
}

internal fun planScaffold(
  payload: Map<String, Any?>,
  repoRoot: Path,
  kind: String,
  adapters: ScaffoldAdapterSeams,
  userHome: Path = resolveUserHome(null),
): ScaffoldPlan =
  when (kind) {
    SKILL_KIND_HORIZONTAL -> {
      policyRejectBaselineLayersForNonPlatformPack(payload, kind)
      planHorizontal(payload, repoRoot)
    }
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED -> {
      policyRejectBaselineLayersForNonPlatformPack(payload, kind)
      planPlatformOverridePiloted(payload, repoRoot)
    }
    SKILL_KIND_PLATFORM_PACK -> planPlatformPack(payload, repoRoot, adapters, userHome)
    SKILL_KIND_CODE_REVIEW_AREA -> {
      policyRejectBaselineLayersForNonPlatformPack(payload, kind)
      planCodeReviewArea(payload, repoRoot)
    }
    SKILL_KIND_ADD_ON -> {
      policyRejectBaselineLayersForNonPlatformPack(payload, kind)
      planAddOn(payload, repoRoot, adapters)
    }
    SKILL_KIND_AGENT_ADDON -> {
      policyRejectBaselineLayersForNonPlatformPack(payload, kind)
      planAgentAddon(payload, repoRoot)
    }
    else -> throw unknownSkillKindError("Scaffold payload declares unsupported kind '$kind'.")
  }

internal fun planHorizontal(
  payload: Map<String, Any?>,
  repoRoot: Path,
): ScaffoldPlan {
  val name = requireString(payload, "name")
  val skillPath = repoRoot.resolve("skills").resolve(name)
  val subagents = policyOptionalSpecialistSubagents(payload, SKILL_KIND_HORIZONTAL)
  return ScaffoldPlan(
    kind = SKILL_KIND_HORIZONTAL,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = "horizontal",
    platform = "",
    area = "",
    isShelled = false,
    notes = emptyList(),
    description = requireStringOrDefault(payload, "description", ""),
    contentBody = payload["content_body"] as? String,
    subagentSpecialists = subagents.specialists,
    subagentsSuppressed = subagents.suppressed,
  )
}

internal fun planPlatformOverridePiloted(
  payload: Map<String, Any?>,
  repoRoot: Path,
): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  val family = requireString(payload, "family")
  val name = canonicalName(payload, defaultName = defaultPlatformOverrideName(platform, family))
  val subagents = policyOptionalSpecialistSubagents(payload, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED)
  val isShelled = family in SHELLED_FAMILIES
  if (!isShelled) {
    return planPreShellPlatformOverride(
      ScaffoldPlatformOverridePlanArgs(payload, repoRoot, platform, family, name, subagents),
    )
  }
  return planShelledPlatformOverride(
    ScaffoldPlatformOverridePlanArgs(payload, repoRoot, platform, family, name, subagents),
  )
}

internal fun planPlatformPack(
  payload: Map<String, Any?>,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
  userHome: Path = resolveUserHome(null),
): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  rejectPlatformPackSubagentOverrides(payload)
  val defaults = policyResolvePlatformPackDefaults(payload, platform)
  val registration = normalizeExternalPackRegistration(payload["pack_registration"])
  val externalRoot = (payload["pack_location_path"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
  val packRoot =
    if (externalRoot != null) {
      resolveExternalPlatformPackSourcePath(userHome, externalRoot)
    } else {
      repoRoot.resolve("platform-packs").resolve(platform)
    }
  if (externalRoot != null && registration == PACK_REGISTRATION_REGISTER) {
    requireExistingExternalPack(packRoot, platform)
  } else if (Files.exists(packRoot, LinkOption.NOFOLLOW_LINKS)) {
    throw skillAlreadyExistsError(
      "Platform pack target '$packRoot' already exists. " +
        "Remove it or pick a new platform slug before retrying.",
    )
  }
  val plan =
    buildPlatformPackScaffoldPlan(
      PlatformPackScaffoldPlanArgs(payload, repoRoot, adapters, platform, defaults, packRoot),
    )
  if (externalRoot == null) {
    return plan
  }
  return plan.copy(
    externalPackRoot = packRoot,
    externalPackRegistrationMode = registration,
    notes = plan.notes + "Planned external platform pack registration at '$packRoot'.",
  )
}

internal const val PACK_REGISTRATION_CREATE: String = "create"
internal const val PACK_REGISTRATION_REGISTER: String = "register"

internal fun normalizeExternalPackRegistration(raw: Any?): String {
  val value = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: PACK_REGISTRATION_CREATE
  if (value != PACK_REGISTRATION_CREATE && value != PACK_REGISTRATION_REGISTER) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field 'pack_registration' must be 'create' or 'register'.",
    )
  }
  return value
}

private fun requireExistingExternalPack(
  packRoot: Path,
  platform: String,
) {
  val manifestPath = packRoot.resolve("platform.yaml")
  if (!Files.isDirectory(packRoot, LinkOption.NOFOLLOW_LINKS) ||
    !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
  ) {
    throw invalidScaffoldPayloadError(
      "pack_registration register requires an existing platform pack directory at '$packRoot'.",
    )
  }
  val declared = loadPlatformPack(packRoot).slug
  if (declared != platform) {
    throw invalidScaffoldPayloadError(
      "pack_registration register found platform '$declared' at '$packRoot', expected '$platform'.",
    )
  }
}

internal fun rejectPlatformPackSubagentOverrides(payload: Map<String, Any?>) {
  val field = listOf("subagent_specialists", "no_subagents").firstOrNull(payload::containsKey) ?: return
  throw invalidScaffoldPayloadError(
    "Scaffold payload field '$field' is not supported for kind 'platform-pack'; " +
      "the review structure standard requires exactly one manifest-derived native agent per declared specialist.",
  )
}

internal fun specialistFocus(
  displayName: String,
  area: String,
  routingSignals: List<String>,
): String = "$displayName ${defaultAreaFocus(area)} across ${routingSignals.joinToString(", ")} signals"

internal fun planCodeReviewArea(
  payload: Map<String, Any?>,
  repoRoot: Path,
): ScaffoldPlan {
  policyRejectLeafSubagentSpecialists(payload, SKILL_KIND_CODE_REVIEW_AREA)
  val platform = requireString(payload, "platform")
  val area = requireString(payload, "area")
  if (area !in APPROVED_CODE_REVIEW_AREAS) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload declares code-review area '$area' that is not in the approved set $APPROVED_CODE_REVIEW_AREAS.",
    )
  }
  val name = canonicalName(payload, defaultName = "bill-$platform-code-review-$area")
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  val manifestPath = packRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw missingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a code-review area to it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val skillPath = packRoot.resolve("code-review").resolve(name)
  return ScaffoldPlan(
    kind = SKILL_KIND_CODE_REVIEW_AREA,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = "code-review",
    platform = platform,
    area = area,
    isShelled = true,
    notes = listOf(sharedContractNote()),
    displayName = pack.displayName ?: deriveDisplayName(platform),
    description = requireStringOrDefault(payload, "description", ""),
    contentBody = payload["content_body"] as? String,
  )
}
