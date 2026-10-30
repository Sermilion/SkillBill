package skillbill.ports.install.mcp.model

import skillbill.install.model.McpRegistrationOutcome
import java.nio.file.Path

data class InstallMcpProfileEnvironment(
  val claudeConfigDir: String? = null,
  val codexHome: String? = null,
) {
  fun asEnvironmentMap(): Map<String, String> =
    buildMap {
      claudeConfigDir?.let { put(CLAUDE_CONFIG_DIR_ENV, it) }
      codexHome?.let { put(CODEX_HOME_ENV, it) }
    }

  companion object {
    const val CLAUDE_CONFIG_DIR_ENV: String = "CLAUDE_CONFIG_DIR"
    const val CODEX_HOME_ENV: String = "CODEX_HOME"

    fun from(environment: Map<String, String>): InstallMcpProfileEnvironment? {
      val claudeConfigDir = environment[CLAUDE_CONFIG_DIR_ENV]
      val codexHome = environment[CODEX_HOME_ENV]
      return if (claudeConfigDir == null && codexHome == null) {
        null
      } else {
        InstallMcpProfileEnvironment(claudeConfigDir, codexHome)
      }
    }
  }
}

data class InstallMcpRegistrationRequest(
  val agent: String,
  val runtimeMcpBin: Path,
  val home: Path?,
  val profileEnvironment: InstallMcpProfileEnvironment? = null,
)

data class InstallMcpUnregistrationRequest(
  val agent: String,
  val home: Path?,
  val profileEnvironment: InstallMcpProfileEnvironment? = null,
)

data class InstallMcpRegistrationResult(
  val outcome: McpRegistrationOutcome,
)
