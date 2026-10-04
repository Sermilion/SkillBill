package skillbill.infrastructure.skills.externalplatformpack

import skillbill.error.core.externalPlatformPackConfig
import java.nio.file.Path

internal fun requireExternalPlatformPackEntryMap(
  configPath: Path,
  index: Int,
  entry: Any?,
): Map<*, *> =
  entry as? Map<*, *> ?: throw externalPlatformPackConfig(
    "External platform pack config at '$configPath': 'external_platform_pack_sources[$index]' must be a mapping.",
  )

internal fun requireExternalPlatformPackEntryPath(
  configPath: Path,
  index: Int,
  map: Map<*, *>,
): String =
  (map[ExternalPlatformPackConfigKeys.PATH] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    ?: throw externalPlatformPackConfig(
      "External platform pack config at '$configPath': 'external_platform_pack_sources[$index].path' " +
        "must be a non-empty string.",
    )
