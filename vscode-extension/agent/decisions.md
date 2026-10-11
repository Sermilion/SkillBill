## [2026-10-11] Accept the selected post-child monitor without ranking workflows
Context: Runtime selection can return the live goal monitor directly after a child finishes, so the plugin may still hold Active implement at a higher runSequence without seeing child Done.
Decision: Allow only a different workflow execution's Active or Paused monitor to replace Active workflow implement at a lower sequence; retain correlation, Done-replacement, and resurrection guards.
Reason: The spec requires both plugins to display the selected goal step without loosening standalone live ordering. Sequence alone cannot represent this child-to-goal handoff, and status identity and revision remain unchanged.
Alternatives considered: Accepting every lower sequence across execution ids would admit older standalone work; forcing a child Done poll or bumping status_revision is outside the spec.

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
Superseded by: Accept the selected post-child monitor without ranking workflows (2026-10-11)
