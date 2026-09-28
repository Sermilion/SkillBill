## Intake Contract

Collect and confirm:

- issue key
- intended outcome
- acceptance criteria
- known constraints and non-goals

If the issue key is missing, stop and ask for it. Do not invent one.

## Mode Selection

Classify into one of two modes:

- `single_spec`: one normal implementation pass is appropriate
- `decomposed`: multiple independently resumable subtasks are required

Use `single_spec` by default unless the work clearly needs multiple dependency-ordered subtasks. Mode is sizing and planning metadata only; it never changes the artifact shape or executor.
