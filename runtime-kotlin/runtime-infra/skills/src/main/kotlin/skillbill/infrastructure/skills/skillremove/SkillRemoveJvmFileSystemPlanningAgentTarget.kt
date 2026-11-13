package skillbill.infrastructure.skills.skillremove

import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.infrastructure.skills.nativeagent.support.claudeConfigRoots
import skillbill.infrastructure.skills.nativeagent.support.codexAgentsTargets
import skillbill.install.model.SupportedAgent
import skillbill.skillremove.model.AgentSymlinkUnlink
import skillbill.skillremove.model.SkillRemovalRequest
import java.nio.file.Path

internal fun SkillRemoveJvmFileSystemPlanning.agentUnlinksForSkills(
  request: SkillRemovalRequest,
  cascadedSkillNames: List<String>,
): List<AgentSymlinkUnlink> {
  val resolvedHome = skillRemoveUserHome(request, home)
  val environment = resolveEnvironmentMap(request.environment)
  val out = mutableListOf<AgentSymlinkUnlink>()
  cascadedSkillNames.forEach { name ->
    SupportedAgent.values().forEach { provider ->
      agentHomeDirs(provider, resolvedHome, environment).forEach { dir ->
        val candidate = dir.resolve("$name.md")
        out += AgentSymlinkUnlink(provider = provider, path = candidate.toString().replace('\\', '/'))
      }
    }
  }
  return out
}

internal fun SkillRemoveJvmFileSystemPlanning.agentUnlinksForPlatform(
  request: SkillRemovalRequest,
  platform: String,
): List<AgentSymlinkUnlink> {
  val resolvedHome = skillRemoveUserHome(request, home)
  val environment = resolveEnvironmentMap(request.environment)
  val out = mutableListOf<AgentSymlinkUnlink>()
  SupportedAgent.values().forEach { provider ->
    agentHomeDirs(provider, resolvedHome, environment).forEach { dir ->
      out +=
        AgentSymlinkUnlink(
          provider = provider,
          path = dir.resolve("bill-$platform-*").toString().replace('\\', '/'),
        )
    }
  }
  return out
}

internal fun SkillRemoveJvmFileSystemPlanning.agentHomeDirs(
  provider: SupportedAgent,
  home: Path,
  environment: Map<String, String>,
): List<Path> =
  when (provider) {
    SupportedAgent.CLAUDE -> claudeConfigRoots(home, environment).map { it.resolve("agents") }
    SupportedAgent.CODEX -> codexAgentsTargets(home, environment)
    SupportedAgent.CURSOR -> listOf(home.resolve(requireNotNull(provider.simpleHomeDirectory)).resolve("agents"))
  }
