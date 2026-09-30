package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyScalingOf
import skillbill.engine.featuretask.phase.prompt.directives.projectAuthoringDisciplineDirective

internal object InlineReviewPromptSections {
  const val REVIEW_DIRECTIVE: String =
    "Review the last commit against its first parent in this repository. Fix every Blocker and Major " +
      "finding in this same session before you emit. Emit remaining findings and a verdict of approved or " +
      "changes_requested. Do not run `skill-bill phase review` or launch review subagents. Criterion-gap " +
      "detection remains exclusive to the audit phase. Do not run `./gradlew check`, the pack collect-all " +
      "gate, or `skill-bill phase validation`; validate owns those."

  const val VERIFY_FINDINGS_DIRECTIVE: String =
    "Verify every finding from the single preceding review pass against the subtask spec intent " +
      "projection and the scoped boundary-memory catalog in the briefing. Each finding receives a " +
      "titles-only heading catalog for boundaries that own its paths; name the relevant heading_id " +
      "values verbatim when a boundary entry informs your judgement. Do not edit the worktree."

  const val IMPLEMENT_FIX_DIRECTIVE: String =
    "Address every finding verify_findings carried on the CURRENT working tree as " +
      "incremental reconciliation. Every carried finding — Blocker, Major, Minor, and Nit — is in " +
      "scope; specialist narratives and raw review output are not, and a finding verification " +
      "refuted is not carried at all: do not fix it and do not report on it. Do not re-apply " +
      "the plan from scratch or expand scope beyond the carried findings. Treat any fix already present " +
      "as a no-op. See the mutating-phase idempotency contract below."

  private const val REVIEW_VALUE_CONTENT: String =
    "State each remaining finding with its severity (blocker, major, minor, or nit) and a file reference, " +
      "and say whether the review approved or requested changes. A Blocker or Major finding means changes " +
      "were requested."

  private const val VERIFY_FINDINGS_VALUE_CONTENT: String =
    "Name every review finding by its finding id and say for each whether verification verified it or " +
      "rejected it. Reject a finding only when you can cite the file:line construct that shows it is not a " +
      "defect; a finding you do not address, or cannot settle, stays verified and is carried into repair."

  private const val IMPLEMENT_FIX_VALUE_CONTENT: String =
    "Name every carried finding by its finding id and say whether you fixed it, found that no edit was " +
      "required (with the reason), or attempted it and it is still open (with the reason and the " +
      "constructs you touched). A finding left out of the report stays owed, and a finding still open " +
      "after a second attempt goes to an operator. Build owns compile and build proof, and validate owns " +
      "tests and full checks: do not build, compile, run tests, or invoke `./gradlew check` here."

  fun review(
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
    directive: String,
  ): PhaseStepPromptSections {
    val scaling = ceremonyScalingOf(inputs.briefing)
    return PhaseStepPromptSections(
      taskDirective = directive,
      ceremonyLine =
        "The runtime owns ${scaling.reviewScope.promptLabel}. Keep the review gate real: inspect the implemented " +
          "change for defects and record concrete file references.",
      stepContext = reviewExecutionDirective(inputs),
      valueContent = REVIEW_VALUE_CONTENT,
    )
  }

  fun verifyFindings(): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = VERIFY_FINDINGS_DIRECTIVE,
      valueContent = VERIFY_FINDINGS_VALUE_CONTENT,
    )

  fun implementFix(): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = IMPLEMENT_FIX_DIRECTIVE,
      authoringDiscipline = projectAuthoringDisciplineDirective(),
      testValueDiscipline = true,
      valueContent = IMPLEMENT_FIX_VALUE_CONTENT,
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
}
