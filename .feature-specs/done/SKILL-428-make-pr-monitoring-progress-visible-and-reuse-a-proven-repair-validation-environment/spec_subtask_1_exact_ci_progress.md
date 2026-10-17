# SKILL-428 subtask 1: Explain CI waits through existing progress

## Scope

Improve monitor prose and forward available activity through existing progress, CLI and status paths. Make queued CI, running CI, source repair and merge resolution understandable without a new event protocol or schema.

## Acceptance Criteria

1. S1-1. Guidance asks the monitor to name the current PR and available commit/check links, say what is running or waiting, and explain the next useful event. Queued CI is described as waiting, and known runner capacity is attributed to actual evidence. Missing runner information is described as unavailable, without requiring administrative API access.
2. S1-2. Existing progress sinks show meaningful activity changes before completion. Quiet work can produce a concise liveness update through current facilities, ideally every 30 seconds. It does not imply a check passed, change a deadline, or consume an attempt.
3. S1-3. Agents report in natural prose without prescribed headings, field names, tokens, activity codes, journals or required output shapes. Runtime forwarding uses existing facts and hooks. No consumer parses prose to decide whether validation or a repair succeeded.
4. S1-4. Existing CI verdicts, targeting, merge precedence, cancellation, ownership and publication behavior remain unchanged here. Bounded output excludes credentials, raw transcripts and unbounded command/log bodies. Missing optional detail introduces no refusal or block.
5. S1-5. Documentation distinguishes an active child from meaningful source progress and explains that local checks still need remote CI confirmation. Only facts already available from current execution/check observations are claimed.

## Implementation guidance

Start with the current monitor instruction text and the ordinary activity/progress path. Remove or replace advice that leaves the caller with only phase transitions. Preserve authored content.md and generated-output boundaries.

Where existing callbacks already carry useful activity, forward it to existing callers instead of adding another transport, status store or event vocabulary. Avoid publishing a new public DTO or contract just to make this prose visible. Do not invent a structured progress file, parse agent narration, expand remote telemetry or require a heartbeat as a condition of success.

A useful update says which check is running or queued, how long it has been observed when known, or which local task the repair is doing. Unknown detail stays unknown. Use current links and commit identity rather than deriving identity from a check name or treating a stale check as a new result.

Preserve SKILL-427's target/worktree boundaries. This subtask adds no target resolver, ownership mechanism or publication rule.

## Verification

Review the authored guidance against a quiet local command and a mixed queued/running CI example. If forwarding code changes, extend an existing monitor/renderer route test to show an intermediate update before terminal completion and unchanged verdict/cancellation behavior. Do not write tests that match exact prose or add schema validation.

Apply the parent architecture obligations and existing privacy/output bounds. Only later validation runs project checks. This spec correction runs no tests, builds, installers or implementation.

## Dependency notes

This subtask has no dependency on SKILL-427. The next subtask adds environment reuse guidance, and the last corrects the repair budget.
