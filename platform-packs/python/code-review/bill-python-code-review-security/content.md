---
name: bill-python-code-review-security
description: Review Python security risks across dependencies, auth, secrets, unsafe parsing, path/subprocess use, SSRF, uploads, and sensitive logging.
internal-for: skill-bill
---

# Python Security Review

Review exploitable trust-boundary and sensitive-data failures.

## Focus

- Supply chain, authentication, authorization, tenant isolation, secrets, parsing, rendering, paths, uploads, SSRF, subprocesses, sessions, and sensitive errors or logs

## Ignore

- Generic dependency warnings unless the diff creates or worsens a concrete exposure
- Hardening preferences without attacker-controlled input, a crossed trust boundary, or policy-relevant impact

## Applicability

Use this specialist for dependencies, routes, object permissions, tenant boundaries, tokens, parsing, templates, files, archives, network clients, subprocesses, browser sessions, forms, model hydration, and webhooks.

## Project-Specific Rules

### Python Security Rules

- Reject untrusted bytes reaching `pickle.loads`, `dill.loads`, or `joblib.load`; object deserialization can execute attacker code and compromise the process.
- Require `yaml.safe_load` and hardened XML parsing such as `defusedxml` for attacker-controlled documents; unsafe constructors or entities risk code execution and file disclosure.
- Verify Jinja `Environment(autoescape=True)`, Django templates, and markdown-to-HTML output preserve escaping or sanitize with an explicit allowlist; unsafe markup creates stored cross-site scripting exposure.
- Require archive and upload handling through bounded sizes, canonical names, and validated content rather than `ZipFile.extractall` or client MIME claims; zip bombs and traversal can exhaust resources or overwrite files.
- Require paths resolved with `Path.resolve` to remain beneath an owned root and reject unsafe symlink following; lexical prefix checks permit traversal and cross-tenant file exposure.
- Reject `subprocess.run(..., shell=True)` and attacker-controlled executable, option, or `cwd` values; require a timeout, cancellation that terminates and reaps the child, and an explicit environment allowlist rather than inherited credentials, because even safe argv construction can permit command injection, orphaned-process exhaustion, or secret exposure.
- Require outbound `requests` or `httpx` targets to use an allowlist, validated schemes, resolved-address controls, redirect revalidation, and egress policy; URL parsing alone cannot prevent SSRF to internal networks.
- For tenant-owned data, require Django, SQLAlchemy, or service-layer lookups to apply a trusted tenant and visibility predicate before returning an object, then enforce object authorization before disclosing or mutating it. Accept verified database-per-tenant, schema-per-tenant, or session-enforced isolation only when raw SQL, background work, and reused sessions cannot bypass it; unverified isolation, unscoped fetches, or ambient identity without resource checks enable cross-tenant exposure and privilege escalation.
- Require JWT validation through a pinned algorithm plus issuer, audience, expiry, not-before, key selection, and token purpose; decode-only `jwt.decode` usage accepts invalid credentials and corrupts authentication state.
- Require webhook signatures to be verified against the exact raw request body with an explicitly allowed algorithm and trusted key selection, enforce a bounded timestamp freshness window, and durably reject replayed event identifiers or signatures before effects; reserialized bodies, algorithm confusion, stale deliveries, or missing replay state allow forged or repeated mutations.
- When browser sessions are detected, verify Django or Flask CSRF middleware, session rotation after authentication or privilege changes, `SameSite`, `Secure`, `HttpOnly`, trusted hosts, and proxy headers match deployment; unchanged session identifiers or weakened cookie boundaries enable fixation and forged or stolen requests.
- Require signed capability URLs and password-reset or action tokens to carry a bounded expiry and the intended resource, principal, and purpose; reusable or over-broad links allow stale authorization to outlive the grant.
- When Django `ModelForm` or untrusted Pydantic input is detected, require an explicit `fields` allowlist and normal `model_validate` validation; broad model binding or attacker-controlled use of `model_construct` can assign privileged state while bypassing validation.
- Reject secrets, tokens, cookies, personal data, request bodies, model `repr`, and stack traces in `logging`, tracing attributes, or user errors; diagnostics can create durable credential exposure.
- Require dependency resolution from trusted indexes with hashes or an unchanged lock through `uv.lock`, Poetry lockfiles, or pip-tools output; dependency confusion and mutable versions risk supply-chain compromise.
- Verify `pyproject.toml` build backends and build requirements are reviewed and version constrained; malicious backend execution occurs before package code and can violate build security policy.
- Reject attacker-controlled expressions passed to `eval`, `exec`, or dynamic `importlib.import_module`; arbitrary execution is a critical security failure.
- Require temporary uploads created with `NamedTemporaryFile` or `TemporaryDirectory` to close and delete on error paths; leaked sensitive files create persistent data exposure.
- Verify Flask or Django production settings disable `DEBUG` and sanitize exception handlers; exposed tracebacks leak secrets, paths, and contract data.
- Require `SpooledTemporaryFile` and upload parsers to enforce memory, disk, and lifecycle resource limits; oversized concurrent requests otherwise cause resource exhaustion and service failure.
- For Blocker or Major findings, describe the concrete authorization-bypass or data-exposure scenario.
