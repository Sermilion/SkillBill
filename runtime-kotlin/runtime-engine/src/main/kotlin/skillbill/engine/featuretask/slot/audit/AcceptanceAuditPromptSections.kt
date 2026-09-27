package skillbill.engine.featuretask.slot.audit

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyScalingOf

internal object AcceptanceAuditPromptSections {
  const val AUDIT_READONLY_EVIDENCE_SENTENCE: String =
    "All evidence is read-only repository facts: never run a build, a test, or any other command as audit " +
      "evidence; validation owns test execution and failures."

  const val DIRECTIVE: String =
    "Verify every acceptance criterion in the briefing against the current repository: locate the implementation " +
      "that provides the required behavior and test cases whose assertions verify that behavior. Missing " +
      "implementation, missing tests, and tests that do not exercise the criterion are gaps. One meaningful test " +
      "may cover several criteria; a test name, empty test, mock-only interaction, or tautological assertion is " +
      "not coverage. Read the tree at the resolved checkpoint — the diff over its base_ref/head_ref plus its " +
      "scoped_owned_paths. The upstream implement value is structured prose (implementation_receipt JSON stuffed " +
      "inside value): read and interpret it as a producer CLAIM, not evidence. Never mark a criterion satisfied " +
      "because that string lists a completed task id, a changed path, or reconciliation_evidence claiming " +
      "reconciled. Repair every fixable gap in this same agent session, then re-check the entire in-scope " +
      "criterion list from the beginning before you complete. Do not spawn subagents, invoke repair skills, or " +
      "hand findings to another phase. End your final response with the remaining acceptance criteria only: emit " +
      "an explicit empty list `[]` when every criterion has implementation and meaningful test coverage after " +
      "repairs; otherwise emit the remaining criteria as plain text, bullets, or a numbered list without " +
      "validating their structure. Use status blocked or failed with a concrete failure_disposition when the " +
      "planning criterion list is missing or unreadable or an external dependency prevents repair. " +
      AUDIT_READONLY_EVIDENCE_SENTENCE

  private const val VALUE_CONTENT: String =
    "value carries the remaining acceptance criteria only. Only an explicit empty list `[]` (ordinary whitespace\n" +
      "or Markdown fencing allowed when the complete value is exactly that empty list) completes audit; any other\n" +
      "non-blank text starts one fresh audit retry with that text forwarded verbatim as the only criterion scope\n" +
      "for the next audit retry. A whitespace-only value, or a remaining-criteria list nested only inside summary,\n" +
      "is rejected.\n" +
      "Every audit invocation re-checks the complete in-scope criterion set from scratch against the\n" +
      "current tree. Prior partial checks, provider sessions, and repair receipts do not skip checks.\n" +
      "Repair fixable gaps in this same session before you settle. Use blocked or failed with a\n" +
      "failure_disposition when the criterion list is missing or an external dependency prevents repair.\n" +
      "Inspect code and test coverage only: do not run builds, tests, or other commands as audit evidence.\n" +
      "Validation owns test execution and failures.\n" +
      "verdict: omit it unless every criterion is met, then set satisfied; never invent review-style tokens\n" +
      "(for example remediation_required or changes_requested)."

  fun sections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): PhaseStepPromptSections {
    val focusHint = inputs.auditRetryFocusHint?.takeIf(String::isNotBlank)
    return PhaseStepPromptSections(
      taskDirective = DIRECTIVE,
      ceremonyLine =
        "Apply ${ceremonyScalingOf(inputs.briefing).auditCeremony.promptLabel}. Keep the audit gate real: verify " +
          "every acceptance criterion in scope for implementation and meaningful test coverage, repair fixable " +
          "gaps in this same session, and re-check the in-scope list before completion.",
      valueContent = VALUE_CONTENT,
      retryFocus = auditRetryFocusDirective(focusHint),
      briefingRewrite =
        focusHint?.let { hint -> { briefing: FeatureTaskRuntimePhaseLaunchBriefing -> briefing.forAuditRetry(hint) } },
    )
  }
}

private fun auditRetryFocusDirective(focusHint: String?): String {
  if (focusHint.isNullOrBlank()) return ""
  return """
    ## Prior audit focus hint (remaining criteria only)
    Only the unresolved acceptance criteria below are in scope for this audit retry. Do not inspect,
    re-verify, or modify criteria that were already resolved in the preceding audit session. Repair
    these unresolved criteria in this same session and emit only the criteria that remain unresolved.
    $focusHint
    """.trimIndent()
}

private fun FeatureTaskRuntimePhaseLaunchBriefing.forAuditRetry(
  focusHint: String,
): FeatureTaskRuntimePhaseLaunchBriefing {
  require(focusHint.isNotBlank()) { "Audit retry focus hint must be non-blank." }
  return copy(
    acceptanceCriteria = focusHint.lines().filter(String::isNotBlank),
    briefingText = briefingText.replaceAuditAcceptanceCriteria(focusHint),
  )
}

private fun String.replaceAuditAcceptanceCriteria(focusHint: String): String {
  val start = indexOf("acceptance_criteria:\n")
  require(start >= 0) { "Audit retry briefing is missing its acceptance_criteria section." }
  val contentStart = start + "acceptance_criteria:\n".length
  val end = indexOf("\nmandates_and_overrides:", contentStart)
  require(end >= 0) { "Audit retry briefing acceptance_criteria section has no closing boundary." }
  val replacement =
    focusHint.lines()
      .filter(String::isNotBlank)
      .joinToString("\n") { "  ${it.trim()}" }
  return replaceRange(contentStart, end + 1, "$replacement\n")
}
