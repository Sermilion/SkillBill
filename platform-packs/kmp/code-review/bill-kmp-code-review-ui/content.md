---
name: bill-kmp-code-review-ui
description: Use when reviewing or building KMP UI surfaces. Today this skill is implemented with Jetpack Compose-specific guidance, but it is the canonical KMP UI review capability so future platform UI guidance can live behind the same slash command. Enforces state hoisting, proper recomposition handling, slot-based APIs, accessibility, theming, string resources, preview annotations, and official UI framework guidelines. Use when user mentions Compose review, UI review, recomposition, state hoisting, or Composable code.
internal-for: skill-bill
---

# KMP UI Review Specialist

Review only UI state, rendering, framework usage, and interaction correctness.

## Focus

- Compose state ownership, recomposition, effects, navigation boundaries, previews, and loading/content/error/empty rendering
- Android and Compose Multiplatform API selection at the source-set boundary

## Ignore

- Accessibility-only findings that belong to the `ux-accessibility` specialist
- Escaping, secrets, and sensitive-data failures that belong to the `security` specialist

## Applicability

Use this specialist for `@Composable` functions, UI state holders, Compose navigation, platform UI interop, previews, and user-visible state transitions.

## Project-Specific Rules

### Governed Compose Correctness Rules

- Read [compose-guidelines.md](compose-guidelines.md); an H2 section is insufficient for the detailed Compose rubric, and this companion is its single source of truth.
- Enforce every governed H2 rubric section against the applicable target: State Hoisting; Composable Function Signature Conventions; Recomposition & Performance; Theming & Design System; String Resources & Localization; Compose Multiplatform; Composable Structure & Decomposition; Side Effects; Navigation Integration; Preview Annotations; Error Handling & Loading States; Proper UI Elements; Modifier Best Practices; and ViewModel Integration.
- Do not duplicate that checklist here; require violations to identify the named rubric section, concrete failure precondition, and user-visible or runtime consequence.

### Add-On Boundary Failure Checks

- Require common UI state to have one `StateFlow` owner and platform hosts to adapt lifecycle delivery; reject duplicate collectors that race to consume navigation or effects.
- Verify `LaunchedEffect` and `DisposableEffect` cancel and restart with stable keys on Android activities, iOS view controllers, desktop windows, and browser pages; reject stale-key behavior that leaks work or renders incorrect state.
- Require portable destination identity and restoration in common navigation contracts, then adapt `NavHost`, iOS presentation, desktop windows, and browser history at host boundaries; reject a target back-stack failure caused by sharing host-only state.
- Verify `UIKitView`, Android insets, desktop focus, and browser canvas integration owns attachment and disposal per target; reject lifecycle ordering that leaks a host view or sends input to a detached surface.
- Require shared resources to use Compose `Res` accessors and verify target packaging; reject Android `R`, Apple bundle, desktop classpath, or web asset leakage that causes a missing-resource failure.
- Require layouts to use evidence such as `BoxWithConstraints` for available space, input mode, and window class; reject target-name branching that produces incorrect rendering after resize or rotation.
- Verify `rememberSaveable` uses a target-supported saver for state that must survive host recreation; reject serialization failure or state loss across Android recreation, iOS host replacement, or browser reload.
- Require stable `key` values for lazy lists and movable content; reject index keys that attach remembered state to incorrect rows after insertion or reordering.
- Verify `snapshotFlow` and derived state observe only the intended reads; reject feedback loops or over-broad observation that causes recomposition races and performance failure.
- Require `AndroidView` and `UIKitView` update callbacks to be idempotent and disposal-aware; reject interop mutation that duplicates listeners, leaks resources, or corrupts native view state.

- Require generated `addon_usage` selection from the baseline routing table; reject Android-only guidance applied to `commonMain` or iOS code because it creates an invalid platform contract.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
