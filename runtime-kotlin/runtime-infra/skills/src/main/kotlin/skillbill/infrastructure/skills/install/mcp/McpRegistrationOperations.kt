package skillbill.infrastructure.skills.install.mcp

import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.install.plan.codexConfigRoots
import skillbill.infrastructure.skills.nativeagent.support.claudeConfigRoots
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpProfileOutcome
import skillbill.install.model.McpRegistrationOutcome
import skillbill.install.model.SupportedAgent
import skillbill.ports.repository.toFileLocation
import java.nio.file.Path

object McpRegistrationOperations {
  fun register(
    agent: String,
    runtimeMcpBin: Path,
    home: Path? = null,
    environment: Map<String, String>,
  ): McpRegistrationOutcome {
    val resolvedHome = home ?: resolveUserHome(null)
    val command = runtimeMcpBin.toAbsolutePath().normalize().toString()
    return when (val installAgent = SupportedAgent.fromId(agent)) {
      SupportedAgent.CLAUDE ->
        claudeFanOut(agent, resolvedHome, environment) { perProfilePath ->
          McpJsonConfig.register(agent, perProfilePath, command)
        }
      SupportedAgent.CODEX ->
        codexFanOut(agent, resolvedHome, environment) { perProfilePath ->
          McpTomlConfig.register(agent, perProfilePath, command)
        }
      SupportedAgent.JUNIE ->
        McpRegistrationOutcome.Applied(
          McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command),
        )
      SupportedAgent.CURSOR ->
        McpRegistrationOutcome.Applied(
          McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command),
        )
    }
  }

  fun unregister(
    agent: String,
    home: Path? = null,
    environment: Map<String, String>,
  ): McpRegistrationOutcome {
    val resolvedHome = home ?: resolveUserHome(null)
    return when (val installAgent = SupportedAgent.fromId(agent)) {
      SupportedAgent.CLAUDE ->
        claudeFanOut(agent, resolvedHome, environment) { perProfilePath ->
          McpJsonConfig.unregister(agent, perProfilePath)
        }
      SupportedAgent.CODEX ->
        codexFanOut(agent, resolvedHome, environment) { perProfilePath ->
          McpTomlConfig.unregister(agent, perProfilePath)
        }
      SupportedAgent.JUNIE ->
        McpRegistrationOutcome.Applied(McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome)))
      SupportedAgent.CURSOR ->
        McpRegistrationOutcome.Applied(McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome)))
    }
  }

  fun configFormatFor(agent: SupportedAgent): McpConfigFormat =
    if (agent.mcpUsesToml) McpConfigFormat.TOML else McpConfigFormat.JSON

  fun configPathFor(
    agent: SupportedAgent,
    home: Path,
  ): Path = home.resolve(agent.mcpConfigRelativePath)

  private fun claudeProfileConfigPaths(
    home: Path,
    environment: Map<String, String>,
  ): List<Path> {
    val defaultRoot =
      home.resolve(requireNotNull(SupportedAgent.CLAUDE.profileDirectoryPrefix).removeSuffix("-"))
        .toAbsolutePath()
        .normalize()
    return claudeConfigRoots(home, environment).map { root ->
      if (root == defaultRoot) {
        home.resolve(SupportedAgent.CLAUDE.mcpProfileFileName)
      } else {
        root.resolve(SupportedAgent.CLAUDE.mcpProfileFileName)
      }
    }
  }

  private fun codexProfileConfigPaths(
    home: Path,
    environment: Map<String, String>,
  ): List<Path> {
    val roots = codexConfigRoots(home, environment)
    return if (roots.isNotEmpty()) {
      roots.map { root -> root.resolve(SupportedAgent.CODEX.mcpProfileFileName) }
    } else {
      listOf(home.resolve(SupportedAgent.CODEX.mcpConfigRelativePath))
    }
  }

  private fun claudeFanOut(
    agent: String,
    home: Path,
    environment: Map<String, String>,
    mutate: (Path) -> McpMutationResult,
  ): McpRegistrationOutcome =
    profileFanOut(
      agent = agent,
      profilePaths = claudeProfileConfigPaths(home, environment),
      representativePath = home.resolve(SupportedAgent.CLAUDE.mcpConfigRelativePath),
      failureLabel = "Claude",
      mutate = mutate,
    )

  private fun codexFanOut(
    agent: String,
    home: Path,
    environment: Map<String, String>,
    mutate: (Path) -> McpMutationResult,
  ): McpRegistrationOutcome =
    profileFanOut(
      agent = agent,
      profilePaths = codexProfileConfigPaths(home, environment),
      representativePath = home.resolve(SupportedAgent.CODEX.mcpConfigRelativePath),
      failureLabel = "Codex",
      mutate = mutate,
    )

  private fun profileFanOut(
    agent: String,
    profilePaths: List<Path>,
    representativePath: Path,
    failureLabel: String,
    mutate: (Path) -> McpMutationResult,
  ): McpRegistrationOutcome {
    val outcomes = mutableListOf<McpProfileOutcome>()
    val failures = mutableListOf<Pair<Path, Throwable>>()

    profilePaths.forEach { perProfilePath ->
      runCatching { mutate(perProfilePath) }
        .onSuccess { result -> outcomes.add(McpProfileOutcome(result.configPath, result.changed)) }
        .onFailure { error -> failures.add(perProfilePath to error) }
    }

    if (failures.isNotEmpty()) {
      val names = failures.joinToString("; ") { (path, error) -> "$path: ${error.message}" }
      return McpRegistrationOutcome.ProfilesFailed(
        "Failed to update $failureLabel MCP config for profile(s): $names",
        succeeded = outcomes.toList(),
      )
    }

    return McpRegistrationOutcome.Applied(
      McpMutationResult(
        agent = agent,
        configPath = representativePath.toFileLocation(),
        changed = outcomes.any { it.changed },
        profiles = outcomes,
      ),
    )
  }
}

enum class McpConfigFormat {
  JSON,
  TOML,
}
