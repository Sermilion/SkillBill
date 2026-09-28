---
name: bill-typescript-code-review
description: Use when reviewing TypeScript libraries, applications, services, Node or browser runtimes, APIs, persistence, tests, and TSX UI surfaces.
internal-for: skill-bill
---

# Adaptive TypeScript PR Review

Review TypeScript against the repository's `tsconfig`, package manager, module system, runtime targets, framework conventions, generated-code boundaries, and public compatibility policy. A successful type check is evidence, not proof of runtime correctness: types disappear at I/O, serialization, reflection, and JavaScript interop boundaries.

## Classification Rules

- If TypeScript configuration and first-party TypeScript source dominate the changed product surface, classify the owned diff as `typescript`.
- Otherwise keep TypeScript ownership only for first-party TypeScript files when another stack owns the changed product surface.
- Always select `bill-typescript-code-review-architecture`.
- Select at least two and at most ten specialists.
- Use `bill-typescript-code-review-platform-correctness` as the default second lane when no narrower signal applies.
- Select testing for material behavior, configuration, type-level contract, or regression changes.
- Send untrusted input and runtime validation to security and API contracts as well as the behavior-owning lane.
- Treat `any`, unchecked assertions, non-null assertions, disabled strictness, and ignored diagnostics as risk signals only when they bypass a reachable contract.

## Diff-Signal Routing Table

- Package/module boundaries, dependency direction, project references, or public abstractions -> `architecture` specialist.
- Event-loop work, allocation, bundles, rendering, repeated I/O, or hot paths -> `performance` specialist.
- Strictness, narrowing, generics, assertions, module resolution, or Node/browser targets -> `platform-correctness` specialist.
- Auth, secrets, untrusted input, DOM injection, process/file/network boundaries, or dependencies -> `security` specialist.
- Type tests, unit/integration/browser tests, mocks, timers, or concurrency -> `testing` specialist.
- Exported types, HTTP/RPC, schemas, serialization, events, or compatibility -> `api-contracts` specialist.
- ORM/query code, migrations, transactions, serialization, or durable writes -> `persistence` specialist.
- Promises, cancellation, retries, queues, timeouts, shutdown, or observability -> `reliability` specialist.
- TSX, state, events, forms, routing, rendering, or hydration -> `ui` specialist.
- Semantics, keyboard/focus flow, accessible names, feedback, or localization -> `ux-accessibility` specialist.

## Mixed Diffs

- Keep the baseline specialists for the whole review, add only area-relevant lanes, and use lightweight file-level classification from paths, imports, configuration, and framework markers to build each specialist scope.
- Architecture receives every first-party TypeScript-owned file; other specialists receive only matching files.
- Exclude generated, vendored, build-output, and non-stack files from specialist scope and dominance scoring, including `node_modules/`, `dist/`, generated clients, and generated declarations.
- Ambient `*.d.ts` declarations or TypeScript used only for build tooling do not make the repository TypeScript-owned.
- After exclusions, drop empty lanes and restore the minimum by assigning all TypeScript-owned files to platform-correctness when architecture would otherwise stand alone.
- Load each selected specialist's governed rubric so every selected specialist result is retained and attributed.
- Launch selected specialists as subagents in this harness in a deterministic order and retain every selected specialist result.

## Finding Discipline

- Calibrate severity to concrete production, operator, client, or user impact using only the governed severity vocabulary.
- Verify every triggering precondition and reachable failure path before reporting a finding.
- Keep findings attributed to their specialist lane through collection and merge.
- Deduplicate overlapping findings without losing the strongest evidence, consequence, or ownership attribution.
- Name the violated type, runtime, repository, API, or operational contract and the concrete failure; do not report formatter output, subjective style, or unsafe syntax by keyword alone.
