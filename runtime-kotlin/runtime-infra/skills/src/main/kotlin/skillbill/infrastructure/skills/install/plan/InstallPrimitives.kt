package skillbill.infrastructure.skills.install.plan

import skillbill.error.shellcontent.invalidInternalSkillClassification
import skillbill.infrastructure.host.jvm.rollbackDeleteIfExists
import skillbill.infrastructure.skills.install.staging.StagedSymlinkTargetInput
import skillbill.infrastructure.skills.install.staging.resolveStagedSymlinkTarget
import skillbill.infrastructure.skills.nativeagent.support.agentPathExists
import skillbill.infrastructure.skills.nativeagent.support.agentPathsHavePresence
import skillbill.infrastructure.skills.nativeagent.support.codexAgentIsPresent
import skillbill.infrastructure.skills.scaffold.authoring.parseInternalForFrontmatter
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallTransaction
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import java.io.FileNotFoundException
import java.nio.file.Files
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

internal data class InstallConfigRoots(
  val claude: Path,
  val codex: Path,
)

internal fun installConfigRoots(
  home: Path,
  environment: Map<String, String>,
): InstallConfigRoots =
  InstallConfigRoots(
    claude = claudeConfigRoot(home, environment),
    codex = codexConfigRoot(home, environment),
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
  )

internal fun codexAgentsPath(
  home: Path,
  environment: Map<String, String>,
): Path = codexConfigRoot(home, environment).resolve("agents")

internal fun detectAgents(
  home: Path,
  environment: Map<String, String>,
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
  var currentTransaction = transaction
  for (target in agentTargets) {
    val outcome = installSkillSymlink(resolvedSkill, symlinkTarget, target, currentTransaction)
    outcome.linkPath?.let(created::add)
    currentTransaction = outcome.transaction
  }
  return InstallSkillOutcome(created, currentTransaction)
}

private data class InstallSkillSymlinkOutcome(
  val linkPath: Path?,
  val transaction: InstallTransaction?,
)

private fun installSkillSymlink(
  resolvedSkill: Path,
  symlinkTarget: Path,
  target: AgentTarget,
  transaction: InstallTransaction?,
): InstallSkillSymlinkOutcome {
  Files.createDirectories(target.path.toPath())
  val linkPath = target.path.toPath().resolve(resolvedSkill.fileName)
  if (Files.isSymbolicLink(linkPath)) {
    val existingTarget = runCatching { Files.readSymbolicLink(linkPath).toAbsolutePath().normalize() }.getOrNull()
    if (existingTarget == symlinkTarget) {
      return InstallSkillSymlinkOutcome(null, transaction)
    }
    Files.deleteIfExists(linkPath)
  } else if (Files.exists(linkPath)) {
    Files.delete(linkPath)
  }
  Files.createSymbolicLink(linkPath, symlinkTarget)
  val updatedTransaction = transaction?.withRecordedSymlink(linkPath.toFileLocation())
  return InstallSkillSymlinkOutcome(linkPath, updatedTransaction)
}

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
