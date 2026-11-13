package skillbill.infrastructure.skills.install.runtime

import skillbill.infrastructure.skills.install.plan.SUPPORTED_AGENTS
import skillbill.infrastructure.skills.install.plan.agentPaths
import skillbill.infrastructure.skills.install.plan.claudeConfigRoot
import skillbill.infrastructure.skills.install.plan.claudeConfigRoots
import skillbill.infrastructure.skills.install.plan.codexConfigRoots
import skillbill.infrastructure.skills.install.plan.installConfigRoots
import skillbill.infrastructure.skills.install.plan.resolveInstallHome
import skillbill.infrastructure.skills.nativeagent.support.devinAgentsDirectory
import skillbill.infrastructure.skills.nativeagent.support.devinConfigRoot
import skillbill.install.model.SupportedAgent
import skillbill.ports.system.HostPlatformPort
import java.nio.file.Path

internal object InstallOperationsPaths {
  fun agentPath(
    agent: String,
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort,
  ): Path {
    val supported = SupportedAgent.fromWire(agent)
    require(supported in SUPPORTED_AGENTS) {
      "Unknown agent '$agent'. Supported agents: ${SUPPORTED_AGENTS.joinToString { it.wireValue }}."
    }
    val resolvedHome = resolveInstallHome(home, hostPlatform)
    return agentPaths(resolvedHome, installConfigRoots(resolvedHome, environment, hostPlatform)).getValue(supported)
  }

  fun claudeRoots(
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort,
  ): List<Path> {
    val resolvedHome = resolveInstallHome(home, hostPlatform)
    return claudeConfigRoots(resolvedHome, environment)
  }

  fun codexRoots(
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort,
  ): List<Path> {
    val resolvedHome = resolveInstallHome(home, hostPlatform)
    return codexConfigRoots(resolvedHome, environment)
  }

  fun claudeAgentsPath(
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort,
  ): Path {
    val resolvedHome = resolveInstallHome(home, hostPlatform)
    return claudeConfigRoot(resolvedHome, environment).resolve("agents")
  }

  fun cursorAgentsPath(
    home: Path?,
    hostPlatform: HostPlatformPort,
  ): Path {
    val resolvedHome = resolveInstallHome(home, hostPlatform)
    return resolvedHome.resolve(requireNotNull(SupportedAgent.CURSOR.simpleHomeDirectory)).resolve("agents")
  }

  fun devinAgentsPath(
    home: Path?,
    environment: Map<String, String>,
    hostPlatform: HostPlatformPort,
  ): Path {
    val resolvedHome = resolveInstallHome(home, hostPlatform)
    return devinAgentsDirectory(devinConfigRoot(resolvedHome, environment, hostPlatform))
  }
}
