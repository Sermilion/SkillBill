---
name: bill-php-code-review-architecture
description: Use when reviewing PHP ownership, dependency direction, composition roots, and cross-boundary lifecycle design.
internal-for: skill-bill
---

# PHP Architecture Review Specialist

Review structural decisions only when they create concrete coupling, lifecycle, consistency, or operability risk.

## Focus

- Composer packages, PSR-4 namespaces, modules, and dependency direction
- Framework composition roots and container lifetimes
- HTTP, CLI, queue, event, transaction, generator, and asynchronous boundaries

## Ignore

- Pattern-name preferences that do not change ownership or behavior
- Demands for elaborate layering in simple, coherent CRUD code

## Applicability

Infer the established architecture from `composer.json`, namespaces, service configuration, entry points, and adjacent code. Apply Symfony, Laravel, Doctrine, or asynchronous guidance only when those dependencies and boundaries are repository-owned.

## Project-Specific Rules

### PHP Boundary Architecture Rules

- Require dependencies between Composer packages and PSR-4 roots to follow the repository's declared direction; a reverse `use` dependency can create a cycle and break isolated reuse.
- Reject business rules placed in `Controller`, `Command`, Blade, or Twig entry points when another transport must share them; duplicated orchestration produces inconsistent state transitions.
- Ensure Symfony wiring in `services.yaml` or Laravel bindings in a service provider remain the composition root; runtime service-locator calls hide dependencies and cause container resolution failures.
- Require container scopes to match execution lifetime; a shared service holding `Request`, tenant, user, or `EntityManager` state can leak data in a persistent worker.
- Verify HTTP, console, queue, scheduler, and event entry points invoke one application operation rather than reimplementing it; divergent paths create contract and authorization regressions.
- Ensure one owner controls each transaction opened through `EntityManager::wrapInTransaction()`, `Connection::transactional()`, or `DB::transaction()`; nested ambiguous ownership can commit partial data or deadlock.
- Reject persistence entities, Eloquent models, or framework request objects crossing a module boundary when their lifecycle differs; leaked storage state couples consumers and corrupts contracts.
- Require external systems behind a repository-standard client or port with explicit timeout and failure translation; direct `HttpClient` or `Http` facade calls spread unsafe retry behavior.
- Verify PSR-4 namespaces express module ownership and are not bypassed by `class_alias()` or ad-hoc includes; hidden aliases create autoload and build failures.
- Ensure domain events and integration messages have distinct PHP types and ownership; reusing an in-process event as a queue payload can serialize invalid private state.
- Require publish-after-commit behavior to use the established outbox or dispatch seam; calling `dispatch()` before durable commit risks consumers observing missing data.
- Reject cross-module Doctrine joins or Eloquent relationship traversal when module APIs own the data; convenient coupling creates authorization exposure and migration lock-in.
- Verify generators returned across repository boundaries retain an open PDO cursor only for a documented lifetime; escaping `yield` can exhaust resources and block later transactions.
- Require `Fiber` or promise abstractions to stay behind an async-capable boundary selected by repository runtime evidence; leaking suspension into synchronous callers causes blocking and timeout regressions.
- Ensure `RoadRunner`, `Swoole`, `FrankenPHP`, Horizon, and Messenger worker bootstraps define per-unit reset ownership; request-era singleton assumptions otherwise leak mutable state.
- Reject catch-all `Throwable` translation in infrastructure when it erases domain or transport failure types; collapsed errors break retry and client-status contracts.
- Verify shared interfaces use syntax supported by the root `composer.json` PHP constraint and every declared CI or deployment runtime; `composer.lock` records resolved dependencies rather than the supported runtime matrix, so unsupported union, enum, or readonly syntax can cause deployment startup failure.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
