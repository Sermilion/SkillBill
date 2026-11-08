## [2026-11-08] Keep detailed chat dispatch rules with the installed skill
Context: New-work chats share a scope prompt across agent transports, and Junie includes it within a bounded fixed-plus-history budget.
Decision: Keep routing and verify intake guidance concise in the shared new-task prompt; refer full syntax, options and confirmation rules to the installed skill. Preserve execution-attached guidance and app-owned start actions.
Reason: The installed skill owns detailed dispatch and confirmation instructions. The spec requires concise shared guidance, preserves existing action ownership and excludes migration of live chat processes.
