---
name: bill-typescript-code-review-ux-accessibility
description: Use when reviewing TypeScript UI semantics, names, focus, keyboard input, live feedback, localization, motion, zoom, and assistive-technology behavior.
internal-for: skill-bill
---

# UX and Accessibility Review Specialist

## Focus

- Native semantics, accessible names, roles, states, descriptions, and relationships
- Focus order and restoration, keyboard operation, validation, and live feedback
- Localization, pluralization, direction, zoom, motion, portals, and assistive technology

## Ignore

- Copy or visual preference without comprehension or task-completion impact
- Defer UI correctness concerns to the `ui` specialist and security concerns to the `security` specialist
- ARIA preference when native semantics already satisfy the contract

## Applicability

Apply to the actual DOM produced by detected React, Vue, Svelte, Solid, Angular, Lit, vanilla, or other repository UI code; TSX syntax does not establish framework behavior.

## Project-Specific Rules

### Semantic and Assistive Contract Rules

- Interactive controls must use native `button`, `a`, `input`, and related elements where possible; reject custom widgets whose role, name, state, or activation contract is invalid. Verify `getByRole` output, accessible name, state, and keyboard activation in the rendered browser before reporting this failure.
- Accessible names and descriptions must remain connected through `label`, `aria-labelledby`, or repository primitives after conditional rendering; prevent unnamed or misleading controls. Verify the resolved label and description relationships before and after the conditional update before reporting this failure.
- Tables, headings, landmarks, lists, and form groups must express visible structure in the accessibility tree; flag navigation or comprehension failure caused by decorative markup. Verify `getByRole('heading')` order, landmark names, table associations, and list or group membership before reporting this failure.
- Dynamic framework props must not emit contradictory ARIA states or duplicate IDs; reject an assistive-technology contract that diverges from visible state. Verify rendered `aria-*` attributes, unique ID references, and announced state after each transition before reporting this failure.

### Focus, Keyboard, and Feedback Failure Rules

- Each detected native element or WAI-ARIA widget must implement its own keyboard contract: preserve ordinary tab and activation behavior for native controls, arrow navigation only for patterns such as menus, tabs, or composite widgets that define it, and Escape only where the pattern owns dismissible state. Verify `keyboard interaction trace` before reporting this failure.
- Focus must move or restore intentionally after navigation, dialog close, deletion, validation failure, and portal teardown; flag loss, trapping, or unexpected theft of focus. Verify `document.activeElement` through the complete interaction sequence before reporting this failure.
- Validation errors must identify the field, explain recovery, and update `aria-invalid` and described relationships; reject a form failure that screen-reader users cannot locate. Verify field state, error reference, summary link, and recovery focus target before reporting this failure.
- Loading, progress, errors, and completion must use appropriate `aria-live`, status, or alert behavior without repeated announcements; prevent silent or disruptive async feedback. Verify live-region mutations and representative screen-reader announcements across the async lifecycle before reporting this failure.

### Localization and Perception Rules

- Messages must preserve localization keys, interpolation, plural rules, number and date formatting, and text expansion; reject hard-coded English or grammar failure that changes meaning. Verify `Intl.PluralRules` output for representative locales, plural categories, and expanded strings before reporting this failure.
- Layout and interaction must support `dir="rtl"`, logical properties, zoom, reflow, and text resizing at repository-supported breakpoints; prevent clipped content or reversed controls. Verify `dir=rtl` screenshots and task completion at 200% zoom and narrow reflow before reporting this failure.
- Motion, auto-updates, time limits, hover-only content, color-only meaning, and pointer gestures must provide accessible alternatives; flag vestibular or input-mode exclusion. Verify reduced-motion behavior and equivalent keyboard, touch, and non-color cues before reporting this failure.
- Operational evidence must include keyboard traversal, accessibility-tree inspection, automated checks, and representative screen-reader behavior for the changed task; reject an accessibility claim based only on typed props. Verify the recorded traversal, `axe` violations, and screen-reader task outcome before reporting this failure.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
