---
name: bill-generic-code-review-security
description: Technology-neutral security review for trust boundaries, authorization, secrets, input, and side effects.
internal-for: skill-bill
---

# Generic Security Review

## Focus

Follow untrusted input to privileged reads, writes, execution, parsing, network access, and logs. Verify object-level authorization, tenant separation, secret handling, safe defaults, output encoding, and resistance to path, injection, and confused-deputy attacks.

## Ignore

- Hypothetical threats without attacker-controlled input or a reachable sensitive operation.

## Applicability

Use when changes cross trust boundaries or affect identity, authorization, secrets, or dangerous sinks.

## Project-Specific Rules

### Security Rules

- Require `object-authorization-check` at the operation that reads or mutates the resource; caller-only enforcement permits bypass and data exposure.
- Verify `tenant-scope-filter` is derived from trusted identity rather than request fields; attacker-controlled scope can leak cross-tenant data.
- Reject `secret-log-field` output in errors, traces, or analytics because operational systems can become a durable credential exposure.
- Ensure `untrusted-parser-limit` bounds size, depth, count, and time; unrestricted parsing risks memory exhaustion and denial of service.
- Require `output-encoding-context` to match the destination interpreter; incorrect encoding creates injection and security failures.
- Verify `filesystem-path-root` resolves and remains beneath its authorized base after links and normalization; traversal can expose arbitrary data.
- Reject `outbound-url-target` requests before validating scheme, host, redirects, and resolved addresses because they enable server-side request forgery.
- Ensure `privileged-command-argv` separates executable arguments from shell text; interpolation of untrusted input creates command injection.
- Require `state-changing-request-token` or an equivalent origin defense where ambient credentials authenticate browser requests; omission risks cross-site actions.
- Verify `deserialization-type-set` is closed to approved records; attacker-selected types can trigger unsafe construction or authorization bypass.
- Reject `security-default-mode` that becomes permissive when configuration is absent or malformed because deployment drift can silently expose operations.
- Require `credential-comparison-path` to use the established constant-time verifier and current password policy; ad hoc comparison risks authentication bypass and secret exposure.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
