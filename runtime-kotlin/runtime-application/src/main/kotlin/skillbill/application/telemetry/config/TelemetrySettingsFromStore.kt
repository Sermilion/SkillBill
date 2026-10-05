package skillbill.application.telemetry.config

import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.ports.telemetry.model.TelemetrySettingsLoad
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.telemetry.DEFAULT_TELEMETRY_BATCH_SIZE
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_ENABLED_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_LEVEL_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_PROXY_URL_ENVIRONMENT_KEY
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.parsePositiveTelemetryIntOrNull
import skillbill.telemetry.parseTelemetryBoolValueOrNull
import skillbill.telemetry.parseTelemetryLevelValueOrNull
import skillbill.telemetry.positiveTelemetryIntError
import skillbill.telemetry.telemetryBoolValueError
import skillbill.telemetry.telemetryLevelValueError
import skillbill.telemetry.telemetryProxyUrl
import java.nio.file.Path

internal fun resolveTelemetrySettingsFromStore(
  materialize: Boolean,
  environment: Map<String, String>,
  configStore: TelemetryConfigStore,
): TelemetrySettingsLoad {
  val configPath = configStore.configPath()
  val config =
    when (val read = readTelemetrySettingsConfig(materialize, configStore)) {
      is TelemetrySettingsValue.Loaded -> read.value
      is TelemetrySettingsValue.Unavailable -> return TelemetrySettingsLoad.Unavailable(read.reason)
    }
  val baseSettings =
    when (val parsed = configBackedSettings(configPath, config)) {
      is TelemetrySettingsValue.Loaded -> parsed.value
      is TelemetrySettingsValue.Unavailable -> return TelemetrySettingsLoad.Unavailable(parsed.reason)
    }
  val envSettings =
    when (val parsed = applyEnvironmentOverrides(baseSettings, environment)) {
      is TelemetrySettingsValue.Loaded -> parsed.value
      is TelemetrySettingsValue.Unavailable -> return TelemetrySettingsLoad.Unavailable(parsed.reason)
    }
  val enabled = envSettings.level != "off"
  val (proxyUrl, customProxyUrl) = telemetryProxyUrl(envSettings.customProxyUrl)
  return if (enabled && envSettings.installId.isBlank()) {
    TelemetrySettingsLoad.Unavailable(
      "Telemetry is enabled but no install_id is configured at '$configPath'. " +
        "Run 'skill-bill telemetry enable' to create one.",
    )
  } else {
    TelemetrySettingsLoad.Loaded(
      TelemetrySettings(
        configPath = configPath.toFileLocation(),
        level = envSettings.level,
        enabled = enabled,
        installId = envSettings.installId,
        proxyUrl = proxyUrl,
        customProxyUrl = customProxyUrl,
        batchSize = envSettings.batchSize,
      ),
    )
  }
}

private fun readTelemetrySettingsConfig(
  materialize: Boolean,
  configStore: TelemetryConfigStore,
): TelemetrySettingsValue<TelemetryConfigDocument?> =
  when (val configRead = configStore.read()) {
    is TelemetryConfigRead.Malformed -> TelemetrySettingsValue.Unavailable(configRead.reason)
    TelemetryConfigRead.Absent -> TelemetrySettingsValue.Loaded(if (materialize) configStore.ensure() else null)
    is TelemetryConfigRead.Present -> {
      if (!materialize) {
        TelemetrySettingsValue.Loaded(configRead.document)
      } else {
        val telemetry = configRead.document.payload["telemetry"] as? Map<*, *>
        val enabled = telemetry?.get("enabled")
        if (
          telemetry != null &&
          !telemetry.containsKey("level") &&
          enabled is String &&
          parseTelemetryBoolValueOrNull(enabled) == null
        ) {
          TelemetrySettingsValue.Unavailable(telemetryBoolValueError("telemetry.enabled"))
        } else {
          TelemetrySettingsValue.Loaded(configStore.ensure())
        }
      }
    }
  }

internal fun loadTelemetrySettingsFromStore(
  materialize: Boolean,
  environment: Map<String, String>,
  configStore: TelemetryConfigStore,
): TelemetrySettings =
  when (val result = resolveTelemetrySettingsFromStore(materialize, environment, configStore)) {
    is TelemetrySettingsLoad.Loaded -> result.settings
    is TelemetrySettingsLoad.Unavailable -> throw IllegalArgumentException(result.reason)
  }

private data class MutableTelemetrySettings(
  val level: String,
  val customProxyUrl: String,
  val batchSize: Int,
  val installId: String,
)

private fun configBackedSettings(
  configPath: Path,
  config: TelemetryConfigDocument?,
): TelemetrySettingsValue<MutableTelemetrySettings> =
  if (config == null) {
    TelemetrySettingsValue.Loaded(
      MutableTelemetrySettings(
        level = "off",
        customProxyUrl = "",
        batchSize = DEFAULT_TELEMETRY_BATCH_SIZE,
        installId = "",
      ),
    )
  } else {
    settingsFromConfigDocument(configPath, config)
  }

private fun settingsFromConfigDocument(
  configPath: Path,
  config: TelemetryConfigDocument,
): TelemetrySettingsValue<MutableTelemetrySettings> {
  val payload = config.payload
  val telemetryRaw = payload["telemetry"]
  val telemetry =
    when (telemetryRaw) {
      null -> emptyMap()
      is Map<*, *> -> telemetryRaw.entries.filter { it.key is String }.associate { it.key as String to it.value }
      else -> return TelemetrySettingsValue.Unavailable(
        "Telemetry config at '$configPath' must contain a 'telemetry' object.",
      )
    }
  val level = telemetryLevelFromConfig(telemetry)
  val parsedLevel =
    when (level) {
      is TelemetrySettingsValue.Loaded -> level.value
      is TelemetrySettingsValue.Unavailable -> return TelemetrySettingsValue.Unavailable(level.reason)
    }
  val batchSize = telemetryBatchSize(telemetry)
  val parsedBatchSize =
    when (batchSize) {
      is TelemetrySettingsValue.Loaded -> batchSize.value
      is TelemetrySettingsValue.Unavailable -> return TelemetrySettingsValue.Unavailable(batchSize.reason)
    }
  return TelemetrySettingsValue.Loaded(
    MutableTelemetrySettings(
      level = parsedLevel,
      customProxyUrl = telemetry["proxy_url"]?.toString()?.trim().orEmpty(),
      batchSize = parsedBatchSize,
      installId = payload["install_id"]?.toString()?.trim().orEmpty(),
    ),
  )
}

private sealed interface TelemetrySettingsValue<out T> {
  data class Loaded<T>(val value: T) : TelemetrySettingsValue<T>

  data class Unavailable(val reason: String) : TelemetrySettingsValue<Nothing>
}

private fun telemetryLevelFromConfig(telemetry: Map<String, Any?>): TelemetrySettingsValue<String> {
  val levelRaw = telemetry["level"]
  val enabledRaw = telemetry["enabled"]
  return when {
    levelRaw != null ->
      parseTelemetryLevelValueOrNull(levelRaw.toString())?.let { TelemetrySettingsValue.Loaded(it) }
        ?: TelemetrySettingsValue.Unavailable(telemetryLevelValueError("telemetry.level"))
    enabledRaw is Boolean -> TelemetrySettingsValue.Loaded(if (enabledRaw) "anonymous" else "off")
    enabledRaw is String ->
      parseTelemetryBoolValueOrNull(enabledRaw)?.let { TelemetrySettingsValue.Loaded(if (it) "anonymous" else "off") }
        ?: TelemetrySettingsValue.Unavailable(telemetryBoolValueError("telemetry.enabled"))
    enabledRaw != null -> TelemetrySettingsValue.Loaded(if (enabledRaw == true) "anonymous" else "off")
    else -> TelemetrySettingsValue.Loaded("anonymous")
  }
}

private fun telemetryBatchSize(telemetry: Map<String, Any?>): TelemetrySettingsValue<Int> =
  when (val batchSizeRaw = telemetry["batch_size"]) {
    is Int -> TelemetrySettingsValue.Loaded(batchSizeRaw)
    is Number -> TelemetrySettingsValue.Loaded(batchSizeRaw.toInt())
    null -> TelemetrySettingsValue.Loaded(DEFAULT_TELEMETRY_BATCH_SIZE)
    else ->
      parsePositiveTelemetryIntOrNull(batchSizeRaw.toString())?.let { TelemetrySettingsValue.Loaded(it) }
        ?: TelemetrySettingsValue.Unavailable(
          positiveTelemetryIntError(batchSizeRaw.toString(), "telemetry.batch_size"),
        )
  }

private fun applyEnvironmentOverrides(
  settings: MutableTelemetrySettings,
  environment: Map<String, String>,
): TelemetrySettingsValue<MutableTelemetrySettings> {
  var level = settings.level
  var customProxyUrl = settings.customProxyUrl
  var batchSize = settings.batchSize
  var installId = settings.installId

  environment[TELEMETRY_LEVEL_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    level =
      parseTelemetryLevelValueOrNull(it)
        ?: return TelemetrySettingsValue.Unavailable(telemetryLevelValueError(TELEMETRY_LEVEL_ENVIRONMENT_KEY))
  } ?: environment[TELEMETRY_ENABLED_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    val enabled =
      parseTelemetryBoolValueOrNull(it)
        ?: return TelemetrySettingsValue.Unavailable(telemetryBoolValueError(TELEMETRY_ENABLED_ENVIRONMENT_KEY))
    level = if (enabled) "anonymous" else "off"
  }
  environment[TELEMETRY_PROXY_URL_ENVIRONMENT_KEY]
    ?.takeIf(String::isNotBlank)
    ?.let { customProxyUrl = it.trim() }
  environment[INSTALL_ID_ENVIRONMENT_KEY]
    ?.takeIf(String::isNotBlank)
    ?.let { installId = it.trim() }
  environment[TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    batchSize =
      parsePositiveTelemetryIntOrNull(it)
        ?: return TelemetrySettingsValue.Unavailable(
          positiveTelemetryIntError(it, TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY),
        )
  }
  return TelemetrySettingsValue.Loaded(
    MutableTelemetrySettings(
      level = level,
      customProxyUrl = customProxyUrl,
      batchSize = batchSize,
      installId = installId,
    ),
  )
}
