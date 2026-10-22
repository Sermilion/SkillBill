# Feature Spec Preparation

`bill-feature-spec` prepares governed feature-spec artifacts without starting implementation. Every successful preparation emits a parent spec, one or more distinct executable subtask specs, and a schema-valid manifest for the goal runtime.

## Subtask Sizing

A subtask pays the whole ceremony: preplan, plan, implement, review, validate, one commit. That
cost barely moves between a two-file change and a twenty-file one, so decomposition exists to cut
work too large for a single pass, not to give each layer, file, or logical unit its own ticket.

Every spec you author must have one to five acceptance criteria. When a candidate subtask needs
more than five, use the fewest subtask specs needed to stay within the limit and distribute the
criteria as evenly as possible. For a split caused by criterion count, the largest and smallest
specs differ by at most one criterion. Six criteria become two specs with three each; eleven become
three specs with four, four, and three. Keep related outcomes together and record their dependency
order when assigning criteria to the balanced specs.

Keep each criterion focused on one observable production outcome. Never drop requirements, hide extra
criteria in nested lists, or combine unrelated outcomes to fit the limit. Preserve every requirement
across the bundle and record dependencies between the resulting subtasks.

Default to one subtask when its scope fits within five criteria and no criterion is subtask-sized
(see Criterion Sizing). Also split when one implement pass cannot carry the work to a reviewable end,
a later part cannot be specified until an earlier contract or schema lands, or the parts ship
separately. Not reasons to split: layer order, one file or symbol each, "add the field, then read
the field", tests on their own, or a narrative of steps one agent would finish in a sitting.

Since each subtask is one commit, the commit must stand alone: a commit that deletes a path whose
replacement lands later, or adds a field nothing reads, belongs in the commit that finishes the
change. When promoting a criterion to its own subtask would break that rule, move the dependent
work into the promoted subtask with it.

Keep related work together within the five-criterion limit. There is no fixed limit on the number
of subtasks; create as many as the acceptance criteria, criterion sizes, and execution boundaries
require.

## Criterion Sizing

Assess every acceptance criterion before you assign it to a subtask. A repair worker gets one
criterion at a time, and a criterion too large for one implement pass never converges: each pass
lands part of it and the next audit finds more. Estimate from the preplan digest and the files the
criterion must change, not from the length of its text.

For each criterion, estimate:

- production lines added or changed, excluding tests, fixtures, and generated files
- production files created or changed
- areas touched, where an area is one module or owning package, such as `runtime-domain`,
  `runtime-infra/sqlite`, `orchestration/contracts`, or the goal-runner package in `runtime-engine`;
  name each area

A criterion is subtask-sized when any estimate exceeds these limits: about 300 production lines,
8 production files, or 3 areas. A subtask-sized criterion becomes its own full subtask. Give that
subtask its own spec with its own one to five acceptance criteria, written to the same standard, and
size those criteria again. Do not keep a subtask-sized criterion as one item and hope implement
finishes it.

Record the assessment in a `## Criterion Sizing` section of every subtask spec, after the
acceptance criteria. Write one numbered entry per criterion, using the criterion's number, with the
three estimates, the named areas, and the verdict: fits, or promoted to subtask N. When a criterion
was promoted, the spec it came from still lists the sizing entry that justified the promotion. The
estimates are planning judgments, not completion conditions. Audit does not fail a criterion because
the real diff differs from its estimate.

## Writing Acceptance Criteria

A one-sentence criterion is not allowed. An implement, audit, or repair worker reads the criterion
and must know exactly what to build and what done looks like without guessing. Unwrap every
criterion into several sentences that state:

- the production end state, in plain terms
- where it lives: the files, symbols, contracts, tables, or commands that change, by name
- the exact values involved: field names, enum members, wire keys, limits, orderings, error codes,
  and the behavior for each edge case and failure path the criterion covers
- the boundary: what this criterion does not cover, and which other criterion or subtask owns the
  adjacent work
- how audit confirms it by reading the tree

Put every detail the criterion depends on inside the criterion itself. Do not leave requirements in
a separate prose section for a worker to connect to a criterion. If a requirements section lists
facts that two criteria share, restate the shared facts in each criterion that needs them. A vague
noun such as "identities", "failure facts", or "validated persistence" must be followed by the exact
list it means.

Write each criterion as one numbered list item. Continue its sentences on indented lines under the
number. Never use nested bullets inside a criterion; the runtime reads them as separate
subcriteria.

An authored parent spec has at most five feature-level outcome criteria; the subtask specs carry
the detailed outcomes. An existing operator-authored parent is immutable input and may contain
more than five criteria. Preserve it and distribute all of its requirements across compliant subtasks.

## Service / Spec Source Mode

`bill-feature-spec` accepts an optional `service:default/linear` argument that selects where
the prepared spec is tracked. This is orthogonal to `single_spec` vs `decomposed`: either mode
may run under either service.

Resolve the effective spec-source mode with the runtime, which encapsulates the precedence
`service: arg > config spec_type > local` and loud-fails on a malformed `.skill-bill/config.yaml`:

```bash
skill-bill config resolve-spec-type --arg <service-arg-or-empty>
```

- Pass the value after `service:` as `--arg` (for example `--arg linear` or `--arg local`).
  When no `service:` argument is given, or it is `service:default`, pass an empty `--arg` (or
  `--arg default`) so the command falls back to the repo-local `spec_type` and then to `local`.
- A `0` exit prints the resolved mode (`local` or `linear`) on stdout; use that as the
  effective mode.
- A non-zero exit means the config is malformed or the service value is unrecognized. Surface
  that failure and stop — do not guess a mode.

If the resolved mode is `linear`, follow **Linear Mode Preparation** before writing artifacts.
Otherwise follow **Local Mode**.

## Linear Mode Preparation

Run only when the resolved spec-source mode is `linear`. Execute these steps in this exact
order so that no Linear issue is created and no artifact is written unless the whole sequence
can proceed (no partial state):

1. **Verify the Linear MCP is available and authenticated.** If it is unavailable or
   unauthenticated, loud-fail with a clear message **before** creating any Linear issue or
   writing any artifact. Create nothing and write nothing on this path.
2. **Compose all spec content in memory first** — the parent spec, every subtask spec, and
   the manifest. The `.feature-specs/{KEY}-{name}/` directory name is not known
   until the parent issue key is returned, so do not write to disk yet.
3. **Create the parent Linear issue**, tagged `task`, whose description is the parent spec
   content. Capture the returned issue key.
4. **Create one sub-issue per subtask**, each tagged `task`, each description
   carrying that subtask's spec content under a clear per-subtask header so the ticket is
   human-legible. Capture each sub-issue's `linear_issue_id`. Rehydration keys off
   `linear_issue_id`, not header text, so the header is for humans only.
5. **Adopt the parent issue's returned key** as the issue key, the
   `.feature-specs/{KEY}-{name}/` directory name, and the manifest `issue_key`.
6. **Write via the shared preparation path**, stamping manifest `spec_source: linear` and
   recording every subtask's `linear_issue_id`. Do not fork writing logic by preparation mode.
7. **Mid-sequence failure (orphan parent):** if a sub-issue create fails after the parent
   issue already exists, loud-fail surfacing the created parent key and any created sub-issue
   keys for manual cleanup, and write no artifacts.

## Local Mode

Run when the resolved spec-source mode is `local` (no config, `spec_type: local`, or
`service:local`). Local mode is unchanged from today:

- Make no Linear calls.
- `spec_source` resolves to `local`.
- Omit the optional manifest `spec_source` field; absence is read as `local`.

## Phase feasibility

Before writing a parent or subtask spec, check every requirement, constraint,
non-goal, and implementation step against the authority of the phase that must
perform it. State acceptance criteria as production repository end states that
implement can produce and audit can inspect. Tests are never acceptance criteria:
put required tests in Test Obligations and command execution and its evidence in
Validation Strategy. Keep review, commit, PR, history, and install actions with
the phases or parent runtime that own them.

Do not invent scope restrictions that prevent review or validation from repairing
defects required checks uncover. A relocation may preserve behavior and assertions
while still permitting production wiring, test setup, formatting, and lint repairs.
Do not turn a small intended diff into a blanket ban on production edits, test-body
edits, or changes outside the planned file list. Preserve architecture rules and
observable behavior instead.

Preserve explicit operator constraints. If one conflicts with a required phase's
work, report the conflict during preparation or planning before authoring an
executable spec. Do not silently relax it or defer an impossible requirement to a
later worker. Apply this check to the whole spec, including Scope, Non-Goals,
Dependency Notes, Validation Strategy, and Implementation Details.

## Spec Format Contract

Every parent and subtask spec is read back by the runtime
(`FileSystemFeatureTaskRuntimeRunInvariantsSource`) to extract its acceptance
criteria. That reader is format-sensitive, so authored specs MUST follow this
contract or the runtime fails the run with "must list at least one criterion":

- The acceptance-criteria section heading MUST begin with `## Acceptance Criteria`
  (case-insensitive). A trailing qualifier such as `## Acceptance Criteria (this subtask)`
  is allowed; a different heading word is not.
- Each criterion MUST be its own list item: a numbered item (`1. ...`) or a
  bullet (`- ...`, optionally a `- [ ]` checkbox). Do not place criteria in
  prose paragraphs under the heading.
- One to five criteria are required in every authored parent and executable subtask spec.
  An existing operator-authored parent retains its original criteria unchanged.
- Each criterion MUST be observable in the phase that currently consumes the
  spec. Do not require a later phase's result as an acceptance criterion:
  no validate quality-check receipt, review clearance, commit sha, or PR URL
  on implement or audit criteria. Those belong in Validation Strategy or in
  that later phase. Audit cannot mint validate evidence; implement cannot
  mint review clearance. A criterion that needs a future phase is
  impossible and must not be written.
- A criterion MUST NOT require running a command. Implement and audit never
  run builds, tests, or generators; validate does. Every criterion must be
  checkable by reading the tree. Do not write criteria like:
  - "`./gradlew check` passes", "the suite is green", or "lint is clean"
  - "fixtures match their baseline" when that needs a capture or generator run
  - "goal planning runs end to end", or any other runtime behavior observed by
    running it
  Instead, state the production repository fact that makes the result true:
  the code exists and implements the behavior, or the wiring is in place. Put
  the command in Validation Strategy.
- A criterion MUST NOT require tests. Acceptance criteria describe production
  end states only. Never write a test-only criterion, and never make a
  criterion name a test, test class, fixture, test coverage, regression case,
  or a guard's synthetic-violation case. List every required test under a
  `## Test Obligations` section; validate runs them.
- A criterion MUST NOT require content in an artifact the runtime writes: the
  commit message, the commit sha, the PR title or body, or history entries.
  Ask for a file under the spec folder instead (for example
  `census_subtask_N.md`).

Prefer the canonical numbered form the runtime writer emits, with each criterion unwrapped over
several sentences on indented continuation lines:

```markdown
## Acceptance Criteria

1. First criterion end state. The files and symbols it changes.
   The exact fields, values, and edge-case behavior.
   What it does not cover and who owns that. How audit confirms it.
2. Second criterion, written the same way.

## Criterion Sizing

1. About 180 production lines, 5 files, 2 areas (`runtime-domain`, `runtime-infra/sqlite`). Fits.
2. About 420 production lines, 11 files, 4 areas (...). Promoted to subtask 3.
```

## Output Rules

The manifest is validated against the decomposition manifest schema contract before persistence and again when the runtime reads it.

Each subtask spec must contain scope, acceptance criteria, criterion sizing, non-goals, dependency notes, validation strategy, and next path, plus `## Test Obligations` when the change needs tests. The acceptance-criteria section must follow the **Spec Format Contract** above so the runtime can extract it.

For decomposed goals, the runtime creates or amends the active subtask commit before review. Review and approval bind to the exact committed target and tree; later code repairs invalidate that approval. Finalization may add only the declared boundary-history outputs under `agent/history.md` and `agent/decisions.md` after the reviewed tree is verified.

Return the next command as:

```bash
skill-bill goal <issue_key>
```

## Handoff Contract Inputs

Prepared artifacts define governed acceptance and subtask boundaries, but do
not grant later agents access to the complete preparation artifact map.
Feature-task runtime selects named, versioned consumer projections and applies
UTF-8 and collection budgets before launch. Agents cannot add sources, widen
field allowlists, or choose a fallback artifact.
