---
name: bill-php-code-review-ux-accessibility
description: Use when reviewing accessibility and task completion in PHP-rendered forms, templates, and server-driven components.
internal-for: skill-bill
---

# PHP UX and Accessibility Review Specialist

Review whether users can perceive, navigate, understand, and complete PHP-owned interactions.

## Focus

- Server-rendered semantics, labels, errors, headings, and landmarks
- Keyboard and focus behavior in Livewire, Filament, Symfony UX, and comparable components
- Localization, directionality, live updates, and progressive enhancement

## Ignore

- Visual taste without a task-completion consequence
- Client surfaces that the PHP repository does not render or configure
- Defer general `ui` state correctness to that lane and exploit or disclosure findings to `security`.

## Applicability

Use template and component checks only when Blade, Twig, Symfony Forms, Livewire, Filament, Inertia, or related PHP source owns the resulting markup or state update.

## Project-Specific Rules

### Accessible PHP Presentation Rules

- Require every Symfony Form, Blade, or Twig control to have an appropriate accessible name, preferring a visible associated `<label>` for form fields while accepting wrapping labels, `aria-labelledby`, native button text, or `fieldset` and `legend` where semantically correct; a missing or conflicting name prevents screen-reader identification.
- Ensure validation messages connect through `aria-describedby` and invalid controls expose `aria-invalid`; visual-only errors make form failures inaccessible.
- Require the server `error-summary` to link to invalid controls and receive focus after a failed submission; otherwise keyboard users cannot locate the failure.
- Verify first-error focus does not override user intent after background `Livewire` validation; unexpected movement breaks keyboard task order.
- Ensure `Livewire` or Symfony UX DOM replacement restores focus to the triggering control, dialog, or new target; rerenders can lose focus entirely.
- Require `button`, disclosure, tab, menu, and Filament actions to be keyboard operable; click-only handlers create a task-completion failure.
- Verify modal `focus-trap` behavior restores focus on close using the component lifecycle; escaped focus creates an unsafe interaction failure.
- Ensure heading levels and `<main>`, `<nav>`, and other landmarks survive Blade/Twig layouts; broken structure makes navigation incorrect.
- Require Livewire status, queue progress, and validation updates to use a restrained `aria-live` region; silent changes create an accessibility failure.
- Require `aria-busy` during a server-driven operation that delays replacement content; missing operational status creates a timeout-like task failure for assistive users.
- Reject color-only `required`, error, selected, or success states in rendered markup; users may miss invalid or completed actions.
- Ensure translated strings from `trans()`, `__()`, or Twig `trans` retain placeholders, plural rules, and names; malformed localization can cause an invalid accessible label.
- Require `lang` and, for right-to-left locales, `dir` on the rendered document or relevant subtree; absent directionality breaks reading and control order.
- Require framework-appropriate locale initialization and reset at every request or job boundary, including Laravel `app()->setLocale()` or Symfony request-locale and mutable translator or formatter state when owned; reading an already-leaked locale leaves later users with incorrect dates, numbers, or validation messages.
- Ensure `redirect()` and pagination responses preserve a meaningful page title and focus destination; missing context creates a navigation failure.
- Require core form submission and error recovery to work without `Livewire`, Turbo, or Symfony UX JavaScript when progressive enhancement is claimed; script failure must not block completion.
- Verify server/client hydration does not duplicate `id` values, labels, or live regions across Inertia transitions; duplicate semantics create an invalid navigation contract.
- Ensure `403` or session-expiry errors provide a recoverable path and do not erase entered non-sensitive data; abrupt failure causes preventable task loss.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
