## [2026-11-12] Preserve OpenCode conversation identity on resume
Context: An app chat ID differs from its OpenCode handle, and saved records can have missing or unsafe bindings.
Decision: Save the handle and effective configuration and checkout binding before prompting. Resume only through session/load; expose unsafe records for recovery and require a separate new chat after refusal.
Reason: A replacement session or transcript replay would disguise lost provider continuity. Optional binding fields keep older records readable.

## [2026-11-12] Constrain OpenCode permissions before chat prompts
Context: ReadOnly must deny mutations and Act requires operator decisions, while ordinary runtime launches permit native bypass.
Decision: Inspect effective agent permissions and verify a per-launch permission overlay before prompting. Refuse uncertain policy or operator authority and retain original one-time approval options.
Reason: Chat requirements take precedence over general launch defaults. Existing allow rules must not bypass operator authority, and remembered options must not become continuing local grants.

## [2026-11-12] Settle capable chat transports before cancelling collectors
Context: Cancelling the receiving job first prevents Stop from delivering terminal failure and completing owned cleanup.
Decision: Use typed repository stop settlement before joining or cancelling execution and new-task collectors. Start settlement undispatched before queued work can launch and retain existing cancellation for other transports.
Reason: The active collector must receive terminal delivery and cleanup failures before Stop releases it. A cancelled collector cannot establish delivery.

## [2026-11-08] Keep detailed chat dispatch rules with the installed skill
Context: New-work chats share a scope prompt across agent transports, and Junie includes it within a bounded fixed-plus-history budget.
Decision: Keep routing and verify intake guidance concise in the shared new-task prompt; refer full syntax, options and confirmation rules to the installed skill. Preserve execution-attached guidance and app-owned start actions.
Reason: The installed skill owns detailed dispatch and confirmation instructions. The spec requires concise shared guidance, preserves existing action ownership and excludes migration of live chat processes.
