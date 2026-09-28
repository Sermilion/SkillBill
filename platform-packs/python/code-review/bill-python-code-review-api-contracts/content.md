---
name: bill-python-code-review-api-contracts
description: Review Python API contracts, validation, status codes, schemas, OpenAPI compatibility, and serialization behavior.
internal-for: skill-bill
---

# Python API Contracts Review

Review request, response, and public interface compatibility.

## Focus

- Validation, status codes, schemas, serialization, error payloads, pagination, idempotency, and backward compatibility

## Ignore

- Internal refactors that cannot change externally observable behavior
- Framework preferences without a concrete client, documentation, or integration consequence

## Applicability

Use this specialist for FastAPI, Django, Flask, Starlette, DRF, WSGI/ASGI handlers, public Python APIs, CLI contracts, event payloads, webhooks, and versioned endpoints.

## Project-Specific Rules

### Python API Contract Rules

- Require runtime validation with pydantic, marshmallow, a DRF serializer, or explicit checks before typed Python input reaches domain work; annotations alone permit invalid contract data and late failures.
- Verify pydantic `model_fields_set` and `model_dump(exclude_unset=True)` preserve absent, explicit `None`, empty, and defaulted values; collapsing presence semantics can corrupt PATCH state.
- When pydantic v2 is detected, reject stale `dict`, `parse_obj`, validator, alias, or config assumptions that change `model_dump` output or validation errors; migration drift breaks clients.
- Require FastAPI body, query, header, and dependency declarations to match generated `openapi.json`; mismatched runtime parsing or documentation causes valid clients to fail.
- When Django REST Framework is detected, verify `Serializer.is_valid`, partial updates, nested writes, and `validated_data` ownership before `save`; bypasses risk invalid persisted data and incorrect status responses.
- When Flask is detected, require a concrete pre-parse request-body limit such as `MAX_CONTENT_LENGTH` before `request.get_json`, then explicit schema validation before using payload fields; unbounded or permissive parsing can exhaust memory, crash handlers, or accept invalid contract state.
- Require stable machine-readable error codes and intentional HTTP status mapping through `HTTPException`, DRF exceptions, or registered Flask handlers; raw exception text breaks retry and recovery behavior.
- Verify response serialization preserves `Decimal`, `Enum`, UUID, and timezone-aware `datetime` precision and documented aliases; implicit coercion can corrupt wire data.
- Require `StreamingResponse`, Django `StreamingHttpResponse`, or generator responses to surface validation errors before headers commit and close owned resources on cancellation; late failure or leaks return a misleading success status and exhaust capacity.
- Require background response work such as FastAPI `BackgroundTasks` to have an observable failure owner and durable inputs; post-response exceptions must not silently lose contract-required effects.
- Require every generally retriable mutation request to use an idempotency key, uniqueness constraint, durable deduplication record, or a demonstrably idempotent operation, not only webhooks; client, framework, or transaction retries must not duplicate durable effects and exhausted attempts must remain observable.
- Require bounded pagination with a deterministic cursor and unique tie-breaker in `order_by`; concurrent inserts otherwise duplicate or omit client-visible records.
- Verify webhooks authenticate raw request bytes and use an idempotency key or durable event identifier before effects; reserialization or replay can cause signature failure or duplicate state.
- Reject incompatible public signatures, CLI entry points, schema fields, enum values, or event payloads without a versioned migration path; mixed-version consumers will fail at the contract boundary.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
