package skillbill.infrastructure.host

import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path

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
): Path {
  environment[CONFIG_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    return expandAndNormalizeTelemetryPath(it, userHome)
  }
  val durableConfig =
    userHome.resolve(".config").resolve("skill-bill").resolve("config.json")
      .toAbsolutePath().normalize()
  if (Files.exists(durableConfig)) return durableConfig
  val legacyConfig = userHome.resolve(".skill-bill").resolve("config.json").toAbsolutePath().normalize()
  if (Files.exists(legacyConfig)) return legacyConfig
  return durableConfig
}

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
