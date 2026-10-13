---
name: bill-go-code-review-code-quality
description: Go language constructs, clear helpers, cohesive state, error handling, ownership, and standard-library choices.
internal-for: skill-bill
---

## Focus

Review changed Go code for avoidable maintenance work. Follow the repository's Go version, package boundaries, and established conventions before suggesting a rewrite. Report a concrete location, the maintenance cost, and a behavior-preserving change; no reachable failure is required.

## Ignore

- Behavioral defects owned by failure specialists; route correctness, security, persistence, reliability, and accessibility findings to those lanes.
- Issues already enforced by the repository's configured formatter, linter, compiler, or static-analysis rules.
- Style preferences without a concrete maintenance cost or a behavior-preserving rewrite.

## Applicability

Use this lane for every routed Go pack review, regardless of whether changed code has a reachable failure. Inspect changed code only and follow repository conventions before applying general language preferences. Zero findings is valid when the code is clear.

## Project-Specific Rules

### Idioms and helper clarity rules

- Require repeated `if err != nil` branches to share a helper only when they perform the same translation; otherwise the extra helper obscures which operation failed. Preserve `%w` wrapping and cleanup order when extracting it.
- Require `errors.Is` for sentinel checks when wrapped errors are accepted; direct equality hides wrapped causes and forces callers to duplicate translation logic. Keep the existing error value and return path.
- Require `errors.Join` only when every cleanup error must remain observable; dropping a deferred close error hides resource ownership and makes failures harder to trace. Preserve the primary error while joining cleanup errors in the same order.
- Require interfaces to stay beside the consumer and expose only its calls, such as a `Read` method shaped like `io.Reader`; a broad interface adds indirection and hides concrete ownership. Replace it with the narrow consumer-owned interface without changing call behavior.

### Cohesive state and duplication rules

- Require related fields that change together to live in one `struct` update; parallel maps create invalid combinations and extra state tracing. Extract a cohesive struct while preserving field validation.
- Require `strings.Cut` for a single delimiter split when its two-result form matches the existing parser; repeated index and slice arithmetic duplicates parser maintenance. Keep the same absent-delimiter behavior.
- Require `slices.Clone` when a returned slice must be detached from caller-owned storage; hand-written copies obscure aliasing and add maintenance. Preserve element order and nil-versus-empty behavior.
- Require validation branches to distinguish malformed input from absence before combining them; equivalent-looking `if` branches obscure contract distinctions and make validation ownership ambiguous. Retain each existing error and zero-value outcome.

### Dead code, responsibility, and naming rules

- Require duplicated normalization to use one helper only after copies have drifted; separate `strings.TrimSpace` and `strings.ToLower` rules cause future behavior drift. Preserve the established transformation order.
- Require deletion of unreachable branches after checking production callers and Go build tags such as `//go:build linux`; dead fallback code misstates supported behavior and adds review work. Keep any configured tagged caller that remains reachable.
- Require functions that combine decoding, policy, and persistence to expose those responsibilities separately, using boundaries such as `json.Decoder` and `database/sql`; mixed jobs add control-flow tracing. Extract only meaningful steps and preserve error propagation and transaction and cleanup order.
- Require `defer` to remain in the resource-owning scope when extracting helpers; moving it can change close order and hide leaks. Keep cleanup on every return path.
- Require names for `context.Context`, channels, and worker state to identify ownership; vague names make goroutine mutation and close authority ambiguous. Rename locals only, preserving APIs and serialized values.

- For every finding, identify the changed-code location, the concrete maintenance cost, and a behavior-preserving rewrite. Preserve validation, errors, resource closing, and serialized values.
- Report every code-quality finding as Minor; never Blocker or Major.
