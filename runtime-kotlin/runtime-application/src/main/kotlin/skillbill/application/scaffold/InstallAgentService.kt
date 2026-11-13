package skillbill.application.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.agent.model.ClaudeConfigRootsRequest
import skillbill.ports.install.agent.model.CodexConfigRootsRequest
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsRequest
import skillbill.ports.install.agent.model.InstallAgentDirectoryRequest
import skillbill.ports.install.agent.model.InstallAgentPathRequest
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupRequest
import java.nio.file.Path

@Inject
class InstallAgentService(
  private val agentTargetPort: InstallAgentTargetPort,
) {
  fun agentPath(
    agent: String,
    home: Path? = null,
    environment: Map<String, String> = emptyMap(),
  ): Path =
    agentTargetPort.agentPath(InstallAgentPathRequest(agent = agent, home = home, environment = environment)).path

  fun detectAgentTargets(
    home: Path? = null,
    environment: Map<String, String> = emptyMap(),
  ) = agentTargetPort.detectAgentTargets(DetectInstallAgentTargetsRequest(home, environment)).targets

  fun claudeRoots(
    home: Path? = null,
    environment: Map<String, String>,
  ): List<Path> =
    agentTargetPort.claudeConfigRoots(ClaudeConfigRootsRequest(home = home, environment = environment)).roots

  fun codexRoots(
    home: Path? = null,
    environment: Map<String, String>,
  ): List<Path> =
    agentTargetPort.codexConfigRoots(CodexConfigRootsRequest(home = home, environment = environment)).roots

  fun codexAgentsPath(
    home: Path? = null,
    environment: Map<String, String> = emptyMap(),
  ): Path =
    agentTargetPort.agentDirectory(
      InstallAgentDirectoryRequest(agent = "codex", home = home, environment = environment),
    ).path

  fun claudeAgentsPath(home: Path? = null): Path = agentDirectory("claude", home)

  fun cursorAgentsPath(home: Path? = null): Path = agentDirectory("cursor", home)

  fun cleanupAgentTarget(
    targetDir: Path,
    skillNames: List<String>,
    legacyNames: List<String>,
    managedInstallMarker: String,
    home: Path? = null,
  ) = agentTargetPort.cleanupAgentTarget(
    InstallAgentTargetCleanupRequest(
      targetDir = targetDir,
      skillNames = skillNames,
      legacyNames = legacyNames,
      managedInstallMarker = managedInstallMarker,
      home = home,
    ),
  ).cleanup

  private fun agentDirectory(
    agent: String,
    home: Path?,
  ): Path = agentTargetPort.agentDirectory(InstallAgentDirectoryRequest(agent = agent, home = home)).path
}
