package skillbill.infrastructure.launcher.mcp

import skillbill.ports.system.HostPlatformPort
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal fun managedSourcePaths(
  host: HostPlatformPort,
  environment: Map<String, String>,
): List<Path>? {
  val os = host.osName.lowercase()
  return when {
    os.contains("linux") -> listOf(Path.of("/etc/opencode"))
    os.contains("mac") || os.contains("darwin") -> macManagedSources(host)
    os.contains("win") -> windowsManagedSources(environment)
    else -> null
  }
}

private fun macManagedSources(host: HostPlatformPort): List<Path>? {
  val username = host.resolveUserName()?.takeIf(String::isNotBlank) ?: return null
  if (username.any { it == '/' || it == '\\' }) return null
  if (username == "." || username == "..") return null
  return listOf(
    Path.of("/Library/Application Support/opencode"),
    Path.of("/Library/Managed Preferences", username, "ai.opencode.managed.plist"),
    Path.of("/Library/Managed Preferences/ai.opencode.managed.plist"),
  )
}

private fun windowsManagedSources(environment: Map<String, String>): List<Path>? {
  val programData =
    environment[OpenCodeReviewEnvironmentKeys.PROGRAM_DATA]
      ?: environment[OpenCodeReviewEnvironmentKeys.PROGRAM_DATA_UPPER]
      ?: return null
  val root = Path.of(programData)
  if (!root.isAbsolute) return null
  return listOf(root.resolve("opencode"))
}

internal fun managedSourceRefusal(paths: List<Path>): String? {
  paths.forEach { path ->
    val components = generateSequence(path) { it.parent }.toList().asReversed()
    for (component in components) {
      val attributes =
        try {
          Files.readAttributes(component, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
          break
        }
      if (attributes.isSymbolicLink || component == path || !attributes.isDirectory) {
        return "Managed OpenCode configuration is present or indeterminate; isolated review is unavailable."
      }
    }
  }
  return null
}
