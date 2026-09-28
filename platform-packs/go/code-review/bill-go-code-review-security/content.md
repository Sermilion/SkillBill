---
name: bill-go-code-review-security
description: Use when reviewing Go authentication, authorization, templates, input limits, paths, processes, SSRF, uploads, secrets, dependencies, and security middleware.
internal-for: skill-bill
---

# Go Security Review Specialist

Own trust boundaries and attacker-controlled behavior. Apply framework rules only when the repository imports or configures that framework.

## Focus

- Authentication, authorization, sessions, templates, input limits, paths, processes, SSRF, secrets, and reachable dependencies
- Exploitable authorization-bypass, injection, traversal, resource-exhaustion, and data-exposure paths

## Ignore

- Hypothetical ecosystem risks without a reachable repository precondition
- Reliability or API-contract findings without an attacker-controlled consequence

## Applicability

Trace user-controlled bytes through parsing, authorization, rendering, filesystem, network, and process boundaries. Verify both rejection and safe resource limits.

## Project-Specific Rules

### Go Security Rules

- Require authentication middleware around every protected `http.Handler`; a missing route wrapper risks anonymous data exposure.
- Require login to rotate session identifiers and set `HttpOnly`, `Secure`, and appropriate `SameSite` cookie attributes; session fixation or script-readable cookies expose authenticated accounts.
- Verify state-changing browser handlers use a repository-compatible CSRF token and object authorization after authentication; cookie-authenticated requests without CSRF protection permit unauthorized writes.
- Require applicable OAuth 2.0 authorization flows to validate `state` and exact redirect URIs, and require PKCE for public clients or other flows whose threat model needs code-interception protection; omitted controls permit login CSRF or stolen-code exchange.
- Require applicable OIDC flows to validate `nonce` when replay binding is required, plus issuer, audience, token lifetime, signature, and rotating verification keys for ID tokens; omitted identity checks can accept replayed or attacker-issued tokens.
- Verify object-level authorization after loading the target through `r.PathValue` or the active router rather than trusting its identifier; identifier swapping creates cross-tenant data exposure.
- Ensure identity stored in `context.Context` uses a private typed key and cannot be supplied by request data; key collisions risk authorization bypass.
- Require untrusted HTML to render through `html/template`, not `text/template`; the wrong engine risks cross-site scripting.
- Reject converting user input to `template.HTML`, `template.URL`, or `template.JS` without a narrow trusted-source proof; typed bypasses disable contextual escaping and create injection exposure.
- Ensure parsers such as `json.Decoder`, `multipart.Reader`, and `bufio.Scanner` have byte, field, or token limits; oversized input risks memory or CPU resource failure.
- Require filesystem targets to pass `filepath.Rel` containment and use symlink-safe, race-resistant opening such as `openat`-style traversal with `O_NOFOLLOW` where supported; `filepath.Clean` alone permits symlink swaps that escape the allowed root.
- Verify URL paths use `path` semantics and OS paths use `filepath`; mixing them creates platform-specific traversal or routing bugs.
- Reject `exec.Command("sh", "-c", userInput)` and require a fixed executable plus separated arguments, leading-option rejection, and `--` where the child supports it; shell interpretation or option injection permits attacker-controlled behavior.
- Ensure `exec.CommandContext` receives a bounded context and constrained environment; hostile subprocesses risk timeout failure or secret-data exposure.
- Require SSRF policy at connection time through a controlled `net.Dialer` or `http.Transport.DialContext` that validates every resolved IP, plus `http.Client.CheckRedirect` validation on each hop; preflight URL checks alone lose DNS-rebinding and redirect races and expose internal services.
- Verify uploads use `http.MaxBytesReader`, sanitized server-owned names, and content inspection where required; trusting filenames risks overwrite, traversal, or storage exhaustion.
- Ensure archive extraction validates every `zip.File` target remains beneath the destination; zip-slip entries risk data corruption by overwriting executable or configuration files.
- Reject secrets, bearer tokens, cookies, or raw credentials in `slog` attributes and errors; logs can create durable credential exposure.
- Require dependency findings from `govulncheck ./...` to be evaluated against reachable packages and fixed or explicitly blocked; ignoring reachable advisories leaves known exploitation risk.
- Verify applicable chi, Gin, Echo, `grpc.UnaryServerInterceptor`, or oauth middleware order preserves authentication before authorization and handlers; wrong ordering risks protected-operation exposure.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
