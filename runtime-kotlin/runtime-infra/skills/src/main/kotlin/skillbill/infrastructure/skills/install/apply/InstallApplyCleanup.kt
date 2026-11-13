package skillbill.infrastructure.skills.install.apply

import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.skills.install.plan.platformSkills
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import skillbill.infrastructure.skills.install.staging.pruneLegacySkillCacheDirs
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.ListedSkillNames
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun cleanupExistingSkillBillLinks(
  plan: InstallPlan,
  platformManifests: List<PlatformManifest>,
  failures: MutableList<InstallApplyIssue>,
) {
  if (!plan.request.replaceExistingSkillBillLinks) return
  val cleanupSkillNames =
    (
      plan.skills +
        platformManifests.flatMap(::platformSkills)
    )
      .map(InstallPlanSkill::name)
      .distinct()
  val preservedTargetNames =
    standaloneInstallableSkills(plan.skills, plan.selectedPlatformSlugs.toSet())
      .flatMap { skill -> listOf(skill.name, ListedSkillNames.publicName(skill.name)) }
      .toSet()
  val legacyNames = legacySkillBillCleanupNames(cleanupSkillNames)
  val cleanupContext =
    InstallCleanupContext(
      skillNames = emptyList(),
      legacyNames = (cleanupSkillNames + legacyNames).filterNot { name -> name in preservedTargetNames },
      installedSkillsRoot = installedSkillsCacheRoot(plan.request.home.toPath()),
      preservedTargetNames = preservedTargetNames,
    )
  plan.agents.forEach { agentTarget ->
    val targetDir = agentTarget.path.toPath()
    val publicTarget = targetDir.resolve(ListedSkillNames.PUBLIC_DISPATCHER)
    if (
      ListedSkillNames.DISPATCHER in cleanupSkillNames &&
      Files.exists(publicTarget, LinkOption.NOFOLLOW_LINKS) &&
      !InstallCleanupOperations.isOwnedSymlink(publicTarget, cleanupContext.installedSkillsRoot)
    ) {
      failures.add(
        InstallApplyIssue(
          kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
          message = "Existing public dispatcher target at $publicTarget was preserved.",
          skillName = ListedSkillNames.PUBLIC_DISPATCHER,
          agent = agentTarget.agent,
          path = publicTarget.toFileLocation(),
        ),
      )
    } else {
      cleanupOneTarget(agentTarget.agent, targetDir, cleanupContext, failures)
    }

    legacyClaudeCommandsDir(agentTarget.agent, targetDir)?.let { legacyDir ->
      cleanupOneTarget(agentTarget.agent, legacyDir, cleanupContext, failures)
    }
  }
  pruneLegacySkillCacheDirs(
    home = plan.request.home.toPath(),
    legacySkillNames = legacySkillBillCacheNames(cleanupSkillNames),
    liveSkillNames = cleanupSkillNames,
  )
}

private data class InstallCleanupContext(
  val skillNames: List<String>,
  val legacyNames: List<String>,
  val installedSkillsRoot: Path,
  val preservedTargetNames: Set<String>,
)

private fun cleanupOneTarget(
  agent: SupportedAgent,
  targetDir: Path,
  cleanupContext: InstallCleanupContext,
  failures: MutableList<InstallApplyIssue>,
) {
  catchingNonCancellation {
    InstallCleanupOperations.cleanupAgentTarget(
      targetDir = targetDir,
      skillNames = cleanupContext.skillNames + cleanupContext.legacyNames,
      managedInstallMarker = ".skill-bill-install",
      installedSkillsRoot = cleanupContext.installedSkillsRoot,
      preservedTargetNames = cleanupContext.preservedTargetNames,
    )
  }.onSuccess { (_, skipped) ->
    skipped.forEach { path ->
      failures.add(
        InstallApplyIssue(
          kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
          message = "Existing target at $path was preserved because cleanup ownership could not be established.",
          skillName = path.fileName.toString(),
          agent = agent,
          path = path.toFileLocation(),
        ),
      )
    }
  }.getOrElse { error ->
    failures.add(
      InstallApplyIssue(
        kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
        message = error.message.orEmpty(),
        agent = agent,
        path = targetDir.toFileLocation(),
        causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
      ),
    )
  }
}

private fun legacyClaudeCommandsDir(
  agent: SupportedAgent,
  targetDir: Path,
): Path? =
  if (agent == SupportedAgent.CLAUDE && targetDir.fileName?.toString() == "skills") {
    targetDir.resolveSibling("commands")
  } else {
    null
  }
