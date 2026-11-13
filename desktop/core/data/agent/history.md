## [2026-11-13] SONA-12 subtask 3: Devin desktop chat
Areas: desktop/core/data/chat
- Devin is a desktop chat provider. DevinChatTransport is registered in the ChatTransport DI set and runs turns through AcpProvider.Devin from runtime-infra.
- Model discovery runs `devin models list --format json` through the finite ChatProcessLauncher path with empty stdin, so no generation prompt is sent. DevinChatModels flattens families[].variants[] into exact model_uid and label pairs (swe-2-high stays SWE-2 High), never family slug, family_uid or aliases. Malformed rows, an empty list, nonzero exit, start or read failure and oversize output return ChatModelsRead.Failed with chat.devin_model_discovery_failed and a `devin auth login` hint. No fallback list; cancellation propagates.
- reusable: ChatCommand.stdoutLimitBytes is an opt-in stdout byte budget for finite commands. With a budget, stdout decodes as strict UTF-8 and overflow surfaces as a read failure. Callers without a budget keep the lenient reader; ACP openAcknowledged is unchanged.
- reusable: FreshAcpChatTransport holds the former fourth-agent transport body, parameterized by AcpProvider: a fresh ACP session per turn and replayPrompt with the 120,000-character earlier-exchange budget. DevinChatTransport and the retired fourth agent's transport are thin subclasses; the retired fourth agent's behavior is unchanged.
- CursorAcpTurn takes executable aliases from SupportedAgent.fromWire(provider.id), so Devin launches `devin acp` instead of falling into the retired fourth agent branch. Mode and profile stay cursor-only. An exactModels provider refuses a request carrying a separate effort before admission or launch (chat.devin_acp_effort_refused). Admission, release, stream acknowledgement and cancellation use the unchanged cursorAcpTurn and ChatApprovalAuthority.openCursor paths.
- Tests: ChatModelCatalogTest (exact variant UIDs, argv, empty stdin, cwd, limit, failure matrix), StdoutReaderTest (budgeted reader), AgentChatTransportsTest (Devin argv, replay order, missing authority launches nothing and keeps the saved conversation).
- Limits: no happy-path ACP turn or stale-permission-card test, because ChatApprovalAuthority needs a DatabaseSessionManager no test fakes; those paths are shared cursorAcpTurn code. Desktop chat sends empty ACP session context. Live Devin approve/deny, cancellation, retry and model correction were left to goal QA. No persisted chat schema or Devin session field.
Feature flag: N/A
Acceptance criteria: 3/3 implemented (AC-003 stale-card isolation verified by review only)

## [2026-11-11] SKILL-530 live Claude and Codex delivery transports
Areas: desktop/core/data/chat, desktop/core/domain, runtime-kotlin/runtime-infra/launcher/process/codex
- Claude reuses its initialized persistent process and single input writer. ChatProcessInput separates writer acquisition from completion; ClaudeSteeringDeliveries accepts matching replay UUIDs and keeps acquired but unacknowledged writes uncertain.
- Codex uses the active authenticated App Server process, real threadId and expectedTurnId, with session and generation checks repeated at write acquisition. Ordered text/localImage input uses turn/steer; acceptance requires a matching returned string turnId.
- The existing RPC drain reads steering replies beside normal output and approvals. Registrations precede input delivery, and structured steering rejection remains local to its request.
- reusable: launcher steering request/response helpers and optional pinned 0.166.0 schema admission check parameter, response and ClientRequest binding shapes without gating ordinary chat.
- Completion closes new steering admission while retaining submitted registrations for buffered replies. Teardown classifies unresolved requests from write evidence without keeping the process alive solely for acknowledgement.
- reusable: CodexTurnImages owns a unique directory for initial and steering images, uses collision-safe files and serializes creation with cleanup. Rejection retains files until turn teardown.
- Both transports publish retained per-session delivery updates until retirement and use the shared queue's fallback, uncertainty and explicit retry ownership. No provider idempotency or reconciliation guarantee is assumed.
- Limits: authenticated provider behavior remains unverified. Validation changed code after QA settled, so QA does not cover the final tree. No durable chat schema or runtime workflow contract changed.
Feature size: MEDIUM
Feature flag: N/A
Acceptance criteria: 4/4 Codex subtask criteria implemented in source; shared Claude and consumer coverage is recorded in desktop/feature/chat/agent/history.md.

## [2026-11-08] SKILL-470 preload new chat guidance
Areas: desktop/core/data chat production and JVM tests
- MEDIUM feature. New-work chats default applicable requests to Skill Bill goals, standalone phases or operations and honor explicit requests for direct work.
- The shared new-task prompt points to the installed skill's current dispatch instructions. Missing tooling or command failure requires an actionable explanation instead of silent direct execution.
- Acceptance or spec verification, including requests called phase verify, routes to operation:verify with original intake and supplied target, mode and QA options intact. Only missing intake requires a question.
- Verify defaults remain HEAD against origin/HEAD, inline mode and QA off. The skill owns criteria extraction and proposal confirmation before audits, review and verdict; unresolved intake must not produce invented tracker content.
- Followed the existing shared prompt and scope-selection pattern. App-owned starts, execution-chat guidance and scoped Verify findings routing remain unchanged; non-start actions remain ordinary chat turns.
- Focused prompt assertions cover routing, intake, confirmation, failure handling and execution isolation. No new reusable component, transport signature, action kind or persisted schema was introduced.
- Limits: installed dispatcher and tracker resolution depend on the environment. Prompt guidance cannot guarantee agent behavior, and existing live Claude processes retain their prior prompt.
Feature flag: N/A
Acceptance criteria: 3/3 implemented
