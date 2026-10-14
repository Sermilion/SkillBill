# SKILL-420 Backend and web QA phase

## Outcome

Skill Bill runs backend and web acceptance flows against an isolated environment with synthetic data. Backend QA calls real endpoints and checks observable side effects. Web QA drives the running application through available browser tools or Playwright. When no isolated runnable environment can be established within the QA budget, it records a skip and the workflow continues.

Backend/web QA extends the shared QA phase from SKILL-419. It is available standalone, after audit in full workflows, and as an opt-in evaluator in the existing verify operation.

## Scope

- Manifest-owned backend/web QA guidance and discovery of existing repository environment setup.
- Disposable application dependencies, migrations, synthetic fixtures, external-service substitutes, startup readiness, and cleanup.
- Docker Compose as the preferred full-stack environment mechanism when suitable, with existing Testcontainers or local isolated setups reused where available.
- Endpoint and browser execution, assertions, evidence, report-only execution, and the shared `qa_fix` repair substep.
- Multiple applicable targets in a full-stack or mixed repository, with explicit coverage per target.

## Operator behavior

Reuse the SKILL-419 entry points and policy rather than adding `backend-qa`, `web-qa`, or a second listed skill.

- `skill-bill phase qa [<instructions>] --agent <agent>` discovers and exercises backend/web flows in report-only mode.
- Full runs accept `qa:auto|off`, default `auto`. QA runs immediately after satisfied acceptance audit when the target and isolated environment are feasible.
- `skill-bill operation verify <intake> qa:on` adds report-only QA after `completeness_audit` and before verdict. Omitted QA or `qa:off` leaves verify's existing behavior unchanged and does not start QA environment discovery.

Selection, target binding, outcome validation, evidence, status, and retry semantics are the shared horizontal contract. Verify shows QA selection in its existing proposal and persists it through confirmation and resume. No extra confirmation ceremony is introduced for local disposable resources.

## Environment discovery and isolation

Prefer existing repository QA Compose files, test profiles, Testcontainers fixtures, migration commands, and test-data builders. Inspect their effective configuration rather than assuming names such as `test` or `local` prove isolation. Bind the QA environment to the tested revision and explicit test configuration.

When a suitable existing setup is absent, the workflow may create and retain a small repository-native QA setup bounded to the changed acceptance flows. Do not attempt to containerize an entire deployment platform or require Kubernetes. Standalone and verify use temporary configuration and scripts outside tracked source; they do not add harness files to the operator's checkout.

Docker Compose is the default for a backend or full-stack environment with several services. Each run has a unique project identity, fresh owned volumes, isolated credentials, and selected free ports. Do not attach production or developer database volumes, shared external networks, host Docker sockets, or inherited production secrets. Containerization alone is not evidence of isolation.

Use the application's supported database engine in a disposable instance. Run real migrations, then seed deterministic synthetic fixtures with dedicated QA accounts. A real database engine is allowed; production and personal development databases are not. An isolated temporary SQLite file is valid when SQLite is the supported production engine. Do not swap engines merely to make QA easy.

Resolve outbound dependencies explicitly. Email, payments, object storage, webhooks, identity providers, queues, and similar effects use local substitutes, test implementations, or an already declared isolated sandbox. Restrict egress where feasible and record any required external test endpoints. If isolation of a required effect cannot be established, leave that flow untested or skip the target. Never send synthetic writes to an uncertain endpoint.

A frontend does not have to run in Docker. A local server, including one started through Playwright's `webServer` support, may connect to disposable backend dependencies. Frontend mocks are valid for UI-only flows but cannot establish backend integration coverage. Container networking and browser accessibility are resolved deliberately; the endpoint presented to a host browser must belong to the QA environment.

Docker unavailability is not an automatic skip if an existing isolated local setup works. Conversely, a running Docker daemon does not make an unsafe configuration usable. Missing dependencies, incompatible architecture, inaccessible tooling, or insufficient setup budget have distinct recorded reasons.

## Backend execution

Discover endpoint contracts and relevant acceptance flows from the spec and repository. Use the existing HTTP client, API test tools, or a small temporary script to call the running application. Authenticate as synthetic test users.

Assert status, response content, and relevant persisted or asynchronous outcomes. Include changed authorization and validation boundaries, such as rejection of an unauthorized request or invalid payload, when those boundaries are part of the feature. A `200` response or successful health check alone does not establish feature behavior.

Prefer setup through existing fixture builders or application APIs. Direct database seeding may prepare a known initial state, but it must not bypass the application operation being tested. Queue and background-job flows have bounded waits and record whether the expected outcome occurred. Missing observability leaves the assertion incomplete rather than passed.

## Web execution

Use available browser automation tools for exploratory QA and reuse existing Playwright flows for repeatable assertions. Do not assume the runtime-launched agent has the parent session's browser connector; discover capabilities in the actual worker environment. Use an available CLI browser driver when suitable, or skip with a reason when no driver is usable.

Exercise the changed user journeys in the running app with synthetic accounts. Assert visible outcomes, relevant navigation, form validation, and changed access-control behavior. Capture screenshots and browser errors where available. Check relevant responsive layouts and keyboard interactions when required by the feature. A page load alone is not a completed journey.

Bind browser traffic, authentication, and storage state to the isolated environment. Never reuse the operator's logged-in production browser profile. Record whether backend requests reached the real disposable application or mocks. Visual evidence complements assertions; it does not silently substitute for unexecuted functionality.

## Outcomes, target binding, and repair

Use SKILL-419's `passed`, `findings_open`, `skipped`, and `incomplete` verdicts and repair semantics. Expected unavailable environments allow optional workflows to continue with a visible reason. Product failures are retained, even if another service or browser later becomes unavailable. Invalid contracts, corrupt durable state, runtime defects, and cancellation are not skips.

Report coverage per applicable target. A backend pass does not make skipped browser QA pass. A UI pass with mocked APIs does not establish backend behavior. An aggregate report keeps every reproduced finding and every untested flow; it cannot claim all selected flows passed when some did not run.

Full workflows launch the shared `qa_fix` substep to repair reproduced bugs and retain valuable regression flows. It consumes the latest accepted QA findings and reproduction evidence. At most three `qa_fix` attempts are allowed under the shared budget, with consumed attempts and repair output retained across resume. Passed, skipped, or infrastructure-only incomplete QA does not launch it. Each repair returns through acceptance audit and QA before proceeding; `qa_fix` cannot clear its own findings. Exhaustion blocks with retained evidence. A later behavior-changing edit invalidates prior QA success before task completion.

Standalone and verify do not repair application code, create commits, push, or alter tracked harness files. Verify runs the environment from its resolved immutable target head, using a temporary target checkout when required. It does not boot the current dirty checkout and label it as a PR or revision-range target. Existing report-only source and HEAD invariants remain enforced.

Verify QA contributes reproduced failures to the consolidated verdict. Missing capabilities or infrastructure-only incomplete coverage are reported separately and do not automatically become product defects. With QA omitted, existing verification checks and confirmation behavior remain unchanged.

## Resource ownership and evidence

Every environment belongs to one QA invocation. Persist sufficient non-secret identities to reconcile its owned containers, volumes, processes, ports, temporary files, and private evidence after interruption. Concurrent runs do not share fixtures or overwrite each other's resources.

Readiness probes distinguish services still starting, startup failure, missing configuration, and a healthy but behaviorally failing product. Each setup, assertion, asynchronous wait, and teardown has a bounded budget. Repeated infrastructure retries do not consume unbounded time or erase product findings.

Cleanup runs on success, skip, failure, cancellation, and resume reconciliation. Remove only resources proven to belong to the invocation, including its disposable database volumes. Never use global Docker prune or tear down the operator's development stack. Cleanup is idempotent; failed cleanup retains resource identities and recovery guidance in the report. Cancellation propagates after attempted cleanup.

Each receipt records the tested checkpoint and artifact identity, environment identity, target type, isolation facts, fixture identity, toolchain, selected and untested flows, assertions, observed failures, and private evidence references. Do not put tokens, connection credentials, synthetic user passwords, response bodies containing sensitive data, or full logs into durable workflow receipts or remote telemetry.

## Acceptance criteria

1. Backend and web targets are available through the shared standalone phase, optional workflow step, and opted-in verify evaluator. There is one horizontal QA contract and no platform-specific listed QA skill.
2. A backend fixture can start an isolated app and supported disposable database, apply migrations, seed synthetic data, call an acceptance endpoint, and assert both its response and relevant side effect.
3. A web fixture can start the tested app, open it with an available browser driver, authenticate with test state, exercise an acceptance journey, and retain assertion and screenshot evidence. Health checks and page loads alone do not qualify.
4. Compose setup uses per-run identities and fresh storage. Existing Testcontainers and local isolated setups remain usable without introducing a second environment owner. Docker absence is reported distinctly from absence of all usable QA environments.
5. Effective configuration rejects production or personal database connections, unknown inherited secrets, shared persistent storage, and unresolved required outbound effects before the first mutation. Rejected isolation produces an observable skip or untested flow, never a network write to the uncertain target.
6. A static or UI-only frontend can run without Docker. Its report distinguishes mock-backed UI assertions from real backend integration. Full-stack coverage retains backend and browser outcomes separately.
7. Missing browser capabilities in a runtime worker do not silently substitute the parent session's tools or turn a screenshot claim into coverage. Supported CLI browser tooling can be used; otherwise the report identifies the unavailable capability.
8. A reproduced endpoint, database, asynchronous, or UI failure launches the shared `qa_fix` substep, with at most three attempts retained across resume. Each repair re-enters audit and QA; exhaustion blocks. Passed, skipped, or infrastructure-only incomplete QA does not launch repair. Repair claims and infrastructure loss do not clear existing findings.
9. Standalone and verify leave tracked files and HEAD unchanged. Temporary environments and scripts are tied to the invocation and cleaned up. Neither entry point repairs or publishes application changes.
10. Verify defaults QA to off, preserving existing evaluator behavior. `qa:on` persists across proposal, confirmation, and resume and executes against the resolved target head. QA runs after completeness audit and before verdict; findings and coverage limits appear in the verdict.
11. Missing isolated setup or exceeded environment-setup budget produces a recorded skip and allows optional workflow progress. Interrupted assertions produce incomplete evidence. Malformed contracts, corrupt state, defects, and cancellation retain loud-fail or cancellation behavior.
12. Receipt validation rejects stale target identity, claimed passes without assertions, hidden partial coverage, missing isolation evidence, and invalid outcome payloads. Later behavior-changing edits invalidate prior QA results.
13. Concurrent and resumed invocations cannot attach another run's volumes, reuse its fixture state, or delete its containers. Cleanup removes only owned resources, retains evidence, and reports failures without swallowing cancellation.
14. Pack manifests dynamically declare capability and guidance for applicable backend/web targets. Custom or removed packs do not require edits to a central platform list. New fields validate in the canonical pack schema, and runtime-consumed extension fields follow existing anchoring rules.
15. Progress, CLI reports, verify artifacts, workflow state, and telemetry use the shared QA vocabulary and distinguish skips, failures, and partial coverage without duplicating wire keys or leaking private evidence.

## Non-goals

- Production databases, production browser sessions, copied production datasets, or destructive QA against deployed services.
- Requiring Docker for every frontend or rebuilding a project's deployment infrastructure.
- Automatic global installation of Docker, browser connectors, or paid testing services.
- Kubernetes orchestration, load testing, or a full security-testing product.
- Replacing acceptance audit, code review, project validation, or existing build gates.
- Application repair during standalone QA or verify.
- A second QA engine or separate backend/web workflow composition.

## Constraints and dependency

SKILL-419 owns the shared QA phase, policy tokens, receipt contract, workflow repair, verify opt-in, and lifecycle semantics. SKILL-420 extends those owners and adds backend/web environment execution. Preferred implementation order is SKILL-419, then SKILL-420. If the shared contract has not landed, implement against the current repository and deliver the missing shared seam in its declared owner; do not create a parallel contract or leave a waiting placeholder. Recheck overlapping landed acceptance criteria before review.

Apply `docs/runtime-command-guidance.md`, `docs/architecture-guidelines.md` A1 through A12 and G1 through G7, `runtime-kotlin/ARCHITECTURE.md` Design Principles, `docs/code-principles.md`, `docs/observability-policy.md`, and `docs/skill-source-generation.md`. Engine coordinates the shared phase; infrastructure owns Docker, processes, HTTP, and browser protocols. Packs own platform guidance. New shared declarations need actual cross-module consumers or a port signature; no dependency bags or generic transport ports.

Base branch is `main`. This is a requirements spec only. Implementation planning must preserve existing work and resolve the current source without creating a second QA vocabulary. Authored skill changes require `./install.sh`; generated support files are not committed.

## Validation strategy

Name each realistic regression before adding tests. High-value evidence covers rejected production connections and outbound effects before mutation, fresh fixtures, API side-effect assertions, mocked versus real backend attribution, unavailable browsers, verify opt-in and immutable target execution, product finding retention after infrastructure failure, cleanup ownership under concurrent runs, cancellation, interrupted-run reconciliation, and stale receipt rejection. Validate accepted and rejected environment declarations and receipts through their actual contract seams.

Use a small disposable backend and web fixture for tool-dependent smoke tests when Docker and browser tooling are available. Retain an explicit skip when unavailable; fake adapters prove policy and lifecycle only. Run the repository's required validation checks during implementation. QA's product build and execution are separate from audit inspection and do not replace project quality gates.

## References

- [Docker Compose application model](https://docs.docker.com/compose/intro/compose-application-model/)
- [Docker Compose networking](https://docs.docker.com/compose/how-tos/networking/)
- [Docker Compose cleanup](https://docs.docker.com/reference/cli/docker/compose/down/)
- [Playwright web server](https://playwright.dev/docs/test-webserver)
- [Playwright authentication](https://playwright.dev/docs/auth)
- [Testcontainers](https://java.testcontainers.org/)
