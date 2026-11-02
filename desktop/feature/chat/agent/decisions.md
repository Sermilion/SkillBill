## [2026-11-02] Keep the started guard in memory on the live chat
Context: The chat now stays open after a Goal run or Plan only start, and the discard that used to stop a second start is gone.
Decision: Store started on NewTaskOffer only. Clear it in clearChatFeedback and when sendTurn admits a turn. Do not persist it in ChatStore.
Reason: Persisting it needs a ChatNewTaskContext change in core/domain and ChatStore serialization, so a resumed draft can start again as an accepted limit. A repeat start would probably launch a second run under a new key, inferred from startNewTask without reading prepareNewTask or the allocator. A pending start that completes after leave does not set started on another chat because the Started copy requires ownsChat.
Revisit when: the guard needs to survive resume or restart.

## [2026-11-02] Refuse repeat Goal and Plan starts in start()
Context: After a Goal run or Plan only start, those starts must stop without hiding Review, Validation, QA or PR.
Decision: start() returns early for GoalRun and PlanOnly when started is set, before starting or startNewTask. canStart also requires started to be null. allowStartActions stays host.onStart != null. Standalone kinds skip the guard and do not clear it.
Reason: Card buttons, prefix controls and agent-suggested chips all reach draft.start, so one guard covers them. Setting allowStartActions false would hide standalone chips. The callout explains a no-op goal chip click.
