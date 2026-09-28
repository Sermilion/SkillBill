---
name: bill-python-code-review-persistence
description: Review Python persistence code including SQLAlchemy, Django ORM, raw SQL, migrations, transactions, locking, sessions, and consistency.
internal-for: skill-bill
---

# Python Persistence Review

Review data correctness and persistence lifecycle.

## Focus

- ORM and raw SQL behavior, migrations, transactions, locking, sessions, bulk writes, tenant scope, and cross-store consistency

## Ignore

- Query style preferences without a correctness, consistency, durability, or material performance consequence
- Schema or ORM code outside the changed transaction and data ownership boundary

## Applicability

Use this specialist for SQLAlchemy, Django ORM, peewee, raw SQL, query builders, migrations, repositories, connection pools, sessions, outboxes, and cache invalidation.

## Project-Specific Rules

### Python Persistence Rules

- Require explicit, non-overlapping responsibility ownership for SQLAlchemy `Session` or `AsyncSession` creation, transaction completion, rollback, and close. Different components may own different responsibilities only when each responsibility has one unambiguous owner; overlap or gaps risk partial commits and connection leaks after exceptions.
- Verify `expire_on_commit` and lazy relationship access before returning ORM objects beyond session scope; detached loads can fail after a response has begun.
- Account for SQLAlchemy `autoflush` before `select` or relationship queries and require `no_autoflush` only around an intentional invariant; premature writes can trigger invalid constraints.
- Require one `AsyncSession` per concurrent task and reject sharing a session across `asyncio.gather`, `asyncio.TaskGroup`, `asyncio.create_task`, or equivalent concurrently executing work; session state is mutable and races can corrupt transaction ordering.
- Require explicit isolation and locking through SQLAlchemy `with_for_update`, Django `QuerySet.select_for_update()`, version columns, database constraints, or atomic Django `F()` updates for contested mutations; unchecked read-modify-write loses data under concurrency.
- Require the whole transaction to roll back before a bounded retry of classified serialization failures or deadlocks; independently require a uniqueness constraint, durable deduplication record, idempotency key, or demonstrably idempotent operation only for external effects or an ambiguous commit outcome, because a fully rolled-back database mutation can be replayed safely while an uncertain effect can be duplicated.
- When Django is detected, verify nested `transaction.atomic` savepoints and `transaction.on_commit` callbacks preserve durable ordering; external effects before outer commit risk phantom delivery.
- Verify `bulk_create`, `bulk_update`, and `QuerySet.update` account for bypassed `save`, signals, validation, and `auto_now`; reliance on skipped hooks leaves contract data invalid.
- Require a trusted tenant predicate on every tenant-owned SQLAlchemy, Django ORM, or raw SQL read and write, including upserts, and require inserts and bulk inserts to assign the tenant key from trusted request or job context rather than payload data, unless verified database-per-tenant, schema-per-tenant, or session-enforced isolation is established before queries and cannot be bypassed by raw SQL, background work, or reused sessions; unverified scoping or assignment creates cross-tenant exposure or data loss.
- Require Alembic or Django migrations to use expand-and-contract ordering and deploy-compatible defaults; incompatible schema transitions break mixed-version application instances.
- Require `RunPython` or Alembic data migrations and backfills to be resumable, idempotent, bounded, and reversible where possible; interruption otherwise leaves corrupt partial state.
- Verify production index creation uses the database's non-blocking mechanism when table size makes locks unsafe; for PostgreSQL `CONCURRENTLY`, require Django `AddIndexConcurrently` in a migration with `atomic = False` or an Alembic `autocommit_block`, plus detection and recovery of an invalid index after interruption, because running concurrent DDL inside a transaction or leaving a failed index behind breaks deployment availability.
- Require an atomic outbox or equivalent transaction-owned record for event publication and cache-consistency work; cache updates must use durable delivery and reconciliation, or a bounded-staleness design with versioned keys, explicit TTL, and repair, because an after-commit invalidation can fail after durable data changes and leave stale reads indefinitely.
- Require cardinality-appropriate eager loading: Django `select_related` or SQLAlchemy `joinedload` for scalar relationships, and `prefetch_related` or `selectinload` for collections where appropriate. Demand bounded query-count evidence proving query growth does not scale with result cardinality; uncontrolled N+1 access causes latency and resource failure.
- Treat projections such as Django `values` or `values_list`, SQLAlchemy `load_only`, and selected columns as remedies for unused fields and hydration cost, not substitutes for relationship loading; conflating the two leaves either query multiplication or over-fetch unresolved.
- Require unique constraints and explicit `IntegrityError` conflict mapping for idempotent writes; check-then-insert races can corrupt contract data with duplicates.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
