## [2026-11-11] Keep Codex steering within the normal turn's RPC and image lifetime
Context: Normal output, approval requests and steering replies share one authenticated App Server process. Local image paths can remain in provider use after steering rejection.
Decision: Route steering replies through the existing RPC drain and retain initial and steering images in a unique turn-owned directory until teardown. Completion closes admission while cleanup handles buffered replies and unresolved requests.
Reason: Competing readers can take another request's response or approval. Batch rejection does not prove that image use has ended, and an unresolved acknowledgement does not justify extending a completed process's lifetime.

## [2026-11-11] Admit Codex steering independently of ordinary chat
Context: Steering needs pinned turn/steer parameter, response and ClientRequest binding shapes beyond the existing ordinary-chat capability check.
Decision: Check steering schemas only for steering and return an unavailable capability through the shared queued fallback. Keep ordinary chat admission independent.
Reason: Missing or incompatible optional steering support must preserve ordinary chat and must not turn Send now into cancellation.

## [2026-11-08] Keep detailed chat dispatch rules with the installed skill
Context: New-work chats share a scope prompt across agent transports, and Junie includes it within a bounded fixed-plus-history budget.
Decision: Keep routing and verify intake guidance concise in the shared new-task prompt; refer full syntax, options and confirmation rules to the installed skill. Preserve execution-attached guidance and app-owned start actions.
Reason: The installed skill owns detailed dispatch and confirmation instructions. The spec requires concise shared guidance, preserves existing action ownership and excludes migration of live chat processes.
