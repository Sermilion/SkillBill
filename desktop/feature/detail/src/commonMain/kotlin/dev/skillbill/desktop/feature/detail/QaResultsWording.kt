package dev.skillbill.desktop.feature.detail

internal const val EMPTY_SCOPE = "empty_scope"

private val SKIP_PHRASES = mapOf(
  "capability_absent" to "the QA capability it needs is missing",
  "unsupported_target" to "the target is not supported",
  "disabled_policy" to "QA is turned off for this run",
  "isolation_unavailable" to "an isolated QA workspace was not available",
  "unauthorized_target" to "the target is not authorized",
  "offline_target" to "the target is offline",
  "device_contended" to "another run holds every device",
  "production_only" to "the only target is production",
  "docker_unavailable" to "Docker is not available",
  "environment_unavailable" to "an isolated environment could not be set up",
  "isolation_rejected" to "the environment failed the isolation checks",
  "setup_budget_exceeded" to "environment setup ran past its time limit",
  "browser_unavailable" to "no browser driver is available",
)

private val SHORT_REASONS = mapOf(
  "capability_absent" to "capability missing",
  "unsupported_target" to "target not supported",
  "disabled_policy" to "QA turned off",
  "isolation_unavailable" to "isolation unavailable",
  "unauthorized_target" to "target not authorized",
  "offline_target" to "target offline",
  "device_contended" to "device in use",
  "production_only" to "production only",
  "docker_unavailable" to "Docker missing",
  "environment_unavailable" to "environment missing",
  "isolation_rejected" to "isolation rejected",
  "setup_budget_exceeded" to "setup timed out",
  "browser_unavailable" to "browser driver missing",
)

internal fun isKnownSkipReason(token: String): Boolean = token == EMPTY_SCOPE || token in SKIP_PHRASES

internal fun humanized(token: String): String = token.replace('_', ' ')

internal fun capitalized(text: String): String = text.replaceFirstChar(Char::uppercase)

internal fun skipPhrase(token: String): String = SKIP_PHRASES[token] ?: humanized(token)

internal fun shortReason(token: String): String = SHORT_REASONS[token] ?: humanized(token)

internal fun counted(count: Int, noun: String): String = "$count ${if (count == 1) noun else plural(noun)}"

private fun plural(noun: String): String = when {
  noun.endsWith("s") -> noun
  noun.endsWith("y") -> noun.dropLast(1) + "ies"
  else -> noun + "s"
}

/** "a", "a and b", "a, b and c". */
internal fun joinedList(parts: List<String>): String =
  if (parts.size < 2) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
