---
name: bill-typescript-code-review-platform-correctness
description: Use when reviewing TypeScript type soundness, emitted JavaScript, module loading, bundlers, and runtime-target compatibility.
internal-for: skill-bill
---

# Platform Correctness Review Specialist

## Focus

- Type-level claims versus values and control flow that exist after erasure
- Compiler, declaration, module-resolution, bundler, and package-export behavior
- Node, Deno, Bun, browser, worker, edge, and test-runtime availability

## Ignore

- Diagnostics that already prevent the changed project from compiling
- Assertions whose producer invariant is proven on every reachable path
- Type-style preferences without an emitted-code or runtime consequence

## Applicability

Apply only to the compiler options, module graph, package topology, and runtimes detected in the repository; never infer a browser, server, or framework target from a `.ts` or `.tsx` suffix alone.

## Project-Specific Rules

### Type and State Correctness Rules

- External values must enter as `unknown` and be narrowed by checks that verify every claimed field; reject a type guard whose partial predicate permits invalid state at runtime. Verify accepted and rejected runtime payloads at the narrowing boundary before reporting this failure.
- Generic constraints and conditional types must preserve the caller-to-implementation relationship visible in `tsc --noEmit`; flag variance or inference changes that make an unsafe write or incorrect return type reachable. Verify inferred types and the compiler diagnostic for an unsafe caller before reporting this failure.
- Uses of `any`, unchecked casts, non-null assertions, `@ts-ignore`, and declaration merging require producer evidence; reject an escape hatch when malformed data can leak past the erased type boundary. Verify producer schema output and a malformed runtime value through the escape hatch before reporting this failure.
- Optional, absent, `undefined`, and `null` states must remain distinct through `exactOptionalPropertyTypes`, object spread, JSON, and forms or a valid state can be silently corrupted. Verify serialized keys and round-trip values for all four states before reporting this failure.

### Emission and Consumer Contract Rules

- Verify `declaration` and `declarationMap` output against emitted JavaScript in the built package; reject declaration drift that lets a TypeScript consumer call a missing export or misstates runtime behavior. Verify `built declaration and JavaScript diff` before reporting this failure.
- Package entry points must align `exports`, `types`, file extensions, and conditional branches; fail a change that routes ESM, CommonJS, or plain JavaScript consumers to incompatible artifacts. Verify `packed-package consumer matrix` before reporting this failure.
- Review `module`, `moduleResolution`, interop flags, top-level await, and side-effect metadata together; reject compiler success when Node or a supported bundler loads the emitted graph incorrectly. Verify `runtime import matrix` before reporting this failure.
- Type-only imports and const-enum or decorator transforms must match the configured compiler or transpiler; prevent a runtime crash caused by an erased import or divergent emitted JavaScript. Verify `emitted JavaScript` before reporting this failure.

### Target and Toolchain Failure Rules

- The selected `target` and `lib` must match every supported runtime; reject code whose syntax, built-ins, or polyfill assumptions fail on the declared Node, Deno, Bun, browser, worker, or edge matrix. Verify emitted syntax and execution in the oldest supported runtime before reporting this failure.
- Browser globals such as `window` and server globals such as `process` must be guarded by the actual deployment boundary; prevent an availability failure hidden by broad ambient types. Verify server-side and browser entry execution without cross-environment ambient globals before reporting this failure.
- Bundler aliases, package conditions, tree-shaking, and code-splitting must resolve the same public contract as the compiler; flag a production-only build failure that editor resolution cannot reveal. Verify `production bundle output` before reporting this failure.
- Generated or ambient `*.d.ts` files must be checked against the producing tool and runtime artifact; reject stale declarations that conceal an invalid call, serialization mismatch, or missing module. Verify `generated declaration provenance` before reporting this failure.
- For Blocker or Major findings, describe the concrete invalid-state or ordering failure scenario.
