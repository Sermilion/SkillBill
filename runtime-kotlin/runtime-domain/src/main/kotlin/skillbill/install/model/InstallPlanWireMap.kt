package skillbill.install.model

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.install.INSTALL_PLAN_CONTRACT_VERSION
import skillbill.contracts.install.InstallPlanContract

class InstallPlanWireMap private constructor(
  private val delegate: Map<String, Any?>,
) : Map<String, Any?> by delegate {
  companion object {
    fun from(map: Map<String, Any?>): InstallPlanWireMap = InstallPlanWireMap(LinkedHashMap(map))
  }
}

fun buildInstallPlanWireMap(plan: InstallPlan): InstallPlanWireMap =
  InstallPlanWireMap.from(
    linkedMapOf(
      SharedPayloadKeys.STATUS to InstallPlanPayloadKeys.PLANNED_STATUS,
      SharedPayloadKeys.CONTRACT_VERSION to INSTALL_PLAN_CONTRACT_VERSION,
      InstallPlanPayloadKeys.AGENTS to plan.agents.map(::agentTargetWireMap),
      InstallPlanPayloadKeys.PLATFORM_PACKS to
        plan.discoveredPlatformPacks.map { pack ->
          linkedMapOf(
            InstallPlanPayloadKeys.SLUG to pack.slug,
            InstallPlanPayloadKeys.PACK_ROOT to pack.packRoot.toString(),
            InstallPlanPayloadKeys.SELECTED to pack.selected,
          )
        },
      InstallPlanPayloadKeys.SELECTED_PLATFORMS to plan.selectedPlatformSlugs,
      InstallPlanPayloadKeys.SKILLS to
        plan.skills
          .filter { skill -> skill.internalFor == null }
          .map { skill ->
            linkedMapOf(
              InstallPlanPayloadKeys.NAME to skill.name,
              InstallPlanPayloadKeys.KIND to skill.kind.wireName(),
              InstallPlanPayloadKeys.PLATFORM to skill.platformSlug,
              InstallPlanPayloadKeys.SOURCE_DIR to skill.sourceDir.toString(),
            )
          },
      InstallPlanPayloadKeys.STAGING_ROOT to plan.staging.root.toString(),
      InstallPlanPayloadKeys.STAGING to
        plan.staging.skillPaths.map { intent ->
          linkedMapOf(
            InstallPlanPayloadKeys.SKILL_NAME to intent.skillName,
            InstallPlanPayloadKeys.SOURCE_DIR to intent.sourceDir.toString(),
            InstallPlanPayloadKeys.STAGING_DIR to intent.stagingDir.toString(),
            InstallPlanPayloadKeys.CONTENT_HASH to intent.contentHash,
          )
        },
      InstallPlanPayloadKeys.TELEMETRY_LEVEL to plan.telemetryLevel.id,
      InstallPlanPayloadKeys.MCP_REGISTRATION to
        linkedMapOf(
          InstallPlanPayloadKeys.REGISTER to plan.mcpRegistrationIntent.register,
          InstallPlanPayloadKeys.RUNTIME_MCP_BIN to plan.mcpRegistrationIntent.runtimeMcpBin?.toString(),
          InstallPlanPayloadKeys.AGENTS to plan.mcpRegistrationIntent.agents.map(SupportedAgent::id),
        ),
      InstallPlanPayloadKeys.RUNTIME_DISTRIBUTION to
        linkedMapOf(
          InstallPlanPayloadKeys.RUNTIME_INSTALL_ROOT to plan.runtimeDistributionInputs.runtimeInstallRoot.toString(),
          InstallPlanPayloadKeys.RUNTIME_CLI_BUILD_DIR to plan.runtimeDistributionInputs.runtimeCliBuildDir?.toString(),
          InstallPlanPayloadKeys.RUNTIME_MCP_BUILD_DIR to plan.runtimeDistributionInputs.runtimeMcpBuildDir?.toString(),
          InstallPlanPayloadKeys.RUNTIME_CLI_INSTALL_DIR to
            plan.runtimeDistributionInputs.runtimeCliInstallDir?.toString(),
          InstallPlanPayloadKeys.RUNTIME_MCP_INSTALL_DIR to
            plan.runtimeDistributionInputs.runtimeMcpInstallDir?.toString(),
          InstallPlanPayloadKeys.RUNTIME_LAUNCHER_BIN_DIR to
            plan.runtimeDistributionInputs.runtimeLauncherBinDir?.toString(),
        ),
      InstallPlanPayloadKeys.WINDOWS_SYMLINK_PREFLIGHT to windowsPreflightWireMap(plan.windowsSymlinkPreflight),
      InstallPlanPayloadKeys.REPLACE_EXISTING_SKILL_BILL_LINKS to plan.request.replaceExistingSkillBillLinks,
    ),
  )

fun InstallPlan.toInstallPlanContract(): InstallPlanContract = InstallPlanContract.wrap(buildInstallPlanWireMap(this))

private fun agentTargetWireMap(target: InstallAgentTarget): Map<String, Any?> =
  linkedMapOf(
    InstallPlanPayloadKeys.AGENT to target.agent.id,
    InstallPlanPayloadKeys.PATH to target.path.toString(),
    InstallPlanPayloadKeys.SOURCE to target.source.name.lowercase(),
  )

private fun windowsPreflightWireMap(preflight: WindowsSymlinkPreflight): Map<String, Any?> =
  linkedMapOf(
    InstallPlanPayloadKeys.STATE to preflight.state.name.lowercase(),
    InstallPlanPayloadKeys.DECISION to preflight.decision.name.lowercase(),
    InstallPlanPayloadKeys.MESSAGE to preflight.message,
  )

private fun InstallPlanSkillKind.wireName(): String = name.lowercase()
