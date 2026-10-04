package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.error.shellcontent.invalidScaffoldPayloadError
import skillbill.error.shellcontent.missingPlatformPackError
import skillbill.error.shellcontent.unknownPreShellFamilyError
import skillbill.infrastructure.skills.agentaddon.AgentAddonSchemaValidator
import skillbill.infrastructure.skills.scaffold.payload.requireStringListPayload
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.PRE_SHELL_FAMILIES
import skillbill.install.model.SupportedAgent
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.policy.model.OptionalSubagents
import skillbill.scaffold.policy.model.PlatformPackDefaults
import java.nio.file.Files
import java.nio.file.Path
import skillbill.infrastructure.skills.scaffold.payload.rejectLeafSubagentSpecialists as policyRejectLeafSubagentSpecialists
import skillbill.infrastructure.skills.scaffold.payload.requireStringMap as requireString
import skillbill.infrastructure.skills.scaffold.payload.requireStringOrDefaultMap as requireStringOrDefault
import skillbill.infrastructure.skills.scaffold.payload.resolvePlatformPackSelection as policyResolvePlatformPackSelection
import skillbill.scaffold.policy.platformpack.platformPackNotes as policyPlatformPackNotes

internal data class ScaffoldPlatformOverridePlanArgs(
  val payload: Map<String, Any?>,
  val repoRoot: Path,
  val platform: String,
  val family: String,
  val name: String,
  val subagents: OptionalSubagents,
)

internal data class PlatformPackScaffoldPlanArgs(
  val payload: Map<String, Any?>,
  val repoRoot: Path,
  val adapters: ScaffoldAdapterSeams,
  val platform: String,
  val defaults: PlatformPackDefaults,
  val packRoot: Path,
)

internal fun planAddOn(
  payload: Map<String, Any?>,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
): ScaffoldPlan {
  policyRejectLeafSubagentSpecialists(payload, SKILL_KIND_ADD_ON)
  val name = requireString(payload, "name")
  val platform = requireString(payload, "platform")
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  if (!Files.isRegularFile(packRoot.resolve("platform.yaml"))) {
    throw missingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a governed add-on into it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val externalLocationPath = optionalAddonLocationPath(payload, repoRoot)
  val addonDir = externalLocationPath ?: packRoot.resolve("addons")
  val skillFile = addonDir.resolve("$name.md")
  val addOnFile = displayPath(repoRoot, skillFile)
  return ScaffoldPlan(
    kind = SKILL_KIND_ADD_ON,
    skillName = name,
    skillPath = addonDir,
    skillFile = skillFile,
    contentFile = null,
    family = "add-on",
    platform = platform,
    area = "",
    isShelled = false,
    notes = listOf("After creation, edit the generated add-on body in `$addOnFile`, then validate and render."),
    description = requireStringOrDefault(payload, "description", ""),
    addonBody = payload["body"] as? String,
    addonConsumerSkillDirs = adapters.resolveAddonConsumerSkillDirs(payload, packRoot, pack),
    externalAddonLocationPath = externalLocationPath,
  )
}

internal fun planAgentAddon(
  payload: Map<String, Any?>,
  repoRoot: Path,
): ScaffoldPlan {
  val slug = requireString(payload, "slug")
  val description = requireString(payload, "description")
  val agentIds = requireStringListPayload(payload["agent_ids"], "agent_ids")
  val consumers = requireStringListPayload(payload["consumers"], "consumers")
  AgentAddonSchemaValidator().validate(
    mapOf(
      SharedPayloadKeys.CONTRACT_VERSION to "1.0",
      "slug" to slug,
      "description" to description,
      "agent_ids" to agentIds,
      "consumers" to consumers,
    ),
    "agent-addon scaffold payload",
  )
  validateAgentAddonDescription(description)
  agentIds.forEach(::validateAgentAddonAgentId)
  consumers.forEach(::validateAgentAddonConsumerId)
  val agentAddonsRoot = repoRoot.resolve("agent-addons").toAbsolutePath().normalize()
  validateAgentAddonSlugRoot(slug, agentAddonsRoot)
  val root = agentAddonsRoot.resolve(slug).normalize()
  return ScaffoldPlan(
    kind = SKILL_KIND_AGENT_ADDON,
    skillName = slug,
    skillPath = root,
    skillFile = root.resolve("agent-addon.yaml"),
    contentFile = root.resolve("content.md"),
    family = "agent-addon",
    platform = "",
    area = "",
    isShelled = false,
    notes = listOf("Agent add-on '$slug' will be delivered to its declared consumers during install rendering."),
    description = description,
    contentBody = payload["content_body"] as? String,
    agentIds = agentIds,
    agentAddonConsumers = consumers,
  )
}

internal fun validateAgentAddonAgentId(id: String) {
  try {
    SupportedAgent.parseAgentAddonId(id)
  } catch (error: SkillBillRuntimeException) {
    if (error.code != AgentAddonFailureCode.INVALID_AGENT_ID) throw error
    throw invalidScaffoldPayloadError(error.message ?: "Unknown agent '$id'.", error)
  }
}

internal fun validateAgentAddonConsumerId(id: String) {
  if (id == AgentAddonConsumer.LEGACY_BILL_FEATURE_ID) {
    throw invalidScaffoldPayloadError(
      "Agent add-on consumer '$id' is retired; declare '${AgentAddonConsumer.SKILL_BILL.id}' instead.",
    )
  }
  try {
    AgentAddonConsumer.fromId(id)
  } catch (error: IllegalArgumentException) {
    throw invalidScaffoldPayloadError(error.message ?: "Unknown agent add-on consumer '$id'.", error)
  }
}

internal fun validateAgentAddonDescription(description: String) {
  if (description != description.trim() || '\n' in description || '\r' in description) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field 'description' must be trimmed and single-line for an agent add-on.",
    )
  }
}

internal fun validateAgentAddonSlugRoot(
  slug: String,
  agentAddonsRoot: Path,
) {
  val root = agentAddonsRoot.resolve(slug).normalize()
  if (!root.startsWith(agentAddonsRoot)) {
    throw invalidScaffoldPayloadError("Scaffold payload field 'slug' escapes the agent-addons root.")
  }
}

internal fun planPreShellPlatformOverride(args: ScaffoldPlatformOverridePlanArgs): ScaffoldPlan {
  if (args.family !in PRE_SHELL_FAMILIES) {
    val replacement =
      if (args.family == "feature-" + "implement") {
        " Use 'feature-task' instead."
      } else {
        ""
      }
    throw unknownPreShellFamilyError(
      "Scaffold payload declares pre-shell family '${args.family}' " +
        "that is not in the registered set $PRE_SHELL_FAMILIES.$replacement",
    )
  }
  val skillPath = args.repoRoot.resolve("skills").resolve(args.platform).resolve(args.name)
  val notes =
    listOf(
      "Pre-shell family '${args.family}' placed at '${args.repoRoot.relativize(skillPath)}'; " +
        "will move when the family is piloted onto the shell+content contract.",
    )
  return ScaffoldPlan(
    kind = SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    skillName = args.name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = args.family,
    platform = args.platform,
    area = "",
    isShelled = false,
    notes = notes,
    contentBody = args.payload["content_body"] as? String,
    subagentSpecialists = args.subagents.specialists,
    subagentsSuppressed = args.subagents.suppressed,
  )
}

internal fun planShelledPlatformOverride(args: ScaffoldPlatformOverridePlanArgs): ScaffoldPlan {
  val packRoot = args.repoRoot.resolve("platform-packs").resolve(args.platform)
  val manifestPath = packRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw missingPlatformPackError(
      "Platform pack '${args.platform}' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a skill into it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val skillPath = packRoot.resolve(args.family).resolve(args.name)
  val notes =
    listOf(
      "Author skill instructions only in sibling `content.md` files. " +
        "Generated `SKILL.md` wrappers and platform pointer files are render/install output.",
    )
  return ScaffoldPlan(
    kind = SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    skillName = args.name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = args.family,
    platform = args.platform,
    area = "",
    isShelled = true,
    notes = notes,
    displayName = pack.displayName ?: deriveDisplayName(args.platform),
    contentBody = args.payload["content_body"] as? String,
    subagentSpecialists = args.subagents.specialists,
    subagentsSuppressed = args.subagents.suppressed,
  )
}

internal fun buildPlatformPackScaffoldPlan(args: PlatformPackScaffoldPlanArgs): ScaffoldPlan {
  val baselineName = canonicalName(args.payload, defaultName = "bill-${args.platform}-code-review")
  val baselineLayers = args.adapters.optionalBaselineLayers(args.payload, args.repoRoot, args.platform)
  val selection = policyResolvePlatformPackSelection(args.payload)
  val selectedAreas = selection.selectedAreas
  val specialistPlan = platformPackSpecialistPlan(args, selectedAreas)
  val notes = policyPlatformPackNotes(args.platform, args.defaults.presetUsed, selectedAreas)
  return platformPackScaffoldPlanBody(
    PlatformPackScaffoldPlanBodyArgs(
      scaffold = args,
      baselineName = baselineName,
      baselineLayers = baselineLayers,
      specialistPlan = specialistPlan,
      notes = notes,
    ),
  )
}
