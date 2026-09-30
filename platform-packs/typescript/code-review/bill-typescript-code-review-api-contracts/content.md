---
name: bill-typescript-code-review-api-contracts
description: Use when reviewing TypeScript declarations, JavaScript consumers, runtime schemas, HTTP or RPC behavior, serialization, events, and version compatibility.
internal-for: skill-bill
---

# API Contracts Review Specialist

## Focus

- Exported declarations and the JavaScript behavior behind erased types
- HTTP, RPC, GraphQL, event, message, and generated-client contracts
- Serialization, errors, idempotency, evolution, and version skew

## Ignore

- Private refactors with no observable consumer change
- Naming preferences that do not change compatibility
- Requests to expose implementation-only types for convenience

## Applicability

Apply to repository-declared package entry points, schemas, transports, producers, consumers, and supported versions; never treat a TypeScript annotation as runtime validation.

## Project-Specific Rules

### Runtime Shape and Data Validation Rules

- Because erased types provide no runtime validation, untrusted request, event, configuration, and storage values must be parsed as `unknown` by the detected schema library or explicit validator; reject invalid data accepted only because a type annotation exists. Verify `wire contract fixture` before reporting this failure.
- Validators such as `zod`, `valibot`, `io-ts`, JSON Schema, or repository equivalents must match optional, nullable, defaulted, coercion, and unknown-field policy; prevent silent data corruption. Verify `wire contract fixture` before reporting this failure.
- Serialization must define dates, bigint, binary values, enums, maps, and precision explicitly; flag a wire failure that differs between server, browser, worker, or plain JavaScript consumers. Verify `wire contract fixture` before reporting this failure.
- Discriminated unions must validate the runtime discriminant and handle unknown variants; reject exhaustive compile-time code that crashes on a newer producer. Verify `wire contract fixture` before reporting this failure.

### Public and Wire Contract Rules

- Exported functions, classes, overloads, generic constraints, and `*.d.ts` output must match emitted JavaScript; prevent a caller failure caused by declaration-only compatibility. Verify `wire contract fixture` before reporting this failure.
- HTTP and RPC endpoints must preserve status, headers, pagination, error envelopes, cancellation, and idempotency semantics; reject a behavioral regression hidden by unchanged request types. Verify `wire contract fixture` before reporting this failure.
- Event and message changes must remain readable by deployed consumers under the repository's compatibility policy; flag removal, renaming, or semantic reuse that breaks version-skew processing. Verify `wire contract fixture` before reporting this failure.
- Package `exports` and generated clients must expose the same runtime and type surface to ESM, CommonJS, browser, and declared target consumers; reject entry-point drift. Verify `built-tarball export matrix` before reporting this failure.

### Evolution and Operational Failure Rules

- Additive fields must have tolerant-reader and default state behavior proven for older clients; prevent a rollout failure when producers and consumers deploy independently. Verify `wire contract fixture` before reporting this failure.
- Error codes and machine-readable fields must remain stable while internal causes stay private; reject a mapping change that breaks retry, UX, or monitoring decisions. Verify `wire contract fixture` before reporting this failure.
- Mutating operations must carry a repository-owned `idempotency-key` or equivalent deduplication identity across timeout and retry; prevent duplicate side effects after an ambiguous response. Verify `wire contract fixture` before reporting this failure.
- Contract evidence must include schema, integration, generated-declaration, and representative wire fixtures where applicable; flag a compatibility claim unsupported by the affected consumer matrix. Verify `wire contract fixture` before reporting this failure.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
