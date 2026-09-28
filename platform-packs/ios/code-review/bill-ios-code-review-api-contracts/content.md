---
name: bill-ios-code-review-api-contracts
description: Use when reviewing iOS HTTP, Codable, and detected GraphQL contract risks.
internal-for: skill-bill
---

# API Contracts Review Specialist

Review only high-signal API-contract issues.

## Focus

- URLSession request, response, cancellation, and error behavior
- Codable wire compatibility
- Detected GraphQL schema, code generation, and cache contracts

## Ignore

- Networking-library preferences without a reachable compatibility failure
- Generated output that the configured generator reproduces identically

## Applicability

Use the REST/`Codable` branch when the changed surface uses `URLSession`, another HTTP client, or `Codable`. Apply GraphQL rules only when operations, schemas, generated clients, or cache configuration are detected. Respect the deployment target and configured generator; repository-local guidance is optional enrichment, never required to make these rules usable.

## Project-Specific Rules

### HTTP Contract Correctness Rules

- A `URLRequest` must preserve the server's method, headers, query encoding, and body contract; reject changes that produce an invalid request and cause authorization or validation failures.
- A `URLSession` response must classify the response status before decoding a success model through `HTTPURLResponse.statusCode`; never decode an error body as success because that failure corrupts client state.
- `JSONDecoder.keyDecodingStrategy`, `dateDecodingStrategy`, and `dataDecodingStrategy` must match the wire contract; reject drift that causes decode failures or incorrect data.
- Missing, null, type-mismatched, or corrupt values in required `Codable` fields must use a controlled request error path; never use unrelated defaults that hide an invalid server contract and create later state failures.
- Structured server error payloads must be decoded and preserved with status, server code, and field details through `Decodable` error mapping; reject lossy mapping that makes operational failures impossible to diagnose.
- Request cancellation must propagate through `URLSessionTask.cancel()` or async `URLSession.data(for:)`; reject wrappers that swallow `CancellationError` and leak network or UI lifecycle work.

### GraphQL And Generated Contract Rules

- When `.graphql` files or an Apollo configuration are detected, schema and operation changes must run the configured code generator; never hand-edit generated `API.swift` because the next build overwrites the fix and breaks the contract.
- The repository's codegen command must reproduce committed generated output exactly; require the schema, every changed operation and fragment, and generated client artifacts to align, rejecting stale types that fail compilation or serialize an incorrect request.
- GraphQL nullability changes must be verified at every generated call site; reject force unwraps such as `response.data!.viewer` that can crash on a valid nullable response.
- Apollo `typePolicies`, cache keys, and merge functions must be reviewed when identifiers or list pagination change; reject cache rules that corrupt entity identity or duplicate state.
- Mutation optimistic responses and explicit `ApolloStore` writes must match regenerated selection sets; reject partial cache writes that leave invalid data after rollback or relaunch.
- API retries must gate on idempotency and classified transport failures; never retry a non-idempotent `URLRequest` blindly because duplicated writes are a correctness and operational risk.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
