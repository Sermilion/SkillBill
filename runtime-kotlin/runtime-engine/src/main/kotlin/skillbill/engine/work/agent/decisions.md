## [2026-10-11] Keep board and detail envelopes separate from ide-status
Context: SKILL-418 needs machine-wide lists and detail panels while existing consumers use ide-status 0.3.
Decision: Put the unchanged snapshot inside new 1.0 board/detail wrappers; keep completion metadata and detail extras on those wrappers.
Reason: The spec requires existing work list/status behavior and 0.3 payloads to remain compatible. Reusing status projection avoids a second lifecycle formula.

## [2026-10-11] Retain finished work by completion evidence
Context: Board windows must describe when work finished, even when heartbeats or status freshness continue changing.
Decision: Apply inclusive windows to completion evidence after lifecycle projection. Include unfinished work regardless of age; label terminal-goal state-entry fallback and diagnose missing completion evidence.
Reason: Freshness timestamps would extend retention without a new completion. Projecting first keeps goals awaiting monitor active even when their child has finished.
Alternatives considered: Updated-at or heartbeat retention would misrepresent the selected completion window.

## [2026-10-11] Correlate machine-wide work by stored repository identity
Context: Different repositories can share issue keys, and board has no checkout or branch filter.
Decision: Decode only stored absolute repository identities, group candidates before goal-child exclusion, and diagnose omitted missing or invalid identities.
Reason: The spec requires repository-local issue correlation and forbids manufacturing a root from the process checkout, filesystem, or home directory. Global issue-key grouping could hide another repository's work.

## [2026-10-11] Resolve detail independently of board retention
Context: An old standalone invocation remains addressable after its board window expires, and an issue key can have several historical goal runs.
Decision: Resolve exact workflow and invocation ids, merge only associated execution records, and attach manifest extras only when the parent workflow matches.
Reason: Searching retained board output would lose old invocations. Matching a manifest only by issue key could attach another run's title and subtasks.
