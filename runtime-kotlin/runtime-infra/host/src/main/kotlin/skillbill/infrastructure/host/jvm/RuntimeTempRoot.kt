package skillbill.infrastructure.host.jvm

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val runtimeTempLog: Logger = Logger.getLogger("skillbill.runtime.temp")
private val sharedTempRoot: Path = Path.of("/tmp")

fun resolveRuntimeTempRoot(
  configuredTempDir: String?,
  environment: Map<String, String>,
  userHome: Path,
): Path {
  val configured = configuredTempDir?.takeIf(String::isNotBlank)?.let { Path.of(it) }
  if (configured != null && isPrivateTempRoot(configured)) return configured
  val stateScratch = resolveTelemetryStateDir(environment, userHome).resolve("scratch").resolve("jvm")
  return try {
    Files.createDirectories(stateScratch)
  } catch (error: IOException) {
    val fallback = configured ?: sharedTempRoot
    runtimeTempLog.log(Level.WARNING, "could not create temp root $stateScratch; using $fallback", error)
    fallback
  }
}

private fun isPrivateTempRoot(configured: Path): Boolean {
  if (!Files.isDirectory(configured)) {
    runtimeTempLog.warning("configured temp root $configured is not a directory; using the state scratch directory")
    return false
  }
  val realPath = realPathOrNull(configured) ?: return false
  return realPath != realPathOrNull(sharedTempRoot)
}

private fun realPathOrNull(path: Path): Path? =
  try {
    path.toRealPath()
  } catch (error: IOException) {
    runtimeTempLog.log(Level.WARNING, "could not read the real path of $path", error)
    null
  }
