package skillbill.infrastructure.skills.install.plan

import skillbill.error.shellcontent.invalidInternalSkillClassification
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.rollbackDeleteIfExists
import skillbill.infrastructure.skills.install.opencode.opencodeConfigRoots
import skillbill.infrastructure.skills.install.staging.StagedSymlinkTargetInput
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import skillbill.infrastructure.skills.install.staging.resolveStagedSymlinkTarget
import skillbill.infrastructure.skills.install.symlink.InstallSymlinkException
import skillbill.infrastructure.skills.install.symlink.RetiredDispatcherLinks
import skillbill.infrastructure.skills.install.symlink.createNewSymlinkWithGuidance
import skillbill.infrastructure.skills.install.symlink.createReplacementSymlinkWithGuidance
import skillbill.infrastructure.skills.install.symlink.retireObsoleteDispatcherLinks
import skillbill.infrastructure.skills.nativeagent.support.agentPathExists
import skillbill.infrastructure.skills.nativeagent.support.agentPathsHavePresence
import skillbill.infrastructure.skills.nativeagent.support.codexAgentIsPresent
import skillbill.infrastructure.skills.nativeagent.support.devinConfigRoot
import skillbill.infrastructure.skills.nativeagent.support.devinSkillsDirectory
import skillbill.infrastructure.skills.scaffold.authoring.parseInternalForFrontmatter
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallTransaction
import skillbill.install.model.ListedSkillNames
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.ports.system.HostPlatformPort
import skillbill.scaffold.model.PlatformManifest
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import skillbill.infrastructure.skills.nativeagent.support.claudeSkillTargets as nativeClaudeSkillTargets
import skillbill.infrastructure.skills.nativeagent.support.codexSkillTargets as nativeCodexSkillTargets
import skillbill.infrastructure.skills.nativeagent.support.detectCodexAgentsTargets as nativeDetectCodexAgentsTargets
import skillbill.infrastructure.skills.nativeagent.support.discoverClaudeConfigRoots as nativeDiscoverClaudeConfigRoots
import skillbill.infrastructure.skills.nativeagent.support.discoverCodexConfigRoots as nativeDiscoverCodexConfigRoots

internal val SUPPORTED_AGENTS: List<SupportedAgent> = SupportedAgent.entries

internal val CODEX_AGENTS_KIND: String = SupportedAgent.CODEX.nativeAgentsKind
internal val CLAUDE_AGENTS_KIND: String = SupportedAgent.CLAUDE.nativeAgentsKind
internal val JUNIE_AGENTS_KIND: String = SupportedAgent.JUNIE.nativeAgentsKind
internal val CURSOR_AGENTS_KIND: String = SupportedAgent.CURSOR.nativeAgentsKind
internal val DEVIN_AGENTS_KIND: String = SupportedAgent.DEVIN.nativeAgentsKind

internal data class InstallConfigRoots(
  val claude: Path,
  val codex: Path,
  val devin: Path,
  val opencodeSkills: Path,
)

internal fun installConfigRoots(
  home: Path,
  environment: Map<String, String>,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
): InstallConfigRoots =
  InstallConfigRoots(
    claude = claudeConfigRoot(home, environment),
    codex = codexConfigRoot(home, environment),
    devin = devinConfigRoot(home, environment, hostPlatform),
    opencodeSkills = opencodeConfigRoots(home, environment).skillDirectory,
  )

internal fun agentPaths(
  home: Path,
  configRoots: InstallConfigRoots,
): Map<SupportedAgent, Path> =
  mapOf(
    SupportedAgent.CLAUDE to configRoots.claude.resolve("skills"),
    SupportedAgent.JUNIE to home.resolve(SupportedAgent.JUNIE.simpleHomeDirectory!!).resolve("skills"),
    SupportedAgent.CURSOR to home.resolve(SupportedAgent.CURSOR.simpleHomeDirectory!!).resolve("skills"),
    SupportedAgent.CODEX to configRoots.codex.resolve("skills"),
    SupportedAgent.DEVIN to devinSkillsDirectory(configRoots.devin),
    SupportedAgent.OPENCODE to configRoots.opencodeSkills,
  )

internal fun codexAgentsPath(
  home: Path,
  environment: Map<String, String>,
): Path = codexConfigRoot(home, environment).resolve("agents")

internal fun detectAgents(
  home: Path,
  environment: Map<String, String>,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
): List<AgentTarget> {
  return SUPPORTED_AGENTS.flatMap { agent ->
    when (agent) {
      SupportedAgent.CLAUDE -> {
        val roots = nativeDiscoverClaudeConfigRoots(home, environment)
        val targets = nativeClaudeSkillTargets(roots.paths)
        if (!agentPathsHavePresence(roots.paths + targets, "inspect Claude installation", roots)) {
          emptyList()
        } else {
          targets.map { path -> AgentTarget(agent.wireValue, path.toFileLocation()) }
        }
      }
      SupportedAgent.CODEX -> {
        val roots = nativeDiscoverCodexConfigRoots(home, environment)
        val targets = nativeCodexSkillTargets(home, roots.paths)
        if (!codexAgentIsPresent(home, environment, roots)) {
          emptyList()
        } else {
          targets.map { path -> AgentTarget(agent.wireValue, path.toFileLocation()) }
        }
      }
      SupportedAgent.JUNIE,
      SupportedAgent.CURSOR,
      -> {
        val root = home.resolve(requireNotNull(agent.simpleHomeDirectory))
        val installPath = root.resolve("skills")
        if (!anyAgentPathExists(listOf(installPath, root), "inspect ${agent.wireValue} installation")) {
          emptyList()
        } else {
          listOf(AgentTarget(agent.wireValue, installPath.toFileLocation()))
        }
      }
      SupportedAgent.OPENCODE -> {
        val roots = opencodeConfigRoots(home, environment)
        if (!anyAgentPathExists(listOf(roots.skillDirectory, roots.configDirectory), "inspect OpenCode installation")) {
          emptyList()
        } else {
          listOf(AgentTarget(agent.wireValue, roots.skillDirectory.toFileLocation()))
        }
      }
      SupportedAgent.DEVIN -> {
        val root = devinConfigRoot(home, environment, hostPlatform)
        val installPath = devinSkillsDirectory(root)
        if (!anyAgentPathExists(listOf(installPath, root), "inspect ${agent.wireValue} installation")) {
          emptyList()
        } else {
          listOf(AgentTarget(agent.wireValue, installPath.toFileLocation()))
        }
      }
    }
  }
}

private fun anyAgentPathExists(
  paths: List<Path>,
  operation: String,
): Boolean = paths.map { path -> agentPathExists(path, operation) }.any { present -> present }

internal fun detectCodexAgentsTargets(
  home: Path,
  environment: Map<String, String>,
): List<AgentTarget> {
  return nativeDetectCodexAgentsTargets(home, environment)
    .map { target -> AgentTarget(target.name, target.path.toFileLocation()) }
}

internal data class InstallContext(
  val repoRoot: Path? = null,
  val home: Path,
  val manifests: List<PlatformManifest>? = null,
  val selectedPackSkills: List<InstallPlanSkill> = emptyList(),
  val selectedPlatformSlugs: Set<String> = emptySet(),
  val environment: Map<String, String> = emptyMap(),
  val catalogLoader: PlatformPackCatalogLoader? = null,
)

internal data class InstallSkillOutcome(
  val linkPaths: List<Path>,
  val transaction: InstallTransaction?,
  val issues: List<InstallApplyIssue> = emptyList(),
  val replacedSymlinks: Map<Path, Path> = emptyMap(),
  val retiredSymlinks: Map<Path, Path> = emptyMap(),
)

internal fun installSkill(
  skillPath: Path,
  agentTargets: Iterable<AgentTarget>,
  transaction: InstallTransaction? = null,
  context: InstallContext,
): InstallSkillOutcome {
  val resolvedSkill = skillPath.toAbsolutePath().normalize()
  if (!Files.isDirectory(resolvedSkill)) {
    throw FileNotFoundException("Skill directory '$resolvedSkill' does not exist.")
  }
  parseInternalForFrontmatter(resolvedSkill.resolve("content.md"))?.let { declaredParent ->
    throw invalidInternalSkillClassification(
      "Skill '${resolvedSkill.fileName}' declares 'internal-for: $declaredParent' and cannot be " +
        "installed or linked directly: internal skills install as '<skill-name>.md' sidecars inside " +
        "their parent's installed directory. Install the parent skill instead.",
    )
  }
  val symlinkTarget =
    resolveStagedSymlinkTarget(
      StagedSymlinkTargetInput(
        resolvedSkill = resolvedSkill,
        repoRoot = context.repoRoot,
        home = context.home,
        manifests = context.manifests,
        selectedPackSkills = context.selectedPackSkills,
        selectedPlatformSlugs = context.selectedPlatformSlugs,
        environment = context.environment,
        catalogLoader = context.catalogLoader,
      ),
    )
  val created = mutableListOf<Path>()
  val issues = mutableListOf<InstallApplyIssue>()
  val replacedSymlinks = mutableMapOf<Path, Path>()
  val retiredSymlinks = mutableMapOf<Path, Path>()
  val installedSkillsRoot = installedSkillsCacheRoot(context.home)
  val publicName = ListedSkillNames.publicName(resolvedSkill.fileName.toString())
  var currentTransaction = transaction
  for (target in agentTargets) {
    val outcome =
      installSkillSymlink(
        resolvedSkill,
        symlinkTarget,
        target,
        installedSkillsRoot,
        currentTransaction,
      )
    outcome.linkPath?.let(created::add)
    outcome.issue?.let(issues::add)
    outcome.previousTarget?.let { previous -> outcome.linkPath?.let { replacedSymlinks[it] = previous } }
    if (publicName == ListedSkillNames.PUBLIC_DISPATCHER && outcome.issue == null) {
      val retired = retireDispatcherLinks(target, context)
      retiredSymlinks += retired.rawTargets
      issues += retired.issues
    }
    currentTransaction = outcome.transaction
  }
  return InstallSkillOutcome(created, currentTransaction, issues, replacedSymlinks, retiredSymlinks)
}

private fun retireDispatcherLinks(
  target: AgentTarget,
  context: InstallContext,
): RetiredDispatcherLinks {
  val targetDir = target.path.toPath().toAbsolutePath().normalize()
  val isClaudeSkillsTarget =
    target.name == SupportedAgent.CLAUDE.wireValue ||
      targetDir in nativeClaudeSkillTargets(nativeDiscoverClaudeConfigRoots(context.home, context.environment).paths)
  return retireObsoleteDispatcherLinks(
    targetDir = targetDir,
    isClaudeSkillsTarget = isClaudeSkillsTarget,
    installedSkillsRoot = installedSkillsCacheRoot(context.home),
    agent = SupportedAgent.fromWireOrNull(target.name),
  )
}

private data class InstallSkillSymlinkOutcome(
  val linkPath: Path?,
  val transaction: InstallTransaction?,
  val issue: InstallApplyIssue? = null,
  val previousTarget: Path? = null,
)

private fun installSkillSymlink(
  resolvedSkill: Path,
  symlinkTarget: Path,
  target: AgentTarget,
  installedSkillsRoot: Path,
  transaction: InstallTransaction?,
): InstallSkillSymlinkOutcome {
  val targetDir = target.path.toPath().toAbsolutePath().normalize()
  val linkPath = targetDir.resolve(ListedSkillNames.publicName(resolvedSkill.fileName.toString()))
  return try {
    Files.createDirectories(targetDir)
    var previousTarget: Path? = null
    if (Files.isSymbolicLink(linkPath)) {
      val existingTarget = resolveLinkTarget(linkPath)
      if (existingTarget == symlinkTarget) return InstallSkillSymlinkOutcome(null, transaction)
      if (existingTarget == null || !existingTarget.startsWith(installedSkillsRoot.toAbsolutePath().normalize())) {
        return failedLink(
          target,
          linkPath,
          transaction,
          "Existing link is outside the managed Skill Bill cache and was preserved.",
        )
      }
      previousTarget = existingTarget
    } else if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
      return failedLink(target, linkPath, transaction, "Existing non-link target was preserved.")
    }
    if (previousTarget != null) {
      createReplacementSymlinkWithGuidance(linkPath, symlinkTarget)
    } else {
      createNewSymlinkWithGuidance(linkPath, symlinkTarget)
    }
    val updatedTransaction = transaction?.withRecordedSymlink(linkPath.toFileLocation())
    InstallSkillSymlinkOutcome(linkPath, updatedTransaction, previousTarget = previousTarget)
  } catch (error: IOException) {
    failedLink(
      target,
      linkPath,
      transaction,
      error.message ?: "Could not create skill link.",
      error,
    )
  } catch (error: InstallSymlinkException) {
    failedLink(
      target,
      linkPath,
      transaction,
      error.message ?: "Could not create skill link.",
      error,
    )
  }
}

private fun resolveLinkTarget(linkPath: Path): Path? =
  try {
    val target = Files.readSymbolicLink(linkPath)
    (if (target.isAbsolute) target else linkPath.parent.resolve(target)).toAbsolutePath().normalize()
  } catch (_: IOException) {
    null
  }

private fun failedLink(
  target: AgentTarget,
  linkPath: Path,
  transaction: InstallTransaction?,
  message: String,
  error: Throwable? = null,
): InstallSkillSymlinkOutcome =
  InstallSkillSymlinkOutcome(
    linkPath = null,
    transaction = transaction,
    issue =
      InstallApplyIssue(
        kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
        message = message,
        skillName = linkPath.fileName.toString(),
        agent = SupportedAgent.fromWireOrNull(target.name),
        path = linkPath.toFileLocation(),
        guidance = (error as? InstallSymlinkException)?.guidance,
        causeClass = error?.javaClass?.name,
      ),
  )

internal fun uninstallTargets(createdSymlinks: Iterable<Path>): List<Path> {
  val removed = mutableListOf<Path>()
  for (linkPath in createdSymlinks) {
    if (Files.isSymbolicLink(linkPath) || Files.exists(linkPath)) {
      rollbackDeleteIfExists(linkPath)
      removed.add(linkPath)
    }
  }
  return removed
}
