## [2026-11-12] Preserve OpenCode conversation identity on resume
Context: An app chat ID differs from its OpenCode handle, and saved records can have missing or unsafe bindings.
Decision: Save the handle and effective configuration and checkout binding before prompting. Resume only through session/load; expose unsafe records for recovery and require a separate new chat after refusal.
Reason: A replacement session or transcript replay would disguise lost provider continuity. Optional binding fields keep older records readable.

## [2026-11-12] Enforce OpenCode chat permissions and settle stop before cancellation
Context: ReadOnly must deny mutations, Act requires operator decisions, and cancelling a collector first can lose terminal delivery.
Decision: Verify a per-launch permission overlay before prompting, retain one-time operator decisions, and settle capable transports before joining or cancelling turn collectors.
Reason: Existing provider allow rules must not bypass chat approval authority. The active collector must receive terminal delivery and cleanup failures before Stop releases it.

## [2026-11-08] Keep detailed chat dispatch rules with the installed skill
Context: New-work chats share a scope prompt across agent transports, and Junie includes it within a bounded fixed-plus-history budget.
Decision: Keep routing and verify intake guidance concise in the shared new-task prompt; refer full syntax, options and confirmation rules to the installed skill. Preserve execution-attached guidance and app-owned start actions.
Reason: The installed skill owns detailed dispatch and confirmation instructions. The spec requires concise shared guidance, preserves existing action ownership and excludes migration of live chat processes.
