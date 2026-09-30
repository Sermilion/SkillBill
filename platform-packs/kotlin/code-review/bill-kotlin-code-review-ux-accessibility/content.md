---
name: bill-kotlin-code-review-ux-accessibility
description: Review standalone Kotlin desktop, server-rendered, CLI, and TUI usability and accessibility behavior.
internal-for: skill-bill
---

# UX and Accessibility Review Specialist

## Focus

- Semantics, focus, keyboard access, semantic HTML, screen readers, color, resize, localization, and task recovery

## Ignore

- Android and Compose Multiplatform source-set or target-specific accessibility, which belongs to KMP; standalone Compose Desktop remains Kotlin-owned. UI rendering and security findings belong to their specialist lanes; ignore subjective polish without task or access failure

## Applicability

Run only when a Kotlin diff exposes Compose Desktop, Swing, JavaFX, server-rendered HTML, CLI, or TUI interaction.

## Project-Specific Rules

### Accessibility Review Rules

- Require meaningful Compose Desktop controls to expose accurate `Modifier.semantics` roles and labels, then verify assistive-technology exposure against the repository's Compose version and supported desktop OS; semantics alone do not prove the platform accessibility bridge is active.
- Treat Compose Desktop accessibility activation as version- and platform-dependent: require repository evidence for any runtime flag, JVM property, or automatic activation path rather than prescribing one switch across releases.
- Prefer semantic controls or `Modifier.clickable`, `toggleable`, or `selectable`, which already provide focus and keyboard activation; require manual `focusable` and key handling only for low-level gesture implementations, where mouse-only behavior blocks task completion and duplicate handlers can trigger an action twice.
- Verify focus movement after dialogs, errors, and dynamic content through `FocusRequester`; lost focus can trap keyboard users in invalid state.
- Require state changes such as progress and validation to expose `stateDescription` or equivalent semantics; silent updates can cause accessibility failure.
- Verify Swing custom components implement an accurate `AccessibleContext`; absent names, roles, or state can break screen-reader navigation.
- Require Swing labels to associate with controls via `labelFor`; unlabeled fields cause ambiguous form input and validation failure.
- Verify JavaFX controls preserve focus traversal and accessible text when replacing skins or cells; custom rendering can remove keyboard access.
- Reject server templates that use clickable `div` elements instead of semantic `button`, `a`, `label`, and heading elements; incorrect HTML breaks keyboard and screen-reader contracts.
- Require server validation errors to be linked with `aria-describedby` and summarized near focus; unassociated errors can prevent form recovery.
- Verify CLI prompts have noninteractive flags and deterministic errors; prompt-only commands can fail automation and screen-reader workflows.
- Reject TUI meaning conveyed only through ANSI color; require text, symbols, or `NO_COLOR` behavior so low-vision and plain-terminal users retain state information.
- Require terminal layouts and localized messages to survive resize and wide Unicode text; clipped controls or broken cursor ordering can block task completion.
- Require `Modifier.semantics` state to follow observable UI lifecycle changes; stale announcements can break accessible task ordering.
- Require Swing focus changes on the EDT, crossing with `SwingUtilities.invokeLater` only when currently off-thread; needless enqueueing can reorder focus after a newer user action.
- Verify semantic templates with `axe-core` or an equivalent accessibility test; missing contract evidence risks invalid HTML and data-entry failure.
- Require `gradle test` coverage for localized resource loading; missing keys can cause build-time or runtime accessibility failure.
- Require bounded terminal redraw work around `SIGWINCH`; unbounded resize processing can exhaust resources and cause latency failure.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.

Android and Compose Multiplatform source-set or target-specific semantics must route to the KMP UX/accessibility lane, which deterministically augments this retained Kotlin baseline for standalone Compose Desktop.
