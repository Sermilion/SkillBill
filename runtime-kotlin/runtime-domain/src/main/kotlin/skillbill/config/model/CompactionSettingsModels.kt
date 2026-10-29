package skillbill.config.model

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

const val COMPACTION_KEY: String = "compaction"

object CompactionFieldKeys {
  const val ENABLED: String = "enabled"
  const val WINDOW_TOKENS: String = "window_tokens"
  const val TRIGGER_PCT: String = "trigger_pct"
  const val PHASES: String = "phases"
}

private const val DEFAULT_COMPACTION_ENABLED: Boolean = true
internal const val DEFAULT_COMPACTION_WINDOW_TOKENS: Int = 400_000
internal const val DEFAULT_COMPACTION_TRIGGER_PCT: Int = 70

private const val MIN_COMPACTION_TRIGGER_TOKENS: Int = 200_000

private const val PERCENT_SCALE: Int = 100
private val VALID_TRIGGER_PCT: IntRange = 1..PERCENT_SCALE

data class PhaseCompactionDirective(
  val windowTokens: Int,
  val triggerPct: Int,
) {
  val triggerTokens: Int get() = windowTokens / PERCENT_SCALE * triggerPct

  init {
    require(windowTokens > 0) { "PhaseCompactionDirective.windowTokens must be positive." }
    require(triggerPct in VALID_TRIGGER_PCT) {
      "PhaseCompactionDirective.triggerPct must be between ${VALID_TRIGGER_PCT.first} and ${VALID_TRIGGER_PCT.last}."
    }
  }
}

data class CompactionSettings(
  val enabled: Boolean = DEFAULT_COMPACTION_ENABLED,
  val windowTokens: Int = DEFAULT_COMPACTION_WINDOW_TOKENS,
  val triggerPct: Int = DEFAULT_COMPACTION_TRIGGER_PCT,
  val phases: Map<String, PhaseCompactionDirective> = emptyMap(),
) {
  fun directiveFor(phaseId: String): PhaseCompactionDirective? {
    if (!enabled) return null
    return phases[phaseId] ?: PhaseCompactionDirective(windowTokens, triggerPct)
  }

  companion object {
    val DEFAULT: CompactionSettings = CompactionSettings()
  }
}

sealed interface CompactionField<out T> {
  data object Missing : CompactionField<Nothing>

  data object Null : CompactionField<Nothing>

  data class Value<T>(val value: T) : CompactionField<T>
}

data class PhaseCompactionFields(
  val windowTokens: CompactionField<Int> = CompactionField.Missing,
  val triggerPct: CompactionField<Int> = CompactionField.Missing,
)

data class CompactionSettingsFields(
  val enabled: CompactionField<Boolean> = CompactionField.Missing,
  val windowTokens: CompactionField<Int> = CompactionField.Missing,
  val triggerPct: CompactionField<Int> = CompactionField.Missing,
  val phases: CompactionField<Map<String, PhaseCompactionFields>> = CompactionField.Missing,
) {
  companion object {
    val EMPTY: CompactionSettingsFields = CompactionSettingsFields()
  }
}

sealed interface CompactionSettingsParse {
  data class Valid(
    val settings: CompactionSettings,
    val fields: CompactionSettingsFields = CompactionSettingsFields.EMPTY,
  ) : CompactionSettingsParse

  data class Invalid(
    val keyPath: String,
    val value: String,
    val reason: String,
  ) : CompactionSettingsParse
}

fun parseCompactionSettings(raw: Any?): CompactionSettingsParse {
  val invalid = compactionInvalid(raw)
  if (invalid != null) return invalid
  return CompactionSettingsParse.Valid(buildCompactionSettings(raw), buildCompactionSettingsFields(raw))
}

private fun compactionInvalid(raw: Any?): CompactionSettingsParse.Invalid? {
  val root = raw as? Map<*, *> ?: return invalidCompaction(COMPACTION_KEY, raw, "must be a mapping.")
  val fields = fieldsOf(root)
  return unsupportedFieldInvalid(COMPACTION_KEY, fields, COMPACTION_FIELDS, "is not a supported compaction field.")
    ?: enabledInvalid(fields)
    ?: triggerInvalid(COMPACTION_KEY, fields, DEFAULT_COMPACTION_WINDOW_TOKENS, DEFAULT_COMPACTION_TRIGGER_PCT)
    ?: phasesInvalid(
      fields[PHASES_KEY],
      intFieldValue(fields, WINDOW_KEY, DEFAULT_COMPACTION_WINDOW_TOKENS),
      intFieldValue(fields, TRIGGER_PCT_KEY, DEFAULT_COMPACTION_TRIGGER_PCT),
    )
}

private fun fieldsOf(map: Map<*, *>): Map<String, Any?> =
  map.entries.associate { (key, value) -> key.toString() to value }

private fun unsupportedFieldInvalid(
  path: String,
  fields: Map<String, Any?>,
  supported: Set<String>,
  reason: String,
): CompactionSettingsParse.Invalid? =
  fields.entries.firstOrNull { (key, _) -> key !in supported }?.let { (key, value) ->
    invalidCompaction("$path.$key", value, reason)
  }

private fun enabledInvalid(fields: Map<String, Any?>): CompactionSettingsParse.Invalid? {
  val value = fields[ENABLED_KEY]
  if (value == null || value is Boolean) return null
  return invalidCompaction("$COMPACTION_KEY.$ENABLED_KEY", value, "must be a boolean.")
}

private fun triggerInvalid(
  path: String,
  fields: Map<String, Any?>,
  defaultWindow: Int,
  defaultPct: Int,
): CompactionSettingsParse.Invalid? =
  intFieldInvalid(path, fields, WINDOW_KEY)
    ?: intFieldInvalid(path, fields, TRIGGER_PCT_KEY)
    ?: saneTriggerInvalid(
      path,
      intFieldValue(fields, WINDOW_KEY, defaultWindow),
      intFieldValue(fields, TRIGGER_PCT_KEY, defaultPct),
    )

private fun phasesInvalid(
  raw: Any?,
  defaultWindow: Int,
  defaultPct: Int,
): CompactionSettingsParse.Invalid? {
  if (raw == null) return null
  val phases = raw as? Map<*, *> ?: return invalidCompaction("$COMPACTION_KEY.$PHASES_KEY", raw, "must be a mapping.")
  return phases.entries.firstNotNullOfOrNull { (rawPhaseId, rawDirective) ->
    phaseInvalid(rawPhaseId, rawDirective, defaultWindow, defaultPct)
  }
}

private fun phaseInvalid(
  rawPhaseId: Any?,
  rawDirective: Any?,
  defaultWindow: Int,
  defaultPct: Int,
): CompactionSettingsParse.Invalid? {
  val path = "$COMPACTION_KEY.$PHASES_KEY.$rawPhaseId"
  if (rawPhaseId !is String || rawPhaseId !in FeatureTaskRuntimePhaseIds.all) {
    return invalidCompaction(path, rawDirective, "is not a runtime phase.")
  }
  val directive = rawDirective as? Map<*, *> ?: return invalidCompaction(path, rawDirective, "must be a mapping.")
  val fields = fieldsOf(directive)
  return unsupportedFieldInvalid(path, fields, PHASE_DIRECTIVE_FIELDS, "is not a supported phase compaction field.")
    ?: triggerInvalid(path, fields, defaultWindow, defaultPct)
}

private fun intFieldInvalid(
  path: String,
  fields: Map<String, Any?>,
  key: String,
): CompactionSettingsParse.Invalid? {
  val value = fields[key] ?: return null
  val whole = (value as? Number)?.let { it.toDouble() == it.toInt().toDouble() } ?: false
  return if (whole) null else invalidCompaction("$path.$key", value, "must be a whole number.")
}

private fun intFieldValue(
  fields: Map<String, Any?>,
  key: String,
  fallback: Int,
): Int = (fields[key] as? Number)?.toInt() ?: fallback

private fun saneTriggerInvalid(
  path: String,
  windowTokens: Int,
  triggerPct: Int,
): CompactionSettingsParse.Invalid? {
  val trigger = windowTokens / PERCENT_SCALE * triggerPct
  return when {
    windowTokens <= 0 ->
      invalidCompaction("$path.$WINDOW_KEY", windowTokens, "must be a positive number of tokens.")

    triggerPct !in VALID_TRIGGER_PCT ->
      invalidCompaction(
        "$path.$TRIGGER_PCT_KEY",
        triggerPct,
        "must be between ${VALID_TRIGGER_PCT.first} and ${VALID_TRIGGER_PCT.last}.",
      )

    trigger < MIN_COMPACTION_TRIGGER_TOKENS ->
      invalidCompaction(
        "$path.$WINDOW_KEY",
        windowTokens,
        "yields a $trigger-token compaction trigger at $triggerPct%, below the " +
          "$MIN_COMPACTION_TRIGGER_TOKENS-token floor; a trigger this low refills within a few turns and the " +
          "provider aborts the run as thrashing.",
      )

    else -> null
  }
}

private fun buildCompactionSettings(raw: Any?): CompactionSettings {
  val fields = fieldsOf(checkNotNull(raw as? Map<*, *>) { "validated compaction settings must be a mapping." })
  val windowTokens = intFieldValue(fields, WINDOW_KEY, DEFAULT_COMPACTION_WINDOW_TOKENS)
  val triggerPct = intFieldValue(fields, TRIGGER_PCT_KEY, DEFAULT_COMPACTION_TRIGGER_PCT)
  return CompactionSettings(
    enabled = fields[ENABLED_KEY] as? Boolean ?: DEFAULT_COMPACTION_ENABLED,
    windowTokens = windowTokens,
    triggerPct = triggerPct,
    phases = buildPhases(fields[PHASES_KEY], windowTokens, triggerPct),
  )
}

private fun buildCompactionSettingsFields(raw: Any?): CompactionSettingsFields {
  val fields = fieldsOf(checkNotNull(raw as? Map<*, *>) { "validated compaction settings must be a mapping." })
  val phases =
    authoredField(fields, PHASES_KEY) { phaseMap ->
      val rawPhases = checkNotNull(phaseMap as? Map<*, *>) { "validated phase settings must be a mapping." }
      rawPhases.entries.associate { (rawPhaseId, rawFields) ->
        val phaseId = checkNotNull(rawPhaseId as? String) { "validated phase id must be a string." }
        val phaseFields =
          fieldsOf(checkNotNull(rawFields as? Map<*, *>) { "validated phase directive must be a mapping." })
        phaseId to
          PhaseCompactionFields(
            windowTokens = authoredField(phaseFields, WINDOW_KEY) { (it as Number).toInt() },
            triggerPct = authoredField(phaseFields, TRIGGER_PCT_KEY) { (it as Number).toInt() },
          )
      }
    }
  return CompactionSettingsFields(
    enabled = authoredField(fields, ENABLED_KEY) { it as Boolean },
    windowTokens = authoredField(fields, WINDOW_KEY) { (it as Number).toInt() },
    triggerPct = authoredField(fields, TRIGGER_PCT_KEY) { (it as Number).toInt() },
    phases = phases,
  )
}

private fun <T> authoredField(
  fields: Map<String, Any?>,
  key: String,
  value: (Any) -> T,
): CompactionField<T> {
  if (!fields.containsKey(key)) return CompactionField.Missing
  val raw = fields[key] ?: return CompactionField.Null
  return CompactionField.Value(value(raw))
}

private fun buildPhases(
  raw: Any?,
  defaultWindow: Int,
  defaultPct: Int,
): Map<String, PhaseCompactionDirective> {
  val phases = raw as? Map<*, *> ?: return emptyMap()
  return phases.entries.associate { (rawPhaseId, rawDirective) ->
    val fields = fieldsOf(checkNotNull(rawDirective as? Map<*, *>) { "validated phase directive must be a mapping." })
    checkNotNull(rawPhaseId as? String) { "validated phase id must be a string." } to
      PhaseCompactionDirective(
        windowTokens = intFieldValue(fields, WINDOW_KEY, defaultWindow),
        triggerPct = intFieldValue(fields, TRIGGER_PCT_KEY, defaultPct),
      )
  }
}

private fun invalidCompaction(
  keyPath: String,
  value: Any?,
  reason: String,
): CompactionSettingsParse.Invalid =
  CompactionSettingsParse.Invalid(keyPath = keyPath, value = value?.toString() ?: "null", reason = reason)

private const val ENABLED_KEY: String = CompactionFieldKeys.ENABLED
private const val WINDOW_KEY: String = CompactionFieldKeys.WINDOW_TOKENS
private const val TRIGGER_PCT_KEY: String = CompactionFieldKeys.TRIGGER_PCT
private const val PHASES_KEY: String = CompactionFieldKeys.PHASES
private val COMPACTION_FIELDS: Set<String> = setOf(ENABLED_KEY, WINDOW_KEY, TRIGGER_PCT_KEY, PHASES_KEY)
private val PHASE_DIRECTIVE_FIELDS: Set<String> = setOf(WINDOW_KEY, TRIGGER_PCT_KEY)
