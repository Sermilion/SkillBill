---
name: bill-php-code-review-security
description: Use when reviewing PHP trust boundaries, authorization, deserialization, rendering, uploads, process execution, and sensitive data.
internal-for: skill-bill
---

# PHP Security Review Specialist

Report exploitable trust-boundary failures with a reachable attacker-controlled path.

## Focus

- Authentication, authorization, CSRF, signed input, and proxy trust
- Serialization, templates, uploads, files, outbound requests, and processes
- Secrets and sensitive telemetry

## Ignore

- Generic hardening without repository evidence or an attack path
- Framework controls not owned by the detected Symfony or Laravel application

## Applicability

Apply each framework rule only when its relevant Composer package plus source or configuration evidence proves ownership: middleware evidence gates middleware controls, while voters, policies, signatures, hashers, sessions, templates, and process APIs use their own framework evidence. Treat every request, queue message, uploaded file, serialized value, and process argument according to its actual trust source.

## Project-Specific Rules

### PHP Security Boundary Rules

- Reject `unserialize()` on attacker-controlled bytes even with superficial validation; gadget chains through `__wakeup()` or `__destruct()` can cause code execution.
- Require hydration of public input into explicit fields rather than arbitrary setters or `__set()` hooks; mass object population can bypass authorization invariants.
- Ensure Blade `{!! !!}` and Twig `|raw` receive only reviewed safe markup; untrusted content at either escaping boundary creates XSS exposure.
- Verify upload acceptance uses `finfo`, decoded content, size limits, an allowlisted format, and a server-selected extension rather than `$_FILES['type']`; store files under generated names in non-executable storage outside the public web root and serve them with safe response headers, or spoofed active content can execute or expose users.
- For existing-file reads, require `realpath()` containment beneath a trusted root; for new targets, canonicalize and contain the parent and use race-resistant creation or opening when attackers can modify the path tree, because lexical checks, missing-target `realpath()` calls, and check-then-open symlink swaps permit traversal or sensitive-data exposure.
- Reject shell strings assembled for `exec()`, `system()`, `shell_exec()`, or `Process::fromShellCommandline()`; escaped-looking input can still enable command injection.
- Ensure Symfony Process argument arrays or Laravel Process APIs separate executable arguments from shell syntax; implicit shell evaluation creates execution risk.
- Require outbound `HttpClient` or `Http` destinations to enforce scheme, host, resolved-address, redirect, and timeout policy; unchecked URLs enable SSRF and resource exhaustion.
- Verify authorization through voters, policies, gates, or the repository boundary before object access; route binding alone can leak another tenant's data.
- Require framework-specific proof that every cookie-authenticated state-changing endpoint validates CSRF through Laravel middleware, Symfony forms, controller attributes, token-manager checks, or an equivalent owned seam, and reject unsafe `GET`; configuration alone can miss exempt actions and permit unauthorized requests.
- Require signed Laravel URLs or Symfony URI signatures to bind every security-relevant parameter and expiry; mutable unsigned fields allow privilege escalation.
- Verify `TrustProxies` or trusted proxy CIDRs cannot be influenced by public clients; forged forwarding headers break secure-cookie and origin decisions.
- Reject secrets in `config/*.php`, committed `.env`, container parameters, or exception messages; repository or log exposure compromises credentials.
- Ensure Monolog context, validation dumps, and `Throwable` traces redact tokens, cookies, authorization headers, and personal data; verbose telemetry creates persistent disclosure.
- Require password verification through `password_verify()` or framework hashers and safe rehash migration; custom comparisons risk credential compromise.
- Require session identifiers to rotate after authentication or privilege changes and invalidate on logout, credential reset, or account revocation when the application owns sessions; retained identifiers permit fixation or continued unauthorized access.
- Require reset, invite, verification, and capability tokens to enforce explicit expiry, audience or action scope, revocation, and atomic one-time use when those flows exist; replayable tokens permit account takeover or repeated privileged actions.
- Verify cache and session keys include tenant/user scope where values are private; key collisions leak authenticated state across principals.
- Ensure exception pages controlled by `APP_DEBUG` cannot be enabled in a public deployment; stack and environment output creates credential and path exposure.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
