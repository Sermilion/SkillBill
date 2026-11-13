## [2026-11-12] Preserve OpenCode conversation identity on resume
Context: An app chat ID differs from its OpenCode handle, and saved records can have missing or unsafe bindings.
Decision: Save the handle and effective configuration and checkout binding before prompting. Resume only through session/load; expose unsafe records for recovery and require a separate new chat after refusal.
Reason: A replacement session or transcript replay would disguise lost provider continuity. Optional binding fields keep older records readable.
## [2026-11-13] Run Devin chat as a fresh ACP session per turn with bounded replay
Context: Devin chat needed conversation continuity. Junie already started a fresh ACP session each turn and replayed earlier exchanges within a 120,000-character budget.
Decision: Move Junie's transport body into FreshAcpChatTransport, parameterized by AcpProvider, with Junie and Devin as thin subclasses. Add no persisted Devin session field and no latest-session continuation.
Reason: Persistent session reuse would need a new saved field and a migration path, and the spec lists it as a non-goal. One shared body keeps the replay budget and partial-save behavior identical across both providers.

## [2026-11-13] Refuse a separate effort for exact-model ACP providers
Context: Devin selects reasoning level through exact variant UIDs such as swe-2-high, and other providers can leave an effort value on the request.
Decision: When a provider declares exactModels and the request carries an effort, cursorAcpTurn fails the turn with AgentUnsupported before admission or launch. The model UID passes through unchanged.
Reason: Encoding or dropping effort silently could send the wrong variant or carry another provider's effort into a Devin turn. A visible refusal tells the user to pick a variant instead.

## [2026-11-11] Keep Codex steering within the normal turn's RPC and image lifetime
Context: Normal output, approval requests and steering replies share one authenticated App Server process. Local image paths can remain in provider use after steering rejection.
Decision: Route steering replies through the existing RPC drain and retain initial and steering images in a unique turn-owned directory until teardown. Completion closes admission while cleanup handles buffered replies and unresolved requests.
Reason: Competing readers can take another request's response or approval. Batch rejection does not prove that image use has ended, and an unresolved acknowledgement does not justify extending a completed process's lifetime.

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
