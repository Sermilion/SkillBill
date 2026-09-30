---
name: bill-rust-code-review-ux-accessibility
description: Use when reviewing accessibility and UX in Rust-owned UI, including semantics, keyboard and focus flow, feedback, localization, and assistive access.
internal-for: skill-bill
---

# UX and Accessibility Review Specialist

## Focus

- Semantic structure, accessible names, labels, roles, states, and relationships
- Keyboard navigation, focus order and restoration, shortcuts, and escape behavior
- Validation, progress, errors, completion feedback, and reduced-motion or contrast needs
- Localization, text expansion, Unicode, terminal capabilities, and screen-reader output

## Ignore

- Copy or visual preference without comprehension, access, or task-completion impact
- Accessibility claims for surfaces outside the Rust-owned diff
- Framework-specific prescriptions when equivalent accessible behavior exists
- UI correctness findings that belong to the `ui` specialist
- Security-only findings that belong to the `security` specialist

## Applicability

Apply when changed Rust UI affects semantics, accessible names, keyboard or focus flow, feedback, localization, or assistive access.

## Project-Specific Rules

### Rust Accessibility Rules

- For wasm or server-rendered HTML, require controls created through `web_sys` or templates to expose native elements or correct ARIA names, roles, and states; reject screen-reader ambiguity.
- For native GUI frameworks such as `egui`, `iced`, or Slint, ensure custom widgets publish available accessibility metadata; flag a task-completion failure when assistive technology cannot identify or operate controls.
- For terminal interfaces using `ratatui`, require a linear basic-terminal or plain-text fallback for essential results; reject operational task failure when cursor addressing, color, or mouse input is unavailable.
- Require every action to be reachable by keyboard events such as `KeyboardEvent` or `crossterm::event::KeyCode`; flag task failure from pointer-only controls or shortcuts that trap input.
- Ensure focus order follows task order and use `HtmlElement::focus` or the framework equivalent to restore focus after dialogs and navigation; reject lost or stolen focus.
- Require dialogs, menus, and overlays to contain focus and support each surface's equivalent Escape event when the interaction contract permits, using `crossterm::event::KeyCode::Esc` only for terminal interfaces; flag task failure from keyboard traps or background activation.
- Ensure validation errors are associated through `aria-describedby`, native labels, or framework accessibility nodes; reject fields whose failure cannot be discovered without visual proximity.
- Require meaningful loading, completion, and error changes to use `aria-live` or an equivalent native accessibility event; flag task failure from silent async state that blocks assistive users.
- Never encode status only through `Color`, CSS color, animation, hover, or spatial position; require text, symbols, or semantic state and reject information loss.
- Require reduced-motion preferences through `matchMedia("(prefers-reduced-motion: reduce)")` or native settings where animation exists; flag vestibular-access risk.
- Verify localization with `fluent_bundle::FluentBundle`, ICU-style message formatting, or the repository catalog, including pluralization and bidi isolation; reject untranslated or directionally corrupted tasks.
- Require terminal layout to use `unicode_width::UnicodeWidthStr` rather than byte length and handle text expansion; reject clipped labels, broken focus indicators, or invalid cursor placement.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
