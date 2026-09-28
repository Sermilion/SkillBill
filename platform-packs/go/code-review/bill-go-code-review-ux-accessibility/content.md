---
name: bill-go-code-review-ux-accessibility
description: Use when reviewing accessibility and task completion in Go-owned templates, forms, fragments, CLI, and TUI surfaces.
internal-for: skill-bill
---

# Go UX and Accessibility Review Specialist

Own semantics, keyboard flow, feedback, localization, and whether a person can complete the task. UI owns rendering mechanics; security owns trust boundaries.

## Focus

- Accessible names, error association, focus, keyboard flow, semantics, live feedback, localization, and terminal interaction
- Concrete assistive-technology or task-completion failures in Go-owned interfaces

## Ignore

- Rendering correctness and state ownership findings owned by UI
- Escaping, authorization, and sensitive-data findings owned by security

## Applicability

Apply to HTML emitted by Go templates and interactive terminal output. Evaluate server-driven updates and failure recovery with keyboard and assistive-technology use in mind.

## Project-Specific Rules

### Go Accessibility Rules

- Require every form control emitted by `html/template` to obtain an accessible name through a native `<label for>`, wrapping label, or valid `aria-label`/`aria-labelledby` relationship; unnamed controls break screen-reader task completion.
- Ensure field errors use `aria-describedby` or an equivalent semantic relationship to the input; visual-only feedback leaves screen-reader users unaware of invalid data and causes task failure.
- Require a validation summary with `tabindex="-1"` to link to failing fields and receive focus when the failed submission's task flow needs summary navigation; unconditional movement can disrupt keyboard users, while absent movement can make the first error unreachable.
- Verify htmx swaps restore focus through actual client behavior only when the active element was replaced or task flow moves to a new region; `HX-Trigger` merely emits an event and cannot by itself prevent focus loss for keyboard and assistive-technology users.
- Ensure `<dialog>` and menu markup supports Escape, tab containment where appropriate, and focus return; incomplete keyboard flow causes task failure without a pointer.
- Require `<h1>` through `<h6>` and landmarks in template composition to remain ordered across nested components; broken semantics create an ordering failure for page navigation.
- Verify dynamic status uses an applicable `aria-live` region without repeatedly announcing decorative changes; silent or noisy updates break feedback and cause task failure.
- Ensure `role="status"` output is conveyed by text or icon meaning in addition to CSS color; color-only errors create inaccessible failure reporting.
- Require `<button>` for mutations and `<a>` for navigation in generated HTML; incorrect elements break keyboard activation and cause task failure.
- Verify localized strings pass through the repository's message catalog rather than `fmt.Sprintf` fragments; concatenation risks incorrect grammar and untranslated failures.
- Ensure dates, numbers, and plural-sensitive counts use configured `golang.org/x/text` formatting when locales are supported; hard-coded output communicates incorrect data and causes contract failure.
- Require user-visible `error` copy to state what failed and a viable recovery action without exposing internals; vague feedback causes task-completion failure.
- Verify applicable htmx client handlers connect `HX-Trigger` events to an `aria-live` status or other semantic feedback when validation or completion must be announced; an event header alone leaves the state change invisible to assistive technology.
- Ensure CLI prompts accept a non-interactive flag or `os.Stdin` path when automation is supported; TTY-only interaction creates an operational timeout failure in pipelines.
- Require terminal errors and progress states to remain understandable with `NO_COLOR` and redirected output; escape-only or color-only status corrupts operational feedback.
- Verify TUI `tea.KeyMsg` controls expose a discoverable keyboard help view and preserve a clear focus indicator; hidden bindings cause task failure by making critical actions unreachable.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
