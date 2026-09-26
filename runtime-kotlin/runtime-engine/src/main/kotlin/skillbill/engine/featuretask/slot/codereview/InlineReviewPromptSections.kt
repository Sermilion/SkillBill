package skillbill.engine.featuretask.slot.codereview

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.PhaseRetryShape
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyScalingOf
import skillbill.engine.featuretask.phase.prompt.directives.envelopeContract
import skillbill.goalrunner.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.review.model.ReviewIssueCategory
import skillbill.workflow.model.goalreview.GoalSubtaskCommitFocusedAccounting

internal object InlineReviewPromptSections {
  const val REVIEW_DIRECTIVE: String =
    "Review the last commit against its first parent in this repository. Fix every Blocker and Major " +
      "finding in this same session before you emit. Emit remaining findings and a verdict of approved or " +
      "changes_requested. Do not run bill-code-review or launch review subagents. Criterion-gap detection " +
      "remains exclusive to the audit phase. Do not run `./gradlew check`, the pack collect-all gate, or " +
      "`bill-code-check`; validate owns those."

  val VERIFY_FINDINGS_DIRECTIVE: String =
    "Verify every finding from the single preceding review pass against the subtask spec intent " +
      "projection and the scoped boundary-memory catalog in the briefing. Each finding receives a " +
      "titles-only heading catalog for boundaries that own its paths; select relevant heading_id " +
      "values in selected_boundary_headings and set boundary_context_unavailable when no eligible " +
      "boundary owns the finding paths. Emit envelope verdict findings_verified or " +
      "no_findings_verified and " +
      "produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS} " +
      "with exactly one {finding_id, disposition} entry per review finding (verified or rejected). " +
      "Optional decoration — reason, severity, location, message, selected_boundary_headings, and " +
      "boundary_context_unavailable — may support the disposition but does not gate settlement. Do " +
      "not edit the worktree."

  const val IMPLEMENT_FIX_DIRECTIVE: String =
    "Address every finding verify_findings carried on the CURRENT working tree as " +
      "incremental reconciliation. Every carried finding — Blocker, Major, Minor, and Nit — is in " +
      "scope; specialist narratives and raw review output are not, and a finding verification " +
      "refuted is not carried at all: do not fix it and do not file an entry for it. Do not re-apply " +
      "the plan from scratch or expand scope beyond the carried findings. Treat any fix already present " +
      "as a no-op. See the mutating-phase idempotency contract below. Emit " +
      "produced_outputs.repair_receipt with contract_version " +
      "\"$FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION\" and exactly one entry per carried " +
      "finding with finding_id (aliases finding_ref, id, and ref are accepted) and outcome " +
      "(addressed, no_edit_required, or attempted_unresolved). Coverage matches on finding_id and " +
      "outcome alone. Optional decoration — constructs, intent, severity, label, text, " +
      "no_edit_reason, and unresolved_reason — may accompany each entry but does not gate settlement. " +
      "A legitimately unedited finding still needs its no_edit_required entry, and a finding you " +
      "could not close needs its attempted_unresolved entry, which buys it one more attempt before it " +
      "goes to an operator. Leaving a *carried* finding out is never an outcome: the round is sent " +
      "back for it. A refuted finding is the one exception, because it was never carried."

  private const val VERIFYING_VERDICT_LINE: String =
    "\n    - \"verdict\": optional top-level string; this verifying phase sets it to drive the " +
      "advance-vs-remediation decision — see the verifying-phase signal above"

  fun review(
    stepName: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    val scaling = ceremonyScalingOf(inputs.briefing)
    return PhaseStepPromptSections(
      taskDirective = REVIEW_DIRECTIVE,
      ceremonyLine =
        "The runtime owns ${scaling.reviewScope.promptLabel}. Keep the review gate real: inspect the implemented " +
          "change for defects and record concrete file references.",
      stepContext = reviewExecutionDirective(inputs),
      retryShape =
        PhaseRetryShape(
          verdictLine = "  \"${FeatureTaskRuntimeVerificationSignalKeys.VERDICT}\": \"approved\",",
          producedOutputsEntry =
            "\"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS}\": [], " +
              "\"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID}\": \"<the Review run ID this pass " +
              "reported>\"",
        ),
      outputContract = envelopeContract(stepName, reviewProducedOutputsAddendum(), VERIFYING_VERDICT_LINE),
    )
  }

  fun verifyFindings(stepName: String): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = VERIFY_FINDINGS_DIRECTIVE,
      retryShape =
        PhaseRetryShape(
          verdictLine = "  \"${FeatureTaskRuntimeVerificationSignalKeys.VERDICT}\": \"findings_verified\",",
          producedOutputsEntry =
            "\"${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS}\": [ " +
              "{ \"finding_id\": \"F-001\", \"disposition\": \"verified\" } ]",
        ),
      outputContract = envelopeContract(stepName, verifyFindingsProducedOutputsAddendum(), VERIFYING_VERDICT_LINE),
    )

  fun implementFix(stepName: String): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = IMPLEMENT_FIX_DIRECTIVE,
      testValueDiscipline = true,
      outputContract = envelopeContract(stepName, RECONCILIATION_REQUIREMENT + IMPLEMENT_FIX_SHAPE, ""),
    )

  private fun reviewExecutionDirective(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String =
    buildString {
      append(resolvedTierInfo(inputs))
      append(baselineUntrackedPolicy(inputs.baselineUntrackedPaths))
      append(materializedScope(inputs))
    }.trim()

  private fun baselineUntrackedPolicy(baselineUntrackedPaths: List<String>): String =
    baselineUntrackedPaths
      .distinct()
      .sorted()
      .takeIf { it.isNotEmpty() }
      ?.let { paths ->
        """
        ## Baseline-untracked review policy
        These paths existed before this run and are excluded from the last-commit review packet:
        ${paths.joinToString("\n") { path -> "- `$path`" }}
        The runtime-owned review driver must not re-add these paths through a replacement diff.
        """.trimIndent()
      }
      .orEmpty()

  private fun materializedScope(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String =
    inputs.goalSubtaskReviewInput?.let { input ->
      """
      ## Last-commit review scope
      Review only the last commit `${input.currentHeadSha}` against its first parent.
      Do not use `origin/main...HEAD`, a merge base, the full feature branch, the durable implement base,
      or the current worktree. Standalone `skill-bill code-review` still reviews the caller target
      (pr, commit SHA or last, or uncommitted changes). The phase driver resolves last-commit itself; it does
      not receive a pre-baked diff blob.
      """.trimIndent()
    }.orEmpty()

  private fun resolvedTierInfo(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String {
    val tier = inputs.resolvedReviewTier
    val rule = inputs.reviewDecidingRule
    return if (tier != null && rule != null) {
      """
      ## Resolved review mode
      AUTO resolved to ${tier.wireValue} by rule "$rule".
      An explicit INLINE always overrides AUTO.
      """.trimIndent()
    } else {
      ""
    }
  }

  private fun reviewProducedOutputsAddendum(): String {
    val findings = FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS
    val verdict = FeatureTaskRuntimeVerificationSignalKeys.VERDICT
    return "\n    - This is a VERIFYING phase: produced_outputs MUST carry a \"$findings\" array (each entry a\n" +
      "      severity/message object; an explicit empty [] affirms no Blocker or Major findings) AND/OR a\n" +
      "      top-level \"$verdict\" of \"approved\" or \"changes_requested\". A Blocker or Major finding sets\n" +
      "      \"changes_requested\" so it is fixed in this same review pass; Minor and Nit do not. Output\n" +
      "      carrying NEITHER signal fails the schema gate loudly — a prose summary alone cannot advance.\n" +
      "      Each finding's \"severity\" MUST be exactly one of blocker, major, minor, nit, and its\n" +
      "      \"issue_category\" MUST be exactly one of " +
      ReviewIssueCategory.entries.joinToString { it.wireValue } + "; any other category value is\n" +
      "      recorded as other.\n" +
      "    - produced_outputs MUST also carry \"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID}\": the " +
      "Review run ID your\n" +
      "      `bill-code-review` invocation reported for this pass, verbatim. It is the key that joins each\n" +
      "      finding here to the imported review run, so a finding's \"id\" plus this run id must be the same\n" +
      "      pair that review recorded. Omit it ONLY if the review genuinely reported no run id; never\n" +
      "      invent, reuse an older, or guess one." + commitFocusedAccountingAddendum()
  }

  private fun verifyFindingsProducedOutputsAddendum(): String =
    "\n    - This is a VERIFYING phase: emit top-level \"${FeatureTaskRuntimeVerificationSignalKeys.VERDICT}\" as " +
      "\"findings_verified\" or \"no_findings_verified\" and exactly one " +
      "produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS} " +
      "entry per review finding with required census fields finding_id and disposition (verified " +
      "or rejected). Recommended optional fields per entry: reason, severity, location, message, " +
      "selected_boundary_headings (heading_id and source_path), and boundary_context_unavailable " +
      "when no eligible boundary owns the finding paths. When you cite boundary memory, copy " +
      "heading_id and source_path verbatim from that finding's boundary_catalog only — never " +
      "invent hashes or reuse another finding's catalog. Concurrent worktree dirt outside the " +
      "review scope is ignored; settle dispositions for the reviewed findings only.\n" +
      "      Required example: {\"finding_id\":\"F-001\",\"disposition\":\"verified\"}."

  private fun commitFocusedAccountingAddendum(): String =
    "\n    - If this pass ran a DELEGATED review over a real commit sequence, produced_outputs MUST also\n" +
      "      carry \"commit_focused_accounting\" exactly as the review reported it: commit_sequence_digest\n" +
      "      (64-char lowercase hex), commit_count, lane_count, focused_commit_count,\n" +
      "      skipped_commit_count (focused + skipped == commit_count), and integration_terminal_outcome,\n" +
      "      one of " +
      GoalSubtaskCommitFocusedAccounting.INTEGRATION_TERMINAL_OUTCOMES.sorted()
        .joinToString() + ".\n" +
      "      Optional when the review reported them: routing_digest, focused_pair_count,\n" +
      "      skipped_pair_count, lane_bundle_sizes, lane_segment_counts, incomplete_lanes,\n" +
      "      parent_analysis_pairs, parent_analysis_bytes, integration_finding_count, and\n" +
      "      integration_skip_reason (REQUIRED when integration_terminal_outcome is\n" +
      "      ${GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE}). Lanes that ended incomplete\n" +
      "      are named in incomplete_lanes; that is non-clean coverage and the integration pass never\n" +
      "      compensates for it. Identities, counts, and lane names ONLY — never a commit subject, a\n" +
      "      path, or diff text. An INLINE or non-commit-sequence pass OMITS the key entirely rather\n" +
      "      than fabricating a sequence identity; never invent or guess a digest or a count."

  private const val RECONCILIATION_REQUIREMENT: String =
    "\n    - produced_outputs MUST include a reconciliation report: a \"reconciled_state\" object\n" +
      "      (or a \"reconciled_state\" entry) with \"reconciled\": true and concrete evidence that the\n" +
      "      changed files are at their intended target state. A status of \"completed\" with the\n" +
      "      reconciliation report missing or \"reconciled\" not true fails the schema gate loudly."

  private const val IMPLEMENT_FIX_SHAPE: String =
    "\n    - Required produced_outputs.repair_receipt shape: contract_version " +
      "\"$FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION\" and one entry per carried finding " +
      "with finding_id (aliases finding_ref, id, ref accepted) and outcome (addressed, " +
      "no_edit_required, or attempted_unresolved). Coverage matches on finding_id and outcome alone.\n" +
      "      Recommended optional fields per entry: constructs, intent, severity, label, text, " +
      "no_edit_reason, and unresolved_reason. The round number and pre-fix checkpoint sha are " +
      "runtime-owned: omit them, never guess them from a briefing hash:\n" +
      "      ```json\n" +
      "      { \"repair_receipt\": {\n" +
      "          \"contract_version\": \"$FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION\",\n" +
      "          \"entries\": [ { \"finding_id\": \"F-001\", \"outcome\": \"addressed\" } ] } }\n" +
      "      ```\n" +
      "      Compilation and test execution belong exclusively to the validate phase. Do NOT build,\n" +
      "      compile, run tests, or invoke `./gradlew check` / the pack collect-all gate here."
}
