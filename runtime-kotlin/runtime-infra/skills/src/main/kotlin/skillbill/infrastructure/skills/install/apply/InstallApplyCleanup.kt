package skillbill.infrastructure.skills.install.apply

import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.skills.install.plan.platformSkills
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import skillbill.infrastructure.skills.install.staging.pruneLegacySkillCacheDirs
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
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
  val legacyNames = legacySkillBillCleanupNames(cleanupSkillNames)
  val cleanupContext =
    InstallCleanupContext(
      skillNames = cleanupSkillNames,
      legacyNames = legacyNames,
      installedSkillsRoot = installedSkillsCacheRoot(plan.request.home.toPath()),
    )
  plan.agents.forEach { agentTarget ->
    cleanupOneTarget(agentTarget.agent, agentTarget.path.toPath(), cleanupContext, failures)

    legacyClaudeCommandsDir(agentTarget.agent, agentTarget.path.toPath())?.let { legacyDir ->
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
)

private fun cleanupOneTarget(
  agent: SupportedAgent,
  targetDir: Path,
  cleanupContext: InstallCleanupContext,
  failures: MutableList<InstallApplyIssue>,
) {
  runCatching {
    InstallCleanupOperations.cleanupAgentTarget(
      targetDir = targetDir,
      skillNames = cleanupContext.skillNames,
      legacyNames = cleanupContext.legacyNames,
      managedInstallMarker = ".skill-bill-install",
      installedSkillsRoot = cleanupContext.installedSkillsRoot,
    )
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
