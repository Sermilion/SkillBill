## [2026-09-24] SKILL-381 subtask 2 — Relay identity, drop reporting, and stats truthfulness

Areas: docs/cloudflare-telemetry-proxy, docs/telemetry-privacy.md
- The bundled relay now drops an event when `distinct_id` is blank or the reserved `test-install-id`, or when a present `properties.install_id` is; an absent `install_id` no longer drops, so pre-`install_id` clients still forward on a real `distinct_id`.
- Drop reporting is additive: `successBodyWithDropCount` merges `dropped_test_events` into the upstream success body while keeping the upstream status, so older clients that read only the status are unaffected. reusable
- Delivery identity is the top-level `uuid`; PostHog collapses unconfirmed resends on it. `$insert_id` is a legacy mirror PostHog does not read — the old comment claiming it deduplicates was wrong and is corrected in `worker.js` and `docs/telemetry-privacy.md`.
- `in_progress_runs` is no longer clamped with `Math.max(..., 0)` in `normalizeVerifyStats`, `normalizeVerifySeriesEntry`, or `summarizeVerifySeriesBucket`: a negative value is real evidence of missing start rows and clamping hid it. The separate `finished_without_start_runs` clamps stay.
- Pattern: worker tests that assert forwarding behavior drive `worker.fetch` with a stubbed `globalThis.fetch` and assert the captured upstream body, rather than exercising an exported helper. `dropTestTraffic` lost its export as a result. reusable
- Limitation: relay changes are code-only here; deployment and PostHog history cleanup remain the operator runbook in the spec, and the drift-check script is not run against the network from this repo.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-09-18] SKILL-354 subtask 2 — Split filesystem infrastructure by ownership

Areas: runtime-kotlin/runtime-infra/{host,contracts,skills,launcher,workflow,http,sqlite}, runtime-kotlin/ARCHITECTURE.md, docs
- Split the former filesystem adapter into `runtime-infra/host`, `runtime-infra/contracts`, `runtime-infra/skills`, and `runtime-infra/launcher`, while retaining the nested `runtime-infra/workflow`, `runtime-infra/http`, and `runtime-infra/sqlite` modules.

## [2026-09-18] SKILL-354 subtask 1 — Nest runtime infrastructure Gradle modules

Areas: runtime-kotlin/settings.gradle.kts, runtime-kotlin/runtime-infra/{fs,http,sqlite}, build-logic/convention, runtime-core architecture tests
- Flat `runtime-infra-fs|http|sqlite` modules moved under `runtime-kotlin/runtime-infra/` as nested Gradle projects `:runtime-infra:fs`, `:runtime-infra:http`, and `:runtime-infra:sqlite` with jar basenames `runtime-infra-<name>-<version>.jar`.
- `skillbill.governed-resources` convention plugin centralizes governed copy tasks; fs declares 33 copies as data with one missing-source template.

## [2026-09-14] SKILL-238 subtask 1 — Delete speculative leftovers
Areas: docs/delegated-review, docs, scripts, platform-packs/typescript/addons, runtime-kotlin/{gradle,runtime-application/repoTest}, uninstall.sh, README.md
- Deleted `docs/delegated-review/` in full, `scripts/split-runloop.py`, the three unshipped SKILL-116 TypeScript add-on scaffolds, the unused `jna` catalog version and library alias, and the expired GLM branch in `uninstall.sh`.
- Reverses the [2026-08-11] SKILL-182 subtask 3 preface-over-delete decision: a historical-record preface still reads as a current capability body, so removal beats annotation once the subsystem is gone. reusable
- `docs/team-control-plane-roadmap.md` now carries positioning and open discovery questions only; the staged bundle-sync, admin-editing, and hosted-controls phases are gone.
- Pattern: when trimming a roadmap to unshipped status, sweep every surface that summarises it in the same change — here README.md and `docs/getting-started-for-teams.md`. reusable
- `uninstall.sh` retained the Copilot historical sweep and sweeps for Claude, Codex, Cursor, and the retired fourth agent; only the obfuscated `printf 'g%s' 'lm'` target past its 2026-08-02 window went.
- Dropped the doc-shape test asserting row numbering 1..47 over the deleted `failure-matrix.md`; a test that asserts over a frozen Markdown archive dies with the archive and guards no code path. reusable
- Limitation: dated prose in this file and in the review-orchestrator and review-delegation histories still names `docs/delegated-review/`; those record past decisions rather than resolve a live path, so they stand.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-18] SKILL-197 subtask 2 — KMP uncovered area disposition
Areas: docs/review-area-ownership, platform-packs/kmp/code-review/security, platform-packs/kmp/code-review/ux-accessibility, runtime-infra/skills/scaffold
- Recorded disposition for all four remaining kotlin-owned areas: `security` declared on `kmp` with on-device plus shared/JVM source-set rules; `performance`, `testing`, and `api-contracts` retained on `kotlin` with per-rule reachability audits in `docs/review-area-ownership.md`.
- Declaring on `kmp` displaces the `kotlin` rubric for that area entirely — a `kmp` specialist cannot defer to the baseline `security` lane. reusable
- `kmp` now owns seven physical areas and three inherited; scaffold composition tests and render snapshots pin that split. `ux-accessibility` boundary pointers name the `kmp` `security` specialist, not the kotlin baseline.
- Limitation: `kotlin` pack byte-unchanged; backend-only triggers stay inert (silent, not misleading) on Android inputs for retained areas.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-11] SKILL-182 subtask 3 delegated-review docs reconciled to live playbook
Areas: docs/delegated-review, docs, AGENTS.md
- Prefaced every `docs/delegated-review/` file as the historical record of the SKILL-159-removed external subsystem, each linking to `orchestration/review-delegation/PLAYBOOK.md` as the live contract; corrected `decision.md` so `auto`/omission resolve to `inline` and `delegated` is explicit-only.
- Stated Cursor's current delegated support once (experimental, explicit opt-in, in-harness, not live-CLI verified) and demoted `provider-capability-matrix.md` so deleted registry types are not read as current sources.
- Aligned `docs/capabilities.md` and `docs/review-telemetry.md` mode/default wording with the playbook; lane accounting now admits plan-identity when the harness returns no launch id.
- Reusable: extend the SKILL-159 removal-preface pattern across a whole historical docs directory, then sweep adjacent reader surfaces so they cannot contradict the live playbook.
- Historical SKILL-145 bodies left otherwise unchanged; AGENTS.md dropped a stale validate-phase duplicate only.
Feature flag: N/A
Acceptance criteria: 8/8 implemented
