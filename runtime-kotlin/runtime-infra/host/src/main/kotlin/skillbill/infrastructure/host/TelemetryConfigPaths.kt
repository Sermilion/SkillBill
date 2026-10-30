package skillbill.infrastructure.host
import skillbill.infrastructure.host.system.FileConfigDocumentLocks
import skillbill.infrastructure.host.system.configDocumentIoException
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

fun resolveTelemetryStateDir(
  environment: Map<String, String>,
  userHome: Path,
): Path =
  environment[STATE_DIR_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    expandAndNormalizeTelemetryPath(it, userHome)
  } ?: userHome.resolve(".skill-bill").toAbsolutePath().normalize()

fun resolveTelemetryConfigPath(
  environment: Map<String, String>,
  userHome: Path,
): Path =
  FileConfigDocumentLocks.withLocks(resolveTelemetryConfigLockPaths(environment, userHome)) {
    FileConfigDocumentLocks.lockedPath(resolveTelemetryConfigPathLocked(environment, userHome))
  }

private fun resolveTelemetryConfigPathLocked(
  environment: Map<String, String>,
  userHome: Path,
): Path {
  val candidates = resolveTelemetryConfigCandidatePaths(environment, userHome)
  if (environment[CONFIG_ENVIRONMENT_KEY]?.isNotBlank() == true) return candidates.single()
  val durableConfig = candidates.first()
  if (configPathAttributes(durableConfig) != null) return durableConfig
  val legacyConfig = candidates.last()
  if (configPathAttributes(legacyConfig) != null) return legacyConfig
  return durableConfig
}

fun resolveTelemetryConfigCandidatePaths(
  environment: Map<String, String>,
  userHome: Path,
): List<Path> =
  environment[CONFIG_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    listOf(expandAndNormalizeTelemetryPath(it, userHome))
  } ?: listOf(
    userHome.resolve(".config").resolve("skill-bill").resolve("config.json").toAbsolutePath().normalize(),
    resolveTelemetryStateDir(environment, userHome).resolve("config.json").toAbsolutePath().normalize(),
  ).distinct()

fun resolveTelemetryConfigLockPaths(
  environment: Map<String, String>,
  userHome: Path,
): List<Path> =
  listOf(
    environment[CONFIG_ENVIRONMENT_KEY]?.takeIf(
      String::isNotBlank,
    )?.let { expandAndNormalizeTelemetryPath(it, userHome) },
    userHome.resolve(".config").resolve("skill-bill").resolve("config.json").toAbsolutePath().normalize(),
    resolveTelemetryStateDir(environment, userHome).resolve("config.json").toAbsolutePath().normalize(),
  ).filterNotNull()

fun resolveTelemetryConfigMigrationLockPaths(
  environment: Map<String, String>,
  userHome: Path,
): List<Path> = resolveTelemetryConfigLockPaths(environment, userHome)

internal fun expandAndNormalizeTelemetryPath(
  rawPath: String,
  userHome: Path,
): Path {
  val normalized =
    when {
      rawPath == "~" -> userHome.toString()
      rawPath.startsWith("~/") -> userHome.resolve(rawPath.removePrefix("~/")).toString()
      else -> rawPath
    }
  return Path.of(normalized).toAbsolutePath().normalize()
}

private fun configPathAttributes(path: Path): BasicFileAttributes? =
  try {
    Files.readAttributes(path, BasicFileAttributes::class.java)
  } catch (_: NoSuchFileException) {
    null
  } catch (error: IOException) {
    throw configDocumentIoException(path, "resolve", error)
  } catch (error: SecurityException) {
    throw configDocumentIoException(path, "resolve", error)
  }
