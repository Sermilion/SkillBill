## [2026-10-22] SKILL-435 subtask 2: structured diagnosis and evidence-based investigation
Areas: runtime-engine diagnostics/block, featuretask and goalrunner; runtime-domain blockincident and validation; runtime-contracts; runtime-ports; runtime-infra/sqlite and workflow validation; runtime-core; runtime-mcp; orchestration/contracts; docs
- MEDIUM feature. Replaced the default investigator with bounded saved-record evidence collection, deterministic classification and optional agent enrichment. Diagnosis records include uncertainty, prerequisites, proposed recovery effects and explicit degradations.
- The collector reads saved workflow, phase ledger, retained output, briefing, attempt and runtime facts without reading project files. Budgets are 16 items, 8 KiB per item and 64 KiB total; truncation preserves full-content hashes and unavailable sources remain visible.
- Typed classification and diagnosis validation keep unsupported causes unknown. Project attribution requires structured causal evidence; legacy findings without failure_subject remain unknown. Raw reasons never populate summary or whatHappened.
- Added exact retained-output reads with identity and digest checks. reusable
- Migration 77 adds immutable diagnosis, evidence and prerequisite history plus quarantine, with desktop change revisions. Owner/generation-fenced settlement stores the record atomically and stamps the post-settle revision. reusable
- Agent output may add only a bounded suspected cause citing collected references. Every started pass records READ_ONLY_NOT_ENFORCED because the launcher supplies no enforcement fact; deterministic diagnoses survive unavailable or rejected agent enrichment.
- Limits: no credentials failure literal exists, so no credentials prerequisite is inferred. Recovery execution, reporting, telemetry and presentation remain later-subtask work. Diagnosis storage is relational; block-incident-schema.yaml stays at 0.2.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
