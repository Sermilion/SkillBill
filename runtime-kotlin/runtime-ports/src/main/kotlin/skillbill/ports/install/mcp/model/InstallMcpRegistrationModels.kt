package skillbill.ports.install.mcp.model

import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.contracts.system.OpenCodeEnvironmentKeys
import skillbill.install.model.McpRegistrationOutcome
import java.nio.file.Path

data class InstallMcpProfileEnvironment(
  val claudeConfigDir: String? = null,
  val codexHome: String? = null,
  val opencodeConfigDir: String? = null,
  val opencodeConfig: String? = null,
  val opencodeConfigContent: String? = null,
  val xdgConfigHome: String? = null,
  val workingDirectory: String? = null,
  val disableProjectConfig: String? = null,
  val databasePath: String? = null,
  val programData: String? = null,
  val appData: String? = null,
) {
  fun asEnvironmentMap(): Map<String, String> =
    buildMap {
      claudeConfigDir?.let { put(CLAUDE_CONFIG_DIR_ENV, it) }
      codexHome?.let { put(CODEX_HOME_ENV, it) }
      opencodeConfigDir?.let { put(OPENCODE_CONFIG_DIR_ENV, it) }
      opencodeConfig?.let { put(OPENCODE_CONFIG_ENV, it) }
      opencodeConfigContent?.let { put(OPENCODE_CONFIG_CONTENT_ENV, it) }
      xdgConfigHome?.let { put(XDG_CONFIG_HOME_ENV, it) }
      workingDirectory?.let { put(PWD_ENV, it) }
      disableProjectConfig?.let { put(OPENCODE_DISABLE_PROJECT_CONFIG_ENV, it) }
      databasePath?.let { put(RuntimeDatabaseEnvironmentKeys.DATABASE_PATH, it) }
      programData?.let { put(PROGRAM_DATA_ENV, it) }
      appData?.let { put(APPDATA_ENV, it) }
    }

  companion object {
    const val CLAUDE_CONFIG_DIR_ENV: String = "CLAUDE_CONFIG_DIR"
    const val CODEX_HOME_ENV: String = "CODEX_HOME"
    const val OPENCODE_CONFIG_DIR_ENV: String = AgentHomeEnvironmentKeys.OPENCODE_CONFIG_DIR
    const val OPENCODE_CONFIG_ENV: String = OpenCodeEnvironmentKeys.CONFIG_FILE
    const val OPENCODE_CONFIG_CONTENT_ENV: String = OpenCodeEnvironmentKeys.CONFIG_CONTENT
    const val XDG_CONFIG_HOME_ENV: String = OpenCodeEnvironmentKeys.XDG_CONFIG_HOME
    const val PWD_ENV: String = "PWD"
    const val OPENCODE_DISABLE_PROJECT_CONFIG_ENV: String = OpenCodeEnvironmentKeys.DISABLE_PROJECT_CONFIG
    const val PROGRAM_DATA_ENV: String = "ProgramData"
    const val APPDATA_ENV: String = "APPDATA"

    fun from(environment: Map<String, String>): InstallMcpProfileEnvironment? {
      val claudeConfigDir = environment[CLAUDE_CONFIG_DIR_ENV]
      val codexHome = environment[CODEX_HOME_ENV]
      val opencodeConfigDir = environment[OPENCODE_CONFIG_DIR_ENV]
      val opencodeConfig = environment[OPENCODE_CONFIG_ENV]
      val opencodeConfigContent = environment[OPENCODE_CONFIG_CONTENT_ENV]
      val xdgConfigHome = environment[XDG_CONFIG_HOME_ENV]
      val workingDirectory = environment[PWD_ENV]
      val disableProjectConfig = environment[OPENCODE_DISABLE_PROJECT_CONFIG_ENV]
      val databasePath = environment[RuntimeDatabaseEnvironmentKeys.DATABASE_PATH]
      val programData = environment[PROGRAM_DATA_ENV]
      val profile =
        InstallMcpProfileEnvironment(
          claudeConfigDir,
          codexHome,
          opencodeConfigDir,
          opencodeConfig,
          opencodeConfigContent,
          xdgConfigHome,
          workingDirectory,
          disableProjectConfig,
          databasePath,
          programData,
        )
      return profile.takeIf { it.asEnvironmentMap().isNotEmpty() }
      val appData = environment[APPDATA_ENV]
      return if (claudeConfigDir == null && codexHome == null && appData == null) {
        null
      } else {
        InstallMcpProfileEnvironment(claudeConfigDir, codexHome, appData)
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
  val expectedRuntimeMcpBin: Path? = null,
)

data class InstallMcpRegistrationResult(
  val outcome: McpRegistrationOutcome,
)
