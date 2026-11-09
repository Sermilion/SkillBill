package skillbill.application.install.model

import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationProfile
import skillbill.install.model.PlatformPackSelection
import java.nio.file.Path

data class ResolveInstallSelectionRequest(
  val home: Path,
  val environment: Map<String, String>,
  val platformPacksRoot: Path,
  val skillsRoot: Path,
  val githubRequested: Boolean,
  val reuseLastSelection: Boolean,
  val explicitPlatformSlugs: Set<String>,
  val explicitTelemetry: InstallTelemetryLevel?,
)

data class ResolvedInstallSelection(
  val installationProfile: InstallationProfile,
  val platformPackSelection: PlatformPackSelection,
  val telemetryLevel: InstallTelemetryLevel,
  val agentSelectionMode: InstallAgentSelectionMode,
  val agentIds: List<String>,
  val agentPaths: List<Path>,
  val mcpRegister: Boolean,
)

sealed interface InstallSelectionResolutionOutcome {
  data class Ready(
    val selection: ResolvedInstallSelection,
  ) : InstallSelectionResolutionOutcome

  data class Refused(
    val message: String,
  ) : InstallSelectionResolutionOutcome
}
