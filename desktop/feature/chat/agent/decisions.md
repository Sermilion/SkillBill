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
