## [2026-10-10] Live-over-Done uses live display, not isLiveOutcome
Context: A cached Phase · done froze out a later live goal whose run_sequence was lower or missing.
Decision: Coordinator-local isLiveOverDone accepts Active, Paused, or Blocked over Done when executionId differs, including a null or lower incoming runSequence.
Reason: Domain isLiveOutcome includes Failed and Stale, and isTerminal includes Blocked. The freeze is cached Done. Failed vs Done stays on sequence rules.
Alternatives considered: Reusing isLiveOutcome() or isTerminal() as the exception predicate.
Revisit when: Incoming Failed replacing Done becomes a product requirement.

## [2026-10-10] Null sequence over Done is live-over-Done, not a reject
Context: IntelliJ returned false when either runSequence was null once both sides had execution metadata.
Decision: Missing current or incoming runSequence now returns isLiveOverDone instead of false.
Reason: Plugin snapshots can carry null runSequence on metadata. Rejecting them would keep Phase · done on screen after a live goal with no sequence.

## [2026-10-10] Same-execution Done does not regress to Active
Context: Live-over-Done must not resurrect a completed execution as Active.
Decision: isLiveOverDone requires executionId to differ. The existing same-execution terminal-regression reject stays.
Reason: A completed phase flickering back to Active is worse than a stale Done. The exception is for a later live execution, not the same one.

## [2026-10-10] Coordinators do not rank workflows
Context: Plugins poll work status. A second ranking in acceptsNewerStatus would duplicate IdeStatusSelectionPolicy.
Decision: The live-over-Done exception only unfreezes a stale Done winner. Runtime selection remains the source of current work.
Reason: Plugin-only ranking cannot fix a wrong snapshot from work status, and a second ranking would drift from the runtime live-cohort policy.
