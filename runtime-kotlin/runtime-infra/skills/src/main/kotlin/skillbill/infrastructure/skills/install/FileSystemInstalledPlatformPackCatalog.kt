package skillbill.infrastructure.skills.install
import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.platformpack.loader.validatePlatformPackFallbacks
import skillbill.model.EnvironmentContext
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files

@Inject
class FileSystemInstalledPlatformPackCatalog(
  private val environment: EnvironmentContext,
) : InstalledPlatformPackCatalogPort {
  override fun manifests(): List<PlatformManifest> {
    val catalogRoots =
      NativeAgentLinkInventory.read(
        environment.userHome,
        emptyList(),
        environment = resolveEnvironmentMap(environment.environment),
      )
        .map { it.cacheTargetPath.parent.parent.resolve("review-catalog/platform-packs") }
        .distinct()
        .filter(Files::isDirectory)
    val root = catalogRoots.maxByOrNull { Files.getLastModifiedTime(it).toMillis() } ?: return emptyList()
    return Files.list(root).use { stream ->
      stream
        .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
        .sorted()
        .map(::loadPlatformManifest)
        .toList()
    }.also(::validatePlatformPackFallbacks)
  }
}
