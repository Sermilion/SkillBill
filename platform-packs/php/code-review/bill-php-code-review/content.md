---
name: bill-php-code-review
description: Use when conducting a thorough PHP PR code review across backend, service, and server-rendered PHP code. Select specialists for architecture, correctness, API contracts, persistence, reliability, security, performance, testing, UI, and UX/accessibility based on changed-file signals.
internal-for: skill-bill
---

# Adaptive PHP PR Review

You are an experienced PHP architect conducting a code review.

This skill owns the baseline PHP review layer. It covers backend/service PHP concerns, server-rendered UI surfaces, and the specialist-selection logic that the PHP review lanes build on top of.

## Classification Rules

Treat coherent local project standards and established architecture as the consistency target. Do not preserve local patterns that are inconsistent, accidental, or harmful; use this PHP pack to flag concrete maintainability, correctness, security, scalability, or testability risks and guide changes toward established PHP/backend practices.

If Composer metadata, PHP source, PHP tests, or PHP framework configuration dominate the changed product surface, classify the diff as PHP-owned and apply this pack.

Otherwise, do not classify the diff as PHP-owned when PHP appears only as tooling or CI glue around another dominant stack, or when the PHP signals come only from generated or vendored files.

Always include:

- `bill-php-code-review-architecture`
- `bill-php-code-review-platform-correctness`

Add other specialists only when the changed files justify them.

### Specialist Selection Bounds

- Minimum 2 specialist reviews: `architecture` plus one other
- If no additional triggers match, include `bill-php-code-review-platform-correctness` as the default second specialist
- If tests changed materially, include `bill-php-code-review-testing`
- Maximum 10 specialist reviews

## Diff-Signal Routing Table

- Composer `autoload.psr-4`, namespaces, service providers, Symfony `services.yaml`, container lifetimes, module APIs, outbox code, generators, or async/Fiber boundaries -> `architecture` specialist.
- `==` versus `===`, `empty()`, `isset()`, `array_key_exists()`, `match`, `Throwable`, references, decoded arrays, mutable statics, worker resets, or `Fiber` suspension -> `platform-correctness` specialist.
- PHP-owned controllers, routes, request DTOs, response resources, Symfony `Request`/Validator/Serializer, Laravel `FormRequest`/`JsonResource`, PSR-7/PSR-15, JSON shapes, enums, dates, status mapping, pagination, webhooks, or idempotency -> `api-contracts` specialist.
- PDO statements, Doctrine `EntityManager`, Eloquent models/scopes/casts, SQL, transactions, locks, migrations, tenant filters, cursors, or N+1 access -> `persistence` specialist.
- Laravel queues/Horizon, Symfony Messenger, acknowledgement, retry/backoff, schedulers, cache keys, locks, timeouts, signals, shutdown handlers, or persistent workers -> `reliability` specialist.
- `unserialize()`, hydration hooks, Blade `{!! !!}`, Twig `|raw`, uploads, paths, `exec()`, `system()`, `shell_exec()`, Symfony Process, `Process::fromShellCommandline()`, Laravel Process APIs, SSRF, policies/voters, CSRF, trusted proxies, secrets, or sensitive logs -> `security` specialist.
- PHPUnit/Pest tests, data providers, framework kernels, database fixtures, queue/cache fakes, worker isolation, PHPStan/Psalm configuration, or PHP runtime matrices -> `testing` specialist.
- Changed tests look suspiciously weak, tautological, or coverage-padding -> `operation:unit-test-value-check`.
- PDO/ORM query volume, hydration, serializer/template loops, batching, `yield`, streamed responses, OPcache, Composer class maps, cache cardinality, worker memory, or blocking fibers -> `performance` specialist.
- Blade, Twig, Livewire, Inertia, Filament, Symfony Forms, old input, error bags, component identity, redirects, pagination, or server/client state handoff -> `ui` specialist.
- PHP-owned Blade or Twig templates, Symfony Forms, Livewire, Inertia, Filament, rendered components, labels/errors, keyboard controls, focus restoration, headings, landmarks, live regions, localization, directionality, or progressive enhancement, even when the diff contains no pre-existing accessibility keywords -> `ux-accessibility` specialist.

## Mixed Diffs

If different parts of the diff touch different review surfaces:

- inspect those changed areas separately
- keep the baseline specialists for the whole review
- add only the specialists needed for the relevant areas
- do not force every file through every specialist

When the diff is large, high-risk, or spans multiple review surfaces, build per-specialist file lists so each selected review lane stays focused:

1. Scan each changed file's path, imports, generated-code markers, Composer metadata, and framework markers for the routing-table signals above.
2. Map each PHP-owned file to the specialists whose signals it matches.
3. The baseline `bill-php-code-review-architecture` and `bill-php-code-review-platform-correctness` specialists receive all PHP-owned changed files and stay selected for the whole review.
4. Every additional PHP specialist receives only files matching its routing signals.
5. Drop additional specialists whose scoped file list is empty after excluding generated, vendored, or non-stack (non-PHP-owned) files.
6. After scoping, verify that both baseline specialists remain selected.

This is a lightweight file-level classification, not a full review.

- Load and execute each selected specialist's governed rubric so every selected lane produces an attributed result.
- Launch every selected lane as a subagent in this harness in a deterministic order and retain every selected result.

## Finding Discipline

- Calibrate severity to concrete production, client, operator, or user impact using only the governed severity vocabulary.
- Verify each triggering precondition and reachable failure path before reporting a finding.
- Keep findings attributed to their specialist lane through collection and merge.
- Deduplicate overlapping findings without losing the strongest evidence, consequence, or ownership attribution.
