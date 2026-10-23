# SKILL-422 subtask 3: evidence

SQLite installer maintenance, recovery regressions, and audit closure. `tests_executed` empty in implement; validate owns execution receipts.

## Architecture checklist (A1–A12 / G1–G7)

| Rule | Applicability | Result |
| --- | --- | --- |
| A1 dependency direction | maintenance tests use public test fixtures + ports factories only | pass |
| A2 state ownership | inventory rows name shell vs application vs session owners | pass |
| A3 persistence seams | no new persistence adapters; regressions hit existing stores | pass |
| A4 contract loud-fail | typed DB access failures reused (`DatabaseFailureCode.ACCESS`) | pass |
| A5 simplicity | no new maintenance subsystem; shell/script owners retained | pass |
| A6–A12 / G1–G7 | no guard widening; no new exemptions | pass (source review) |

## Findings (subtask 3)

| ID | Classification | Summary | Test pointer |
| --- | --- | --- | --- |
| F13 | verified no-finding | Pre-install preserve wipe retains WAL-backed durable rows | T3-01 |
| F14 | verified no-finding | Shell preserve vs explicit/application destructive uninstall | T3-04 |
| F15 | verified no-finding | Corrupt backup read refuses without mutating primary DB | T3-03 |
| EL-422-WAL | external limitation | Historical operator WAL incident not reconstructible from tree | documented only |

Subtask 1–2 findings F1–F12 unchanged; F4/F5/F11/F12 carry-over statuses preserved in `findings-register.md`.

## AC mapping

| AC | Implement evidence | Validate closure |
| --- | --- | --- |
| AC-001 | `state-ownership-inventory.md` subtask 3 table; `findings-register.md` F13–F15; this file | audit + doc cross-check |
| AC-002 | Source review: `../../../uninstall.sh` preserve patterns; T3-01/T3-04 definitions | T3-01, T3-04 execution |
| AC-003 | `SqliteMaintenanceRecoveryRegressionTest`, `InstallerShellMaintenanceRecoveryTest`, fixtures in `SqliteTestDatabaseFixtures.kt` | integrity_check + row assertions in validate |
| AC-004 | T3-03/T3-05; EL-422-WAL limitation | typed failure + byte retention in validate |
| AC-005 | `audit-coverage.md` | audit phase mapping completeness |
| AC-006 | inventory/matrix/register/evidence cross-links | audit trail review |
| AC-007 | named realistic bugs per T3-01–T3-05; disposable homes only | full suite in validate |

## Regression definitions (pending validate execution)

| ID | Realistic bug | Environment | Expected boundary |
| --- | --- | --- | --- |
| T3-01 | Preserve wipe deletes WAL sidecars or committed rows while writer open | disposable `HOME`, `review-metrics.db`, held JDBC connection | `integrity_check` ok; settlement + identity + progress rows exact after reopen |
| T3-02 | Interrupted nested write or checkpoint drops committed maintenance rows | temp `metrics.db` | aborted nested transaction leaves seed snapshot; WAL checkpoint after writer retains `artifacts_json` patch |
| T3-03 | Corrupt backup treated as success or primary DB mutated | temp dir + `backups/baseline/` | `ACCESS` on backup read; primary bytes/rows unchanged; rollback journal reopen retains rows |
| T3-04 | Application uninstall uses preserve semantics or hides partial state removal | shell + application tests with temp FS | preserve keeps DB; explicit shell + application remove tree; failed `removeTree` → `failed` |
| T3-05 | Corrupt workflow JSON silently “recovered” | temp `metrics.db` | corrupt `steps_json` bytes remain; settlement row still exact; no fabricated history |

## Explicitly verified / unavailable

- **Verified:** `InstallerShellDelegationTest` file-presence preservation tests retained; new tests add content/integrity assertions (no replacement).
- **Verified:** `DatabaseWriteMaintenanceRegressionTest` unchanged (readiness probe only).
- **Unavailable:** Historical WAL incident segment history — not reconstructed; see EL-422-WAL.

## Deviations

- No production code repairs required: source review found preserve/destructive contracts already separated; work is regression definitions + audit artifacts.
- No `implementation_plan_subtask_3.md` in tree; implement followed `spec_subtask_3` sequence.
- Format/lint: no safe module-scoped formatter invocation for mixed Kotlin/shell/docs diff; validate owns compile/check.
