---
name: bill-typescript-code-review-persistence
description: Use when reviewing TypeScript ORM and query clients, transactions, migrations, connection lifecycles, durable serialization, and consistency.
internal-for: skill-bill
---

# Persistence Review Specialist

## Focus

- ORM, query-builder, generated client, raw query, and result-mapping behavior
- Transaction ownership, isolation, retries, concurrency, and external side effects
- Migrations, mixed versions, connections, batching, and durable representations

## Ignore

- ORM-versus-SQL preference when both preserve the repository contract
- Query formatting without correctness or performance impact
- Unrelated schema redesign

## Applicability

Apply to the database engine, driver, ORM or query builder, migration tool, deployment topology, and consistency model detected in the repository.

## Project-Specific Rules

### Client and Data Mapping Rules

- Generated clients such as `PrismaClient`, Drizzle, TypeORM, Kysely, or repository adapters must match the deployed schema; reject compile-time confidence when a stale client returns invalid data. Verify `migration rehearsal` before reporting this failure.
- Reads and writes must preserve the distinction among omitted, `undefined`, `null`, database NULL, and defaults; prevent accidental clearing or retention of durable fields. Verify `migration rehearsal` before reporting this failure.
- Dates, decimals, bigint, enums, JSON, and identifiers must use explicit driver-to-domain conversion; flag precision loss, timezone drift, or serialization corruption. Verify `migration rehearsal` before reporting this failure.
- Tenant, ownership, authorization, and soft-delete predicates must reach every query path; reject a data-exposure failure hidden behind a typed repository method. Verify `migration rehearsal` before reporting this failure.

### Transaction and Concurrency Contract Rules

- One component must own the `transaction` boundary for each business operation and await all database work before commit; prevent partial state or use-after-release failures. Verify `migration rehearsal` before reporting this failure.
- Isolation, locks, compare-and-swap conditions, and unique constraints must match the concurrency invariant; flag lost updates, duplicate creation, or write skew with a concrete interleaving. Verify `migration rehearsal` before reporting this failure.
- Transaction retries must recognize driver-specific aborts and keep external calls outside or idempotent; reject a retry that duplicates messages, payments, or other side effects. Verify `migration rehearsal` before reporting this failure.
- Batch and pagination operations must preserve ordering, limits, and partial-failure behavior; prevent memory pressure, skipped rows, or repeated processing at realistic cardinality. Verify `migration rehearsal` before reporting this failure.

### Migration and Resource Failure Rules

- Migrations must support mixed application versions and realistic table sizes through the repository migration tool; reject destructive ordering, long locks, or incompatible reads during rollout. Verify `migration rehearsal` before reporting this failure.
- Backfills must be resumable, bounded, observable, and safe under concurrent writes; prevent data loss or inconsistent completion after interruption. Verify `migration rehearsal` before reporting this failure.
- Pools, clients, cursors, and subscriptions must follow application, request, transaction, or worker lifecycle; flag connection starvation or shutdown hangs from leaked resources. Verify `migration rehearsal` before reporting this failure.
- Persistence evidence must include affected integration tests, migration rehearsal, query plans, or concurrency fixtures as appropriate; reject a consistency claim supported only by TypeScript types. Verify `migration rehearsal` before reporting this failure.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
