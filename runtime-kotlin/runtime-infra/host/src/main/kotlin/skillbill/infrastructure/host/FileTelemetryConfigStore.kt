package skillbill.infrastructure.host
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.model.EnvironmentContext
import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.defaultLocalTelemetryConfig
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryOpenDocument
import skillbill.telemetry.parseTelemetryBoolValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Inject
class FileTelemetryConfigStore(
  private val context: EnvironmentContext,
) : TelemetryConfigStore {
  private val resolvedContext = context.withProcessDefaults()

  override fun stateDir(): Path = resolveTelemetryStateDir(resolvedContext.environment, resolvedContext.userHome)

  override fun configPath(): Path = resolveTelemetryConfigPath(resolvedContext.environment, resolvedContext.userHome)

  override fun read(): TelemetryConfigRead = readTelemetryConfigFileRead(configPath())

  override fun ensure(): TelemetryConfigDocument = ensureTelemetryConfigFile(configPath(), resolvedContext.environment)

  override fun write(document: TelemetryConfigDocument) = writeTelemetryConfigFile(configPath(), document)
}

fun readTelemetryConfigFileRead(path: Path): TelemetryConfigRead {
  if (!Files.exists(path)) {
    return TelemetryConfigRead.Absent
  }
  val parsedObject =
    JsonCodec.parseObjectOrNull(Files.readString(path))
      ?: return TelemetryConfigRead.Malformed("Telemetry config at '$path' is not valid JSON.")
  val payload =
    JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(parsedObject))
      ?: return TelemetryConfigRead.Malformed("Telemetry config at '$path' must contain a JSON object.")
  return TelemetryConfigRead.Present(TelemetryConfigDocument(TelemetryOpenDocument.from(payload)))
}

fun readTelemetryConfigFile(path: Path): TelemetryConfigDocument? =
  when (val result = readTelemetryConfigFileRead(path)) {
    TelemetryConfigRead.Absent -> null
    is TelemetryConfigRead.Malformed -> throw IllegalArgumentException(result.reason)
    is TelemetryConfigRead.Present -> result.document
  }

internal fun ensureTelemetryConfigFile(
  path: Path,
  environment: Map<String, String> = System.getenv(),
): TelemetryConfigDocument {
  path.parent?.let(Files::createDirectories)
  val existing = readTelemetryConfigFile(path)
  val payload = LinkedHashMap<String, Any?>(existing?.payload.orEmpty())
  val fallbackInstallId =
    environment[INSTALL_ID_ENVIRONMENT_KEY]?.trim()?.takeIf(String::isNotBlank)
      ?: UUID.randomUUID().toString()
  val defaults = defaultLocalTelemetryConfig(fallbackInstallId).payload
  val telemetry = normalizedTelemetryMap(payload, defaults)
  payload["install_id"] = normalizedInstallId(payload, defaults)
  payload["telemetry"] = telemetry
  preserveUnownedTopLevelKeys(payload, existing?.payload)
  val document = TelemetryConfigDocument(TelemetryOpenDocument.from(payload))
  if (!Files.exists(path) || existing != document) {
    writeTelemetryConfigFile(path, document)
  }
  return document
}

fun writeTelemetryConfigFile(
  path: Path,
  document: TelemetryConfigDocument,
) {
  path.parent?.let(Files::createDirectories)
  Files.writeString(path, JsonCodec.mapToJsonString(document.payload) + "\n")
}

private fun normalizedInstallId(
  payload: MutableMap<String, Any?>,
  defaults: Map<String, Any?>,
): String =
  (payload["install_id"] as? String)?.takeIf(String::isNotBlank)
    ?: defaults.getValue("install_id").toString()

private fun normalizedTelemetryMap(
  payload: MutableMap<String, Any?>,
  defaults: Map<String, Any?>,
): Map<String, Any?> {
  val telemetryRaw = payload["telemetry"]
  val telemetry =
    (telemetryRaw as? Map<*, *>)
      ?.entries
      ?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?.toMutableMap() ?: mutableMapOf()
  val defaultTelemetry = defaults.getValue("telemetry") as Map<*, *>
  normalizeLegacyEnabledFlag(telemetry)
  listOf("level", "proxy_url", "batch_size").forEach { key ->
    telemetry.putIfAbsent(key, defaultTelemetry[key])
  }
  return telemetry
}

private fun normalizeLegacyEnabledFlag(telemetry: MutableMap<String, Any?>) {
  if (!telemetry.containsKey("level") && telemetry.containsKey("enabled")) {
    val enabledRaw = telemetry.remove("enabled")
    telemetry["level"] = legacyEnabledLevel(enabledRaw)
  } else {
    telemetry.remove("enabled")
  }
}

private fun preserveUnownedTopLevelKeys(
  payload: MutableMap<String, Any?>,
  existing: Map<String, Any?>?,
) {
  val ownedKeys = setOf("install_id", "telemetry")
  existing?.forEach { (key, value) ->
    if (key !in ownedKeys && !payload.containsKey(key)) {
      payload[key] = value
    }
  }
}

private fun legacyEnabledLevel(enabledRaw: Any?): String =
  when (enabledRaw) {
    is Boolean -> if (enabledRaw) "anonymous" else "off"
    is String -> if (parseTelemetryBoolValue(enabledRaw, "telemetry.enabled")) "anonymous" else "off"
    else -> if (enabledRaw == true) "anonymous" else "off"
  }
