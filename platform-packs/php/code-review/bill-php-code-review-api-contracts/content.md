---
name: bill-php-code-review-api-contracts
description: Use when reviewing PHP request validation, response serialization, protocol semantics, and client compatibility.
internal-for: skill-bill
---

# PHP API Contract Review Specialist

Review externally observable PHP service contracts and report only reachable client failures.

## Focus

- Request presence, validation, coercion, and authorization boundaries
- Serialization, errors, status codes, pagination, webhooks, and compatibility
- Symfony, Laravel, and PSR HTTP contracts when detected

## Ignore

- Internal refactors with no transport-visible effect
- Framework-specific advice without owned packages, configuration, or entry points

## Applicability

Gate each framework rule on its concrete surface: HttpFoundation rules require `symfony/http-foundation` request usage, `Assert` rules require `symfony/validator` configuration or source, and Serializer-group rules require `symfony/serializer` mappings or source. Laravel `FormRequest` and `JsonResource` rules each require their own subclass or configuration evidence. PSR-7 and PSR-15 checks require their respective interfaces in `composer.json` or source.

## Project-Specific Rules

### PHP API Contract Rules

- Require `Request::request->has()` or `array_key_exists()` when omitted and explicit `null` mean different operations; truthiness checks can apply an invalid default.
- Reject direct use of coercive query strings such as `$request->query->get('enabled')` without contract normalization; values like `"false"` can select incorrect behavior.
- Ensure Symfony `Assert` constraints or Laravel `FormRequest::rules()` run before application mutation; late validation can persist invalid data.
- Verify Laravel `JsonResource` and Symfony Serializer groups do not expose new entity fields accidentally; implicit serialization creates sensitive-data exposure and client drift.
- Require `json_decode(..., JSON_THROW_ON_ERROR)` or an equivalent checked failure path; silent `null` decoding can accept malformed payloads as valid input.
- Ensure `json_encode()` failures and `JsonException` map to a stable server error rather than an empty body; otherwise clients receive an invalid response contract.
- Verify large identifiers and decimal values retain documented precision across JSON and PHP numeric types; coercion to `float` can corrupt client-visible data.
- Require backed enums, `DateTimeImmutable`, time zones, and nullable fields to serialize in the established wire format; format drift breaks generated clients.
- Ensure validation, authentication, authorization, conflict, and domain failures map to distinct documented status codes; collapsing them into `200` or `500` breaks client recovery.
- Reject pagination without stable ordering and a bounded `limit`; duplicate or missing records make traversal incorrect and create resource risk.
- Require filter and sort field allowlists before feeding Doctrine or Eloquent builders; unchecked names can expose data or trigger invalid SQL failures.
- Verify PSR-7 response bodies are rewound or newly constructed before return; a consumed stream produces an unexpectedly empty client response.
- Ensure PSR-15 middleware either delegates once or returns a response once; double invocation can duplicate state changes and corrupt response ordering.
- Require webhook verification to follow the provider's documented signing contract and define replay tolerance; when that contract signs raw bytes, preserve and verify the raw request body before decoding, because substituting normalized JSON can admit forged or replayed events.
- Ensure mutating endpoints use the repository's `Idempotency-Key` contract when retries are plausible; absent replay storage creates duplicate effects.
- Reject breaking removal or meaning changes in OpenAPI-backed fields without versioning or migration; existing consumers fail at runtime.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
