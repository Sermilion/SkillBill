## [2026-09-24] SKILL-375 subtask 1: one tool declaration, one call path
Areas: runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-core, orchestration/contracts
- `McpToolRegistry.tools` is the single ordered `McpTool` list (26 tools, matching `mcp-tools-list.json`); each declaration carries handler, normalizer, runtime-owned argument keys, and advertised enum subset, and `tools/list`, schema projection, envelope validation, and dispatch all read it. reusable
- One `McpStdioServer.handleLine(line, component)` and one `McpToolDispatcher.dispatch(name, args, component)`; `McpRuntime`, `McpRuntimeLifecycle`, `McpWorkflowRuntime`, `McpComponentAccess` reflection, and every `Any`-typed context overload are gone. Non-client exceptions go through the telemetry capture path; client errors come back as tool errors.
- Family handler files (`review`, `lifecycle`, `telemetry`, `system`, `workflow`, `featuretask`, `scaffold`) take `(McpToolArguments, McpComponent)` and call services directly; argument-free handlers take only `McpComponent` and the registry adapts them, so no `@Suppress(UNUSED_PARAMETER)` remains. `McpToolArguments` names the tool in every argument error instead of `<unknown>`.
- Removed the feature-task open/continue surface and the decomposition MCP mappers: the workflow tools are VERIFY-only, and the `Decomposition*` continue arms throw `UnsupportedOperationException`.
- `McpComponent` exposes only the members main reads, including new `scaffoldGateway` and `resolvedEnvironmentContext` accessors, so handwritten code never reads `runtimeComponent`. Tests moved into family packages that exist in main, repoTest suites included, and drive tools only through `handleLine`.
- Mapper output keys come from runtime-contracts `*Keys` owners; the schema copy uses the governed-resources DSL; `TelemetryEventSchemaValidator` keeps one `assertIdentity(JsonNode)`.
- `runtime-mcp-package-cycle-baseline.txt` is now empty: the family split removed the `core`↔`shared` and `lifecycle`↔`shared` cycles, so any new runtime-mcp package cycle fails the guard outright. reusable
Feature flag: N/A
Acceptance criteria: 15/15 implemented

## [2026-09-18] SKILL-357 subtask 2: one input schema projected from the contract
Areas: runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-application/telemetry, orchestration/contracts, runtime-kotlin
- Projected every MCP tool's advertised input schema from the YAML `$defs` branch, inlining local refs and removing envelope metadata so validation and `tools/list` share one source.
- Moved enum constraints and parity ownership into the telemetry contract, with a golden `tools/list` fixture preserving the published surface. reusable
- Unified strict unknown-argument rejection through the shared validation seam across stdio and dispatcher paths; deleted duplicate Kotlin schemas and walkers.
- Known limitation: runtime-internal telemetry emission branches remain unchanged.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-18] SKILL-357 subtask 1: one composition, one tool table, one framer
Areas: runtime-kotlin/runtime-mcp, runtime-kotlin/runtime-contracts, runtime-kotlin/runtime-core, runtime-kotlin/runtime-application
- Collapsed MCP startup and handlers onto one injected composition, preserving state across sequential stdio requests.
- Replaced parallel tool registries with one ordered declaration list carrying schemas, handlers, and argument normalization. reusable
- Centralized JSON-RPC framing and shared responses for stdio and governed review evidence transports. reusable
- Added typed payload-key failures and explicit degradation records instead of silent defaults or fabricated measurements.
- Known limitation: input-schema declaration ownership remains with SKILL-357 subtask 2.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-09] SKILL-175 remove prose MCP tools and telemetry (subtask 4)
Areas: runtime-kotlin/runtime-mcp, orchestration/{contracts,telemetry-contract,workflow-contract}, docs, docs/cloudflare-telemetry-proxy
- Deleted the prose MCP family end-to-end: `feature_task_prose_*`, hidden `feature_implement_*` aliases, and `goal_prose_*` from registry, dispatcher, lifecycle/goal handlers, input schemas, goldens, and stdio/parity tests — agents can no longer open a prose workflow through MCP.
- Telemetry event schema bumped to `1.8.0` with matching `TELEMETRY_EVENT_CONTRACT_VERSION`; prose/implement/goal_prose tool and event shapes removed so stale fixtures loud-fail on parity. reusable PATTERN: retire MCP tools and schema events in one contract bump.
- Cloudflare proxy keeps ingest pass-through for retired event names (old clients must not get batch failures) but drops them from `/stats` aggregation; docs and worker tests pin that policy. reusable for future event retirements that must not break installed emitters.
- Getting-started / capabilities / telemetry docs and playbooks no longer advertise prose MCP tools as product surface; historical prose rows remain queryable as legacy only.
- Known limitation: CLI `sona workflow` prose family and SQLite prose tables remain until SKILL-175 subtasks 5–6.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-06-23] SKILL-93 update-check-on-bill-feature
Areas: runtime-kotlin/runtime-mcp, orchestration/contracts, skills/bill-feature
- New `update_check` MCP tool: registered in `McpToolRegistry.toolNames`, `McpToolDispatcher.nativeHandlers`, and backed by `McpRuntime.updateCheck()`
- `UpdateCheckService` added as last param of `McpRuntimeServices`; kotlin-inject resolves it automatically — no `RuntimeComponent` changes needed (reusable pattern for adding services to the DI graph)
- `updateCheck()` is a pure-query tool (no `withAutoSync`); returns 5-key map: `status`, `installed_version`, `latest_version`, `recommended_install_command`, `reason`
- `updateCheckEvent` open-object `$defs` block added to telemetry schema after `doctorEvent`; `oneOf` ref added at matching position (reusable pattern for future open-object events)
- Zero-input tools have no `inputSchemas` entry and fall through to `openObjectSchema()` in `McpToolRegistry` (established pattern)
Feature flag: N/A
Acceptance criteria: 12/12 implemented
