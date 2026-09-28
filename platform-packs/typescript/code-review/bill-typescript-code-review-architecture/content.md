---
name: bill-typescript-code-review-architecture
description: Use when reviewing TypeScript workspaces, project references, package exports, dependency direction, runtime partitions, and lifecycle ownership.
internal-for: skill-bill
---

# Architecture Review Specialist

## Focus

- Workspace packages, project references, entry points, and dependency direction
- Compile-time and runtime dependency graphs produced by compilers and bundlers
- Ownership across server, browser, worker, framework, transport, and persistence boundaries

## Ignore

- File placement without a dependency or ownership consequence
- Interface extraction that adds no substitutable boundary
- Framework preference when the repository's existing adapter boundary remains coherent

## Applicability

Apply according to detected workspace metadata, `tsconfig` references, package export maps, bundlers, runtime targets, and framework conventions.

## Project-Specific Rules

### Package and Dependency Contract Rules

- Workspace dependencies must follow the ownership direction encoded by `package.json` and project references; reject a cycle that breaks incremental builds or permits lower layers to depend on adapters. Verify `dependency graph` before reporting this failure.
- Public imports must resolve through declared `exports` rather than package internals; prevent a compatibility failure when a refactor moves an undeclared path. Verify `dependency graph` before reporting this failure.
- Barrel files and path aliases must not hide a runtime cycle visible in the emitted module graph; flag initialization-order bugs that `tsc --noEmit` misses. Verify `dependency graph` before reporting this failure.
- Shared types must live with the contract owner and avoid importing server, database, DOM, or framework code; reject type sharing that leaks a runtime dependency into consumers. Verify `dependency graph` before reporting this failure.

### Runtime Ownership and Lifecycle Rules

- Server, browser, service-worker, web-worker, and edge entry points must own separate environment adapters; prevent `process`, DOM, filesystem, or secret-bearing modules from crossing into an invalid runtime. Verify `dependency graph` before reporting this failure.
- Request, application, component, and worker resources must have one lifecycle owner; reject module singletons or service locators that leak state across tenants, tests, reloads, or worker jobs. Verify `dependency graph` before reporting this failure.
- Framework handlers must delegate stable domain behavior through repository-owned ports; flag a coupling failure when Next.js, Express, Vue, Angular, Svelte, or another adapter becomes the domain contract. Verify `dependency graph` before reporting this failure.
- Background work must be owned by a queue, worker, or process boundary with explicit shutdown; prevent detached promises from outliving the lifecycle that created them. Verify `dependency graph` before reporting this failure.

### Build Graph and Deployment Failure Rules

- Compile-time-only packages must be marked and imported so `tsup`, `vite`, `webpack`, `esbuild`, or the detected bundler omits them; reject bundle growth or browser crashes from server-only transitive code. Verify `dependency graph` before reporting this failure.
- Conditional exports and public entry points must map every supported ESM, CommonJS, browser, and worker consumer to equivalent behavior; fail a deployment matrix with divergent contracts. Verify `dependency graph` before reporting this failure.
- Code generation, decorators, transformers, and build plugins must have explicit ownership and ordering in the task graph; flag stale or nondeterministic artifacts that corrupt package output. Verify `dependency graph` before reporting this failure.
- Project-reference and workspace build boundaries must include every changed producer before its consumers; prevent CI or release failure caused by editor-only source resolution. Verify `dependency graph` before reporting this failure.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
