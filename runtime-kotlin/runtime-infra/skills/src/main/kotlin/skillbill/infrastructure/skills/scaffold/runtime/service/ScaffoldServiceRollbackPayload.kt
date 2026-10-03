
package skillbill.infrastructure.skills.scaffold.runtime.service

import skillbill.error.shellcontent.invalidScaffoldPayloadError
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.displayNameFromSlug
import skillbill.ports.system.HostPlatformPort
import java.nio.file.Path

internal fun canonicalName(
  payload: Map<String, Any?>,
  defaultName: String,
): String {
  val provided = payload["name"] as? String
  return when {
    provided.isNullOrBlank() -> defaultName
    provided != defaultName -> throw invalidScaffoldPayloadError(
      "Scaffold payload field 'name' must be '$defaultName' for this scaffold kind.",
    )
    else -> provided
  }
}

internal fun optionalAddonLocationPath(
  payload: Map<String, Any?>,
  repoRoot: Path,
): Path? {
  if (!payload.containsKey("addon_location_path")) return null
  val rawPath =
    payload["addon_location_path"] as? String
      ?: throw invalidScaffoldPayloadError(
        "Scaffold payload field 'addon_location_path' must be a non-empty string when provided.",
      )
  if (rawPath.isBlank()) {
    throw invalidScaffoldPayloadError(
      "Scaffold payload field 'addon_location_path' must be a non-empty string when provided.",
    )
  }
  val expanded =
    when {
      rawPath == "~" -> resolveUserHome(null).toString()
      rawPath.startsWith("~/") ->
        resolveUserHome(null)
          .resolve(rawPath.removePrefix("~/"))
          .toString()
      else -> rawPath
    }
  val candidate = Path.of(expanded)
  return if (candidate.isAbsolute) {
    candidate.normalize()
  } else {
    repoRoot.resolve(candidate).normalize()
  }
}

internal fun displayPath(
  repoRoot: Path,
  path: Path,
): String {
  val normalizedRoot = repoRoot.toAbsolutePath().normalize()
  val normalizedPath = path.toAbsolutePath().normalize()
  val display =
    if (normalizedPath.startsWith(normalizedRoot)) {
      normalizedRoot.relativize(normalizedPath)
    } else {
      normalizedPath
    }
  return display.toString().replace('\\', '/')
}

internal fun defaultPlatformOverrideName(
  platform: String,
  family: String,
): String =
  if (family == "quality-check") {
    "bill-$platform-code-check"
  } else {
    "bill-$platform-$family"
  }

internal fun deriveDisplayName(platform: String): String = displayNameFromSlug(platform)

internal fun defaultRepoRoot(hostPlatform: HostPlatformPort = JdkHostPlatformPort): Path =
  hostPlatform.resolveWorkingDirectory().toAbsolutePath().normalize()

internal fun noAgentsNote(): String =
  "No local AI agents detected; skipping auto-install. Run `./install.sh` to set up " +
    "agent paths when an agent becomes available."
