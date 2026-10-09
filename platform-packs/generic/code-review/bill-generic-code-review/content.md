---
name: bill-generic-code-review
description: Use as the manifest-declared code-review fallback when no concrete platform pack owns the changed surface or concrete ownership remains ambiguous.
internal-for: skill-bill
---

# Generic Fallback Review

Review unsupported, ambiguous, and technology-neutral changes without claiming concrete platform ownership.

## Classification Rules

- If one concrete pack has positive path ownership, use that concrete pack and do not select generic.
- If governed composition resolves multiple positive path owners, use that composition and do not select generic.
- If no concrete pack has positive path ownership, select generic.
- If equally strong concrete path owners remain ambiguous after composition and content tie-breaking, select generic.
- Otherwise preserve the concrete routing result.

## Diff-Signal Routing Table

- Module boundaries, dependency declarations, or ownership crossings -> `architecture` specialist.
- Idiom choices, opaque helpers, scattered state, or redundant structure -> `code-quality` specialist.
- Hot paths, blocking calls, allocation sites, or resource use -> `performance` specialist.
- Lifecycle, concurrency, state-machine, or runtime behavior -> `platform-correctness` specialist.
- Authentication, authorization, untrusted input, secrets, or sensitive data -> `security` specialist.
- Tests, fixtures, assertions, failure paths, or regression coverage -> `testing` specialist.
- Requests, responses, schemas, validation, serialization, or compatibility -> `api-contracts` specialist.
- Transactions, storage, migrations, consistency, or durability -> `persistence` specialist.
- Retries, timeouts, cancellation, recovery, startup, shutdown, or telemetry -> `reliability` specialist.
- Screens, rendering, navigation, state feedback, or interactions -> `ui` specialist.
- Semantics, focus, keyboard use, localization, or task completion -> `ux-accessibility` specialist.

## Mixed Diffs

- Keep the baseline specialists for the whole review and add only area-relevant specialist lanes.
- Use lightweight file-level classification from paths and changed behavior to build each specialist scope.
- Exclude generated, vendored, build-output, and non-stack files from specialist scope and ownership scoring.
- Launch selected specialists as subagents in this harness in a deterministic order, and retain every selected result.
- Merge every applicable lane in manifest area order and deduplicate only identical failures without losing evidence or specialist attribution.

## Finding Discipline

- Calibrate severity to concrete impact using only the governed severity vocabulary.
- Verify each triggering precondition and reachable failure path before reporting a finding.
- Keep findings attributed to their specialist lane through collection and merge.
- Deduplicate overlapping findings without losing the strongest evidence, consequence, or ownership attribution.
- Put code-quality findings after failure findings in a `#### Code Quality (non-blocking)` sub-section at the end of `### 2. Risk Register`. Continue the F-id sequence, retain each finding's `specialist=` attribution, and mark each finding Minor.
- Code-quality findings never change the Verdict. Put any action items for them after failure action items.
