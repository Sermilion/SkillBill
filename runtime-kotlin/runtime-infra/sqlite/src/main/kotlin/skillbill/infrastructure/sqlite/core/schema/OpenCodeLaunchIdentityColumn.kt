package skillbill.infrastructure.sqlite.core.schema

import skillbill.contracts.JsonCodec
import skillbill.contracts.system.OpenCodeEnvironmentSelection
import skillbill.contracts.system.OpenCodeLaunchIdentity
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import skillbill.contracts.system.OpenCodeLaunchIdentityKeys as K

internal const val OPEN_CODE_IDENTITY_COLUMN = "opencode_identity_json"

internal fun recordOpenCodeLaunchIdentity(connection: Connection) {
  listOf("standalone_phase_status", "ide_status_workflow_execution").forEach { table ->
    val exists =
      connection.prepareStatement("SELECT 1 FROM pragma_table_info('$table') WHERE name = ?").use { statement ->
        statement.setString(1, OPEN_CODE_IDENTITY_COLUMN)
        statement.executeQuery().use { it.next() }
      }
    if (!exists) {
      connection.createStatement().use { statement ->
        statement.execute("ALTER TABLE $table ADD COLUMN $OPEN_CODE_IDENTITY_COLUMN TEXT")
      }
    }
  }
}

internal fun openCodeIdentityJson(identity: OpenCodeLaunchIdentity?): String? =
  identity?.let { value ->
    JsonCodec.valueToJsonString(
      mapOf(
        K.HOME to selection(value.home),
        K.CONFIG_HOME to selection(value.configHome),
        K.DATA_HOME to selection(value.dataHome),
        K.CACHE_HOME to selection(value.cacheHome),
        K.STATE_HOME to selection(value.stateHome),
        K.CONFIG_DIRECTORY to selection(value.configDirectory),
        K.CONFIG_FILE to selection(value.configFile),
        K.DISABLE_PROJECT_CONFIG to selection(value.disableProjectConfig),
        K.EFFECTIVE_HOME to value.effectiveHome,
        K.EFFECTIVE_CONFIG_HOME to value.effectiveConfigHome,
        K.EFFECTIVE_DATA_HOME to value.effectiveDataHome,
        K.EFFECTIVE_CACHE_HOME to value.effectiveCacheHome,
        K.EFFECTIVE_STATE_HOME to value.effectiveStateHome,
        K.EFFECTIVE_CONFIG_DIRECTORY to value.effectiveConfigDirectory,
        K.EFFECTIVE_CONFIG_FILE to value.effectiveConfigFile,
        K.INLINE_CONFIG_PRESENT to value.inlineConfigPresent,
        K.INLINE_CONFIG_FINGERPRINT to value.inlineConfigFingerprint,
      ),
    )
  }

internal fun ResultSet.readOpenCodeIdentity(sourceLabel: String): OpenCodeLaunchIdentity? {
  val raw = getString(OPEN_CODE_IDENTITY_COLUMN) ?: return null
  return decodeOpenCodeIdentity(raw, sourceLabel = sourceLabel)
}

internal fun decodeOpenCodeIdentity(
  raw: String,
  failureCode: RuntimeFailureCode = InstallFailureCode.INVALID_IDE_STATUS_SCHEMA,
  sourceLabel: String = "operation launch",
): OpenCodeLaunchIdentity = OpenCodeIdentityDecoder(raw, failureCode, sourceLabel).decode()

private class OpenCodeIdentityDecoder(
  raw: String,
  private val failureCode: RuntimeFailureCode,
  private val sourceLabel: String,
) {
  private fun invalid(
    message: String,
    cause: Throwable? = null,
  ): Nothing =
    throw SkillBillRuntimeException(failureCode, "Invalid OpenCode identity in $sourceLabel: $message", cause)

  private val values =
    try {
      JsonCodec.parseValue(raw) as? Map<*, *>
    } catch (failure: MalformedJsonTextError) {
      invalid("Malformed OpenCode identity JSON.", failure)
    } ?: invalid("OpenCode identity must be an object.")

  private fun string(
    key: String,
    nullable: Boolean = false,
  ): String? {
    if (key !in values) invalid("OpenCode identity field is missing.")
    val value = values[key]
    if (nullable && value == null) return null
    return value as? String ?: invalid("OpenCode identity field is invalid.")
  }

  private fun selection(key: String): OpenCodeEnvironmentSelection {
    val value = values[key] as? Map<*, *> ?: invalid("OpenCode identity selection is invalid.")
    val supplied = value[K.SUPPLIED] as? Boolean ?: invalid("OpenCode identity selection is invalid.")
    if (K.VALUE !in value) invalid("OpenCode identity selection value is missing.")
    val selected = value[K.VALUE]
    if (selected != null && selected !is String) {
      invalid("OpenCode identity selection is invalid.")
    }
    if (supplied != (selected != null)) {
      invalid("OpenCode identity selection presence is inconsistent.")
    }
    return OpenCodeEnvironmentSelection(supplied, selected)
  }

  private fun absolutePathValue(value: String): String {
    val path =
      try {
        Path.of(value)
      } catch (failure: InvalidPathException) {
        invalid("OpenCode identity path is invalid.", failure)
      }
    if (!path.isAbsolute || path.normalize().toString() != value) {
      invalid("OpenCode identity path is invalid.")
    }
    return value
  }

  private fun absolutePath(key: String): String = absolutePathValue(string(key) ?: invalid("OpenCode path is missing."))

  fun decode(): OpenCodeLaunchIdentity {
    val present = values[K.INLINE_CONFIG_PRESENT] as? Boolean ?: invalid("OpenCode inline identity is invalid.")
    val fingerprint = string(K.INLINE_CONFIG_FINGERPRINT, nullable = true)
    if (present != (fingerprint != null)) invalid("OpenCode inline identity is inconsistent.")
    if (fingerprint != null && !fingerprint.matches(Regex("[0-9a-f]{64}"))) {
      invalid("OpenCode inline identity fingerprint is invalid.")
    }
    return OpenCodeLaunchIdentity(
      home = selection(K.HOME),
      configHome = selection(K.CONFIG_HOME),
      dataHome = selection(K.DATA_HOME),
      cacheHome = selection(K.CACHE_HOME),
      stateHome = selection(K.STATE_HOME),
      configDirectory = selection(K.CONFIG_DIRECTORY),
      configFile = selection(K.CONFIG_FILE),
      disableProjectConfig = selection(K.DISABLE_PROJECT_CONFIG),
      effectiveHome = absolutePath(K.EFFECTIVE_HOME),
      effectiveConfigHome = absolutePath(K.EFFECTIVE_CONFIG_HOME),
      effectiveDataHome = absolutePath(K.EFFECTIVE_DATA_HOME),
      effectiveCacheHome = absolutePath(K.EFFECTIVE_CACHE_HOME),
      effectiveStateHome = absolutePath(K.EFFECTIVE_STATE_HOME),
      effectiveConfigDirectory = absolutePath(K.EFFECTIVE_CONFIG_DIRECTORY),
      effectiveConfigFile = string(K.EFFECTIVE_CONFIG_FILE, nullable = true)?.let(::absolutePathValue),
      inlineConfigPresent = present,
      inlineConfigFingerprint = fingerprint,
    )
  }
}

private fun selection(value: OpenCodeEnvironmentSelection): Map<String, Any?> =
  mapOf(K.SUPPLIED to value.supplied, K.VALUE to value.value)
