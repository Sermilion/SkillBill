---
name: bill-generic-code-review-api-contracts
description: Technology-neutral API contract review for compatibility, validation, representation, and errors.
internal-for: skill-bill
---

# Technology-Neutral Contract Review

## Focus

Inspect request, response, event, command, and file-format boundaries. Verify required and optional fields, nullability, defaults, versioning, validation, error identity, pagination, idempotency, and backward compatibility for existing consumers.

## Ignore

- Internal representation changes with no consumer-visible contract effect.

## Applicability

Use when changes affect requests, responses, validation, schemas, serialization, or errors.

## Project-Specific Rules

### Contract Rules

- Require `request-presence-map` handling to distinguish omitted, null, empty, and defaulted values; collapsing those states can accept invalid data or break an existing client.
- Verify `boundary-validator` runs before any durable or external effect; late validation risks partial mutation followed by a rejection.
- Require `response-field-schema` changes to preserve names, types, nullability, and defaults for existing consumers; silent representation drift breaks serialized contracts.
- Reject `numeric-wire-format` conversions that lose identifier or decimal precision because corrupted data can cross the contract without a visible failure.
- Ensure `error-identity-table` keeps validation, authorization, conflict, retryable, and internal failures distinguishable; flattening them causes incorrect client recovery.
- Verify `pagination-order-key` is stable and unique across page requests; unstable ordering creates duplicate or missing data under concurrent changes.
- Require `idempotency-record` ownership for retried commands that create effects; absent replay state risks duplicate operations and contract violations.
- Reject `event-version-field` removal or semantic reuse without a compatibility path because older subscribers can fail deserialization or apply invalid state.
- Ensure `unknown-field-policy` matches the documented compatibility strategy; accidental strictness can break forward-compatible clients while accidental permissiveness can admit unsafe input.
- Verify `content-type-negotiation` and encoding are explicit at the boundary; ambiguous serialization can produce invalid payloads or security exposure.
- Require `contract-migration-window` evidence before removing deprecated fields or error forms; premature removal causes a production regression for lagging consumers.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
