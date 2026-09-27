# Operation: feature-guard-cleanup (proposal)

Plan the removal of the feature flag named under Operator instructions. This step is read-only: do not edit, create, delete, stage, or commit any file. The runtime compares the repository before and after this step and discards the proposal if it changed. Removal runs only after the operator confirms this proposal.

Report, as plain prose the operator can confirm:
1. Flag: the flag and where it is defined.
2. Winning path: the path that stays once the flag is gone.
3. Dependents found: every file referencing the flag, every `*Legacy` file tied to it, tests that only cover the legacy path, the flag definition, and dependencies only the legacy path uses.
4. Removal plan: the edits, in the Remove order below.
5. Stabilization checklist for the operator to confirm. Record what the repository shows for each item, and leave every answer to the operator:
   - [ ] The flag is ON for 100% of users.
   - [ ] No other flag depends on this one.
   - [ ] No A/B test analysis is still pending.
   - [ ] The stabilization period has passed.
6. Open questions: ambiguous ownership, such as Legacy code shared with other flags.

Apply these feature-guard cleanup rules:

## When To Use

- Feature flag has been enabled for 100% of users.
- No rollback has been needed for an agreed stabilization period.
- Product/team has confirmed the feature is permanent.

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

## Checklist

- [ ] Flag is ON for 100% of users.
- [ ] Stabilization period has passed.
- [ ] All flag references removed from code.
- [ ] All Legacy files deleted.
- [ ] All Legacy tests deleted.
- [ ] Flag definition removed from registry.
- [ ] `bill-code-check` passes.
- [ ] No orphaned imports or dependencies.

## When to Ask User

1. Which flag to clean up — if not specified.
2. Stabilization confirmation — "Has this flag been fully rolled out and stable?"
3. Ambiguous ownership — if Legacy code is shared with other flags.

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
