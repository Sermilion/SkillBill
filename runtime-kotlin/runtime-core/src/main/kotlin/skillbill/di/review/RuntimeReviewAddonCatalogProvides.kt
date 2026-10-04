package skillbill.di.review

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.skills.agentaddon.AgentAddonSelectionResolver
import skillbill.infrastructure.skills.externaladdon.FileExternalAgentAddonSourceConfigStore
import skillbill.infrastructure.skills.install.FileSystemInstalledPlatformPackCatalog
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort

internal interface RuntimeReviewAddonCatalogProvides {
  @Provides
  fun installedPlatformPackCatalogPort(
    adapter: FileSystemInstalledPlatformPackCatalog,
  ): InstalledPlatformPackCatalogPort = adapter

  @Provides
  fun agentAddonSelectionPort(diagnostics: RuntimeDiagnostics): AgentAddonSelectionPort =
    AgentAddonSelectionResolver(diagnostics)

  @Provides
  fun externalAgentAddonSourceConfigPort(
    store: FileExternalAgentAddonSourceConfigStore,
  ): ExternalAgentAddonSourceConfigPort = store
}
