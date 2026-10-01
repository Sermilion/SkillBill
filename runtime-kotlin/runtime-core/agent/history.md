## [2026-10-01] SKILL-389 subtask 2: restore runtime-core test placement under skillbill.di
Areas: runtime-kotlin/runtime-core/test/skillbill.di.workflow, runtime-kotlin/runtime-core/test/skillbill.di.featurespec, runtime-kotlin/runtime-core/test/skillbill.di.review, runtime-kotlin/runtime-core/test/skillbill.di.core, runtime-kotlin/runtime-core/repoTest/skillbill.architecture, runtime-kotlin/runtime-core/skillbill.di.core
- Relocated 11 test files into existing composition packages, including folding di.absent and di.runtime into di.core. Preserved test logic and updated package declarations, imports and the existing support-file suppression path.
- Followed composition ownership and existing-package placement. Added one method to RuntimeCompositionGuardArchitectureTest and registered its placement rule in PrincipleEnforcementInventory.
- Added a reusable placement scanner in ArchitectureScanSupport. It requires valid column-zero declarations, accepts skillbill.di and its descendants, and reports paths for invalid namespaces and retired packages; the guard rejects empty scans and exercises synthetic violations.
- Limits: this guard covers runtime-core/src/test only. No new architecture-test class, baseline row, exemption or runtime contract change; ancillary changes remove stale imports and apply formatting.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-10-01] SKILL-389 subtask 1: repair regressed guards and remove composition forwarders
Areas: runtime-kotlin/runtime-core/skillbill.di.core, runtime-kotlin/runtime-core/repoTest/skillbill.architecture, runtime-kotlin/runtime-core/test/skillbill.di, runtime-kotlin/runtime-cli/test/skillbill.cli
- Removed raw-map assertions about prose, retired machinery and annotations; kept the inner-layer raw-map rule and its rejection fixtures.
- Anchored shared package/import scans at column 0, restored alias-owner and allowed-import controls, and added a regression for indented fixture text. The shared scanner remains reusable.
- RuntimeComponent now owns repository-root, transport and database providers directly. The database factory receives the bound RuntimeVersion; RuntimeBootstrapBindings keeps only runtimeContext.
- Removed goalRunnerManifestStore, goalRunnerWorkflowOutcomeStore and telemetryConfigStorePort exports and their inventory pins. Snapshot and typed-error tests use providers; the CLI no-manifest fixture uses GoalRunnerManifestStoreDefaults.
- Followed single-root composition and deletion of forwarding layers. Added no module, dependency, architecture-test class, baseline row or exemption.
- Limits: the three component exports are removed; wire output and database schema are unchanged. Test relocation and its placement guard remain with subtask 2.
Feature flag: N/A
Acceptance criteria: 8/8 implemented
