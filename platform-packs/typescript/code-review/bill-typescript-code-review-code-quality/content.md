---
name: bill-typescript-code-review-code-quality
description: TypeScript discriminated unions, narrowing, async control flow, module structure, and detected UI or server frameworks.
internal-for: skill-bill
---

## Focus

Review changed TypeScript code for avoidable maintenance work. Follow the project's `tsconfig`, module system, package manager, and detected UI or server framework. Report a concrete location, the maintenance cost, and a behavior-preserving change; no reachable failure is required.

## Ignore

- Behavioral defects owned by failure specialists; route correctness, security, persistence, reliability, and accessibility findings to those lanes.
- Issues already enforced by the repository's configured formatter, linter, compiler, or static-analysis rules.
- Style preferences without a concrete maintenance cost or a behavior-preserving rewrite.

## Applicability

Use this lane for every routed TypeScript pack review, regardless of whether changed code has a reachable failure. Inspect changed code only and follow repository conventions before applying general language preferences. Zero findings is valid when the code is clear.

## Project-Specific Rules

### Idioms and helper clarity rules

- Require closed UI or request states to use a discriminated union and `switch` on `state.kind`; optional fields allow unsupported combinations and add state tracing. Preserve each rendered transition.
- Require `JSON.parse` results to cross an `unknown`-to-validated boundary through the detected runtime schema; repeated shape interpretation obscures validation ownership. Keep accepted values and error behavior.
- Require `async`/`await` or Promise chains to expose each rejection and cleanup path; nested chains hide cancellation and resource ownership. Preserve rejection propagation and cleanup order.
- Require optional fields to distinguish `undefined` from `null` when the API contract does; collapsing them creates serialized-value ambiguity and validation maintenance. Preserve wire values.

### Cohesive state and duplication rules

- Require modules to use existing `package.json` exports or export syntax; forwarding barrel files add indirection and make ownership harder to trace. Preserve public exports when removing a pass-through.
- Require React, Vue, Angular, Node, or server state advice to follow the detected project convention, such as `useState`, Vue `ref`, or Angular `signal`; duplicate state owners create update ambiguity. Preserve rendered and request behavior.
- Require branch simplification only when `undefined`, `null`, rejection, and validation outcomes match; duplicated branches add control-flow tracing. Retain each existing result.
- Require duplicated normalization using `trim` and `toLowerCase` to share one function only after callers diverge; copies drift on casing or defaults and increase maintenance. Keep defaults and trust-boundary validation intact.

### Dead code, responsibility, and naming rules

- Require dead code and `as` assertions to be removed after checking `package.json` exports and generated consumers; stale assertions hide trust-boundary work. Retain reachable generated entry points.
- Require functions mixing parsing, state changes, and rendering or I/O to split at boundaries such as `JSON.parse`, render, or Node `readFile`; combined flows obscure cleanup ownership. Preserve error propagation and resource cleanup.
- Require names to distinguish `unknown` inputs from the project's validated domain type; reuse across boundaries creates validation ambiguity. Keep serialized fields and package exports stable.
- Require `Promise` resource cleanup and cancellation handling to remain visible when extracting async helpers; hidden ownership adds tracing work. Preserve rejected values and rendered output.

- For every finding, identify the changed-code location, the concrete maintenance cost, and a behavior-preserving rewrite. Preserve validation, errors, resource closing, and serialized values.
- Report every code-quality finding as Minor; never Blocker or Major.
