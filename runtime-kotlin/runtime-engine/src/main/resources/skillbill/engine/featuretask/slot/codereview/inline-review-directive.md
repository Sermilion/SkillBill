## Depth

**One pass over the delta. Never re-walk it per area. Full evidence still.**

The parent supplies the baseline, rubrics, and required companion guidance in the launch. Merge them into one checklist before reading changed code. Then traverse the delta exactly once, holding all areas in mind simultaneously — each changed hunk is judged against every applicable area's concerns at the moment you read it.

Reduced depth means no specialist fan-out and no per-area re-walk. Inline never launches one specialist per area. It does not mean sampling the catalog, skipping remaining changed files, or approving on a partial read.

This is explicitly forbidden: reading the delta with architecture in mind, then reading it again for performance, then again for security, and so on. Iterating areas over the same code is not thoroughness — it is the same review repeated N times at N times the cost, and it produces worse findings than one pass with the full checklist loaded, because a defect that only shows up where two areas intersect is invisible to both single-area passes.

Areas are a coverage-accounting dimension in the *output*, not an iteration order for the *work*. The per-area checklist you return records which concerns you carried through that single pass; it is not a log of separate passes.

Verification is the purpose: confirm the change does what it claims and catch the defects a careful reader finds on one attentive pass. This is not an audit of every area in specialist depth. Signals focus the inspection within an area; they never remove a declared area from the checklist. Do not build a case for a marginal finding to justify having looked.
