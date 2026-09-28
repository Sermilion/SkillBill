---
name: bill-kmp-code-review-ux-accessibility
description: Use when reviewing UX correctness and accessibility risks, delegating UI-framework-heavy checks to bill-kmp-code-review-ui. Use when user mentions UX review, accessibility, content description, screen reader, or localization review.
internal-for: skill-bill
---

# KMP UX & Accessibility Review Specialist

Review only user-impacting UX and accessibility failures.

## Focus

- Compose semantics, accessible names and roles, announcements, traversal, touch targets, font scaling, and localization-sensitive UX

## Ignore

- UI framework, rendering, preview, navigation, or loading/content/error/empty-state correctness owned by the `ui` specialist
- Escaping, secrets, or sensitive-data failures owned by the `security` specialist

## Applicability

Use this specialist when changed UI affects assistive technology, keyboard or focus behavior, touch interaction, localization, or task completion.

## Project-Specific Rules

### Compose Semantics Failure Checks

- Require `Modifier.semantics` where native semantics are insufficient; verify `mergeDescendants` preserves a coherent control and reject merges that hide child actions and cause task-completion failure.
- Require `clearAndSetSemantics` only when replacing descendant meaning is intentional; reject it when it erases a name, state, action, or error and leaves an invalid accessibility tree.
- Require decorative images to use `contentDescription = null`; reject meaningful `Image` controls without localized names because assistive-technology users lose the action contract.
- Require dynamic controls to expose accurate `stateDescription` and `Role`; reject incorrect `liveRegion` use that drops urgent state or creates duplicate announcements.
- Require structural titles to expose `heading()` and validation failures to expose `error()`; reject a semantics tree that hides invalid data from assistive technology.

### Traversal, Target, and Scaling Failure Checks

- Verify `traversalIndex` and `isTraversalGroup` produce stable logical order; reject focus ordering that diverges from task flow and sends input to the incorrect control.
- Require `minimumInteractiveComponentSize` or an equivalent 48dp target; reject clipped or overlapping controls that cause touch-operation failure.
- Verify `fontScale` changes preserve actionable text and controls; reject truncation or hidden actions that cause task-completion failure.

### Localization Boundary Failure Checks

- On Android targets, require user-facing text to use `stringResource(R.string.account_title)` and preserve `strings.xml` translations; reject hard-coded content that creates incorrect localized output.
- In common Compose Multiplatform sources, require `Res.string` with `org.jetbrains.compose.resources.stringResource`; reject Android resource APIs in `commonMain` because they break target compilation.
- Verify screen-reader names, roles, states, actions, and announcements through TalkBack on Android, VoiceOver on iOS, desktop accessibility bridges, and the browser accessibility tree; do not assume one target's semantics mapping represents the others.
- Require logical `FocusRequester` restoration and traversal for keyboard, switch, remote, and assistive input; reject lifecycle transitions that lose focus or restore it to an invalid target.
- Verify pointer hover, mouse and trackpad actions, touch gestures, keyboard shortcuts, and platform back or escape conventions have equivalent discoverable outcomes without forcing touch-only interaction.
- Require progress, validation, loading, success, and failure feedback to use semantics such as `liveRegion` without color alone; reject missing or duplicate cross-target announcements.
- Verify localization with plural rules, bidirectional text, long translations, large text, locale-specific formatting, and text expansion without clipped actions or reordered meaning.
- Require `MotionDurationScale` and host contrast preferences to preserve an equivalent state transition; reject animation-only feedback that causes reduced-motion users to miss a state change.
- Require adaptive layouts using `WindowSizeClass` or equivalent evidence to preserve reading order, focus order, target size, and task completion; reject resize behavior that produces incorrect traversal.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
