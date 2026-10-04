package skillbill.engine.featuretask.runner

import java.nio.file.Files
import java.nio.file.Path

internal object SlotBaselineNormalizer {
  const val TIMESTAMP_PLACEHOLDER = "__NORMALIZED_TIMESTAMP__"
  const val SESSION_ID_PLACEHOLDER = "__NORMALIZED_SESSION_ID__"
  const val DURATION_PLACEHOLDER = "__NORMALIZED_DURATION__"
  const val REVIEW_RUN_ID_PLACEHOLDER = "__NORMALIZED_REVIEW_RUN_ID__"
  const val SHA64_PLACEHOLDER = "__NORMALIZED_SHA64__"
  const val SHA40_PLACEHOLDER = "__NORMALIZED_SHA40__"
  const val REPO_ROOT_PLACEHOLDER = "__NORMALIZED_REPO_ROOT__"
  const val TEMP_PATH_PLACEHOLDER = "__NORMALIZED_TEMP_PATH__"
  const val EVENT_UUID_PLACEHOLDER = "__NORMALIZED_EVENT_UUID__"
  const val INVOCATION_ID_PLACEHOLDER = "__NORMALIZED_INVOCATION_ID__"
  const val REVIEW_SESSION_ID_PLACEHOLDER = "__NORMALIZED_REVIEW_SESSION_ID__"

  private const val REPO_ROOT_PREFIX = "skillbill-slot-baseline-repo-"
  private const val TEMP_HOME_PREFIX = "skillbill-slot-baseline-home-"

  private val timestampKey =
    setOf(
      "started_at",
      "updated_at",
      "finished_at",
      "first_started_at",
      "timestamp",
      "heartbeat_at",
      "expires_at",
      "recorded_at",
    )
  private val durationKey = setOf("duration_millis", "duration_ms", "duration_seconds")
  private val wireValueAccessors = setOf("getWireValue", "wireValue")

  private val tempRootAlternation: String =
    Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().let { tmp ->
      listOf(tmp.toString(), tmp.toRealPath().toString())
        .distinct()
        .sortedByDescending(String::length)
        .joinToString("|") { Regex.escape(it.trimEnd('/')) }
    }

  private val repoRootPattern = Regex("""(?:$tempRootAlternation)/$REPO_ROOT_PREFIX\d+""")

  private val tempPathPattern = Regex("""(?:$tempRootAlternation)/[^/\s"'`]+""")

  private val isoInstantPattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z""")

  private val sqliteDatetimePattern = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

  private val sha64Pattern = Regex("""[0-9a-f]{64}""")

  private val sha40Pattern = Regex("""[0-9a-f]{40}""")

  private val reviewRunIdPattern = Regex("""rvw-\d{8}-\d{6}-[a-z0-9]+""")

  private val invocationIdPattern = Regex("""phr-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

  private val reviewSessionIdPattern = Regex("""rvs-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

  fun newRepoRoot(): Path = Files.createTempDirectory(REPO_ROOT_PREFIX)

  fun newTempHome(): Path = Files.createTempDirectory(TEMP_HOME_PREFIX)

  fun normalize(value: Any?): Any? =
    when (value) {
      is Map<*, *> -> normalizeMap(value)
      is List<*> -> value.map(::normalize)
      is String -> normalizeText(value)
      is Enum<*> -> enumWireValue(value)
      else -> value
    }

  fun normalizeText(text: String): String =
    repoRootPattern
      .replace(text, REPO_ROOT_PLACEHOLDER)
      .let { tempPathPattern.replace(it, TEMP_PATH_PLACEHOLDER) }
      .let { isoInstantPattern.replace(it, TIMESTAMP_PLACEHOLDER) }
      .let { sqliteDatetimePattern.replace(it, TIMESTAMP_PLACEHOLDER) }
      .let { sha64Pattern.replace(it, SHA64_PLACEHOLDER) }
      .let { sha40Pattern.replace(it, SHA40_PLACEHOLDER) }
      .let { reviewRunIdPattern.replace(it, REVIEW_RUN_ID_PLACEHOLDER) }
      .let { invocationIdPattern.replace(it, INVOCATION_ID_PLACEHOLDER) }
      .let { reviewSessionIdPattern.replace(it, REVIEW_SESSION_ID_PLACEHOLDER) }

  private fun enumWireValue(value: Enum<*>): Any =
    value.javaClass.methods
      .firstOrNull { method -> method.name in wireValueAccessors && method.parameterCount == 0 }
      ?.invoke(value)
      ?: value.name

  private fun normalizeMap(map: Map<*, *>): Map<String, Any?> =
    map.entries
      .sortedBy { it.key.toString() }
      .associate { (key, raw) ->
        val name = key.toString()
        name to
          when {
            name in timestampKey && raw != null -> TIMESTAMP_PLACEHOLDER
            name in durationKey && raw != null -> DURATION_PLACEHOLDER
            name == "session_id" && raw is String && raw.isNotBlank() -> SESSION_ID_PLACEHOLDER
            name == "review_run_id" && raw is String -> REVIEW_RUN_ID_PLACEHOLDER
            name == "event_uuid" && raw is String -> EVENT_UUID_PLACEHOLDER
            else -> normalize(raw)
          }
      }
}
