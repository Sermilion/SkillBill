---
name: bill-generic-code-review-persistence
description: Technology-neutral persistence review for atomicity, consistency, migrations, ownership, and recovery.
internal-for: skill-bill
---

# Generic Persistence Review

## Focus

Check transaction boundaries, concurrent writers, uniqueness, ordering, partial writes, migrations, backward reads, deletion, retention, and recovery after interruption. Verify durable state has one owner and derived state can be rebuilt safely.

## Ignore

- Storage preferences without a consistency, migration, or durability consequence.

## Applicability

Use when changes affect transactions, storage, migrations, atomicity, or recovery.

## Project-Specific Rules

### Persistence Rules

- Require `unit-of-work-boundary` to commit all invariant-related writes atomically; split commits can leave invalid durable state after a failure.
- Verify `concurrent-update-guard` detects lost updates through locking, versions, or compare-and-set semantics; unchecked writers create data races.
- Reject `uniqueness-check-before-write` as the sole enforcement because concurrent requests can bypass it and corrupt ownership guarantees.
- Ensure `migration-forward-step` preserves readable data for every supported deployed version; destructive rewrites can break mixed-version operation.
- Require `migration-restart-marker` or transactional execution for multi-step changes; interruption without recovery can leave the schema invalid.
- Verify `durable-order-key` is explicit for records whose processing sequence matters; implicit storage order creates replay and consistency failures.
- Reject `delete-cascade-policy` changes without checking retention and ownership because unintended deletion causes irreversible data loss.
- Ensure `derived-state-rebuild` can reconstruct caches and projections from authoritative records; unrecoverable derived data turns corruption into outage.
- Require `write-retry-contract` to classify conflicts separately from permanent validation failures; blind retries can duplicate effects or extend lock timeouts.
- Verify `serialization-version-tag` supports historical records still present in storage; unversioned shape drift can crash readers during rollout.
- Reject `cross-store-commit` claims of atomicity without an outbox, saga, or recovery record because partial success exposes inconsistent data.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
