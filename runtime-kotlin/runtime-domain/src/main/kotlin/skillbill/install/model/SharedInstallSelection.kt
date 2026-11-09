package skillbill.install.model

data class SharedInstallSelection(
  val installationProfile: InstallationProfile = InstallationProfile.ORDINARY,
  val selectedAgents: Set<SupportedAgent>,
  val platformPackSelection: PlatformPackSelection,
  val telemetryLevel: InstallTelemetryLevel,
  val mcpRegistrationChoice: McpRegistrationChoice,
)

enum class InstallationProfile(
  val wireValue: String,
) {
  ORDINARY("ordinary"),
  GITHUB("github"),
  ;

  companion object {
    fun fromWire(value: String): InstallationProfile? = entries.firstOrNull { it.wireValue == value }
  }
}
