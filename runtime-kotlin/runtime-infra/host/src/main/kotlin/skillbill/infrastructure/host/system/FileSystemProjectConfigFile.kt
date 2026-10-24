package skillbill.infrastructure.host.system

import me.tatarka.inject.annotations.Inject
import skillbill.ports.config.ProjectConfigFilePort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import java.nio.file.Files
import java.nio.file.Path

private const val DEFAULT_CONFIG_CONTENT: String = "{}\n"

@Inject
class FileSystemProjectConfigFile(
  private val diagnostics: RuntimeDiagnostics,
  private val projectStore: ProjectStorePort,
) : ProjectConfigFilePort {
  override fun ensureConfigFile(repoRoot: Path): Path {
    val configPath = projectStore.resolve(repoRoot).configPath
    if (readableRepoLocalConfigPath(diagnostics, repoRoot, configPath) == null) {
      Files.createDirectories(configPath.parent)
      Files.writeString(configPath, DEFAULT_CONFIG_CONTENT)
    }
    return configPath
  }
}
