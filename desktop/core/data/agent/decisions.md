## [2026-11-13] Run Devin chat as a fresh ACP session per turn with bounded replay
Context: Devin chat needed conversation continuity. The retired fourth agent already started a fresh ACP session each turn and replayed earlier exchanges within a 120,000-character budget.
Decision: Move the retired fourth agent's transport body into FreshAcpChatTransport, parameterized by AcpProvider, with the retired fourth agent and Devin as thin subclasses. Add no persisted Devin session field and no latest-session continuation.
Reason: Persistent session reuse would need a new saved field and a migration path, and the spec lists it as a non-goal. One shared body keeps the replay budget and partial-save behavior identical across both providers.

## [2026-11-13] Refuse a separate effort for exact-model ACP providers
Context: Devin selects reasoning level through exact variant UIDs such as swe-2-high, and other providers can leave an effort value on the request.
Decision: When a provider declares exactModels and the request carries an effort, cursorAcpTurn fails the turn with AgentUnsupported before admission or launch. The model UID passes through unchanged.
Reason: Encoding or dropping effort silently could send the wrong variant or carry another provider's effort into a Devin turn. A visible refusal tells the user to pick a variant instead.

## [2026-11-11] Keep Codex steering within the normal turn's RPC and image lifetime
Context: Normal output, approval requests and steering replies share one authenticated App Server process. Local image paths can remain in provider use after steering rejection.
Decision: Route steering replies through the existing RPC drain and retain initial and steering images in a unique turn-owned directory until teardown. Completion closes admission while cleanup handles buffered replies and unresolved requests.
Reason: Competing readers can take another request's response or approval. Batch rejection does not prove that image use has ended, and an unresolved acknowledgement does not justify extending a completed process's lifetime.

## [2026-11-11] Admit Codex steering independently of ordinary chat
Context: Steering needs pinned turn/steer parameter, response and ClientRequest binding shapes beyond the existing ordinary-chat capability check.
Decision: Check steering schemas only for steering and return an unavailable capability through the shared queued fallback. Keep ordinary chat admission independent.
Reason: Missing or incompatible optional steering support must preserve ordinary chat and must not turn Send now into cancellation.

## [2026-11-08] Keep detailed chat dispatch rules with the installed skill
Context: New-work chats share a scope prompt across agent transports, and the retired fourth agent includes it within a bounded fixed-plus-history budget.
Decision: Keep routing and verify intake guidance concise in the shared new-task prompt; refer full syntax, options and confirmation rules to the installed skill. Preserve execution-attached guidance and app-owned start actions.
Reason: The installed skill owns detailed dispatch and confirmation instructions. The spec requires concise shared guidance, preserves existing action ownership and excludes migration of live chat processes.
