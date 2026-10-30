package skillbill.config.model

import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import java.math.BigInteger

sealed interface ReviewContextBudgetParse {
  data class Valid(
    val policy: ReviewContextBudgetPolicy,
    val retiredProviderThresholds: Boolean,
  ) : ReviewContextBudgetParse

  data class Invalid(
    val keyPath: String,
    val value: String,
    val reason: String,
  ) : ReviewContextBudgetParse
}

fun parseReviewContextBudget(raw: Any?): ReviewContextBudgetParse {
  if (raw == null) return invalidBudget("review_context_budget", raw, "must be a mapping, not null.")
  val source = raw as? Map<*, *> ?: return invalidBudget("review_context_budget", raw, "must be a mapping.")
  val retired = source.containsKey("provider_token_thresholds")
  val active = source - "provider_token_thresholds"
  val unknown = active.entries.firstOrNull { it.key.toString() !in REVIEW_CONTEXT_BUDGET_KEYS }
  if (unknown != null) {
    return invalidBudget("review_context_budget.${unknown.key}", unknown.value, "is not a recognized key.")
  }
  val defaults = ReviewContextBudgetPolicy.DEFAULT
  val reader = BudgetReader(active)
  val parent = reader.long("max_parent_packet_bytes", defaults.maxParentPacketBytes)
  val launch = reader.long("max_lane_launch_bytes", defaults.maxLaneLaunchBytes)
  val evidence = reader.long("max_lane_evidence_bytes", defaults.maxLaneEvidenceBytes)
  val result =
    reader.long("max_evidence_result_bytes", defaults.maxEvidenceResultBytes)
  val laneResult = reader.long("max_lane_result_bytes", defaults.maxLaneResultBytes)
  val expansions =
    reader.int("max_assignment_expansions", defaults.maxAssignmentExpansions)
  val calls =
    reader.int("max_specialist_tool_calls", defaults.maxSpecialistToolCalls)
  val turns =
    reader.int("max_specialist_model_turns", defaults.maxSpecialistModelTurns)
  val pairs =
    reader.int("max_routing_analysis_pairs", defaults.maxRoutingAnalysisPairs)
  val routingBytes =
    reader.long("max_routing_analysis_bytes", defaults.maxRoutingAnalysisBytes)
  val violation =
    ReviewContextBudgetPolicy.byteLimitViolation(
      listOf(
        parent,
        launch,
        evidence,
        result,
        laneResult,
        defaults.maxSpecIntentProjectionBytes,
      ),
    )
      ?: ReviewContextBudgetPolicy.executionLimitViolation(expansions, calls, turns, pairs, routingBytes)
      ?: ReviewContextBudgetPolicy.relationshipViolation(result, evidence, launch, parent)
  return reader.failure ?: if (violation != null) {
    ReviewContextBudgetParse.Invalid("review_context_budget", raw.toString(), violation)
  } else {
    ReviewContextBudgetParse.Valid(
      ReviewContextBudgetPolicy(
        parent,
        launch,
        evidence,
        result,
        laneResult,
        expansions,
        calls,
        turns,
        pairs,
        routingBytes,
        defaults.maxSpecIntentProjectionBytes,
      ),
      retired,
    )
  }
}

private class BudgetReader(private val active: Map<*, *>) {
  var failure: ReviewContextBudgetParse.Invalid? = null
    private set

  fun long(
    key: String,
    fallback: Long,
  ): Long {
    if (!active.containsKey(key)) return fallback
    val value = active[key]
    if (value == null) {
      failure = failure ?: invalidBudget("review_context_budget.$key", null, "must be an integer, not null.")
      return fallback
    }
    val parsed =
      when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is BigInteger -> value.takeIf { it >= LONG_MIN && it <= LONG_MAX }?.toLong()
        else -> null
      }
    if (parsed == null) {
      val reason =
        if (value is BigInteger) "is outside the signed 64-bit integer range." else "must be an exact integer."
      failure = failure ?: invalidBudget("review_context_budget.$key", value, reason)
    }
    return parsed ?: fallback
  }

  fun int(
    key: String,
    fallback: Int,
  ): Int {
    val parsed = long(key, fallback.toLong())
    if (parsed !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
      failure =
        failure ?: invalidBudget("review_context_budget.$key", parsed, "is outside the signed 32-bit integer range.")
      return fallback
    }
    return parsed.toInt()
  }
}

private fun invalidBudget(
  keyPath: String,
  value: Any?,
  reason: String,
) = ReviewContextBudgetParse.Invalid(keyPath, value?.toString() ?: "null", reason)

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

private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
