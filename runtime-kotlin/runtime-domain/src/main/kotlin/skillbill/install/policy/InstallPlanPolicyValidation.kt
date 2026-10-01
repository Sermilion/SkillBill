package skillbill.install.policy

import skillbill.error.shellcontent.MissingBaselinePlatformSelectionError
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.SupportedAgent
import skillbill.install.model.selectedPlatformSlugs

internal fun validateAgentSelection(input: InstallPolicyInput) {
  val selection = input.request.agentSelection
  when (selection.mode) {
    InstallAgentSelectionMode.DETECTED -> {
      require(selection.manualAgents.isEmpty()) {
        "Detected agent selection must not include manual agents: " +
          selection.manualAgents.map(SupportedAgent::id).sorted().joinToString(", ") + "."
      }
    }
    InstallAgentSelectionMode.MANUAL -> {
      require(selection.detectedTargets.isEmpty()) {
        "Manual agent selection must not include detected targets: " +
          selection.detectedTargets.map { target -> target.agent.id }.sorted().joinToString(", ") + "."
      }
    }
  }
  requireNoDuplicateAgentTargets("request.detectedTargets", selection.detectedTargets)
  requireNoDuplicateAgentTargets("detectedAgentTargets", input.detectedAgentTargets)
  requireNoDuplicateDefaultTargets(input)
  requireNoDuplicateAgentTargets("targetPaths.agentTargets", input.request.targetPaths.agentTargets)
  input.request.targetPaths.agentTargets.forEach { target ->
    validatePath("targetPaths.agentTargets.${target.agent.id}", target.path)
  }
  selection.detectedTargets.forEach { target ->
    validatePath("agentSelection.detectedTargets.${target.agent.id}", target.path)
  }
  input.detectedAgentTargets.forEach { target ->
    validatePath("detectedAgentTargets.${target.agent.id}", target.path)
  }
  input.defaultAgentTargets.forEach { target ->
    validatePath("defaultAgentTargets.${target.agent.id}", target.path)
  }
  if (selection.mode == InstallAgentSelectionMode.MANUAL && selection.manualAgents.isNotEmpty()) {
    val explicitAgents = input.request.targetPaths.agentTargets.map(InstallAgentTarget::agent).toSet()
    val defaultAgents = input.defaultAgentTargets.map { target -> target.agent }.toSet()
    val missingTargets = selection.manualAgents - explicitAgents - defaultAgents
    require(missingTargets.isEmpty()) {
      "Manual agent selection has no explicit or default target path for agent(s): " +
        missingTargets.map(SupportedAgent::id).sorted().joinToString(", ") + "."
    }
  }
}

internal fun validateBaselineCoPresence(
  input: InstallPolicyInput,
  selectedPlatformSlugs: List<String>,
) {
  val selected = selectedPlatformSlugs.toSet()
  val bySlug = input.platformPacks.associateBy(InstallPlatformPackSnapshot::slug)
  if (selected.containsAll(bySlug.keys)) {
    return
  }
  bySlug.values.forEach { pack ->
    if (pack.slug !in selected) {
      return@forEach
    }
    pack.baselineLayers.forEach { layer ->
      if (layer.required && layer.platform !in selected) {
        throw MissingBaselinePlatformSelectionError(
          selectingSlug = pack.slug,
          requiredBaselineSlug = layer.platform,
          declaringManifestPath = pack.packRoot.resolve("platform.yaml").toString(),
        )
      }
    }
  }
}

internal fun validatePlatformSelection(request: InstallPlanRequest) {
  val selection = request.platformPackSelection
  val selectedSlugs = selection.selectedSlugs
  require(selectedSlugs.none(String::isBlank)) {
    "Selected platform slugs must be non-blank."
  }
  when (selection.mode) {
    PlatformPackSelectionMode.NONE ->
      require(selectedSlugs.isEmpty()) {
        "Platform mode NONE must not include selected slugs: ${selectedSlugs.sorted().joinToString(", ")}."
      }
    PlatformPackSelectionMode.ALL ->
      require(selectedSlugs.isEmpty()) {
        "Platform mode ALL must not include selected slugs: ${selectedSlugs.sorted().joinToString(", ")}."
      }
    PlatformPackSelectionMode.SELECTED ->
      require(selectedSlugs.isNotEmpty()) {
        "Platform mode SELECTED requires at least one selected slug."
      }
  }
}

internal fun validateSnapshots(input: InstallPolicyInput) {
  requireNoDuplicateSkills("base skills", input.baseSkills)
  input.baseSkills.forEach { skill ->
    validateSkillSnapshot("base skill snapshot '${skill.name}'", skill)
  }
  validatePlatformPackDiscoverySnapshots(
    input.platformPacks.map { pack ->
      InstallPlatformPackDiscoverySnapshot(slug = pack.slug, packRoot = pack.packRoot)
    },
  )
  input.platformPacks.forEach { pack ->
    requireNoDuplicateSkills("platform pack '${pack.slug}'", pack.skills)
    pack.skills.forEach { skill ->
      validateSkillSnapshot("platform pack '${pack.slug}' skill '${skill.name}'", skill)
      require(skill.kind == InstallPlanSkillKind.PLATFORM_PACK) {
        "Platform pack '${pack.slug}' contains non-platform skill '${skill.name}'."
      }
      require(skill.platformSlug == pack.slug) {
        "Platform pack '${pack.slug}' contains skill '${skill.name}' owned by '${skill.platformSlug}'."
      }
    }
  }
  input.baseSkills.forEach { skill ->
    require(skill.kind == InstallPlanSkillKind.BASE) {
      "Base skill snapshot '${skill.name}' must use kind BASE."
    }
    require(skill.platformSlug == null) {
      "Base skill snapshot '${skill.name}' must not declare a platform slug."
    }
  }
}

internal fun validatePlatformPackDiscoverySnapshots(platformPacks: List<InstallPlatformPackDiscoverySnapshot>) {
  val duplicatePlatformSlugs =
    platformPacks
      .groupBy(InstallPlatformPackDiscoverySnapshot::slug)
      .filterValues { packs -> packs.size > 1 }
      .keys
  require(duplicatePlatformSlugs.isEmpty()) {
    "Discovered platform pack snapshots contain duplicate slug(s): " +
      duplicatePlatformSlugs.sorted().joinToString(", ") + "."
  }
  platformPacks.forEach { pack ->
    require(pack.slug.isNotBlank()) {
      "Discovered platform pack snapshots must not contain a blank slug."
    }
    validatePath("platformPacks.${pack.slug}.packRoot", pack.packRoot)
  }
}

internal fun validateSkillSnapshot(
  label: String,
  skill: InstallPlanSkill,
) {
  require(skill.name.isNotBlank()) {
    "$label must have a non-blank name."
  }
  validatePath("$label.sourceDir", skill.sourceDir)
}
