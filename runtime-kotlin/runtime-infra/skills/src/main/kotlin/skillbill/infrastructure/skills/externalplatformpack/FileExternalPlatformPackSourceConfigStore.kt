package skillbill.infrastructure.skills.externalplatformpack

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.ExternalPlatformPackConfigError
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.readTelemetryConfigFileRead
import skillbill.infrastructure.host.resolveTelemetryConfigPath
import skillbill.infrastructure.host.writeTelemetryConfigFile
import skillbill.install.model.ExternalPlatformPackSource
import skillbill.model.toPath
import skillbill.ports.install.platformpack.ExternalPlatformPackSourceConfigPort
import skillbill.ports.install.platformpack.model.ExternalPlatformPackPathResolveRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackPathResolveResult
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceConfigRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceConfigResult
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceRegistrationRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceUnregisterRequest
import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryOpenDocument
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Inject
class FileExternalPlatformPackSourceConfigStore : ExternalPlatformPackSourceConfigPort {
  override fun readExternalPlatformPackSources(
    request: ExternalPlatformPackSourceConfigRequest,
  ): ExternalPlatformPackSourceConfigResult {
    val configPath = resolveTelemetryConfigPath(request.environment, request.userHome)
    if (!Files.exists(configPath)) {
      return ExternalPlatformPackSourceConfigResult()
    }
    val payload =
      when (val read = readTelemetryConfigFileRead(configPath)) {
        TelemetryConfigRead.Absent -> return ExternalPlatformPackSourceConfigResult()
        is TelemetryConfigRead.Malformed -> throw ExternalPlatformPackConfigError(read.reason)
        is TelemetryConfigRead.Present -> read.document.payload
      }

    val raw =
      payload[ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES]
        ?: return ExternalPlatformPackSourceConfigResult()
    if (raw !is List<*>) {
      throw ExternalPlatformPackConfigError(
        "External platform pack config at '$configPath': " +
          "'${ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES}' must be a list of {path} entries.",
      )
    }
    val sources =
      raw.mapIndexedNotNull { index, entry ->
        parseEntry(configPath, request.userHome, index, entry)
      }
    return ExternalPlatformPackSourceConfigResult(sources)
  }

  override fun registerExternalPlatformPackSource(
    request: ExternalPlatformPackSourceRegistrationRequest,
  ): ExternalPlatformPackSourceConfigResult {
    val configPath = resolveTelemetryConfigPath(request.environment, request.userHome)
    val existing =
      when (val read = readTelemetryConfigFileRead(configPath)) {
        TelemetryConfigRead.Absent -> null
        is TelemetryConfigRead.Malformed -> throw ExternalPlatformPackConfigError(read.reason)
        is TelemetryConfigRead.Present -> read.document
      }
    val payload = LinkedHashMap<String, Any?>(existing?.payload.orEmpty())
    val rawSources = rawExternalPlatformPackSources(configPath, payload)
    val existingSources =
      rawSources.mapIndexedNotNull { index, entry ->
        parseEntry(configPath, request.userHome, index, entry)
      }
    val registeredSource = request.source.normalized()
    val alreadyRegistered =
      existingSources.any { source ->
        source.path == registeredSource.path
      }
    val sources =
      if (alreadyRegistered) {
        existingSources
      } else {
        val updatedRawSources =
          rawSources +
            mapOf(
              ExternalPlatformPackConfigKeys.PATH to registeredSource.path.toString(),
            )
        payload[ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES] = updatedRawSources
        writeTelemetryConfigFile(configPath, TelemetryConfigDocument(TelemetryOpenDocument.from(payload)))
        existingSources + registeredSource
      }
    return ExternalPlatformPackSourceConfigResult(sources)
  }

  override fun unregisterExternalPlatformPackSource(
    request: ExternalPlatformPackSourceUnregisterRequest,
  ): ExternalPlatformPackSourceConfigResult {
    val configPath = resolveTelemetryConfigPath(request.environment, request.userHome)
    val existing =
      when (val read = readTelemetryConfigFileRead(configPath)) {
        TelemetryConfigRead.Absent -> return ExternalPlatformPackSourceConfigResult()
        is TelemetryConfigRead.Malformed -> throw ExternalPlatformPackConfigError(read.reason)
        is TelemetryConfigRead.Present -> read.document
      }
    val payload = LinkedHashMap<String, Any?>(existing.payload)
    val rawSources = rawExternalPlatformPackSources(configPath, payload)
    val target = request.source.normalized().path
    val locations =
      rawSources.mapIndexed { index, entry ->
        entryLocation(configPath, request.userHome, index, entry)
      }
    val kept = rawSources.filterIndexed { index, _ -> locations[index].toFileLocation() != target }
    if (kept.size == rawSources.size) {
      return ExternalPlatformPackSourceConfigResult(
        rawSources.mapIndexed { index, entry -> parseEntry(configPath, request.userHome, index, entry) },
      )
    }
    val remaining =
      kept.mapIndexed { index, entry ->
        parseEntry(configPath, request.userHome, index, entry)
      }
    payload[ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES] = kept
    writeTelemetryConfigFile(configPath, TelemetryConfigDocument(TelemetryOpenDocument.from(payload)))
    return ExternalPlatformPackSourceConfigResult(remaining)
  }

  private fun rawExternalPlatformPackSources(
    configPath: Path,
    payload: Map<String, Any?>,
  ): List<Any?> {
    val raw = payload[ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES] ?: return emptyList()
    if (raw !is List<*>) {
      throw ExternalPlatformPackConfigError(
        "External platform pack config at '$configPath': " +
          "'${ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES}' must be a list of {path} entries.",
      )
    }
    return raw
  }

  private fun entryLocation(
    configPath: Path,
    userHome: Path,
    index: Int,
    entry: Any?,
  ): Path {
    return try {
      val map = requireExternalPlatformPackEntryMap(configPath, index, entry)
      val rawPath = requireExternalPlatformPackEntryPath(configPath, index, map)
      resolveExternalPlatformPackSourcePath(userHome, rawPath)
    } catch (error: ExternalPlatformPackConfigError) {
      throw error
    } catch (error: InvalidPathException) {
      throw ExternalPlatformPackConfigError(
        "External platform pack config at '$configPath': " +
          "external_platform_pack_sources[$index].path is not a valid path.",
        error,
      )
    }
  }

  private fun parseEntry(
    configPath: Path,
    userHome: Path,
    index: Int,
    entry: Any?,
  ): ExternalPlatformPackSource {
    val resolvedPath = entryLocation(configPath, userHome, index, entry)
    return ExternalPlatformPackSource(path = resolvedPath.toFileLocation())
  }

  private fun ExternalPlatformPackSource.normalized(): ExternalPlatformPackSource =
    ExternalPlatformPackSource(path = path.toPath().toAbsolutePath().normalize().toFileLocation())

  override fun resolveExternalPlatformPackPath(
    request: ExternalPlatformPackPathResolveRequest,
  ): ExternalPlatformPackPathResolveResult =
    ExternalPlatformPackPathResolveResult(
      path = resolveExternalPlatformPackSourcePath(request.userHome, request.rawPath),
    )
}

internal fun resolveExternalPlatformPackSourcePath(
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
