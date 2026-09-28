package skillbill.infrastructure.skills.scaffold.runtime.validation

import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionContext
import skillbill.infrastructure.skills.nativeagent.rendering.discoverRepoNativeAgentSourceEntries
import skillbill.infrastructure.skills.nativeagent.validation.validateRepoNativeAgents
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.SKILL_CLASSES_DIR
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.discoverSkillClasses
import skillbill.infrastructure.skills.scaffold.platformpack.substanceaudit.PlatformPackSubstanceAudit
import skillbill.infrastructure.skills.scaffold.pointer.validateGeneratedArtifactGuard
import skillbill.infrastructure.skills.scaffold.validation.shape.validateGovernedSkillDrift
import java.nio.file.Path
import kotlin.io.path.isDirectory

internal data class RepoValidationCollected(
  val issues: MutableList<String>,
  val skillNames: Set<String>,
  val addonCount: Int,
  val platformPackCount: Int,
  val nativeAgentCount: Int,
)

internal fun collectRepoValidationIssues(
  root: Path,
  nativeAgentCompositionContext: NativeAgentCompositionContext,
  plannedNativeAgentWorkerIssues: (Path) -> List<String> = { _ -> emptyList() },
): RepoValidationCollected {
  val issues = mutableListOf<String>()
  val skillFiles = discoverSkillFiles(root, issues)
  val platformSkillFiles = discoverPlatformPackSkillFiles(root, issues)
  val skillNames = (skillFiles.keys + platformSkillFiles.keys).toSortedSet()
  val addonFiles = discoverAllAddonFiles(root)
  val platformPacks = validatePlatformPacks(root, issues)
  val portableReviewSkills = discoverPortableReviewSkills(root, issues)
  val nativeAgentSources =
    runCatching { discoverRepoNativeAgentSourceEntries(root) }
      .onFailure { error -> issues += "native agent sources: ${describeDiscoveryFailure(error)}" }
      .getOrDefault(emptyList())
  skillClassDiscoveryFailure(root)?.let { error ->
    issues += "$SKILL_CLASSES_DIR: ${describeDiscoveryFailure(error)}"
    return RepoValidationCollected(
      issues = issues,
      skillNames = skillNames,
      addonCount = addonFiles.size,
      platformPackCount = platformPacks,
      nativeAgentCount = nativeAgentSources.size,
    )
  }
  validateInstallableSkills(skillFiles, root, issues, portableReviewSkills, validateSourceSidecars = true)
  validateInstallableSkills(platformSkillFiles, root, issues, portableReviewSkills, validateSourceSidecars = false)
  validateInternalSidecarCollisions(skillFiles + platformSkillFiles, issues)
  validateInternalSkillClassification(skillFiles, platformSkillFiles, issues)
  validateInternalSidecarReferences(skillFiles + platformSkillFiles, issues)
  validateSkillSourceShape(skillFiles.values, root, issues)
  addonFiles.forEach { addonFile -> validateAddonFile(addonFile, root, issues) }
  validateReadme(
    root.resolve("README.md"),
    skillFiles.keys.toSet(),
    internalSkillNames(skillFiles + platformSkillFiles),
    issues,
  )
  validateSkillReferences(root, skillNames, issues)
  validateSkillOverrides(root.resolve(".agents/skill-overrides.example.md"), skillNames, required = true, issues)
  validateSkillOverrides(root.resolve(".agents/skill-overrides.md"), skillNames, required = false, issues)
  validateSupportingTargets(root, skillFiles.keys + platformSkillFiles.keys, issues)
  validateFeatureAddonDeclarations(root, issues)
  validateAgentAddons(root, issues)
  validateOrchestrationPlaybooks(root, issues)
  validateNoInlineTelemetryContractDrift(root, issues)
  validateSpecialistContractParity(root, issues)
  validatePluginManifest(root.resolve(".claude-plugin/plugin.json"), issues)
  issues += validateRepoNativeAgents(root, nativeAgentCompositionContext).issues
  issues += plannedNativeAgentWorkerIssues(root)
  issues += validatePointerTargetParityIssues(root)
  issues += validateGovernedSkillDrift(root).issues
  issues += validateGeneratedArtifactGuard(root).issues
  val substanceReport = PlatformPackSubstanceAudit.audit(root)
  issues += substanceReport.auditErrors
  issues += substanceReport.violations.map { it.format() }
  validateNoOrchestrationPathsInSkillBodies(root, skillFiles, platformSkillFiles, issues)
  return RepoValidationCollected(
    issues = issues,
    skillNames = skillNames,
    addonCount = addonFiles.size,
    platformPackCount = platformPacks,
    nativeAgentCount = nativeAgentSources.size,
  )
}

private fun skillClassDiscoveryFailure(root: Path): Throwable? =
  if (root.resolve(SKILL_CLASSES_DIR).isDirectory()) {
    runCatching { discoverSkillClasses(root) }.exceptionOrNull()
  } else {
    null
  }

private fun validateInstallableSkills(
  skillFiles: Map<String, Path>,
  root: Path,
  issues: MutableList<String>,
  portableReviewSkills: Set<String>,
  validateSourceSidecars: Boolean,
) {
  skillFiles.forEach { (skillName, skillFile) ->
    validateInstallableSkill(
      ValidateInstallableSkillArgs(
        skillName = skillName,
        contentFile = skillFile,
        root = root,
        issues = issues,
        validateSourceSidecars = validateSourceSidecars,
        portableReviewSkills = portableReviewSkills,
      ),
    )
  }
}
