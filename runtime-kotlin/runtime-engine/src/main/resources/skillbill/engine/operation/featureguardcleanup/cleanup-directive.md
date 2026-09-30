# Feature Guard Cleanup Content

## When To Use

- Feature flag has been enabled for 100% of users.
- No rollback has been needed for an agreed stabilization period.
- Product/team has confirmed the feature is permanent.

## Cleanup Workflow

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

### Step 4: Verify

Run `skill-bill phase validation` to ensure nothing is broken.

## Patterns

Code examples for each cleanup pattern (conditional, DI/factory, navigation/router) are included below.

## Checklist

- [ ] Flag is ON for 100% of users.
- [ ] Stabilization period has passed.
- [ ] All flag references removed from code.
- [ ] All Legacy files deleted.
- [ ] All Legacy tests deleted.
- [ ] Flag definition removed from registry.
- [ ] `skill-bill phase validation` passes.
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
