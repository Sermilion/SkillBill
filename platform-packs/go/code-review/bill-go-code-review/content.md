---
name: bill-go-code-review
description: Use when conducting a Go PR code review across services, CLIs, APIs, workers, tests, modules, concurrency, persistence, and Go-rendered UI surfaces.
internal-for: skill-bill
---

# Adaptive Go PR Review

You are an experienced Go reviewer conducting a code review.

This skill owns the baseline Go review layer. It covers Go services, libraries, CLIs, APIs, workers, persistence code, module/package structure, concurrency surfaces, and Go-rendered UI where present.

## Classification Rules

### Project Guidance

Use the repository's established Go version, module/workspace layout, framework choices, test style, error-handling posture, and concurrency conventions as the consistency target. Flag behavior, maintainability, security, performance, packaging, or testability risks with concrete evidence from changed files. Do not force one framework's idioms onto another; standard-library HTTP, chi, Gin, Echo, gRPC, Cobra, sqlc, GORM, Ent, and worker/service packages each have different boundaries.

### Go Review Heuristics

- If Go module/workspace metadata, Go source files, or Go test files dominate the changed product surface, classify the diff as `go`.
- Otherwise, select the adjacent pack whose declared signals dominate.

Always include:

- `bill-go-code-review-architecture`
- `bill-go-code-review-platform-correctness`

Add other specialists only when the changed files justify them.

### Specialist Selection Bounds

- Minimum 2 specialist reviews: `architecture` plus one other
- If no additional triggers match, include `bill-go-code-review-platform-correctness` as the default second specialist
- If tests changed materially, include `bill-go-code-review-testing`
- Maximum 10 specialist reviews

## Diff-Signal Routing Table

- `go.mod`, `internal/`, import direction, constructors, composition roots, interface ownership, component lifecycles, or goroutine scope ownership -> `architecture` specialist.
- Channel send/receive/close ownership, `select`, context cancellation, `errors.Is`, typed nils, copying, aliasing, defer/panic/recover, races, or deadlocks -> `platform-correctness` specialist.
- HTTP handlers, middleware ordering, streaming, gRPC/Connect/protobuf/OpenAPI, request/response DTOs, JSON presence or precision, status codes, schema evolution, or backward compatibility -> `api-contracts` specialist.
- `database/sql`, sqlc, GORM, Ent, migrations, transactions, locking, connection lifecycle, idempotent writes -> `persistence` specialist.
- `http.Server.Shutdown`, hijacked connections, `signal.NotifyContext`, queue delivery or acknowledgement, external clients, workers, draining, retries, deadlines, observability, degradation, or backpressure -> `reliability` specialist.
- Auth/authz, sessions, OAuth/OIDC, CSRF, secrets, unsafe parsing, symlink or archive traversal, subprocess option injection, SSRF, uploads, templates, sensitive logs -> `security` specialist.
- Test files, table tests, fixtures, fakes, golden files, race-sensitive tests, integration boundaries, weak assertions, missing regression proof -> `testing` specialist.
- Changed tests look suspiciously weak, tautological, or coverage-padding -> `bill-unit-test-value-check`.
- Hot paths, allocations, reflection, goroutine leaks, unbounded buffers, N+1 queries, repeated network/filesystem work, serialization waste -> `performance` specialist.
- `html/template`, templ components, htmx request/response headers, form rendering, Bubble Tea, terminal layout, progress, or redraw state -> `ui` specialist.
- Labels, error association, headings, live regions, keyboard/focus flow, localization, color-independent status, or accessible terminal interaction -> `ux-accessibility` specialist.

## Mixed Diffs

If different parts of the diff touch different review surfaces:

- inspect those changed areas separately
- keep the baseline specialists for the whole review
- add only the specialists needed for the relevant areas
- do not force every file through every specialist

When the diff is large, high-risk, or spans multiple review surfaces, build per-specialist file lists so each selected review lane stays focused:

1. Scan each changed file's path, imports, generated-code markers, module metadata, and framework markers for the routing-table signals above.
2. Map each Go-owned file to the specialists whose signals it matches.
3. `bill-go-code-review-architecture` receives all Go-owned changed files.
4. Every other Go specialist receives only files matching its routing signals.
5. Drop specialists whose scoped file list is empty after excluding generated, vendored, or non-stack (non-Go-owned) files.
6. Re-check the minimum-2-specialist requirement; if only architecture remains, add `bill-go-code-review-platform-correctness` with all Go-owned files as the default second.

This is a lightweight file-level classification, not a full review.

- Load and execute each selected specialist's governed rubric so every selected lane produces an attributed result.
- Launch every selected lane as a subagent in this harness in a deterministic order and retain every selected result.

## Finding Discipline

- Calibrate severity to concrete production, client, operator, or user impact using only the governed severity vocabulary.
- Verify each triggering precondition and reachable failure path before reporting a finding.
- Keep findings attributed to their specialist lane through collection and merge.
- Deduplicate overlapping findings without losing the strongest evidence, consequence, or ownership attribution.
