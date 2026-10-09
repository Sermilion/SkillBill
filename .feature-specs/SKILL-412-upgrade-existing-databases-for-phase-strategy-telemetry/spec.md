# SKILL-412 - upgrade-existing-databases-for-phase-strategy-telemetry

## Mode

single_spec

## Intended Outcome

SKILL-412 Upgrade existing databases for phase strategy telemetry

Fix the telemetry schema upgrade bug and repair the affected local installation using the corrected runtime migration. This is separate from the planned SKILL-411 plugin feature; do not implement or alter that feature.

Observed failure:
Standalone plan SKILL-411 completed successfully, but finish telemetry raised SQLITE_ERROR no such column: phase_strategies in /Users/braian.gapur/.skill-bill/review-metrics.db.
Read-only inspection confirms user_version 48 and migration ensure-schema-columns-and-heals (version 39) already recorded as applied on 2026-09-22. feature_task_runtime_sessions contains launched_models and resolved_agent_ids but lacks phase_strategies and phase_strategy_availability.
Commit c925d5afc (SKILL-404 Phase Strategy Telemetry) added these two nullable TEXT columns to DatabaseColumnMigrationsEnsureFeatureTask.kt, reached through version 39's ensure-schema-columns-and-heals. DatabaseMigrations.apply skips already-applied migration names, so existing databases never receive newly added columns. DatabaseMigrationEntries.kt currently ends at version 48 add-goal-no-change-reason. This repeats the upgrade failure pattern recently fixed for no_change_reason.

Required outcomes:
Add a new uniquely named append-only schema migration to upgrade existing databases with both telemetry columns. Do not rewrite historical migration ledger records, reset the user's database, or fabricate historical strategy measurements. Preserve existing rows and truthful SQL NULL values for unmeasured history. Migration must be safe on fresh databases and databases where the columns already exist, and reopening must be idempotent.
Add focused regression coverage reproducing a prior database whose ledger already contains ensure-schema-columns-and-heals and all current migrations but which lacks the telemetry columns. Prove runtime upgrade creates both columns and permits actual finish telemetry persistence/emission without SQLITE_ERROR. Include fresh-schema and repeat-open behavior where appropriate.
Check directly related schema changes from phase strategy telemetry for other omitted upgrades.
Run appropriate required checks according to repository instructions. After the fix is verified, install the corrected local runtime with the established local installation process and exercise a supported runtime startup path to upgrade /Users/braian.gapur/.skill-bill/review-metrics.db. Back up the affected database consistently before upgrading. Verify both columns exist and telemetry finish persistence works using focused regression/smoke evidence. Do not manually ALTER production database as a substitute for shipping the migration.
Leave SKILL-411's spec bundle intact and do not run its implementation. Report the cause, fix, validation and installed-database upgrade outcome. The active-fix branch condition in AGENTS.md has ended: main history contains d06074bc7 Merge branch base/SKILL-380-phase-slot-strategies.

## Overview

SKILL-412 Upgrade existing databases for phase strategy telemetry

Fix the telemetry schema upgrade bug and repair the affected local installation using the corrected runtime migration. This is separate from the planned SKILL-411 plugin feature; do not implement or alter that feature.

Observed failure:
Standalone plan SKILL-411 completed successfully, but finish telemetry raised SQLITE_ERROR no such column: phase_strategies in /Users/braian.gapur/.skill-bill/review-metrics.db.
Read-only inspection confirms user_version 48 and migration ensure-schema-columns-and-heals (version 39) already recorded as applied on 2026-09-22. feature_task_runtime_sessions contains launched_models and resolved_agent_ids but lacks phase_strategies and phase_strategy_availability.
Commit c925d5afc (SKILL-404 Phase Strategy Telemetry) added these two nullable TEXT columns to DatabaseColumnMigrationsEnsureFeatureTask.kt, reached through version 39's ensure-schema-columns-and-heals. DatabaseMigrations.apply skips already-applied migration names, so existing databases never receive newly added columns. DatabaseMigrationEntries.kt currently ends at version 48 add-goal-no-change-reason. This repeats the upgrade failure pattern recently fixed for no_change_reason.

Required outcomes:
Add a new uniquely named append-only schema migration to upgrade existing databases with both telemetry columns. Do not rewrite historical migration ledger records, reset the user's database, or fabricate historical strategy measurements. Preserve existing rows and truthful SQL NULL values for unmeasured history. Migration must be safe on fresh databases and databases where the columns already exist, and reopening must be idempotent.
Add focused regression coverage reproducing a prior database whose ledger already contains ensure-schema-columns-and-heals and all current migrations but which lacks the telemetry columns. Prove runtime upgrade creates both columns and permits actual finish telemetry persistence/emission without SQLITE_ERROR. Include fresh-schema and repeat-open behavior where appropriate.
Check directly related schema changes from phase strategy telemetry for other omitted upgrades.
Run appropriate required checks according to repository instructions. After the fix is verified, install the corrected local runtime with the established local installation process and exercise a supported runtime startup path to upgrade /Users/braian.gapur/.skill-bill/review-metrics.db. Back up the affected database consistently before upgrading. Verify both columns exist and telemetry finish persistence works using focused regression/smoke evidence. Do not manually ALTER production database as a substitute for shipping the migration.
Leave SKILL-411's spec bundle intact and do not run its implementation. Report the cause, fix, validation and installed-database upgrade outcome. The active-fix branch condition in AGENTS.md has ended: main history contains d06074bc7 Merge branch base/SKILL-380-phase-slot-strategies.

## Acceptance Criteria

1. SKILL-412 Upgrade existing databases for phase strategy telemetry Fix the telemetry schema upgrade bug and repair the affected local installation using the corrected runtime migration. This is separate from the planned SKILL-411 plugin feature; do not implement or alter that feature. Observed failure: Standalone plan SKILL-411 completed successfully, but finish telemetry raised SQLITE_ERROR no such column: phase_strategies in /Users/braian.gapur/.skill-bill/review-metrics.db. Read-only inspection confirms user_version 48 and migration ensure-schema-columns-and-heals (version 39) already recorded as applied on 2026-09-22. feature_task_runtime_sessions contains launched_models and resolved_agent_ids but lacks phase_strategies and phase_strategy_availability. Commit c925d5afc (SKILL-404 Phase Strategy Telemetry) added these two nullable TEXT columns to DatabaseColumnMigrationsEnsureFeatureTask.kt, reached through version 39's ensure-schema-columns-and-heals. DatabaseMigrations.apply skips already-applied migration names, so existing databases never receive newly added columns. DatabaseMigrationEntries.kt currently ends at version 48 add-goal-no-change-reason. This repeats the upgrade failure pattern recently fixed for no_change_reason. Required outcomes: Add a new uniquely named append-only schema migration to upgrade existing databases with both telemetry columns. Do not rewrite historical migration ledger records, reset the user's database, or fabricate historical strategy measurements. Preserve existing rows and truthful SQL NULL values for unmeasured history. Migration must be safe on fresh databases and databases where the columns already exist, and reopening must be idempotent. Add focused regression coverage reproducing a prior database whose ledger already contains ensure-schema-columns-and-heals and all current migrations but which lacks the telemetry columns. Prove runtime upgrade creates both columns and permits actual finish telemetry persistence/emission without SQLITE_ERROR. Include fresh-schema and repeat-open behavior where appropriate. Check directly related schema changes from phase strategy telemetry for other omitted upgrades. Run appropriate required checks according to repository instructions. After the fix is verified, install the corrected local runtime with the established local installation process and exercise a supported runtime startup path to upgrade /Users/braian.gapur/.skill-bill/review-metrics.db. Back up the affected database consistently before upgrading. Verify both columns exist and telemetry finish persistence works using focused regression/smoke evidence. Do not manually ALTER production database as a substitute for shipping the migration. Leave SKILL-411's spec bundle intact and do not run its implementation. Report the cause, fix, validation and installed-database upgrade outcome. The active-fix branch condition in AGENTS.md has ended: main history contains d06074bc7 Merge branch base/SKILL-380-phase-slot-strategies.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection. Only an explicit unresolved tracker reference without requirements needs lookup through its connected tracker before planning. Use the returned requirements, not the URL title. If that lookup fails, block with the returned reason before implementation; never infer or substitute requirements.

## Non-Goals

- None

## Validation Strategy

Run the repository's required checks and verify every supplied acceptance criterion.
