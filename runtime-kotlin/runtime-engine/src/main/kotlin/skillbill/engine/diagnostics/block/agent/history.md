## [2026-10-23] SKILL-435 subtask 4: sanitized product reports and correlated telemetry
Areas: runtime-engine diagnostics/block; runtime-domain blockincident/report; runtime-ports; runtime-contracts; runtime-application telemetry; runtime-infra/sqlite, http/productissue and host; runtime-core; docs/telemetry-privacy.md
- MEDIUM feature. Eligible post-rollout Skill Bill defect diagnoses create local reports, deduplicate by normalized-cause fingerprint and link repeated incidents.
- A pure allowlist sanitizer records field outcomes, bounds evidence to 8 items of 512 characters and bodies to 16 KiB, redacts secrets and personal paths, and withholds residual-secret drafts. reusable
- Append-only migrations 80, 81 and 82 persist reports, links, submission intent, unresolved creates, retry accounting and pending comment snapshots. DesktopReadSchema is 82.
- Delivery processes at most four reports per entry under per-report ownership and database identity fencing, with short transactions around network calls. Search reconciles ambiguous creates; incident markers deduplicate comments.
- Pending comments retain sanitized recovery evidence. Retry state survives restart; missing credentials retain LOCAL_ONLY reports that become eligible again when credentials appear.
- The GitHub adapter targets product repository 1183084383 and reuses environment or gh credentials without prompting or storing secrets. The release adapter shares the numeric repository identity.
- Diagnosis, terminal recovery and report telemetry use actual saved identities through the outbox. Off queues nothing; anonymous omits report ids and issue URLs; availability exposes exact lookup identifiers. reusable
- Reporting and telemetry failures emit diagnostics independently of diagnosis and recovery. Limits: corrupt report digests fail loudly on read; optional build metadata is omitted when unavailable; direct PostHog links and report presentation remain outside this subtask.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-10-23] SKILL-435 subtask 3: typed, fenced, user-initiated incident recovery
Areas: runtime-engine diagnostics/block, featuretask, goalrunner and work/control; runtime-domain blockincident and phase ledger; runtime-ports; runtime-infra/sqlite; runtime-contracts; runtime-core; runtime-cli; desktop-tray runtime adapter; docs
- MEDIUM feature. Added a pure eligibility policy and read-only preview with prerequisite instructions. Recovery rejects stale revisions, changed generations, live or unverifiable owners, exhausted budgets, operator decisions and gate failures.
- Added durable recovery attempts with SHA-256 idempotency keys, owner-fenced transitions and saved launch targets. Migration 78 adds the attempt table and desktop revision triggers; DesktopReadSchema is 78.
- Recovery applies only typed catalog actions through goal and standalone reopen primitives, verifies the blocked phase cleared and records failures without replacing occurrence or diagnosis evidence.
- Resume claims APPLIED to RESUMING before launching the saved goal or standalone invocation through WorkRunActions. Repeated requests reuse the attempt; interrupted reconciliation never reapplies actions or launches work.
- Added work recover preview/apply commands, typed work-control ports and results, JSON status and rejection output, command documentation and desktop result mapping.
- Phase-ledger retention preserves retry and settlement anchors. Recovery retry metadata preserves attempt budgets, completed phases, mandates and checkpoints across resume. reusable
- The durable attempt repository and pure eligibility policy support later reporting and presentation work. reusable
- Limits: recovery requires user activation; unsupported prerequisite probes remain unmet. Resume failure leaves normal launch available. Reporting, telemetry and recovery presentation remain later-subtask work; block-incident-schema.yaml stays at 0.2.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

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
