package skillbill.infrastructure.skills.install.apply

import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallAgentSkillLinkOutcome
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun linkPlannedSkill(
  skill: InstallPlanSkill,
  stagingDir: Path,
  plan: InstallPlan,
  failures: MutableList<InstallApplyIssue>,
): List<InstallAgentSkillLinkOutcome> =
  plan.agents.map { agentTarget ->
    linkSkillToAgent(
      skillName = skill.name,
      stagingDir = stagingDir,
      agentTarget = agentTarget,
      installedSkillsRoot = installedSkillsCacheRoot(plan.request.home.toPath()),
    ).also { outcome ->
      outcome.issue?.let(failures::add)
    }
  }

private fun linkSkillToAgent(
  skillName: String,
  stagingDir: Path,
  agentTarget: InstallAgentTarget,
  installedSkillsRoot: Path,
): InstallAgentSkillLinkOutcome {
  val targetDir = agentTarget.path.toPath().toAbsolutePath().normalize()
  val context =
    SkillLinkContext(
      skillName = skillName,
      agentTarget = agentTarget,
      targetDir = targetDir,
      linkPath = targetDir.resolve(skillName).normalize(),
      linkTarget = stagingDir.toAbsolutePath().normalize(),
      installedSkillsRoot = installedSkillsRoot.toAbsolutePath().normalize(),
    )
  return runCatching { createOrSkipSkillLink(context) }
    .getOrElse { error -> failedSkillLinkOutcome(context, error) }
}

private fun createOrSkipSkillLink(context: SkillLinkContext): InstallAgentSkillLinkOutcome {
  validateSkillLinkPath(context)
  Files.createDirectories(context.targetDir)
  if (Files.isSymbolicLink(context.linkPath)) {
    val existingTarget = resolveSymlinkTarget(context.linkPath)
    if (existingTarget == context.linkTarget) {
      return skillLinkOutcome(
        context = context,
        status = InstallAgentLinkStatus.SKIPPED,
        message = "already linked to ${context.linkTarget}",
      )
    }
    require(existingTarget != null && existingTarget.startsWith(context.installedSkillsRoot)) {
      "Existing symlink at ${context.linkPath} points outside Skill Bill installed-skills cache and was preserved."
    }
  } else if (Files.exists(context.linkPath, LinkOption.NOFOLLOW_LINKS)) {
    error("Existing non-symlink path at ${context.linkPath} was preserved.")
  }
  if (Files.isSymbolicLink(context.linkPath)) {
    createReplacementSymlinkWithGuidance(context.linkPath, context.linkTarget)
  } else {
    createNewSymlinkWithGuidance(context.linkPath, context.linkTarget)
  }
  return skillLinkOutcome(
    context = context,
    status = InstallAgentLinkStatus.CREATED,
    message = "linked to ${context.linkTarget}",
  )
}

private fun validateSkillLinkPath(context: SkillLinkContext) {
  val rawName = context.skillName
  require(rawName.isNotBlank() && '/' !in rawName && '\\' !in rawName) {
    "Skill name '$rawName' is not a safe single path segment."
  }
  val rawPath = Path.of(rawName)
  require(!rawPath.isAbsolute && rawPath.nameCount == 1 && rawPath.normalize() == rawPath) {
    "Skill name '$rawName' is not a safe single path segment."
  }
  require(context.linkPath.startsWith(context.targetDir) && context.linkPath.parent == context.targetDir) {
    "Skill link path '${context.linkPath}' escapes agent target dir '${context.targetDir}'."
  }
}

private fun skillLinkOutcome(
  context: SkillLinkContext,
  status: InstallAgentLinkStatus,
  message: String,
  issue: InstallApplyIssue? = null,
): InstallAgentSkillLinkOutcome =
  InstallAgentSkillLinkOutcome(
    agent = context.agentTarget.agent,
    targetDir = context.targetDir.toFileLocation(),
    linkPath = context.linkPath.toFileLocation(),
    linkTarget = context.linkTarget.toFileLocation(),
    status = status,
    message = message,
    issue = issue,
  )

private fun failedSkillLinkOutcome(
  context: SkillLinkContext,
  error: Throwable,
): InstallAgentSkillLinkOutcome {
  val symlinkError = error as? InstallSymlinkException
  val issue =
    InstallApplyIssue(
      kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
      message = error.message.orEmpty(),
      skillName = context.skillName,
      agent = context.agentTarget.agent,
      path = context.linkPath.toFileLocation(),
      guidance = symlinkError?.guidance,
      causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
    )
  return skillLinkOutcome(
    context = context,
    status = InstallAgentLinkStatus.FAILED,
    message = error.message.orEmpty(),
    issue = issue,
  )
}

private fun resolveSymlinkTarget(linkPath: Path): Path? =
  runCatching {
    val rawTarget = Files.readSymbolicLink(linkPath)
    val resolvedTarget = if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget)
    resolvedTarget.toAbsolutePath().normalize()
  }.getOrNull()

private data class SkillLinkContext(
  val skillName: String,
  val agentTarget: InstallAgentTarget,
  val targetDir: Path,
  val linkPath: Path,
  val linkTarget: Path,
  val installedSkillsRoot: Path,
)
