package skillbill.infrastructure.skills.scaffold.rendering

private const val CODE_REVIEW_STATE_LIFECYCLE_RULE_COUNT = 4
private const val CODE_REVIEW_CONTRACT_DATA_RULE_COUNT = 3
private const val CODE_REVIEW_RESOURCE_TOOLCHAIN_RULE_COUNT = 3
private const val CODE_REVIEW_CONTRACT_DATA_RULE_NUMBER_OFFSET = 5
private const val CODE_REVIEW_RESOURCE_TOOLCHAIN_RULE_NUMBER_OFFSET = 8

internal fun qualityCheckContent(summary: String): String =
  buildString {
    appendLine("## Purpose")
    appendLine()
    appendLine(summary)
    appendLine()
    appendLine("## Execution Steps")
    appendLine()
    appendLine("1. Determine the files in scope for the current unit of work.")
    appendLine(
      "2. Discover commands from repository build files, wrappers, and CI configuration " +
        "before falling back to defaults.",
    )
    appendLine("3. Run the pack's quality-check entrypoint and capture the scoped failures.")
    appendLine("4. Fix only failures that belong to the scoped work unless the contract says otherwise.")
    appendLine("5. TODO: name the repository files, wrappers, or CI jobs used to discover the exact checker commands.")
    appendLine("6. TODO: replace this prompt with backticked repository-specific tool commands and scoped variants.")
    appendLine()
    appendLine("## Fix Strategy")
    appendLine()
    appendLine("- Follow a priority-ordered fix ladder and never suppress failures or add TODO comments.")
    appendLine("- Keep changes aligned with the project's existing conventions and build tooling.")
    appendLine("- Escalate to the full suite when targeted checks cannot establish safety.")
    appendLine("- Call out any blocker that requires a maintainer decision instead of guessing.")
    appendLine()
    appendLine("### Repair Window")
    appendLine()
    appendLine(
      "Collect one complete finding set before repairing anything. While that set is open, do not invoke " +
        "any check, test, compile, format-task, quality-check command, pack checker, skill-bill phase validation, " +
        "or delegated subagent check. Allowed work is read, search, and source edits only.",
    )
    appendLine(
      "- TODO: define failure ownership, priority order, targeted reruns, " +
        "and the condition that escalates to the full suite.",
    )
  }

internal fun areaReviewContent(
  summary: String,
  area: String,
  stackLabel: String,
): String {
  val areaLabel = area.replace("-", " ")
  return buildString {
    appendLine("## Focus")
    appendLine()
    appendLine(summary)
    appendLine()
    appendAreaReviewIgnoreSection(areaLabel, area)
    appendLine()
    appendLine("## Applicability")
    appendLine()
    appendLine("Use this specialist when changed code primarily affects $areaLabel concerns for this platform.")
    appendLine()
    appendLine("## Project-Specific Rules")
    appendLine()
    appendLine("### Review Rules")
    appendLine()
    appendLine(areaStarterRule(stackLabel, area))
    appendAreaReviewRuleSections(stackLabel, area)
    appendLine(canonicalSeverityCloser(area))
  }
}

private fun StringBuilder.appendAreaReviewIgnoreSection(
  areaLabel: String,
  area: String,
) {
  appendLine("## Ignore")
  appendLine()
  appendLine("- Findings outside $areaLabel concerns or unsupported by a concrete consequence.")
  appendLine("- Style-only preferences, formatting, and naming bikeshedding.")
  when (area) {
    "ui" -> {
      appendLine(
        "- Defer accessibility concerns to the ux-accessibility specialist " +
          "and security concerns to the security specialist.",
      )
    }
    "ux-accessibility" -> {
      appendLine(
        "- Defer UI correctness concerns to the ui specialist " +
          "and security concerns to the security specialist.",
      )
    }
  }
}

private fun StringBuilder.appendAreaReviewRuleSections(
  stackLabel: String,
  area: String,
) {
  appendLine()
  appendLine("### State, Lifecycle, and Ordering Failures")
  appendLine()
  repeat(CODE_REVIEW_STATE_LIFECYCLE_RULE_COUNT) { index ->
    appendLine(todoRule(stackLabel, area, "state/lifecycle/ordering", index + 1))
  }
  appendLine()
  appendLine("### Contract, Data, and Security Failures")
  appendLine()
  repeat(CODE_REVIEW_CONTRACT_DATA_RULE_COUNT) { index ->
    appendLine(
      todoRule(stackLabel, area, "contract/data/security", index + CODE_REVIEW_CONTRACT_DATA_RULE_NUMBER_OFFSET),
    )
  }
  appendLine()
  appendLine("### Resource, Toolchain, and Operational Failures")
  appendLine()
  repeat(CODE_REVIEW_RESOURCE_TOOLCHAIN_RULE_COUNT) { index ->
    appendLine(
      todoRule(
        stackLabel,
        area,
        "resource/toolchain/operational",
        index + CODE_REVIEW_RESOURCE_TOOLCHAIN_RULE_NUMBER_OFFSET,
      ),
    )
  }
}

private fun todoRule(
  stackLabel: String,
  area: String,
  cluster: String,
  number: Int,
): String =
  "- TODO rule $number ($cluster): require or prohibit one concrete `${stackLabel.ifBlank { "platform" }}` " +
    "mechanism for $area and name the observable failure or consequence in this same bullet."

private fun areaStarterRule(
  stackLabel: String,
  area: String,
): String {
  val stack = stackLabel.ifBlank { "Platform" }
  val rule = reviewAreaRule(area)
  return "- Verify `$stack ${rule.surface}` preserve their documented invariants; reject ${rule.failure}."
}

internal fun canonicalSeverityCloser(area: String): String =
  if (area == "code-quality") {
    "- Report every code-quality finding as Minor; never Blocker or Major."
  } else {
    "- For Blocker or Major findings, describe the concrete ${reviewAreaRule(area).consequenceScenario} scenario."
  }

private fun reviewAreaRule(area: String): ReviewAreaRule =
  when (area) {
    "code-quality" ->
      ReviewAreaRule(
        "language idioms and state models",
        "a behavior-preserving rewrite changes a documented invariant",
        "code-quality finding",
      )
    "architecture" ->
      ReviewAreaRule(
        "module and dependency APIs",
        "a dependency cycle or ownership boundary failure",
        "dependency-cycle or ownership-boundary failure",
      )
    "performance" ->
      ReviewAreaRule(
        "hot-path and resource APIs",
        "a measurable latency, memory, or throughput failure",
        "latency, memory-pressure, or throughput failure",
      )
    "platform-correctness" ->
      ReviewAreaRule(
        "lifecycle and concurrency APIs",
        "an invalid state or ordering failure",
        "invalid-state or ordering failure",
      )
    "security" ->
      ReviewAreaRule(
        "authentication and sensitive-data APIs",
        "an authorization or data-exposure failure",
        "authorization-bypass or data-exposure",
      )
    "testing" ->
      ReviewAreaRule(
        "test and fixture APIs",
        "an undetected regression or false-positive test failure",
        "undetected-regression or false-positive test",
      )
    "api-contracts" ->
      ReviewAreaRule(
        "request and serialization APIs",
        "a compatibility or validation failure",
        "compatibility or validation failure",
      )
    "persistence" ->
      ReviewAreaRule(
        "transaction and storage APIs",
        "a consistency or durability failure",
        "data-loss, consistency, or durability failure",
      )
    "reliability" ->
      ReviewAreaRule(
        "timeout and retry APIs",
        "an availability, duplication, or cleanup failure",
        "availability, duplication, or cleanup failure",
      )
    "ui" ->
      ReviewAreaRule(
        "UI state and rendering APIs",
        "an observable interaction or rendering failure",
        "user-visible interaction or rendering failure",
      )
    "ux-accessibility" ->
      ReviewAreaRule(
        "semantics and input APIs",
        "an accessibility or task-completion failure",
        "accessibility or task-completion failure",
      )
    else ->
      ReviewAreaRule(
        "$area APIs",
        "a concrete invariant or boundary failure",
        "invariant or boundary failure",
      )
  }

private data class ReviewAreaRule(
  val surface: String,
  val failure: String,
  val consequenceScenario: String,
)

internal fun baselineReviewContent(summary: String): String =
  buildString {
    appendLine("## Classification Rules")
    appendLine()
    appendLine(summary)
    appendLine("- If the platform's strong signals dominate, select this pack.")
    appendLine("- Otherwise, select the adjacent pack whose declared signals dominate.")
    appendLine()
    appendLine("## Diff-Signal Routing Table")
    appendLine()
    appendLine("- Module boundaries, dependency declarations, or ownership crossings -> `architecture` specialist.")
    appendLine("- Hot paths, blocking calls, allocation sites, or resource use -> `performance` specialist.")
    appendLine("- Lifecycle, concurrency, state-machine, or runtime API changes -> `platform-correctness` specialist.")
    appendLine("- Authentication, authorization, untrusted input, secrets, or sensitive data -> `security` specialist.")
    appendLine("- Tests, fixtures, assertions, or regression coverage -> `testing` specialist.")
    appendLine("- Requests, responses, serialization, validation, or compatibility -> `api-contracts` specialist.")
    appendLine("- Transactions, queries, migrations, storage, or consistency -> `persistence` specialist.")
    appendLine("- Timeouts, retries, background work, cleanup, or observability -> `reliability` specialist.")
    appendLine("- Rendering, visual state, interaction, or UI framework changes -> `ui` specialist.")
    appendLine(
      "- Semantics, focus, keyboard input, assistive technology, or task flow -> `ux-accessibility` specialist.",
    )
    appendLine()
    appendLine("## Mixed Diffs")
    appendLine()
    appendLine("Keep the baseline specialists for the whole review and use lightweight file-level classification.")
    appendLine("Exclude generated, vendored, and non-stack-owned files from every specialist's scope.")
    appendLine(
      "Launch selected specialists as subagents in this harness in a deterministic order " +
        "and retain every selected specialist result.",
    )
    appendLine()
    appendLine("## Finding Discipline")
    appendLine()
    appendLine(
      "Keep this section limited to platform-specific finding preconditions; " +
        "generated orchestration owns universal severity and precondition checks, keeps findings attributed through" +
        "merge, " +
        "and deduplicates without losing evidence.",
    )
  }
