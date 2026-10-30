package skillbill.application.telemetry.config

import skillbill.application.telemetry.model.TelemetrySettingsSnapshot
import skillbill.application.telemetry.model.TelemetrySnapshotAvailability
import skillbill.application.telemetry.model.TelemetrySnapshotEffectiveLevel
import skillbill.application.telemetry.model.TelemetrySnapshotEnvironment
import skillbill.application.telemetry.model.TelemetrySnapshotInstallIdSource
import skillbill.application.telemetry.model.TelemetrySnapshotPriorLevel
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
import skillbill.telemetry.model.TelemetryConfigDocumentParse
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.parsePositiveTelemetryIntOrNull
import skillbill.telemetry.parseTelemetryBoolValueOrNull
import skillbill.telemetry.parseTelemetryConfigDocument
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
): TelemetrySettingsLoad =
  configStore.withConfigTransaction {
    val configPath = configStore.configPath()
    val config =
      when (val read = readTelemetrySettingsConfig(materialize, configStore)) {
        is TelemetrySettingsValue.Loaded -> read.value
        is TelemetrySettingsValue.Unavailable -> return@withConfigTransaction TelemetrySettingsLoad.Unavailable(
          read.reason,
        )
      }
    val baseSettings =
      when (val parsed = configBackedSettings(configPath, config)) {
        is TelemetrySettingsValue.Loaded -> parsed.value
        is TelemetrySettingsValue.Unavailable -> return@withConfigTransaction TelemetrySettingsLoad.Unavailable(
          parsed.reason,
        )
      }
    val envSettings =
      when (val parsed = applyEnvironmentOverrides(baseSettings, environment)) {
        is TelemetrySettingsValue.Loaded -> parsed.value
        is TelemetrySettingsValue.Unavailable -> return@withConfigTransaction TelemetrySettingsLoad.Unavailable(
          parsed.reason,
        )
      }
    val enabled = envSettings.level != "off"
    val (proxyUrl, customProxyUrl) = telemetryProxyUrl(envSettings.customProxyUrl)
    if (enabled && envSettings.installId.isBlank()) {
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

fun telemetrySettingsSnapshotFromConfig(
  configPath: String,
  filePresent: Boolean,
  configuredLevel: String?,
  configuredInstallId: String?,
  environment: TelemetrySnapshotEnvironment,
): TelemetrySettingsSnapshot {
  val displayLevel = if (filePresent) configuredLevel ?: "anonymous" else "anonymous"
  val baseLevel = if (filePresent) configuredLevel ?: "anonymous" else "off"
  val installId = environment.installId?.trim()?.takeIf(String::isNotBlank) ?: configuredInstallId.orEmpty()
  val installIdSource = installIdSource(environment.installId, configuredInstallId)
  val configHasInstallId = configuredInstallId?.isNotBlank() == true
  val levelOverride = resolveTelemetryLevelOverride(baseLevel, environment.level, environment.enabled)
  val hasEnvironmentLevel = environment.level?.isNotBlank() == true || environment.enabled?.isNotBlank() == true
  if (levelOverride is TelemetrySettingsValue.Unavailable) {
    return TelemetrySettingsSnapshot(
      configuredLevel = displayLevel,
      effectiveLevel = TelemetrySnapshotEffectiveLevel.Unavailable(levelOverride.reason),
      availability = TelemetrySnapshotAvailability.Unavailable(levelOverride.reason),
      previousLevel = TelemetrySnapshotPriorLevel.Unavailable,
      levelEnvironmentOverride = hasEnvironmentLevel,
      installIdSource = installIdSource,
      configHasInstallId = configHasInstallId,
    )
  }
  val effectiveLevel = (levelOverride as TelemetrySettingsValue.Loaded).value.level
  val previousLevel = TelemetrySnapshotPriorLevel.Known(effectiveLevel)
  val batchError = telemetryBatchSizeError(environment.batchSize)
  val installIdError =
    if (effectiveLevel != "off" && installId.isBlank()) {
      val detail = "Telemetry is enabled but no install_id is configured at '$configPath'."
      "$detail Run 'skill-bill telemetry enable' to create one."
    } else {
      null
    }
  val availabilityErrors = listOfNotNull(batchError, installIdError)
  val availability =
    when {
      availabilityErrors.isNotEmpty() -> {
        val reason = availabilityErrors.joinToString("\n")
        TelemetrySnapshotAvailability.Unavailable(reason)
      }
      else -> TelemetrySnapshotAvailability.Available(effectiveLevel)
    }
  return TelemetrySettingsSnapshot(
    configuredLevel = displayLevel,
    effectiveLevel = TelemetrySnapshotEffectiveLevel.Known(effectiveLevel),
    availability = availability,
    previousLevel = previousLevel,
    levelEnvironmentOverride = hasEnvironmentLevel,
    installIdSource = installIdSource,
    configHasInstallId = configHasInstallId,
  )
}

private fun installIdSource(
  environmentId: String?,
  configuredId: String?,
): TelemetrySnapshotInstallIdSource =
  when {
    environmentId?.isNotBlank() == true -> TelemetrySnapshotInstallIdSource.ENVIRONMENT
    configuredId?.isNotBlank() == true -> TelemetrySnapshotInstallIdSource.CONFIG
    else -> TelemetrySnapshotInstallIdSource.MISSING
  }

private fun readTelemetrySettingsConfig(
  materialize: Boolean,
  configStore: TelemetryConfigStore,
): TelemetrySettingsValue<TelemetryConfigDocument?> =
  when (val configRead = configStore.read()) {
    is TelemetryConfigRead.Malformed -> TelemetrySettingsValue.Unavailable(configRead.reason)
    TelemetryConfigRead.Absent -> TelemetrySettingsValue.Loaded(if (materialize) configStore.ensure() else null)
    is TelemetryConfigRead.Present ->
      when (val parsed = parseTelemetryConfigDocument(configStore.configPath().toString(), configRead.document)) {
        is TelemetryConfigDocumentParse.Invalid -> TelemetrySettingsValue.Unavailable(parsed.reason, parsed.keyPath)
        is TelemetryConfigDocumentParse.Valid ->
          TelemetrySettingsValue.Loaded(if (materialize) configStore.ensure() else configRead.document)
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

internal data class MutableTelemetrySettings(
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

internal fun settingsFromConfigDocument(
  configPath: Path,
  config: TelemetryConfigDocument,
): TelemetrySettingsValue<MutableTelemetrySettings> =
  when (val parsed = parseTelemetryConfigDocument(configPath.toString(), config)) {
    is TelemetryConfigDocumentParse.Valid ->
      TelemetrySettingsValue.Loaded(
        MutableTelemetrySettings(
          level = parsed.settings.level,
          customProxyUrl = parsed.settings.customProxyUrl,
          batchSize = parsed.settings.batchSize,
          installId = parsed.settings.installId,
        ),
      )
    is TelemetryConfigDocumentParse.Invalid -> TelemetrySettingsValue.Unavailable(parsed.reason, parsed.keyPath)
  }

internal sealed interface TelemetrySettingsValue<out T> {
  data class Loaded<T>(val value: T) : TelemetrySettingsValue<T>

  data class Unavailable(val reason: String, val keyPath: String? = null) : TelemetrySettingsValue<Nothing>
}

private fun applyEnvironmentOverrides(
  settings: MutableTelemetrySettings,
  environment: Map<String, String>,
): TelemetrySettingsValue<MutableTelemetrySettings> {
  var level = settings.level
  var customProxyUrl = settings.customProxyUrl
  var batchSize = settings.batchSize
  var installId = settings.installId

  when (
    val levelOverride =
      resolveTelemetryLevelOverride(
        level,
        environment[TELEMETRY_LEVEL_ENVIRONMENT_KEY],
        environment[TELEMETRY_ENABLED_ENVIRONMENT_KEY],
      )
  ) {
    is TelemetrySettingsValue.Loaded -> level = levelOverride.value.level
    is TelemetrySettingsValue.Unavailable -> return levelOverride
  }
  environment[TELEMETRY_PROXY_URL_ENVIRONMENT_KEY]
    ?.takeIf(String::isNotBlank)
    ?.let { customProxyUrl = it.trim() }
  environment[INSTALL_ID_ENVIRONMENT_KEY]
    ?.takeIf(String::isNotBlank)
    ?.let { installId = it.trim() }
  environment[TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let {
    batchSize = parsePositiveTelemetryIntOrNull(it)
      ?: return TelemetrySettingsValue.Unavailable(positiveTelemetryIntError(it, TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY))
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

private data class ResolvedTelemetryEnvironmentLevel(val level: String)

private fun resolveTelemetryLevelOverride(
  current: String,
  rawLevel: String?,
  rawEnabled: String?,
): TelemetrySettingsValue<ResolvedTelemetryEnvironmentLevel> {
  if (!rawLevel.isNullOrBlank()) {
    val level =
      parseTelemetryLevelValueOrNull(rawLevel)
        ?: return TelemetrySettingsValue.Unavailable(telemetryLevelValueError(TELEMETRY_LEVEL_ENVIRONMENT_KEY))
    return TelemetrySettingsValue.Loaded(ResolvedTelemetryEnvironmentLevel(level))
  }
  return resolveLegacyTelemetryLevel(current, rawEnabled)
}

private fun resolveLegacyTelemetryLevel(
  current: String,
  rawEnabled: String?,
): TelemetrySettingsValue<ResolvedTelemetryEnvironmentLevel> {
  if (rawEnabled.isNullOrBlank()) return TelemetrySettingsValue.Loaded(ResolvedTelemetryEnvironmentLevel(current))
  val enabled =
    parseTelemetryBoolValueOrNull(rawEnabled)
      ?: return TelemetrySettingsValue.Unavailable(telemetryBoolValueError(TELEMETRY_ENABLED_ENVIRONMENT_KEY))
  return TelemetrySettingsValue.Loaded(ResolvedTelemetryEnvironmentLevel(if (enabled) "anonymous" else "off"))
}

private fun telemetryBatchSizeError(raw: String?): String? =
  raw?.takeIf(String::isNotBlank)?.takeIf { parsePositiveTelemetryIntOrNull(it) == null }
    ?.let { positiveTelemetryIntError(it, TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY) }
