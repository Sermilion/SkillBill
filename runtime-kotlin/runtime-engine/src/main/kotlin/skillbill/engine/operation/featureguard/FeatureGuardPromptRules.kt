package skillbill.engine.operation.featureguard

import skillbill.engine.directive.directiveResource

internal object FeatureGuardPromptRules {
  const val PROPOSAL_STEP: String = "operation.feature-guard.proposal"
  const val APPLY_STEP: String = "operation.feature-guard.apply"

  private const val GUARD_DIRECTIVE_RESOURCE: String = "/skillbill/engine/operation/featureguard/guard-directive.md"

  private const val PROPOSAL_TASK: String =
    "# Operation: feature-guard (proposal)\n\n" +
      "Plan how to guard the change described under Operator instructions behind a feature flag. This step is " +
      "read-only: do not edit, create, delete, stage, or commit any file. The runtime compares the repository " +
      "before and after this step and discards the proposal if it changed. Edits run only after the operator " +
      "confirms this proposal.\n\n" +
      "Report, as plain prose the operator can confirm:\n" +
      "1. Flag: the flag name, following project conventions, and any existing flag to reuse instead.\n" +
      "2. Affected files: every file the change touches.\n" +
      "3. Pattern: small, medium, or large (Legacy Pattern), and why that size fits.\n" +
      "4. Single switch point: the one place the flag is checked, as file and symbol.\n" +
      "5. Legacy vs New: what stays untouched as the legacy path and what is new.\n" +
      "6. Flag setup: where the flag is defined, default `false`, type (REMOTE or LOCAL), and its description.\n" +
      "7. Rollback plan: why the application behaves exactly as before when the flag is OFF.\n" +
      "8. Open questions: any When to Ask User item the repository does not settle.\n\n" +
      "Apply these feature-guard rules:"

  private const val APPLY_TASK: String =
    "# Operation: feature-guard (apply)\n\n" +
      "The operator confirmed the proposal under `Prior step value: $PROPOSAL_STEP`. Implement exactly that " +
      "proposal: its flag, files, pattern, single switch point, Legacy vs New split, flag setup, and rollback " +
      "plan. Do not change the plan; if it cannot be implemented as written, stop and report why instead of " +
      "improvising. Do not run `./gradlew check`, `skill-bill phase validation`, or any other check suite. Finish " +
      "with a short report of the files changed, where the single switch point lives, and why the flag-OFF path " +
      "is unchanged.\n\n" +
      "Apply these feature-guard rules:"

  val proposal: String by lazy { listOf(PROPOSAL_TASK, guardDirective()).joinToString("\n\n") }

  val apply: String by lazy { listOf(APPLY_TASK, guardDirective()).joinToString("\n\n") }

  private fun guardDirective(): String = directiveResource(GUARD_DIRECTIVE_RESOURCE).trimEnd()
}
