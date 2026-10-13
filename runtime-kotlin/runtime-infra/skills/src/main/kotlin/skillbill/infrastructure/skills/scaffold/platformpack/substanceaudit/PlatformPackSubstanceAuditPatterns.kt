package skillbill.infrastructure.skills.scaffold.platformpack.substanceaudit

import kotlin.io.path.name

internal fun rolePlaceholder(name: String): String =
  when {
    name.contains("code-check") -> "role-quality-check"
    name.contains("code-review-") -> "role-specialist"
    name.contains("code-review") -> "role-baseline"
    else -> "role-platform"
  }

internal val RULE_HEADING = Regex("rule|check|requirement|failure|correctness", RegexOption.IGNORE_CASE)
internal val OBLIGATION =
  Regex("\\b(must|never|require|reject|ensure|verify|flag|prevent|do not|fail)\\b", RegexOption.IGNORE_CASE)
internal val FAILURE =
  Regex(
    "\\b(fail|failure|reject|break|bug|risk|leak|loss|corrupt|deadlock|race|crash|invalid|" +
      "incorrect|unsafe|consequence|regression|exposure|starvation|timeout)\\w*",
    RegexOption.IGNORE_CASE,
  )
internal val QUALITY_MAINTENANCE_COST =
  Regex(
    "\\b(maintenance|harder to follow|harder to reason|obscure|obscures|hide|hidden|drift|" +
      "misstate|misstates|trace|tracing|clarif|indirection|unnecessary|invalid combination|" +
      "ownership|ambiguous|ambiguity|effort)\\w*",
    RegexOption.IGNORE_CASE,
  )
internal val CLUSTERS =
  listOf(
    Regex("state|lifecycle|concurr|order|race|deadlock", RegexOption.IGNORE_CASE),
    Regex("contract|data|valid|auth|security|serial|exposure", RegexOption.IGNORE_CASE),
    Regex(
      "resource|performance|toolchain|build|operat|memory|latency|timeout|gradle|compiler",
      RegexOption.IGNORE_CASE,
    ),
  )
internal val PLACEHOLDERS =
  Regex(
    "\\b(TODO|FIXME|TBD|XXX)\\b|\\b(generic|example)\\s+(mechanism|api|command)\\b|" +
      "\\b(fill|replace)\\s+(this|me|content)\\b",
    RegexOption.IGNORE_CASE,
  )
internal val EVIDENCE =
  Regex(
    "`([^`]+)`|(?:^|\\s)(?:./)?[a-z0-9_.-]+(?:gradle|lint|test|build|check|config)[a-z0-9_.:/-]*",
    RegexOption.IGNORE_CASE,
  )
internal val GENERIC_EVIDENCE =
  Regex(
    "(?:^|[\\s._-])(generic|example|placeholder)|(?:mechanism|api|command)(?:s)?$",
    RegexOption.IGNORE_CASE,
  )
internal val GENERATED_POINTER_NAMES =
  setOf(
    "review-orchestrator.md",
    "specialist-contract.md",
    "review-delegation.md",
    "review-scope.md",
    "shell-ceremony.md",
    "telemetry-contract.md",
    "stack-routing.md",
  )
