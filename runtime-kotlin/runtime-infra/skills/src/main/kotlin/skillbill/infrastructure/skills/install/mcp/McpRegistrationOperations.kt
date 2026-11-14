package skillbill.infrastructure.skills.install.mcp

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.install.opencode.OpenCodeRoots
import skillbill.infrastructure.skills.install.opencode.opencodeConfigRoots
import skillbill.infrastructure.skills.install.plan.codexConfigRoots
import skillbill.infrastructure.skills.nativeagent.support.claudeConfigRoots
import skillbill.infrastructure.skills.nativeagent.support.devinConfigRoot
import skillbill.infrastructure.skills.nativeagent.support.devinMcpConfigPath
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpProfileOutcome
import skillbill.install.model.McpRegistrationOutcome
import skillbill.install.model.SupportedAgent
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.repository.toFileLocation
import skillbill.ports.system.HostPlatformPort
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Path

object McpRegistrationOperations {
  fun register(
    agent: String,
    runtimeMcpBin: Path,
    home: Path? = null,
    environment: Map<String, String> = emptyMap(),
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): McpRegistrationOutcome {
    val resolvedHome = resolveUserHome(home, hostPlatform)
    return registerWithProfileEnvironment(
      InstallMcpRegistrationRequest(
        agent,
        runtimeMcpBin,
        resolvedHome,
        resolvedProfileEnvironment(environment, hostPlatform),
      ),
      hostPlatform,
    )
  }

  internal fun registerWithProfileEnvironment(
    request: InstallMcpRegistrationRequest,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
    diagnostics: RuntimeDiagnostics? = null,
  ): McpRegistrationOutcome {
    val agent = request.agent
    val runtimeMcpBin = request.runtimeMcpBin
    val home = request.home
    val profileEnvironment = checkNotNull(request.profileEnvironment)
    val resolvedHome = resolveUserHome(home, hostPlatform)
    val installAgent = SupportedAgent.fromId(agent)
    val openCodeRoots =
      if (installAgent == SupportedAgent.OPENCODE) {
        resolveOpenCodeRoots(resolvedHome, profileEnvironment, diagnostics)
      } else {
        null
      }
    val command =
      if (installAgent == SupportedAgent.OPENCODE) {
        val path =
          if (runtimeMcpBin.isAbsolute) {
            runtimeMcpBin
          } else {
            openCodeRoots?.workingDirectory?.resolve(runtimeMcpBin) ?: resolvedHome.resolve(runtimeMcpBin)
          }
        path.toAbsolutePath().normalize().toString()
      } else {
        runtimeMcpBin.toAbsolutePath().normalize().toString()
      }
    return when (installAgent) {
      SupportedAgent.CLAUDE ->
        claudeFanOut(agent, resolvedHome, profileEnvironment) { perProfilePath ->
          McpJsonConfig.register(agent, perProfilePath, command)
        }
      SupportedAgent.CODEX ->
        codexFanOut(agent, resolvedHome, profileEnvironment) { perProfilePath ->
          McpTomlConfig.register(agent, perProfilePath, command)
        }
      SupportedAgent.CURSOR ->
        McpRegistrationOutcome.Applied(
          McpJsonConfig.register(agent, configPathFor(installAgent, resolvedHome), command),
        )
      SupportedAgent.OPENCODE ->
        checkNotNull(openCodeRoots).let { roots ->
          reportOpenCodeErrors(diagnostics) {
            OpenCodeMcpConfig(resolvedHome, profileEnvironment, roots, hostPlatform)
              .register(openCodeTargetPath(roots), command)
          }
        }
      SupportedAgent.DEVIN ->
        McpRegistrationOutcome.Applied(
          McpJsonConfig.register(
            agent,
            configPathFor(installAgent, resolvedHome, profileEnvironment.asEnvironmentMap(), hostPlatform),
            command,
            McpJsonEntryShape.PLAIN_STDIO,
          ),
        )
    }
  }

  fun unregister(
    agent: String,
    home: Path? = null,
    environment: Map<String, String> = emptyMap(),
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): McpRegistrationOutcome {
    val resolvedHome = resolveUserHome(home, hostPlatform)
    return unregisterWithProfileEnvironment(
      InstallMcpUnregistrationRequest(
        agent,
        resolvedHome,
        resolvedProfileEnvironment(environment, hostPlatform),
      ),
      hostPlatform,
    )
  }

  internal fun unregisterWithProfileEnvironment(
    request: InstallMcpUnregistrationRequest,
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
    diagnostics: RuntimeDiagnostics? = null,
  ): McpRegistrationOutcome {
    val agent = request.agent
    val home = request.home
    val profileEnvironment = checkNotNull(request.profileEnvironment)
    val resolvedHome = resolveUserHome(home, hostPlatform)
    return when (val installAgent = SupportedAgent.fromId(agent)) {
      SupportedAgent.CLAUDE ->
        claudeFanOut(agent, resolvedHome, profileEnvironment) { perProfilePath ->
          McpJsonConfig.unregister(agent, perProfilePath)
        }
      SupportedAgent.CODEX ->
        codexFanOut(agent, resolvedHome, profileEnvironment) { perProfilePath ->
          McpTomlConfig.unregister(agent, perProfilePath)
        }
      SupportedAgent.CURSOR ->
        McpRegistrationOutcome.Applied(McpJsonConfig.unregister(agent, configPathFor(installAgent, resolvedHome)))
      SupportedAgent.OPENCODE ->
        resolveOpenCodeRoots(resolvedHome, profileEnvironment, diagnostics).let { roots ->
          reportOpenCodeErrors(diagnostics) {
            OpenCodeMcpConfig(resolvedHome, profileEnvironment, roots, hostPlatform)
              .unregister(openCodeTargetPath(roots))
          }
        }
      SupportedAgent.DEVIN ->
        McpRegistrationOutcome.Applied(
          McpJsonConfig.unregister(
            agent,
            configPathFor(installAgent, resolvedHome, profileEnvironment.asEnvironmentMap(), hostPlatform),
          ),
        )
    }
  }

  fun configFormatFor(agent: SupportedAgent): McpConfigFormat =
    if (agent.mcpUsesToml) McpConfigFormat.TOML else McpConfigFormat.JSON

  fun configPathFor(
    agent: SupportedAgent,
    home: Path,
    environment: Map<String, String> = emptyMap(),
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): Path =
    when (agent) {
      SupportedAgent.DEVIN -> devinMcpConfigPath(devinConfigRoot(home, environment, hostPlatform))
      SupportedAgent.CLAUDE,
      SupportedAgent.CODEX,
      SupportedAgent.CURSOR,
      SupportedAgent.OPENCODE,
      -> home.resolve(agent.mcpConfigRelativePath)
    }

  private fun claudeProfileConfigPaths(
    home: Path,
    environment: InstallMcpProfileEnvironment,
  ): List<Path> {
    val defaultRoot =
      home.resolve(requireNotNull(SupportedAgent.CLAUDE.profileDirectoryPrefix).removeSuffix("-"))
        .toAbsolutePath()
        .normalize()
    return claudeConfigRoots(home, environment.asEnvironmentMap()).map { root ->
      if (root == defaultRoot) {
        home.resolve(SupportedAgent.CLAUDE.mcpProfileFileName)
      } else {
        root.resolve(SupportedAgent.CLAUDE.mcpProfileFileName)
      }
    }
  }

  private fun codexProfileConfigPaths(
    home: Path,
    environment: InstallMcpProfileEnvironment,
  ): List<Path> {
    val roots = codexConfigRoots(home, environment.asEnvironmentMap())
    return if (roots.isNotEmpty()) {
      roots.map { root -> root.resolve(SupportedAgent.CODEX.mcpProfileFileName) }
    } else {
      listOf(home.resolve(SupportedAgent.CODEX.mcpConfigRelativePath))
    }
  }

  private fun claudeFanOut(
    agent: String,
    home: Path,
    environment: InstallMcpProfileEnvironment,
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
    environment: InstallMcpProfileEnvironment,
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
      val result =
        catchingNonCancellation { mutate(perProfilePath) }.getOrElse { error ->
          if (error is InterruptedIOException || error is ClosedByInterruptException) {
            Thread.currentThread().interrupt()
            throw error
          }
          failures.add(perProfilePath to error)
          return@forEach
        }
      outcomes.add(McpProfileOutcome(result.configPath, result.changed))
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

private inline fun reportOpenCodeErrors(
  diagnostics: RuntimeDiagnostics?,
  operation: () -> McpRegistrationOutcome,
): McpRegistrationOutcome =
  try {
    val outcome = operation()
    when (outcome) {
      is McpRegistrationOutcome.Applied -> outcome.mutation.coverage?.let { diagnostics?.info(it) }
      is McpRegistrationOutcome.ProfilesFailed -> diagnostics?.warning(outcome.message)
    }
    outcome
  } catch (error: SkillBillRuntimeException) {
    diagnostics?.warning("OpenCode MCP configuration operation stopped before a safe result.", error)
    throw error
  }

private fun resolveOpenCodeRoots(
  home: Path,
  profileEnvironment: InstallMcpProfileEnvironment,
  diagnostics: RuntimeDiagnostics?,
): OpenCodeRoots =
  try {
    opencodeConfigRoots(home, profileEnvironment.asEnvironmentMap())
  } catch (error: SkillBillRuntimeException) {
    diagnostics?.warning("OpenCode MCP source inspection stopped before a safe result.", error)
    throw error
  }

private fun resolvedProfileEnvironment(
  environment: Map<String, String>,
  hostPlatform: HostPlatformPort,
): InstallMcpProfileEnvironment =
  InstallMcpProfileEnvironment.from(
    resolveEnvironmentMap(environment, hostPlatform).let { resolved ->
      if (InstallMcpProfileEnvironment.PWD_ENV in resolved) {
        resolved
      } else {
        resolved + (InstallMcpProfileEnvironment.PWD_ENV to hostPlatform.resolveWorkingDirectory().toString())
      }
    },
  ) ?: InstallMcpProfileEnvironment(
    workingDirectory = hostPlatform.resolveWorkingDirectory().toString(),
  )

enum class McpConfigFormat {
  JSON,
  TOML,
}
