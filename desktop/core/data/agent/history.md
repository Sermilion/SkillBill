## [2026-11-11] SKILL-530 live Claude and Codex delivery transports
Areas: desktop/core/data/chat, desktop/core/domain, runtime-kotlin/runtime-infra/launcher/process/codex
- Claude reuses its initialized persistent process and single input writer. ChatProcessInput separates writer acquisition from completion; ClaudeSteeringDeliveries accepts matching replay UUIDs and keeps acquired but unacknowledged writes uncertain.
- Codex uses the active authenticated App Server process, real threadId and expectedTurnId, with session and generation checks repeated at write acquisition. Ordered text/localImage input uses turn/steer; acceptance requires a matching returned string turnId.
- The existing RPC drain reads steering replies beside normal output and approvals. Registrations precede input delivery, and structured steering rejection remains local to its request.
- reusable: launcher steering request/response helpers and optional pinned 0.166.0 schema admission check parameter, response and ClientRequest binding shapes without gating ordinary chat.
- Completion closes new steering admission while retaining submitted registrations for buffered replies. Teardown classifies unresolved requests from write evidence without keeping the process alive solely for acknowledgement.
- reusable: CodexTurnImages owns a unique directory for initial and steering images, uses collision-safe files and serializes creation with cleanup. Rejection retains files until turn teardown.
- Both transports publish retained per-session delivery updates until retirement and use the shared queue's fallback, uncertainty and explicit retry ownership. No provider idempotency or reconciliation guarantee is assumed.
- Limits: authenticated provider behavior remains unverified, and QA did not cover the final gate-repaired tree. No durable chat schema or runtime workflow contract changed.
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
