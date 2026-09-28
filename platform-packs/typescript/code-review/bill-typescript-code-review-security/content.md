---
name: bill-typescript-code-review-security
description: Use when reviewing TypeScript browser/server trust boundaries, authorization, injection sinks, secrets, build tooling, lockfiles, and dependency supply chain.
internal-for: skill-bill
---

# Security Review Specialist

## Focus

- Identity, authorization, tenancy, sessions, CSRF, and browser/server boundaries
- Runtime validation and HTML, URL, path, query, prototype, process, or redirect sinks
- Client bundles, lifecycle scripts, lockfiles, build plugins, and transitive dependencies

## Ignore

- Unsafe syntax without a reachable untrusted value and consequence
- Dependency concern without an affected package, version, or execution path
- Type-style advice that does not change the trust boundary

## Applicability

Trace repository-owned data from its browser, server, worker, CLI, build, or package-install origin to the privileged sink before reporting a finding.

## Project-Specific Rules

### Identity and Trust Contract Rules

- Every server entry point and privileged queue or isolated-worker handler must derive actor, role, and tenant context from a trusted server-side identity boundary rather than typed client input; reject an authorization bypass or cross-tenant exposure. Verify `trust-boundary trace` before reporting this failure.
- Browser Web Workers and Service Workers remain inside the browser trust boundary: treat messages, caches, clients, and network inputs as untrusted, and require privileged authorization to remain server-side. Verify `worker message trace` before reporting this failure.
- State-changing cookie-authenticated requests must enforce the repository's `SameSite`, origin, and CSRF-token policy; prevent browser-driven forgery even when request bodies validate. Verify `trust-boundary trace` before reporting this failure.
- External JSON, headers, forms, messages, and configuration must remain `unknown` until runtime validation and authorization complete; flag invalid data that reaches a privileged domain state. Verify `trust-boundary trace` before reporting this failure.
- Redirects, callback URLs, and post-message origins must use explicit allowlists; reject open redirects or cross-origin data exposure concealed by string types. Verify `trust-boundary trace` before reporting this failure.

### Injection and Secret Exposure Rules

- DOM insertion, template escape hatches, and `dangerouslySetInnerHTML` or framework equivalents must sanitize for their exact context; prevent XSS through HTML, URL, CSS, or script execution. Verify `trust-boundary trace` before reporting this failure.
- Paths, query fragments, object keys, and process arguments must use safe APIs and reject traversal, prototype pollution, injection, or option-smuggling inputs at the sink. Verify `trust-boundary trace` before reporting this failure.
- Server secrets and privileged environment values must never enter Vite, Next.js, webpack, or detected client substitution prefixes; flag credential exposure in browser bundles or source maps. Verify `trust-boundary trace` before reporting this failure.
- Logs, error responses, telemetry, and serialized state must redact tokens, personal data, stack traces, and internal authorization detail; prevent secondary data leakage. Verify `trust-boundary trace` before reporting this failure.

### Supply-Chain and Operational Failure Rules

- Changes to `package-lock.json`, `pnpm-lock.yaml`, `yarn.lock`, or Bun lockfiles must correspond to intended manifest changes and preserve integrity; reject unexplained dependency drift. Verify `trust-boundary trace` before reporting this failure.
- npm lifecycle scripts, generators, native binaries, build plugins, and TypeScript transformers must be treated as executable code; flag install or CI compromise from unreviewed additions. Verify `trust-boundary trace` before reporting this failure.
- Dependency updates must evaluate reachable advisories, maintainer or provenance changes, and browser/server exposure with repository tooling such as `npm audit`; reject exploitable supply-chain risk. Verify `trust-boundary trace` before reporting this failure.
- Security controls must have server-side or isolated-worker enforcement and adversarial tests; prevent a policy failure when client code, erased types, or bundler dead-code elimination removes the check. Verify `trust-boundary trace` before reporting this failure.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
