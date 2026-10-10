## [2026-10-10] Keep missing-sequence-among-live accept
Context: VS Code already returns true when current or incoming runSequence is missing.
Decision: Do not turn that path into a reject. Add live-over-Done only on sequenceOrder < 0 when both sequences are present.
Reason: Align intent with IntelliJ, live unfreezes Done, not identical control-flow. Tightening missing-sequence would change among-live behavior this subtask did not own.

## [2026-10-10] Same-execution Done does not regress to Active
Context: Live-over-Done must not resurrect a completed execution as Active.
Decision: isLiveOverDone requires executionId to differ. The existing same-execution terminal-regression reject stays.
Reason: A completed phase flickering back to Active is worse than a stale Done. The exception is for a later live execution, not the same one.

## [2026-10-10] Coordinators do not rank workflows
Context: Plugins poll work status. A second ranking in acceptsNewerStatus would duplicate IdeStatusSelectionPolicy.
Decision: The live-over-Done exception only unfreezes a stale Done winner. Runtime selection remains the source of current work.
Reason: Plugin-only ranking cannot fix a wrong snapshot from work status, and a second ranking would drift from the runtime live-cohort policy.
