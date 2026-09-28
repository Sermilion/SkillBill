# Operation: feature-guard (proposal)

Plan how to guard the change described under Operator instructions behind a feature flag. This step is read-only: do not edit, create, delete, stage, or commit any file. The runtime compares the repository before and after this step and discards the proposal if it changed. Edits run only after the operator confirms this proposal.

Report, as plain prose the operator can confirm:
1. Flag: the flag name, following project conventions, and any existing flag to reuse instead.
2. Affected files: every file the change touches.
3. Pattern: small, medium, or large (Legacy Pattern), and why that size fits.
4. Single switch point: the one place the flag is checked, as file and symbol.
5. Legacy vs New: what stays untouched as the legacy path and what is new.
6. Flag setup: where the flag is defined, default `false`, type (REMOTE or LOCAL), and its description.
7. Rollback plan: why the application behaves exactly as before when the flag is OFF.
8. Open questions: any When to Ask User item the repository does not settle.

Apply these feature-guard rules:

## Core Principles

North Star Goal: Single feature flag check to switch between old and new execution paths. Minimize flag usage by structuring code cohesively.

Rollback Guarantee: When the feature flag is OFF, the application MUST behave exactly as it did before any changes.

Cohesive New Code: Avoid sprinkling `if (featureEnabled)` checks throughout the codebase. Structure changes so flag decisions happen at the highest practical level.

## Implementation Strategy

### Step 1: Identify Scope

1. What components/files will be affected?
2. Can changes be isolated to a single entry point?
3. What is the minimum number of feature flag checks needed?

### Step 2: Choose Pattern Based on Change Size

- Small (1-2 files): simple conditional at the call site.
- Medium (refactoring a component): new implementation alongside old, single switch point.
- Large (multiple files, architectural): Legacy Pattern — rename to `*Legacy`, create new, single flag check at routing level.

Code examples and anti-patterns are included below.

### Step 3: Feature Flag Setup

1. Naming: Follow project conventions (e.g., `feature-[name]`, `[platform]-[name]`).
2. Default: Always `false` (disabled) for new features.
3. Type: REMOTE for production rollouts, LOCAL for dev/testing.
4. Documentation: Add clear description of what the flag controls.

## Checklist

- [ ] Can I isolate changes to minimize feature flag checks?
- [ ] Is the legacy path completely preserved?
- [ ] When flag is OFF, is behavior 100% identical to before?
- [ ] Are feature flag checks at the highest practical level?
- [ ] Is new code cohesive and self-contained?

## When to Ask User

1. Feature flag name: What should this feature flag be called?
2. Scope clarification: If changes span many files, confirm the Legacy pattern approach.
3. Existing flags: Is there an existing flag that should be reused?

## Patterns

Choose the smallest pattern that preserves rollback safety for the current change size.

## Small Changes (1-2 files, single function)
Use simple conditional at the call site:
```kotlin
if (featureFlagProvider.isEnabled(NewFeature)) {
  newImplementation()
} else {
  existingImplementation()
}
```

## Medium Changes (refactoring a component/class)
Create a new implementation alongside the old:
```kotlin
// Keep original untouched
class PaymentProcessor { ... }

// Create new version
class PaymentProcessorV2 { ... }

// Single switch point (DI, factory, or call site)
val processor = if (featureEnabled) PaymentProcessorV2() else PaymentProcessor()
```

## Large Changes (multiple files, architectural changes)
Use the **Legacy Pattern**:
1. Rename existing component to `*Legacy` (e.g., `CheckoutScreen` → `CheckoutScreenLegacy`)
2. Keep `*Legacy` completely untouched - no modifications whatsoever
3. Create new component with original name (or new name if preferred)
4. Single feature flag check at the navigation/routing level

```kotlin
// Original file: CheckoutScreen.kt
// Rename to: CheckoutScreenLegacy.kt (DO NOT MODIFY CONTENTS)

// New file: CheckoutScreen.kt (or CheckoutScreenV2.kt)
// Contains new implementation

// Router/Navigation (SINGLE CHECK POINT):
if (featureEnabled) {
  navigateTo(CheckoutScreen)
} else {
  navigateTo(CheckoutScreenLegacy)
}
```

## DO: Single Entry Point Switch
```kotlin
// GOOD: One check, two complete paths
@Composable
fun ProfileScreen() {
  val newProfileEnabled = rememberFeatureFlag(NewProfile)
  if (newProfileEnabled) {
    ProfileScreenV2(...)
  } else {
    ProfileScreenLegacy(...)
  }
}
```

## DO: Factory/DI Level Switch
```kotlin
// GOOD: Inject different implementation based on flag
@Provides
fun providePaymentService(
  featureFlags: FeatureFlagProvider,
  legacy: LegacyPaymentService,
  newService: NewPaymentService
): PaymentService {
  return if (featureFlags.isEnabled(NewPayment)) newService else legacy
}
```

## DO: Keep Legacy Untouched
```kotlin
// GOOD: Legacy file is frozen, no changes
// File: UserProfileLegacy.kt
// This file should have NO modifications after renaming
class UserProfileLegacy { /* original code, unchanged */ }
```

## DON'T: Scatter Flag Checks
```kotlin
// BAD: Multiple flag checks throughout the code
fun processOrder() {
  if (featureEnabled) { step1New() } else { step1Old() }
  commonStep2()
  if (featureEnabled) { step3New() } else { step3Old() }
  if (featureEnabled) { step4New() } else { step4Old() }
}

// GOOD: Single check, complete paths
fun processOrder() {
  if (featureEnabled) {
    processOrderNew()
  } else {
    processOrderLegacy()
  }
}
```

## DON'T: Modify Legacy After Creating It
```kotlin
// BAD: Making "small fixes" to legacy
class CheckoutLegacy {
  fun submit() {
    // Original code
    if (newValidation) { ... }  // NO! Don't add this
  }
}
```

## DON'T: Create Hybrid States
```kotlin
// BAD: Mixing old and new behavior
fun render() {
  oldHeader()
  if (featureEnabled) newBody() else oldBody()
  newFooter()  // This breaks rollback!
}
```
