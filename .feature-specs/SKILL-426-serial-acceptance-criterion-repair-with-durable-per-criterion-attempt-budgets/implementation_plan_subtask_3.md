# Subtask 3 execution checklist and test obligations

## Boundary ownership

The executable spec is spec_subtask_3_serial-repair-reporting.md. This checklist follows the prose correction of 2026-10-14. It replaces the new versioned criterion-payload plan with existing readable report/detail/explanation channels. No reader gains mutation authority.

| Work | Starting owners |
| --- | --- |
| Runtime explanation | FeatureTaskRuntimeStatusService and existing status/block/run reports |
| CLI/MCP-visible text | FeatureTaskRuntimeStatusPresentation, run report presentation and WorkDetailPayloadMapping |
| Selected workflow/status | IdeStatusProjector and IdeStatusService |
| Desktop details | RuntimeWorkMapper and DetailPane |
| IntelliJ details/cache | IdeStatusJsonMapper, StatusUiMapper, SkillBillStatusBarPresentation, StatusDetailsPopupContent and existing preference cache |
| Guidance | docs/runtime-command-guidance.md and necessary skills/skill-bill/content.md sections |

Use existing module owners and text fields. Do not add nested criterion payloads, schema versions, specialized parser validators or cache field groups.

## Ordered checklist

1. Format committed current criterion, used/remaining failures, queue position and latest readable gap through one runtime owner. Distinguish final-audit-required, final source acceptance and later pending quality work. Unknown historical facts remain unavailable.
2. Carry that explanation through existing CLI/MCP report/status/detail text. Preserve common transport versions, field shapes, selected-child identity and freshness. Use existing longer detail channels when a short status channel cannot hold the context.
3. Show context in existing desktop details with cursor and effective freshness preserved. Keep phase/quality progress and stale caveats meaningful. No reader derives counters from gap prose.
4. Carry existing explanation/detail text through IntelliJ tooltip, accessibility and details and its persisted cache. Preserve sanitization, response ordering, short status-bar limits and stale fallback. Reuse the existing cache representation.
5. Add only missing behavioral coverage for dropped facts, wrong selected workflow or lost stale/cache context. Do not test each formatter or pin entire English output.
6. Update command guidance and necessary authored instructions for prose findings/plans, focused repairs, retained failures and final full audit. Remove contradictory structured-admission/adoption directions. Preserve existing commands and later review/quality/commit ownership.

## test_obligations

| Realistic bug | Observable assertion | Criteria |
| --- | --- | --- |
| Exhaustion report loses the actionable gap | Existing report/details include criterion, three used, zero remaining and latest gap | S3-001/S3-002 |
| Parent status shows another child's accounting | Context belongs to the workflow selected by surrounding status | S3-002 |
| Desktop refresh drops context or hides staleness | Existing detail flow keeps meaningful facts, cursor and stale caveat | S3-003 |
| IntelliJ restart/poll failure drops cached explanation | Existing persisted cache restores context with stale/accessibility caveats | S3-004 |
| Empty queue or source acceptance reports full completion | Explanation distinguishes final audit and later pending quality obligations | S3-001/S3-006 |

Reuse existing fixtures across exhausted and final-audit/deferred-work examples. Keep unrelated transport compatibility, ordering and sanitization regressions. There is no new criterion schema/rejection suite and no test for prose-only guidance wording.

## Coverage and handoff

S3-001 owns readable runtime context. S3-002 owns existing report transport. S3-003/S3-004 own actual desktop/IntelliJ reader behavior. S3-005 owns guidance, and S3-006 carries sparse consumer regressions. Subtask 2 supplies committed accounting and prose execution; readers do not reconstruct or mutate it.

## Validation ownership

No runtime checks execute during respecification. Later owning phases run existing required checks and meaningful affected consumer regressions. Read source-generation guidance before actual skill edits; its authorized owner performs required install/render work. Generated outputs stay uncommitted. Parent history, commit, PR and monitoring remain runtime-owned.
