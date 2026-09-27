package skillbill.engine.operation.featureguardcleanup

/**
 * The feature-guard cleanup rules, copied verbatim from `skills/bill-feature-guard-cleanup/content.md`.
 * `ChecklistOperationRulesParityTest` holds the copies equal to the skill while both exist. The skill's
 * "Run `bill-code-check`" step is the validation run the operation performs after the apply step.
 */
internal object FeatureGuardCleanupPromptRules {
  const val PROPOSAL_STEP: String = "operation.feature-guard-cleanup.proposal"
  const val APPLY_STEP: String = "operation.feature-guard-cleanup.apply"

  val WHEN_TO_USE: String =
    """
    ## When To Use

    - Feature flag has been enabled for 100% of users.
    - No rollback has been needed for an agreed stabilization period.
    - Product/team has confirmed the feature is permanent.
    """.trimIndent()

  /** Steps 1-3 of the skill's Cleanup Workflow: identify scope, verify safety, and the ordered removal. */
  val CLEANUP_STEPS: String =
    """
    ### Step 1: Identify Scope

    Before cleanup, gather:

    1. Feature flag name to remove.
    2. All files referencing this flag (search codebase).
    3. All `*Legacy` classes/files associated with this flag.
    4. Tests that cover the legacy path.

    ### Step 2: Verify Safety

    Before deleting anything:

    - Confirm flag is ON for all users (check flag service/config).
    - Confirm no other flags depend on this one.
    - Confirm no A/B test analysis is still pending.

    ### Step 3: Remove (in this order)

    1. Remove flag checks — replace `if (featureEnabled) { new } else { legacy }` with just the new path.
    2. Inline the winning path — if a wrapper exists only for the flag check, remove the wrapper.
    3. Delete Legacy files — remove all `*Legacy` classes and their imports.
    4. Delete Legacy tests — remove tests that only cover the legacy path.
    5. Remove flag definition — delete the flag from the feature flag registry/enum/config.
    6. Remove unused dependencies — if legacy code pulled in dependencies the new code doesn't need.
    """.trimIndent()

  val CHECKLIST: String =
    """
    ## Checklist

    - [ ] Flag is ON for 100% of users.
    - [ ] Stabilization period has passed.
    - [ ] All flag references removed from code.
    - [ ] All Legacy files deleted.
    - [ ] All Legacy tests deleted.
    - [ ] Flag definition removed from registry.
    - [ ] `bill-code-check` passes.
    - [ ] No orphaned imports or dependencies.
    """.trimIndent()

  val WHEN_TO_ASK_USER: String =
    """
    ## When to Ask User

    1. Which flag to clean up — if not specified.
    2. Stabilization confirmation — "Has this flag been fully rolled out and stable?"
    3. Ambiguous ownership — if Legacy code is shared with other flags.
    """.trimIndent()

  val CLEANUP_PATTERNS: String =
    """
    ## Cleanup Patterns

    Use the cleanup pattern that matches the feature-guard pattern originally used.

    ## Simple conditional cleanup
    ```kotlin
    // Before:
    val result = if (featureFlags.isEnabled(NewCheckout)) {
        newCheckoutFlow()
    } else {
        legacyCheckoutFlow()
    }

    // After:
    val result = newCheckoutFlow()
    ```

    ## DI/Factory cleanup
    ```kotlin
    // Before:
    @Provides
    fun providePaymentService(
        featureFlags: FeatureFlagProvider,
        legacy: LegacyPaymentService,
        newService: NewPaymentService
    ): PaymentService {
        return if (featureFlags.isEnabled(NewPayment)) newService else legacy
    }

    // After:
    @Provides
    fun providePaymentService(
        newService: NewPaymentService
    ): PaymentService = newService
    ```

    ## Navigation/Router cleanup
    ```kotlin
    // Before:
    if (featureEnabled) navigateTo(CheckoutScreen) else navigateTo(CheckoutScreenLegacy)

    // After:
    navigateTo(CheckoutScreen)
    // Delete: CheckoutScreenLegacy.kt
    ```
    """.trimIndent()

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
      "be applied as written, stop and report why. Do not run `bill-code-check`, `./gradlew check`, or any " +
      "other check suite: the runtime runs the validation phase after this step, and that run is the " +
      "checklist's `bill-code-check` item. Finish with a short report of the files changed and deleted.\n\n" +
      "Apply these feature-guard cleanup rules:"

  /** The read-only proposal directive: report the plan and the stabilization checklist, never edit. */
  val proposal: String =
    listOf(PROPOSAL_TASK, WHEN_TO_USE, CLEANUP_STEPS, CHECKLIST, WHEN_TO_ASK_USER, CLEANUP_PATTERNS)
      .joinToString("\n\n")

  /** The apply directive: execute exactly the stored proposal. */
  val apply: String = listOf(APPLY_TASK, CLEANUP_STEPS, CHECKLIST, CLEANUP_PATTERNS).joinToString("\n\n")
}
