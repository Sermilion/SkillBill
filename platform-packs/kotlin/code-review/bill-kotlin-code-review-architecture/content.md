---
name: bill-kotlin-code-review-architecture
description: Review Kotlin ownership, module, coroutine-scope, service, transaction, and JVM boundary architecture. Use for Kotlin architecture and dependency reviews.
internal-for: skill-bill
---

# Architecture Review Specialist

## Focus

- Ownership of scopes, suspend boundaries, modules, services, transactions, and interop

## Ignore

- Naming or layout preferences without a reachable architectural failure

## Applicability

Use for Kotlin libraries, Gradle modules, services, workers, and JVM framework integrations.

## Project-Specific Rules

### Architecture Review Rules

- Require each injected `CoroutineScope` to have an explicit lifecycle owner and cancellation boundary; an application-owned scope may deliberately outlive a shorter-lived consuming service, but an unowned or never-cancelled scope risks leaked concurrent work and stale state.
- Reject `GlobalScope` and hidden `CoroutineScope(Job())` construction because unowned jobs break shutdown ordering and leak resources.
- Verify `suspend` appears at I/O or concurrency boundaries such as `Repository.load`; decorative suspension over blocking work causes dispatcher starvation and invalid caller contracts.
- Require infrastructure adapters around blocking APIs such as `Files.readAllBytes` to switch to an explicitly owned blocking context; the dispatcher may be encapsulated by the adapter rather than injected into every API call, but running on `Dispatchers.Default` can still cause thread starvation.
- Reject Gradle dependency cycles visible in `dependencies` or `api(project(...))` because they break build isolation and module ownership.
- Require `internal` visibility or Gradle `implementation` dependencies for declarations that are not part of the published binary contract; exposing them through public signatures or `api` creates downstream ABI and dependency risk.
- Require one framework-neutral application boundary to own each atomic use case; when Exposed is detected, have an infrastructure transaction runner or adapter invoke `newSuspendedTransaction` for that owner rather than leaking Exposed into the application module. Repository-local boundaries risk partial commit, while prescribing that API to Spring, Hibernate, JDBC, or R2DBC would impose an invalid transaction contract on a different framework.
- Require remote calls and event delivery to occur outside `@Transactional` work or through an outbox; holding the transaction risks timeout and incorrect ordering.
- Reject singleton services that capture request-scoped principals or sessions through `@Singleton`; lifecycle mismatch causes cross-request data exposure.
- Require shutdown ordering to stop intake, cancel or drain owned producers, await bounded completion, and only then close `DataSource` or queue clients; reversed teardown lets live work reach closed resources or disappear before durable completion.
- Verify JVM callbacks cross into suspend code through a lifecycle-owned adapter that maps completion and cancellation, such as adapting `CompletionStage` with `await`; blocking `.get()` can deadlock or starve the callback executor.
- Reject Gradle module or `sourceSets` edges that make JVM-only code reachable from unsupported targets; misplaced dependencies or packages can compile in one module yet fail consumers, publication, or another toolchain.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
