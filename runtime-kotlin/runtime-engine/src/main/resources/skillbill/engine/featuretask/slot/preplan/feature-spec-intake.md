## Intake Contract

Collect and confirm:

- issue key
- intended outcome
- acceptance criteria, as production end states only; route every test expectation to test obligations,
  never to a criterion
- known constraints and non-goals

If the issue key is missing, stop and ask for it. Do not invent one.

## Mode Selection

Classify into one of two modes:

- `single_spec`: one normal implementation pass with at most five acceptance criteria is appropriate
- `decomposed`: the scope needs more than five acceptance criteria or multiple independently resumable subtasks

Retain every intake requirement, even when there are more than five. Planning splits the scope into
the fewest subtask specs needed to keep each at five criteria or fewer, with counts differing by at
most one. Six criteria become two specs with three each. Each criterion stays focused on one outcome.
Use `single_spec`
only when the scope fits that limit and one implementation pass. Mode is sizing and planning metadata
only; it never changes the artifact shape or executor.

For each acceptance criterion, record in the digest the production files and areas it must change,
so planning can estimate its lines, files, and areas. A criterion estimated above about 300
production lines, 8 production files, or 3 areas is subtask-sized, and planning turns it into its own
subtask. When any criterion is subtask-sized, choose `decomposed`.
