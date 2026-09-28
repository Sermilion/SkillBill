---
name: bill-php-code-review-persistence
description: Use when reviewing PHP database access, ORM lifecycles, transactions, locking, migrations, and tenant-safe data integrity.
internal-for: skill-bill
---

# PHP Persistence Review Specialist

Review durable-state behavior and identify concrete loss, inconsistency, isolation, or query failures.

## Focus

- PDO and repository-owned SQL
- Doctrine and Eloquent when detected
- Transactions, concurrency, tenant scope, migrations, and streaming

## Ignore

- ORM preferences without a correctness or performance consequence
- Doctrine or Eloquent rules in repositories that do not own those packages

## Applicability

Apply `PDO` rules to direct database access. Apply Doctrine guidance only with `doctrine/orm` evidence, and Eloquent guidance only with `illuminate/database` or Laravel-owned models and configuration.

## Project-Specific Rules

### PHP Persistence Correctness Rules

- Require `PDO::ATTR_ERRMODE` to surface query failures as exceptions or an equally checked result; ignored `false` returns create a data-consistency failure.
- Verify `PDOStatement::bindValue()` uses the intended `PDO::PARAM_*` type for booleans, integers, and large identifiers; driver coercion creates incorrect durable data.
- Reject interpolation of values or identifiers into SQL unless identifiers come from a strict allowlist; unsafe composition creates injection exposure.
- Ensure transaction ownership covers every dependent write and rolls back on `Throwable`; catching only `Exception` causes partial-data loss.
- Require locking or an atomic predicate for read-modify-write operations using `SELECT ... FOR UPDATE`, version columns, or equivalent; concurrent requests otherwise lose updates.
- Verify retry logic recognizes driver-specific deadlock or serialization codes and reruns the whole transaction; partial replay can corrupt data.
- Require Doctrine's closed `EntityManager` to be replaced or reset after rollback; permit `clear()` only while the manager remains open and its managed state is stale, or later persistent jobs fail writes or leak invalid entity state.
- Reject Doctrine lazy proxies escaping the request or serialization boundary; later access can fail on a detached entity or trigger unbounded queries.
- Require deliberate `flush()` placement around aggregate invariants and dispatched work; early flushing creates a consistency failure.
- Verify Eloquent models protect `fillable` or `guarded` attributes at mass-assignment boundaries; unchecked `create($input)` enables authorization exposure.
- Ensure Eloquent global scopes and Doctrine filters enforce tenant ownership on reads, while every insert and identity-based update proves ownership explicitly and uses database constraints where possible; query filters do not protect all write paths, so relying on them can create cross-tenant mutations.
- Reject casts, observers, accessors, or model events that hide externally visible writes without transaction ownership; implicit effects produce a retry failure.
- Require migration ordering and reversible expand/contract compatibility with concurrently deployed application versions defined by repository rollout or deployment policy; use `composer.json` only for PHP runtime and dependency compatibility, or destructive schema changes can break mixed-version live deployments.
- Verify indexes serve actual Doctrine `QueryBuilder`, Eloquent builder, or SQL predicates and ordering; missing support causes a timeout failure.
- Reject per-row access to lazy Doctrine associations or Eloquent relationships; use bounded Doctrine `JOIN FETCH` queries, Eloquent eager loading, or batching where cardinality permits, because repeated lazy loads create production timeout failures.
- Require cursor and `Generator` consumers to close statements and avoid writes on a connection with an active unbuffered result; leaked resources can block the worker.
- Verify timestamps, decimals, JSON, and enum mappings match the selected database driver and PHP types; round-trip drift corrupts durable values.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
