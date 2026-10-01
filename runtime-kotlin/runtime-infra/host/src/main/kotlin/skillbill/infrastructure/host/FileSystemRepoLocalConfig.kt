
package skillbill.infrastructure.host

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import me.tatarka.inject.annotations.Inject
import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.RepoLocalConfigKey
import skillbill.config.model.ValidationGateRepoConfig
import skillbill.config.model.ValidationGateRepoConfigParse
import skillbill.config.model.parseSpecType
import skillbill.config.model.parseValidationGateRepoConfig
import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.MalformedRepoLocalConfigError
import skillbill.error.shellcontent.UnreadableRepoLocalConfigError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemRepoLocalConfig(
  private val diagnostics: RuntimeDiagnostics,
) : RepoLocalConfigPort {
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }

  override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest): ReadRepoLocalConfigResult {
    val configPath = configPath(request.repoRoot)
    if (!Files.exists(configPath)) {
      return ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
    }
    val payload = readConfigPayload(configPath)
    val raw = parseConfigMap(configPath, payload)
    rejectRemovedParallelAgentConfig(configPath, raw)
    return ReadRepoLocalConfigResult(buildConfig(configPath, raw))
  }

  private fun buildConfig(
    path: Path,
    raw: Map<String, Any?>,
  ): RepoLocalConfig =
    RepoLocalConfig(
      specType =
        parseKnownKey(path, raw, RepoLocalConfigKey.SPEC_TYPE) { value -> parseSpecType(value) }
          ?: RepoLocalConfig.defaults().specType,
      reviewContextBudget =
        if (raw.containsKey("review_context_budget")) {
          parseReviewContextBudget(path, raw["review_context_budget"])
        } else {
          ReviewContextBudgetPolicy.DEFAULT
        },
      validationGate =
        if (raw.containsKey("validation_gate")) {
          parseValidationGate(path, raw["validation_gate"])
        } else {
          ValidationGateRepoConfig.defaults()
        },
    )

  private fun rejectRemovedParallelAgentConfig(
    path: Path,
    raw: Map<String, Any?>,
  ) {
    if (!raw.containsKey(REMOVED_PARALLEL_AGENT_KEY)) return
    val normalized = raw[REMOVED_PARALLEL_AGENT_KEY]?.toString()?.trim()?.lowercase()
    if (normalized.isNullOrBlank() || normalized == "none") return
    throw MalformedRepoLocalConfigError(
      path = path.toString(),
      key = REMOVED_PARALLEL_AGENT_KEY,
      value = raw[REMOVED_PARALLEL_AGENT_KEY].toString(),
      reason = "names a removed capability; delete this key or set it to none.",
    )
  }

  private fun parseValidationGate(
    path: Path,
    value: Any?,
  ): ValidationGateRepoConfig =
    when (val parsed = parseValidationGateRepoConfig(value)) {
      is ValidationGateRepoConfigParse.Valid -> parsed.config
      is ValidationGateRepoConfigParse.Invalid -> throw MalformedRepoLocalConfigError(
        path = path.toString(),
        key = parsed.keyPath,
        value = parsed.value,
        reason = parsed.reason,
      )
    }

  private fun parseReviewContextBudget(
    path: Path,
    value: Any?,
  ): ReviewContextBudgetPolicy {
    val raw = budgetMapping(path, "review_context_budget", value)
    if (raw.containsKey("provider_token_thresholds")) {
      diagnostics.warning(
        "Repo-local config degradation: seam=provider_token_thresholds used=ignored " +
          "expected=absent cause=retired configuration block",
      )
    }
    val active = raw - "provider_token_thresholds"
    validateBudgetKeys(path, active, "review_context_budget", REVIEW_CONTEXT_BUDGET_KEYS)
    val defaults = ReviewContextBudgetPolicy.DEFAULT
    return try {
      buildReviewContextBudget(path, active, defaults)
    } catch (error: IllegalArgumentException) {
      throw MalformedRepoLocalConfigError(
        path.toString(),
        "review_context_budget",
        value.toString(),
        error.message ?: "is inconsistent.",
        error,
      )
    }
  }

  private fun buildReviewContextBudget(
    path: Path,
    raw: Map<*, *>,
    defaults: ReviewContextBudgetPolicy,
  ): ReviewContextBudgetPolicy {
    return ReviewContextBudgetPolicy(
      maxParentPacketBytes = budgetLong(path, raw, "max_parent_packet_bytes", defaults.maxParentPacketBytes),
      maxLaneLaunchBytes = budgetLong(path, raw, "max_lane_launch_bytes", defaults.maxLaneLaunchBytes),
      maxLaneEvidenceBytes = budgetLong(path, raw, "max_lane_evidence_bytes", defaults.maxLaneEvidenceBytes),
      maxEvidenceResultBytes = budgetLong(path, raw, "max_evidence_result_bytes", defaults.maxEvidenceResultBytes),
      maxLaneResultBytes = budgetLong(path, raw, "max_lane_result_bytes", defaults.maxLaneResultBytes),
      maxAssignmentExpansions = assignmentExpansions(path, raw, defaults.maxAssignmentExpansions),
      maxSpecialistToolCalls = budgetInt(path, raw, "max_specialist_tool_calls", defaults.maxSpecialistToolCalls),
      maxSpecialistModelTurns = budgetInt(path, raw, "max_specialist_model_turns", defaults.maxSpecialistModelTurns),
      maxRoutingAnalysisPairs = budgetInt(path, raw, "max_routing_analysis_pairs", defaults.maxRoutingAnalysisPairs),
      maxRoutingAnalysisBytes = budgetLong(path, raw, "max_routing_analysis_bytes", defaults.maxRoutingAnalysisBytes),
    )
  }

  private fun <T> parseKnownKey(
    path: Path,
    raw: Map<String, Any?>,
    configKey: RepoLocalConfigKey,
    parser: (String?) -> T?,
  ): T? {
    if (!raw.containsKey(configKey.key)) return null
    val rawValue = raw[configKey.key]
    val asString = rawValue?.toString()
    return parser(asString) ?: throw MalformedRepoLocalConfigError(
      path = path.toString(),
      key = configKey.key,
      value = rawValue?.toString() ?: "null",
      reason = "is not a recognized value for this key.",
    )
  }

  private fun parseConfigMap(
    path: Path,
    payload: String,
  ): Map<String, Any?> {
    if (payload.isBlank()) return emptyMap()
    return try {
      JsonCodec.anyToStringAnyMap(yamlMapper.readValue(payload, Any::class.java)) ?: emptyMap()
    } catch (error: JacksonException) {
      throw MalformedRepoLocalConfigError(
        path = path.toString(),
        key = "",
        value = "<document>",
        reason = "is not a valid YAML mapping.",
        cause = error,
      )
    }
  }

  private fun readConfigPayload(path: Path): String =
    try {
      Files.readString(path)
    } catch (error: IOException) {
      throw UnreadableRepoLocalConfigError(path.toString(), error)
    } catch (error: SecurityException) {
      throw UnreadableRepoLocalConfigError(path.toString(), error)
    }
}

private const val REMOVED_PARALLEL_AGENT_KEY = "code_review_parallel_agent"

private fun budgetMapping(
  path: Path,
  key: String,
  value: Any?,
): Map<*, *> {
  if (value == null) malformedBudget(path, key, value, "must be a mapping, not null.")
  return value as? Map<*, *> ?: malformedBudget(path, key, value, "must be a mapping.")
}

private fun validateBudgetKeys(
  path: Path,
  raw: Map<*, *>,
  prefix: String,
  allowed: Set<String>,
) {
  val unknown = raw.entries.firstOrNull { entry -> entry.key.toString() !in allowed } ?: return
  malformedBudget(path, "$prefix.${unknown.key}", unknown.value, "is not a recognized key.")
}

private fun assignmentExpansions(
  path: Path,
  raw: Map<*, *>,
  fallback: Int,
): Int = budgetInt(path, raw, "max_assignment_expansions", fallback)

private fun budgetInt(
  path: Path,
  raw: Map<*, *>,
  key: String,
  fallback: Int,
): Int {
  val parsed = budgetLong(path, raw, key, fallback.toLong())
  if (parsed !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
    malformedBudget(path, "review_context_budget.$key", parsed, "is outside the signed 32-bit integer range.")
  }
  return parsed.toInt()
}

private fun budgetLong(
  path: Path,
  source: Map<*, *>,
  key: String,
  fallback: Long,
  prefix: String = "review_context_budget",
): Long {
  if (!source.containsKey(key)) return fallback
  val rawValue = source[key] ?: malformedBudget(path, "$prefix.$key", null, "must be an integer, not null.")
  return when (rawValue) {
    is Byte, is Short, is Int, is Long -> (rawValue as Number).toLong()
    is BigInteger ->
      rawValue.longValueExactOrNull()
        ?: malformedBudget(path, "$prefix.$key", rawValue, "is outside the signed 64-bit integer range.")
    else -> malformedBudget(path, "$prefix.$key", rawValue, "must be an exact integer.")
  }
}

private fun malformedBudget(
  path: Path,
  key: String,
  value: Any?,
  reason: String,
): Nothing = throw MalformedRepoLocalConfigError(path.toString(), key, value?.toString() ?: "null", reason)

internal fun configPath(repoRoot: Path): Path =
  repoRoot
    .resolve(".skill-bill")
    .resolve(REPO_LOCAL_CONFIG_FILE_NAME)
    .toAbsolutePath()
    .normalize()

internal const val REPO_LOCAL_CONFIG_FILE_NAME: String = "config.yaml"

private val REVIEW_CONTEXT_BUDGET_KEYS =
  setOf(
    "max_parent_packet_bytes",
    "max_lane_launch_bytes",
    "max_lane_evidence_bytes",
    "max_evidence_result_bytes",
    "max_lane_result_bytes",
    "max_assignment_expansions",
    "max_specialist_tool_calls",
    "max_specialist_model_turns",
    "max_routing_analysis_pairs",
    "max_routing_analysis_bytes",
  )

private fun BigInteger.longValueExactOrNull(): Long? {
  if (this > BigInteger.valueOf(Long.MAX_VALUE) || this < BigInteger.valueOf(Long.MIN_VALUE)) {
    return null
  }
  return toLong()
}
