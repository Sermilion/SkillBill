---
name: bill-php-code-review-ui
description: Use when reviewing PHP-owned templates, forms, server-driven components, and server/client state handoff.
internal-for: skill-bill
---

# PHP UI Review Specialist

Review rendering and interaction correctness on presentation surfaces owned by PHP.

## Focus

- Blade, Twig, Livewire, Filament, Inertia, and Symfony Forms when detected
- Form state, authorization, escaping, identity, redirects, and pagination
- Server/client hydration and progressive enhancement

## Ignore

- Generic frontend styling outside PHP ownership
- Framework component rules without Composer, template, route, or source evidence
- Defer pure `ux-accessibility` failures to that lane and exploit-only template or authorization findings to `security`.

## Applicability

Apply each framework rule only when repository packages and changed templates or components prove it owns the UI. Review Inertia handoff across its PHP props and client boundary, not unrelated client internals.

## Project-Specific Rules

### PHP-Owned UI Correctness Rules

- Require context-specific encoding for every untrusted Blade or Twig sink, including HTML text, attributes, URLs, JavaScript, and CSS, and justify every `{!! !!}` or `|raw`; default HTML autoescaping in `{{ }}` does not make other contexts safe, so a misplaced boundary creates user-data exposure.
- Ensure forms repopulate `old()` values or Symfony submitted data without replacing sensitive defaults; incorrect restoration makes users resubmit wrong data.
- Require validation errors to bind through the exact `error-bag` and attempted form instance; a shared bag can display an invalid message on another form.
- Verify Symfony Form `handleRequest()` and Laravel request validation distinguish absent, unchecked, and false values; truthiness coercion can save incorrect state.
- Ensure template-visible actions repeat server-side authorization through `Policy` or voters; hiding a button alone creates an authorization failure.
- Require Livewire `wire:key` or stable component identity for reordered collections; positional reuse produces a stale-state regression and wrong-row actions.
- Verify Filament `TableAction` resolves the currently authorized record rather than trusting hydrated state; stale identifiers can corrupt another tenant's data.
- Ensure Inertia `share()` props exclude secrets and user-specific cache state; globally shared serialization can cause private-data exposure.
- Require server/client `props` to use stable JSON shapes for enums, dates, and nullable fields; hydration drift breaks interactive rendering.
- Reject `redirect()->withErrors()` flows that discard input or required success state; users otherwise lose work or see incorrect confirmation.
- Ensure `withQueryString()` pagination retains validated filters and deterministic ordering; dropped query state creates an ordering failure with skipped records.
- Require duplicate-submit protection around non-idempotent `POST` actions using disabled state plus server enforcement; double clicks can create a data failure.
- Verify CSRF expiry, session expiry, and authorization failures render recoverable UI states; an unhandled `419` or `403` prevents task completion.
- Ensure repository-owned response or application caches such as `Cache::remember()`, response-cache middleware, or Symfony Cache pools vary rendered output by tenant, locale, and permission; cache-key collisions leak or stale-render data.
- Require server-rendered content to remain usable when `Livewire` or Symfony UX enhancement fails where progressive enhancement is promised; script failure must not erase core actions.
- Verify template `foreach` loops do not trigger lazy ORM queries or mutate application state; rendering can otherwise cause a resource-timeout failure.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
