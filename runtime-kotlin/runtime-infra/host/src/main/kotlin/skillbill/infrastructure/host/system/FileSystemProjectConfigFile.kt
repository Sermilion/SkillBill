package skillbill.infrastructure.host.system

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.ports.config.ProjectConfigFilePort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import java.io.IOException
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
    val legacy = repoRoot.resolve(LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY).resolve(REPO_LOCAL_CONFIG_FILE_NAME)
    return FileConfigDocumentLocks.withLocks(listOf(configPath, legacy)) {
      val actualConfigPath = FileConfigDocumentLocks.lockedPath(configPath)
      try {
        if (readableRepoLocalConfigPath(diagnostics, repoRoot, actualConfigPath) == null) {
          Files.createDirectories(actualConfigPath.parent)
          Files.writeString(actualConfigPath, DEFAULT_CONFIG_CONTENT)
        }
      } catch (error: IOException) {
        throw configDocumentIoException(actualConfigPath, "initialize", error)
      } catch (error: SecurityException) {
        throw configDocumentIoException(actualConfigPath, "initialize", error)
      }
      actualConfigPath
    }
  }
}
