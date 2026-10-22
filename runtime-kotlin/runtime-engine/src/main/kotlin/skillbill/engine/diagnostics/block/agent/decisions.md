## [2026-10-23] Preserve retry budgets through recovery ledger evidence
Context: Recovery reopens a blocked goal or standalone phase without permission to bypass attempt limits or erase completed work.
Decision: Record the admitted recovery attempt on retry metadata, preserve its budget, and retain retry and subsequent settlement anchors when bounding the phase ledger.
Reason: Resume must reconstruct the same retry accounting after persistence and ledger trimming. Reopening a phase must not grant a fresh attempt budget or discard the evidence that controls it.

## [2026-10-23] Claim resume separately from applying recovery
Context: Repeated requests, concurrent callers and interrupted processes can observe recovery after the block clears but before launch settles.
Decision: Apply catalog actions under database fencing, then claim APPLIED to RESUMING before launching the attempt's saved target outside the transaction. Reconciliation observes and settles interrupted work without applying or launching it.
Reason: The idempotency key prevents duplicate actions, while a separate resume claim prevents duplicate launches. The saved target preserves the intended goal or standalone invocation even when the request names a parent work item.

## [2026-10-23] Keep recovery progress in relational attempts
Context: Recovery needs durable progress, ownership and failure detail while the incident already exposes a stable recovery summary.
Decision: Store detailed attempts in an append-only SQLite migration with a unique incident/action/revision key. Keep the existing incident recovery enum and block-incident-schema.yaml at 0.2.
Reason: An attempt is a relational record rather than a new contract document. Separate progress records preserve the incident summary contract and original occurrence and diagnosis while supporting retries and outcome history.

## [2026-10-22] Keep agent enrichment subordinate to typed diagnosis
Context: Investigation consumes untrusted evidence and may use the saved run's agent, but the launcher reports no read-only enforcement fact.
Decision: Parse exactly suspected_cause and evidence_refs, require collected references, and preserve category, prerequisites and recovery action. Every started pass records READ_ONLY_NOT_ENFORCED and yields a partial diagnosis.
Reason: Model suggestions are evidence rather than executable authority. A read-only launch request alone cannot prove enforcement, and failed enrichment must preserve the deterministic diagnosis.
Revisit when: The launcher exposes a trustworthy provider enforcement fact.

## [2026-10-22] Require typed causal evidence for project attribution
Context: A failed quality gate can reflect project code, runtime infrastructure or unavailable evidence. Generic task headers and log text do not establish ownership.
Decision: Classify project failures only from explicit typed project attribution. Keep legacy findings without failure_subject unknown and document the exact ordered failure-code rules.
Reason: Guessing ownership from command names, exit codes or arbitrary messages would turn uncertainty into an unsupported diagnosis.

## [2026-10-22] Stamp diagnosis revisions during fenced settlement
Context: Claim renewals advance the incident revision while investigation runs, so the revision seen at claim time can become stale.
Decision: The investigator returns a draft and terminal state. The worker owns renewal and fallback handling; the repository atomically settles under the owner/generation fence and stamps the resulting incident revision on the record.
Reason: The repository knows the actual post-settle revision. Keeping persistence in the worker preserves its existing lease and failure handling without duplicating ownership logic in the investigator.

## [2026-10-22] Store diagnoses as validated relational history
Context: Subtask 2 adds structured diagnosis history to the existing durable incident lifecycle. The spec's preplan decision D3 assigns domain validation to this record.
Decision: Store immutable diagnosis and child rows in SQLite, validate writes and readback, and quarantine corrupt revisions separately. Keep block-incident-schema.yaml at 0.2.
Reason: The diagnosis is a relational record rather than a YAML document. Revision-keyed quarantine can isolate a corrupt diagnosis while preserving other revisions of the same incident.
