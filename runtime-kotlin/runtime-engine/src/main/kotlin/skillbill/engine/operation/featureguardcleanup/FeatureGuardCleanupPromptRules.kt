package skillbill.engine.operation.featureguardcleanup

import skillbill.engine.directive.directiveResource

internal object FeatureGuardCleanupPromptRules {
  const val PROPOSAL_STEP: String = "operation.feature-guard-cleanup.proposal"
  const val APPLY_STEP: String = "operation.feature-guard-cleanup.apply"

  private const val CLEANUP_DIRECTIVE_RESOURCE: String =
    "/skillbill/engine/operation/featureguardcleanup/cleanup-directive.md"

  private const val PROPOSAL_TASK: String =
    "# Operation: feature-guard-cleanup (proposal)\n\n" +
      "Plan the removal of the feature flag named under Operator instructions. This step is read-only: do not " +
      "edit, create, delete, stage, or commit any file. The runtime compares the repository before and after " +
      "this step and discards the proposal if it changed. Removal runs only after the operator confirms this " +
      "proposal.\n\n" +
      "Report, as plain prose the operator can confirm:\n" +
      "1. Flag: the flag and where it is defined.\n" +
      "2. Winning path: the path that stays once the flag is gone.\n" +
      "3. Dependents found: every file referencing the flag, every `*Legacy` file tied to it, tests that only " +
      "cover the legacy path, the flag definition, and dependencies only the legacy path uses.\n" +
      "4. Removal plan: the edits, in the Remove order below.\n" +
      "5. Stabilization checklist for the operator to confirm. Record what the repository shows for each item, " +
      "and leave every answer to the operator:\n" +
      "   - [ ] The flag is ON for 100% of users.\n" +
      "   - [ ] No other flag depends on this one.\n" +
      "   - [ ] No A/B test analysis is still pending.\n" +
      "   - [ ] The stabilization period has passed.\n" +
      "6. Open questions: ambiguous ownership, such as Legacy code shared with other flags.\n\n" +
      "Apply these feature-guard cleanup rules:"

  private const val APPLY_TASK: String =
    "# Operation: feature-guard-cleanup (apply)\n\n" +
      "The operator confirmed the proposal under `Prior step value: $PROPOSAL_STEP`, including its " +
      "stabilization checklist. Remove the flag exactly as that proposal describes, in the Remove order below: " +
      "flag checks, inline the winning path, delete Legacy files, delete Legacy tests, remove the flag " +
      "definition, remove unused dependencies. Do not touch code the proposal does not name; if the plan cannot " +
      "be applied as written, stop and report why. Do not run `skill-bill phase validation`, `./gradlew check`, " +
      "or any other check suite: the runtime runs the validation phase after this step, and that run is the " +
      "checklist's `skill-bill phase validation` item and Step 4. Finish with a short report of the files " +
      "changed and deleted.\n\n" +
      "Apply these feature-guard cleanup rules:"

  val proposal: String by lazy { listOf(PROPOSAL_TASK, cleanupDirective()).joinToString("\n\n") }

  val apply: String by lazy { listOf(APPLY_TASK, cleanupDirective()).joinToString("\n\n") }

  private fun cleanupDirective(): String = directiveResource(CLEANUP_DIRECTIVE_RESOURCE).trimEnd()
}
