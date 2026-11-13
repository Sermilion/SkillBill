package skillbill.infrastructure.skills.nativeagent.support

import skillbill.error.shellcontent.agentDiscoveryFailed
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import skillbill.ports.system.HostPlatformPort
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val DEVIN_APPDATA_ENV: String = InstallMcpProfileEnvironment.APPDATA_ENV

private const val DEVIN_DIRECTORY_NAME: String = "devin"
private const val DEVIN_UNIX_CONFIG_PARENT: String = ".config"
private const val DEVIN_SKILLS_DIRECTORY: String = "skills"
private const val DEVIN_AGENTS_DIRECTORY: String = "agents"
private const val DEVIN_MCP_CONFIG_FILE: String = "mcp_config.json"

fun devinConfigRoot(
  home: Path,
  environment: Map<String, String>,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
): Path {
  if (!hostPlatform.osName.startsWith("Windows", ignoreCase = true)) {
    return home.resolve(DEVIN_UNIX_CONFIG_PARENT).resolve(DEVIN_DIRECTORY_NAME).toAbsolutePath().normalize()
  }
  val appData = resolveEnvironmentMap(environment, hostPlatform)[DEVIN_APPDATA_ENV]?.trim().orEmpty()
  val appDataPath =
    try {
      appData.takeIf(String::isNotEmpty)?.let(Path::of)
    } catch (_: InvalidPathException) {
      null
    }
  if (appDataPath == null || !appDataPath.isAbsolute) {
    throw agentDiscoveryFailed(
      "resolve the Devin configuration root",
      "%$DEVIN_APPDATA_ENV%\\$DEVIN_DIRECTORY_NAME",
      IllegalArgumentException(
        "$DEVIN_APPDATA_ENV must be set to an absolute path on Windows; set it to your roaming application data " +
          "directory and retry.",
      ),
    )
  }
  return appDataPath.resolve(DEVIN_DIRECTORY_NAME).toAbsolutePath().normalize()
}

fun devinSkillsDirectory(root: Path): Path = root.resolve(DEVIN_SKILLS_DIRECTORY)

fun devinAgentsDirectory(root: Path): Path = root.resolve(DEVIN_AGENTS_DIRECTORY)

fun devinMcpConfigPath(root: Path): Path = root.resolve(DEVIN_MCP_CONFIG_FILE)
