package skillbill.install.policy

import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallPlanDraft
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPlatformSkillMaterializationPlan
import skillbill.install.model.InstallPlatformSkillMaterializationRequest
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.InstallPolicyValidationResult
import skillbill.install.model.InstallPolicyValidationStatus
import skillbill.install.model.McpRegistrationIntent
import skillbill.install.model.PlannedPlatformPack
import skillbill.install.model.selectedPlatformSlugs

object InstallPlanPolicy {
  fun validateRequest(input: InstallPolicyInput): InstallPolicyValidationResult {
    validatePath("repoRoot", input.request.repoRoot)
    validatePath("home", input.request.home)
    validatePath("targetPaths.skillsRoot", input.request.targetPaths.skillsRoot)
    validatePath("targetPaths.platformPacksRoot", input.request.targetPaths.platformPacksRoot)
    validatePath(
      "runtimeDistributionInputs.runtimeInstallRoot",
      input.request.runtimeDistributionInputs.runtimeInstallRoot,
    )
    validateAgentSelection(input)
    validatePlatformSelection(input.request)
    validateSnapshots(input)
    return InstallPolicyValidationResult(InstallPolicyValidationStatus.VALID)
  }

  fun buildPlanDraft(input: InstallPolicyInput): InstallPlanDraft {
    validateRequest(input)
    val agents = resolveAgentTargets(input)
    val selectedPlatformSlugs = selectedPlatformSlugs(input)
    validateBaselineCoPresence(input, selectedPlatformSlugs)
    val discoveredPlatformPacks =
      input.platformPacks.map { pack ->
        PlannedPlatformPack(
          slug = pack.slug,
          packRoot = pack.packRoot,
          selected = pack.slug in selectedPlatformSlugs,
        )
      }
    val skills =
      input.baseSkills +
        input.platformPacks
          .filter { pack -> pack.slug in selectedPlatformSlugs }
          .flatMap(InstallPlatformPackSnapshot::skills)
    requireUniqueSkillNames(skills)
    return InstallPlanDraft(
      request = input.request,
      agents = agents,
      discoveredPlatformPacks = discoveredPlatformPacks,
      selectedPlatformSlugs = selectedPlatformSlugs,
      skills = skills,
      telemetryLevel = input.request.telemetryLevel,
      mcpRegistrationIntent =
        McpRegistrationIntent(
          register = input.request.mcpRegistrationChoice.register,
          runtimeMcpBin = input.request.mcpRegistrationChoice.runtimeMcpBin,
          agents = agents.map(InstallAgentTarget::agent),
        ),
      runtimeDistributionInputs = input.request.runtimeDistributionInputs,
      installationTargetPaths = input.request.targetPaths.copy(agentTargets = agents),
      windowsSymlinkPreflight = input.request.windowsSymlinkPreflight,
    )
  }

  fun planPlatformSkillMaterialization(
    request: InstallPlatformSkillMaterializationRequest,
  ): InstallPlatformSkillMaterializationPlan {
    validatePlatformSelection(request.installRequest)
    validatePlatformPackDiscoverySnapshots(request.platformPacks)
    val explicitlySelected =
      selectedPlatformSlugs(
        selection = request.installRequest.platformPackSelection,
        discoveredSlugs = request.platformPacks.map(InstallPlatformPackDiscoverySnapshot::slug),
      )
    return InstallPlatformSkillMaterializationPlan(
      selectedPlatformSlugs = expandRequiredComposedPacks(explicitlySelected, request.platformPacks),
    )
  }
}
