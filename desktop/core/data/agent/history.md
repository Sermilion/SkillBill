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
