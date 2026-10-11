# SKILL-422 subtask 3: SQLite maintenance and audit closure

## Scope

Audit and repair reinstall/uninstall/maintenance interactions with SQLite data and recovery material, then reconcile all upstream state requirements against the resulting tree. This pass owns a separate filesystem/connection failure model and may stand alone after runtime/consumer semantics land. Filename preservation alone cannot prove database recovery. The operator's reset and the historical WAL incident remain context, never success assertions.

Inspect actual pre-install shell preservation, explicit shell uninstall and application uninstall. Separate intentional destruction from state-preserving reinstall. Include live controlled connections, committed WAL-backed rows, SHM, rollback journals, backup/checkpoint/restore, interrupted cleanup/promotion, typed unreadable-database failures and original-evidence retention. Production records and recovered history remain read-only.

## Acceptance Criteria

1. The inventory/matrix name shell and application maintenance owners, filesystem/database write boundaries, connection requirements, backup/restore evidence, failure diagnostics and documented preservation versus destructive effects. findings-register.md and evidence_subtask_3.md rank maintenance findings with severity, isolated trigger, exact identities, original/recovered bytes and rows, root cause, production owner, reproduction definition and closure evidence. Verified no-finding areas and unavailable historical incident evidence remain explicit.
2. State-preserving pre-install cleanup retains every database, WAL, SHM, rollback journal and backup needed by the admitted recovery scenario, including committed rows written while a controlled connection remains open. Any demonstrated repair uses actual existing maintenance owners and reports partial cleanup/promotion/restore failure honestly. Explicit destructive shell/application uninstall retains its documented separate contract instead of silently becoming reinstall preservation.
3. Disposable SQLite regression fixtures create real schema and meaningful workflow/identity/phase/progress history. Tests assert PRAGMA integrity_check results and exact row/history content after reopen through actual pre-install preservation, checkpoint, rollback-journal recovery, interrupted cleanup/promotion and backup restore. Tests do not equate file presence or a copied .db alone with recoverability. Application and shell entry paths have outcome coverage where their removal semantics differ.
4. Corrupt/unreadable/unsupported recovery cases retain original evidence, return the owning typed failure or refusal and emit bounded diagnostics. Preserved backups and journals are never silently discarded to make a test appear recovered. evidence_subtask_3.md describes unrecoverable history as an evidenced limitation and does not fabricate prior progress or successful phase execution.
5. audit-coverage.md maps every upstream scope item, all seven deliverable groups and the state-related SKILL-414/417/418 requirements to current source, transition entries, named regression assertions, findings or explicit external limitations. All source-confirmed in-scope defects have a repair/test entry; unresolved correctness candidates have a scoped next verification action and cannot be recorded as completed repairs. Documentation, contracts and diagnostics match the final tree.
6. state-ownership-inventory.md, transition-matrix.md, findings-register.md and all three evidence files form one consistent audit trail. Each claimed closed finding distinguishes tree repair from pending or recorded validation evidence. Evidence includes exact affected identity, preserved original state and intended after-state; a baseline reset, dead process, archived spec or telemetry event cannot close a finding.
7. Maintenance and integrated regression definitions name their realistic bugs, use only disposable homes/databases and controlled processes, preserve prior real-bug/parity tests and include an explicit nonempty environment. The architecture checklist cites A1-A12/G1-G7 applicability/results, guards do not widen, and the final plan's execution instructions keep build, validate, history, review, install, commits and PR actions with their authorized owners.

## Non-goals

No live baseline reset or live recovery experiment, no deleting the user's journals/backups, no reconstructing unavailable history, no guarantee of preservation during an explicitly destructive uninstall. No packaging redesign or broad deployment refactor. Repair only demonstrated recovery/preservation/reporting defects and required documentation/contract inconsistencies.

## Dependency notes

Later agents use only the runtime-supplied named consumer projections. References to saved plans or evidence files do not grant extra source access, widen field allowlists or authorize fallback artifacts. The runtime owns including required material in its governed handoff.

Depends on subtask 2 and uses the same inventory, matrix and finding IDs. Current shell preservation already includes source directories, baseline-manifest.json, backups, *.db, *.db-wal, *.db-shm and *.db-journal, per the digest. Treat it as a behavior to verify with SQLite content, not a predetermined defect. Existing InstallerShellReconcileTest and smoke tests mainly establish replacement/idempotence, not journal/data integrity.

Optional historical examination uses original preserved evidence read-only if available. If unavailable, record missing evidence and proceed with disposable reproductions. It does not permit production mutation or claims that the lost parent history was reconstructed. Follow planning-decisions.md for isolation, schema changes and branch assumptions.

## Validation strategy

Implement authors tests and fixtures; audit inspects source and definitions. Only validate runs tests, installers and maintenance experiments under an explicit disposable home with controlled processes. No install touches the active runtime or user's home. Build/runtime owners supply packaged CLI/MCP fixtures where required. Validate captures integrity_check, exact rows/history, database reopen, preserved original backup/journal bytes and honest outcomes for failed recovery. Controlled crash/journal fixtures must be isolated and deterministic, with barriers and admitted process identity instead of sleeps.

Use InstallerShellReconcileTest, scripts/install_smoke_test.sh and SQLiteDatabaseSessionFactoryTypedFailureTest/DatabaseMigrationsTest as owner locations. DatabaseWriteMaintenanceRegressionTest remains readiness-probe coverage and is not substituted for recovery tests. Reuse SqliteTestDatabaseFixtures and SqliteDatabaseSessionFactoryTestSupport across modules, never import internal adapter implementations. Run the parent validation strategy's runtime, desktop, IntelliJ, config/lint, package parity and isolated installer checks under validate authority. Record actual executed evidence separately from this tree acceptance. Test-value obligations and evidence closure steps are saved in implementation_plan_subtask_3.md.

## Next path

The separately authorized runtime owns remaining validation evidence, exact-tree review, history, commit/push, PR and monitoring. This bundle neither launches those actions nor requires their receipts as tree acceptance criteria. Deferred command after separate authorization: `skill-bill goal SKILL-422`.

## Implementation Details

### Inputs and evidence policy

Consume spec_subtask_3_sqlite-maintenance-and-audit-closure.md and both earlier evidence sets. Preplan reports that reinstall preservation already protects database files and sidecars. Verify data readability/recoverability before deciding whether it needs a repair. The lost historical progress cannot be recovered from source or reset state. Preserve originals and report missing/unrecoverable evidence explicitly.

### Starting source map

- install.sh run_pre_install_uninstall invokes uninstall.sh with SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1. uninstall.sh remove_state_dir_preserving_source retains source directories, baseline-manifest.json, backups and database/WAL/SHM/journal patterns. Explicit shell uninstall instead removes the full state directory.
- runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/uninstall/SkillBillUninstallService.kt owns plan/apply and explicit stateRoot removal. Confirm its result/diagnostic semantics separately from shell preservation.
- runtime-kotlin/runtime-cli/src/repoTest/kotlin/skillbill/installer/InstallerShellReconcileTest.kt and scripts/install_smoke_test.sh are existing installer coverage leads. Their replacement/idempotence assertions do not prove SQLite history survives.
- SQLiteDatabaseSessionFactory and core/ops/ConnectionTransactions.kt own connection/transaction and typed failure seams. DatabaseMigrationsTest and SQLiteDatabaseSessionFactoryTypedFailureTest are existing recovery/admission leads. DatabaseWriteMaintenanceRegressionTest guards readiness probing rather than recovery integrity.
- Cross-module fixtures come from runtime-kotlin/runtime-infra/sqlite/src/testFixtures/kotlin/skillbill/infrastructure/sqlite/SqliteTestDatabaseFixtures.kt and SqliteDatabaseSessionFactoryTestSupport.kt. Use public test fixture functions rather than importing internal adapters.

All source facts are digest leads until later implement records current citations. Maintenance backups/promotion helpers not named in preplan must be identified under later authority and inventoried, not assumed absent.

### Implementation sequence

1. Extend inventory/matrix with every actual installer/uninstall/maintenance writer, its preservation/destructive intent, open-connection requirements, transaction/file boundary, partial failure/retry semantics and recovery diagnostic. Trace whether source/staging cleanup can enter the state database tree and which owner performs checkpoint, copy, promotion and restore.
2. Define isolated scenarios using an explicit disposable home, stateRoot and environment. Seed real current schema with distinct workflow, execution identity, accepted phase/ledger and parent progress history. Retain exact expected rows and historical evidence; unrelated empty schema or file names are insufficient.
3. Define a controlled writer that commits WAL-backed data while its connection stays open. The preservation operation must exercise the actual pre-install cleanup owner without touching the live runtime. If the full installer cannot isolate its target from the active runtime, use the actual cleanup entry with packaged/disposable prerequisites; never weaken isolation to obtain evidence.
4. Audit rollback journal/hot-journal recovery, checkpoint, backup restore and interrupted cleanup/promotion. Backup consistency must use an admitted SQLite snapshot/backup operation or a proven closed/checkpointed coherent set, never an arbitrary .db-only live copy. Original backup/journal bytes remain available when recovery fails.
5. For each demonstrated defect, repair the existing narrow maintenance owner with honest typed failure/diagnostics and recoverable partial state. Keep explicit destructive uninstall separate. A refused/failed destructive operation cannot report complete removal; a successful explicit destructive uninstall need not preserve its intentionally deleted database.
6. Add the named regressions below using meaningful real rows and PRAGMA integrity_check plus exact content/reopen assertions. Reuse disposable fixtures and admitted owned processes. Controlled crash uses barriers and precise ownership; do not inject a crash into production or the installed plan runtime.
7. Reconcile upstream scope and seven deliverable groups against all repaired owners. audit-coverage.md includes one mapping per requirement, source citation, matrix entry, regression/finding ID, limitation and execution status. Validate current state-related SKILL-414/417/418 implementation evidence; do not mark them accepted from an archived folder.
8. Update matching documentation/diagnostics only where behavior changed. Include terminal meanings, ownership/fencing, reader purity, finalization, isolation/retention and maintenance intent. New wire/schema contracts need the same owner/version/key/failure/parity discipline; do not bump contracts solely because the audit happened.
9. Finish evidence_subtask_3.md and the shared register. Inspect every claim for original-before evidence, repaired-after tree, named regression and later execution evidence. Source-only candidates remain candidates; confirmed internal defects require repair, and external limitations require exact scope/evidence. No unresolved internal correctness question becomes a completed repair.
10. Apply the architecture checklist and review repairs needed for the intended outcomes. Guard/baseline/exemption changes may only tighten. Leave command execution and receipt capture with validate and remaining runtime phases. Do not publish, launch, install to the real home or write history/commit/PR artifacts yourself.

### Test obligations

| ID and criteria | Realistic bug | Boundary and outcome assertion |
| --- | --- | --- |
| T3-01, AC2/3 | Pre-install cleanup preserves .db but removes committed WAL data or history while an owned writer is open. | Actual preservation path under disposable home retains required coherent recovery files; reopened production store returns exact identity/phase/progress rows and integrity_check is ok. |
| T3-02, AC2/3 | Interrupted cleanup/promotion or checkpoint loses recoverable rows or reports success for a partial result. | Deterministic fault point asserts honest failure, retained originals and successful admitted recovery/reopen with exact committed rows; rollback leaves no fabricated history. |
| T3-03, AC3/4 | Restore selects a damaged/incomplete backup or loses hot-journal recovery while files still exist. | Disposable journal/backup boundary asserts integrity/content after successful recovery and owning typed error plus original evidence on unreadable input. |
| T3-04, AC2/3 | Application uninstall silently applies shell preservation semantics or reports removal when state persists after failure. | Explicit disposable shell/application entry outcomes match documented destructive intent and honest partial failure; reinstall remains state-preserving. |
| T3-05, AC4/5 | Corrupt legacy recovery or unavailable segment history is silently skipped or rewritten as successful completion. | Existing typed parse/recovery boundary preserves bytes and emits exact diagnostic; audit evidence marks unrecoverable/missing history without fabricated rows. Reuse earlier corrupt-record tests where they already prove the branch. |

These groups need the smallest coverage that reaches actual separate failure boundaries. Preserve readiness-probe, source replacement/idempotence and prior regression assertions. Do not write a file-presence test as a substitute for integrity/content recovery. Documentation consistency uses source inspection; it needs no test mirroring prose. Governed contract parity remains mandatory.

### Audit coverage mapping

| Upstream deliverable | Primary owner and required artifact |
| --- | --- |
| 1. Source-cited inventory and matrix | Subtask 1 establishes all families/stores; subtasks 2/3 complete aggregate/consumer and maintenance entries. state-ownership-inventory.md and transition-matrix.md. |
| 2. Ranked reproducible findings and no-finding areas | Every pass updates findings-register.md and evidence_subtask_N.md using shared identity/evidence/classification fields. |
| 3. Confirmed repairs and boundary verification | Subtasks 1/2/3 repair their demonstrated defects and author named production-boundary regressions. Later validate supplies execution evidence and verified closure. |
| 4. Durable, failure, ownership, resume, finalization, legacy and consumer tests | T1-01 through T1-08 and T2-01 through T2-11, with existing tests reused. |
| 5. Truthful complete/running/administrative state | Subtask 1 terminal/fencing rules and subtask 2 parent/provenance/shared consumer rules. |
| 6. SQLite maintenance recovery | T3-01 through T3-05 with real schema, integrity/content/reopen and preservation/destructive distinction. |
| 7. Matching contracts and observable diagnostics | All passes retain typed evidence and owner parity; subtask 3 reconciles docs and audit-coverage.md. |

The later coverage file must also map each detailed scope item, not only these seven group labels. Unsupported vocabulary and unavailable platform/history facts need an explicit limitation entry. Before marking an area verified, cite the real production boundary and distinguish source inspection from executed evidence.

### Validation handoff

Only validate executes full checks, SQLite experiments and installer smoke under controlled disposable environments. Runtime/build owners supply the proper package fixtures. Capture command/result evidence, PRAGMA integrity_check output, exact before/after rows, reopen results, retained evidence and diagnostics. Validation failure belongs to the owning repair path; it cannot be dismissed because a filename exists or a reset made the board empty. Remaining authorized runtime phases own exact-tree review, history, commit, push, PR and monitoring.
