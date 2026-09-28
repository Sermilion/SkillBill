---
name: bill-python-code-review
description: Use when conducting a Python PR code review across applications, libraries, CLIs, APIs, data code, tests, packaging, async/concurrency, and Python-rendered UI surfaces.
internal-for: skill-bill
---

# Adaptive Python PR Review

Review Python applications, libraries, CLIs, services, APIs, persistence code, packaging, tests, async/concurrency surfaces, framework glue, and Python-rendered UI.

## Classification Rules

- If Python product code, packaging, or framework configuration dominates the changed surface, classify the diff as `python`.
- If multiple strong platform signals coexist, keep Python ownership only for Python product files and project-owned Python automation.
- If only generated, vendored, virtual-environment, or incidental Python tooling is present, do not classify the product diff as Python.
- Otherwise classify project-owned `.py` libraries, CLIs, notebooks, and utilities as `python`.

Always keep the `architecture` and `platform-correctness` specialists as the baseline. Add other specialists only when their diff signals apply, and include `testing` whenever tests change materially.

## Diff-Signal Routing Table

- Package layout, import direction, dependency injection, service boundaries, framework coupling, or library/application seams -> `architecture` specialist.
- Hot paths, N+1 queries, repeated I/O, import-time work, memory growth, streaming, batching, or async blocking -> `performance` specialist.
- Runtime semantics, typing or nullability, resources, concurrency, serialization, state transitions, retries, or time logic -> `platform-correctness` specialist.
- Dependency supply chain, auth, secrets, unsafe parsing, templates, paths, subprocesses, SSRF, uploads, or sensitive logs -> `security` specialist.
- Tests, fixtures, monkeypatching, parametrization, async or time behavior, integration boundaries, or regression proof -> `testing` specialist.
- Routes, request or response models, runtime validation, schemas, OpenAPI, status codes, serializers, streaming, webhooks, or compatibility -> `api-contracts` specialist.
- SQLAlchemy, Django ORM, raw SQL, migrations, transactions, locking, sessions, or idempotent writes -> `persistence` specialist.
- External clients, queues, workers, schedulers, retries, timeouts, observability, degradation, or backpressure -> `reliability` specialist.
- Django admin, templates, Streamlit, Dash, Panel, notebooks, reports, forms, PyQt/PySide, Tkinter, Textual, or dashboards -> `ui` specialist.
- Server-rendered or desktop semantics, form feedback, keyboard or focus behavior, localization-sensitive copy, notebook/report alternatives, or assistive technology -> `ux-accessibility` specialist.

## Mixed Diffs

- Keep the baseline specialists for the whole review, add only area-relevant lanes, and use lightweight file-level classification from paths, imports, configuration, and framework markers to build each specialist scope.
- Give architecture all Python-owned changed files; give other specialists only matching files and drop empty lanes.
- Exclude generated, vendored, build-output, and non-stack files from specialist scope and dominance scoring. Common non-owning paths include `.venv/`, `venv/`, `site-packages/`, `build/`, `dist/`, generated protobuf/OpenAPI clients, and vendored dependency trees.
- Let other stack packs own non-Python product files; a nearby Python helper, virtual-environment marker, or CI script does not transfer ownership.
- Re-check the two-specialist minimum after scoping; if only architecture remains, give all Python-owned files to platform-correctness as the default second lane.
- Load each selected specialist's governed rubric so every selected lane produces an attributed result. When tests appear tautological or coverage-padding, also apply the `operation:unit-test-value-check` lens.
- Launch selected specialists as subagents in this harness in a deterministic order and retain every selected specialist result.

## Finding Discipline

- Calibrate severity to concrete production, operator, or client impact using only the governed severity vocabulary.
- Verify every triggering precondition and reachable failure path before reporting a finding.
- Keep findings attributed to their specialist lane through collection and merge.
- Deduplicate overlapping findings without losing the strongest evidence, consequence, or ownership attribution.
