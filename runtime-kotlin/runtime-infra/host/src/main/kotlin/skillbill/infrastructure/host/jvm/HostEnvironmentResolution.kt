package skillbill.infrastructure.host.jvm

import skillbill.ports.system.HostPlatformPort
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Path

fun resolveUserHome(
  home: Path?,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
): Path = (home ?: hostPlatform.resolveUserHome()).toAbsolutePath().normalize()

fun resolveEnvironmentMap(
  environment: Map<String, String>,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
): Map<String, String> = environment.ifEmpty { hostPlatform.resolveEnvironment() }

fun resolveTelemetryStateDir(
  environment: Map<String, String>,
  userHome: Path,
): Path =
  environment[STATE_DIR_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    expandAndNormalizeTelemetryPath(it, userHome)
  } ?: userHome.resolve(".skill-bill").toAbsolutePath().normalize()

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
