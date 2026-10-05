# Boundary decisions

## [2026-10-05] Keep initiating failures primary during cleanup
Context: Authoring rollback and native-agent staging cleanup selected exception types, while bare finally cleanup could replace the failure that triggered it.
Decision: Establish cleanup ownership with Closeable.use immediately after acquisition. Authoring and scaffold rollback use a success flag; temporary staging and atomic-write files always receive cleanup.
Reason: Every failure exit, including cancellation and interruption, must run owned cleanup. use retains the initiating failure and attaches cleanup failures as suppressed.
Alternatives considered: A bare finally does not preserve primary failure ordering; separate IAE/ISE catches miss other failure exits.

## [2026-10-05] Reuse owned input failure codes without catching defects
Context: Staging preparation and native-agent composition converted IllegalArgumentException into handled input rejection, which also intercepted invariant defects.
Decision: Emit existing staging failures and INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA directly for rejected input. Review validation catches only the composition schema code and rethrows unrelated coded failures.
Reason: These boundaries already have a handled failure contract. Reusing it preserves edge classification and reason text without introducing throwables or broad defect recovery. Source-labelled composition framing applies once.

## [2026-10-05] Share nullable agent add-on consumer lookup
Context: Scaffold validation caught throwing consumer lookup; authoring pointer rendering used runCatching to interpret unknown consumers as absence.
Decision: Both callers branch on AgentAddonConsumer.fromIdOrNull. Keep fromId as the throwing wrapper, one unknownIdMessage source, the legacy decode alias and scaffold's explicit retired-consumer rejection.
Reason: Unknown consumers are expected lookup outcomes. Nullable lookup removes exception-based control flow while the shared message source preserves existing rejection text.

## [2026-10-05] Match cleanup recovery to filesystem failure sources
Context: Cleanup caught IllegalStateException even though Files.walk and Files.list can fail through UncheckedIOException; backup restoration performs an atomic move.
Decision: Handle UncheckedIOException at stream-backed deletion boundaries and retain existing IOException handling. Remove the defect catch from atomic-move restoration without adding a stream catch there.
Reason: Real I/O failures still need existing logs, accumulated errors and suppression. Recovering invariant defects as cleanup I/O hides bugs, and atomic moves do not justify stream-specific recovery.
