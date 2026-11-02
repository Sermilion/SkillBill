## [2026-11-02] Keep the previous New task agent visible while ChooseAgent resolves
Context: ChooseAgent cleared selectedAgent and state.agent before resolveAgent finished, so chipText flashed No agent on the chip and Goal run offer.
Decision: ChooseAgent sets agentId and launches resolveAgent without nulling the previous ChatAgent. The resolved agent replaces it only when draft.agentId still matches.
Reason: The chip and offer share selectedAgent. Clearing it was not required for rebind and made the model line blink.

## [2026-11-02] Name why a New task chip is disabled on the chip itself
Context: Busy, start in flight, and missing repository already disabled the chips, but the operator had only ChatActivityPill, a repository-missing Notice, or disabled Start buttons to guess why a click did nothing.
Decision: Each disabled chip exposes the active reason locally with SbTooltip and semantics stateDescription. Precedence is busy, then offer.starting, then repositoryMissing. The hand cursor follows the same enabled or canOpen gate as clickable.
Reason: A chip-local why is readable from the composed tree without waiting on tooltip delay. Other chrome left a dead control with no explanation at the click target.

## [2026-11-02] Change the displayed New task model by picking another agent
Context: The agent chip shows chipText of ChatAgent, which includes model and effort, so it looks like a model editor.
Decision: The chip menu lists agent ids only. onChoose stays a string callback. Same-agent model and effort stay in Settings. New task does not write machine config.
Reason: Execution chat already switches by agent id. A model payload would fork AgentPicker and put matrix writes on this surface.

## [2026-11-02] Keep the started guard in memory on the live chat
Context: The chat now stays open after a Goal run or Plan only start, and the discard that used to stop a second start is gone.
Decision: Store started on NewTaskOffer only. Clear it in clearChatFeedback and when sendTurn admits a turn. Do not persist it in ChatStore.
Reason: Persisting it needs a ChatNewTaskContext change in core/domain and ChatStore serialization, so a resumed draft can start again as an accepted limit. A repeat start would probably launch a second run under a new key, inferred from startNewTask without reading prepareNewTask or the allocator. A pending start that completes after leave does not set started on another chat because the Started copy requires ownsChat.
Revisit when: the guard needs to survive resume or restart.

## [2026-11-02] Refuse repeat Goal and Plan starts in start()
Context: After a Goal run or Plan only start, those starts must stop without hiding Review, Validation, QA or PR.
Decision: start() returns early for GoalRun and PlanOnly when started is set, before starting or startNewTask. canStart also requires started to be null. allowStartActions stays host.onStart != null. Standalone kinds skip the guard and do not clear it.
Reason: Card buttons, prefix controls and agent-suggested chips all reach draft.start, so one guard covers them. Setting allowStartActions false would hide standalone chips. The callout explains a no-op goal chip click.
