package skillbill.ports.install.platformpack

import skillbill.ports.install.platformpack.model.ExternalPlatformPackPathResolveRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackPathResolveResult
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceConfigRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceConfigResult
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceRegistrationRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceUnregisterRequest
import java.nio.file.Path

interface ExternalPlatformPackSourceConfigPort {
  /** Holds the shared machine config lock across registration and the scaffold commit or rollback. */
  fun <T> withConfigTransaction(
    userHome: Path,
    environment: Map<String, String>,
    action: () -> T,
  ): T

  fun readExternalPlatformPackSources(
    request: ExternalPlatformPackSourceConfigRequest,
  ): ExternalPlatformPackSourceConfigResult

  fun registerExternalPlatformPackSource(
    request: ExternalPlatformPackSourceRegistrationRequest,
  ): ExternalPlatformPackSourceConfigResult

  fun unregisterExternalPlatformPackSource(
    request: ExternalPlatformPackSourceUnregisterRequest,
  ): ExternalPlatformPackSourceConfigResult

  fun resolveExternalPlatformPackPath(
    request: ExternalPlatformPackPathResolveRequest,
  ): ExternalPlatformPackPathResolveResult
}
