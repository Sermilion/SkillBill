---
name: bill-go-code-review-api-contracts
description: Use when reviewing Go net/http, RPC, request validation, serialization, response ordering, idempotency, and compatibility contracts.
internal-for: skill-bill
---

# Go API Contract Review Specialist

Own externally observable transport behavior. Leave authentication policy to security and internal workflow placement to architecture.

## Focus

- `net/http`, JSON, RPC, protobuf, validation, status, idempotency, and compatibility contracts
- Client-visible cancellation, serialization, middleware-ordering, and schema-evolution failures

## Ignore

- Authentication and authorization policy owned by security
- Internal refactors with no externally observable contract change

## Applicability

Apply standard-library rules to `net/http` code. Apply router, gRPC, protobuf, or other ecosystem checks only when imports, generated files, or configuration prove that component is in use.

## Project-Specific Rules

### Go API Contract Rules

- Require every `http.Handler` to validate path, query, header, and body inputs before domain work; accepting invalid data risks corrupt state and unstable responses.
- Verify `r.Context()` reaches downstream I/O and is not replaced with `context.Background()`; lost cancellation causes client timeouts and wasted resources.
- Ensure request bodies are bounded with `http.MaxBytesReader` or an equivalent limit before decoding; unlimited reads permit memory exhaustion.
- Require request and response bodies to be closed by the side that owns them; leaked `io.ReadCloser` values can exhaust connections and break availability.
- Reject ambiguous trailing JSON by checking a second `json.Decoder.Decode` for `io.EOF`; silently accepted extra values violate the serialization contract.
- Verify `json.Decoder.DisallowUnknownFields` is used when strict compatibility is intended; ignored misspellings can accept invalid client intent.
- Ensure `encoding/json` tags, `omitempty`, pointer fields, and zero values preserve absent-versus-explicit semantics; accidental omission causes client data loss.
- Verify `json.Decoder.UseNumber`, explicit integer types, or bounded conversion protects identifiers and large numbers when float precision is invalid; silent `float64` rounding corrupts serialized data.
- Require request and response schema evolution to preserve old readers and writers through optional fields, stable `json` names, and deliberate deprecation; removing or retyping a field breaks mixed-version clients.
- Require headers to be set before `WriteHeader` or the first `ResponseWriter.Write`; late headers are discarded and produce incorrect content types or caching.
- Require `WriteHeader` before the body when the contract needs a non-200 response, and reject repeated status writes; allowing the first `ResponseWriter.Write` to commit the intentional default `http.StatusOK` is valid, while implicit status is a defect only when the documented outcome is not 200.
- Reject internal error text sent through `http.Error`; unstable details expose implementation data and violate the public error schema.
- Require mutating endpoints to define retry behavior through an idempotency key or clearly non-idempotent contract; ambiguous retries risk duplicate writes.
- Ensure pagination applies a bounded limit and deterministic cursor order; unstable ordering causes missing or duplicated data between pages.
- Verify applicable `grpc/status` codes and `codes.Code` mappings distinguish validation, authentication, authorization, and availability failures; collapsed codes break retry decisions.
- Require applicable protobuf field-number changes to preserve reserved tags in `.proto` files; tag reuse corrupts serialized compatibility.
- Verify applicable protobuf presence, enum evolution, and unknown-field behavior against generated `protoc` code; collapsing absent values or renumbering enums breaks backward compatibility.
- Require applicable gRPC or Connect calls to propagate `context.Context` deadlines, preserve `status.Code` mappings, and keep generated stubs synchronized with `.proto` sources; missing deadlines leak resources and contract drift breaks clients.
- Ensure applicable chi, Gin, Echo, or Connect middleware preserves route parameters and error mapping; ordering mistakes can bypass validation or return incorrect status.
- Verify webhook handlers authenticate raw bytes before `json.Unmarshal` when the signature contract requires it; reserialization can reject valid messages or accept unsafe data.
- For Blocker or Major findings, describe the concrete compatibility or validation failure scenario.
