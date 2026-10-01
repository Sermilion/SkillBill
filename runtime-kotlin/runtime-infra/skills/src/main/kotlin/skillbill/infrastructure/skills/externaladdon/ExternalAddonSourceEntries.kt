package skillbill.infrastructure.skills.externaladdon

import skillbill.error.core.ExternalAddonConfigError
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.readTelemetryConfigFile
import skillbill.infrastructure.host.resolveTelemetryConfigPath
import skillbill.scaffold.model.SkillKind
import java.nio.file.Files
import java.nio.file.Path

internal data class ExternalAddonSourceEntries(
  val configPath: Path,
  val entries: List<*>,
)

internal fun readExternalAddonSourceEntries(
  environment: Map<String, String>,
  userHome: Path,
  listShapeMessage: String,
): ExternalAddonSourceEntries? {
  val configPath = resolveTelemetryConfigPath(environment, userHome)
  if (!Files.exists(configPath)) return null
  val payload =
    try {
      readTelemetryConfigFile(configPath)?.payload
    } catch (error: IllegalArgumentException) {
      throw ExternalAddonConfigError(error.message.orEmpty(), error)
    } ?: return null
  val raw = payload["external_addon_sources"] ?: return null
  if (raw !is List<*>) {
    throw ExternalAddonConfigError(
      "External addon config at '$configPath': 'external_addon_sources' $listShapeMessage",
    )
  }
  return ExternalAddonSourceEntries(configPath, raw)
}

internal fun resolveSourcePath(
  userHome: Path,
  rawPath: String,
): Path {
  val expanded =
    when {
      rawPath == "~" -> userHome.toString()
      rawPath.startsWith("~/") -> userHome.resolve(rawPath.removePrefix("~/")).toString()
      else -> rawPath
    }
  val candidate = Path.of(expanded)
  return if (candidate.isAbsolute) {
    candidate.normalize()
  } else {
    JdkHostPlatformPort.resolveWorkingDirectory().resolve(candidate).normalize()
  }
}

internal fun requireExternalAddonEntryMap(
  configPath: Path,
  index: Int,
  entry: Any?,
): Map<*, *> =
  entry as? Map<*, *> ?: throw ExternalAddonConfigError(
    "External addon config at '$configPath': 'external_addon_sources[$index]' must be a mapping.",
  )

internal fun validateExternalAddonEntryKind(
  configPath: Path,
  index: Int,
  kind: String?,
) {
  if (kind != null && kind != SkillKind.PLATFORM_PACK.wireValue) {
    throw ExternalAddonConfigError(
      "External addon config at '$configPath': 'external_addon_sources[$index].kind' " +
        "must be 'platform-pack' or 'agent-addon'.",
    )
  }
}

internal fun requireExternalAddonEntryPath(
  configPath: Path,
  index: Int,
  map: Map<*, *>,
): String =
  (map["path"] as? String)?.takeIf(String::isNotBlank)
    ?: throw ExternalAddonConfigError(
      "External addon config at '$configPath': 'external_addon_sources[$index].path' must be a non-empty string.",
    )

internal fun requireExternalAddonEntryPlatform(
  configPath: Path,
  index: Int,
  map: Map<*, *>,
): String =
  (map["platform"] as? String)?.takeIf(String::isNotBlank)
    ?: throw ExternalAddonConfigError(
      "External addon config at '$configPath': 'external_addon_sources[$index].platform' must be a non-empty string.",
    )

internal fun validateExternalAddonEntryDirectory(
  configPath: Path,
  index: Int,
  rawPath: String,
  resolvedPath: Path,
) {
  if (!Files.isDirectory(resolvedPath)) {
    throw ExternalAddonConfigError(
      "External addon config at '$configPath': 'external_addon_sources[$index].path' '$rawPath' " +
        "does not exist or is not a directory.",
    )
  }
}
