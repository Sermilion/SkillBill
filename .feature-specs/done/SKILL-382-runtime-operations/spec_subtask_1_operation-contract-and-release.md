# SKILL-382 Subtask 1 - Operation contract, confirmation gate, update-check, and release

Parent spec: [spec.md](spec.md)
Issue key: SKILL-382

## Scope

**Contract.** `Operation` (KDoc on the interface): wire id, `pre`, `run`, `post`.
Pre and post are in-process. Run is a sequence of steps. An agent step runs through
`PhaseRunner` with a step name local to the operation (never a skeleton step id). An
operation may also run a short skeleton definition as a step through `PhaseRunEntry`.
Run never opens a feature-task skeleton workflow and never launches an agent any other
way. Extend SKILL-380's launch-port architecture rule to the operation packages.

`OperationRegistry` is one explicit `@Provides` list. Duplicate ids or unknown lookup
fail with a typed error. No multibinding or classpath scan.

The outcome type is closed: completed, blocked, failed, `AwaitingConfirmation(token,
proposal summary)`.

**CLI.** An `operation` subcommand, `skill-bill operation <name> [key:value …]`
(`bump:`, `confirm:`, `select:`, `mode:`, `scope:`), beside SKILL-380's `phase`
subcommand; the root command takes no positional tokens. Unknown operation ids are a
usage error. runtime-cli reaches the operation executor through a `RuntimeComponent`
accessor, and every operation type the CLI names is added to
`RuntimeEngineInboundApiTest`'s pinned engine inbound API. Telemetry uses
`invocation_id`, not a feature-task `workflow_id`.

Agent steps settle through the minimal final object SKILL-380 subtask 5 made readable
for any step name; operations have no workflow, so they do not use the MCP settlement
tools.

**Confirmation gate** (parent "Operation confirmation gate").
- An operation declares whether it requires confirmation.
- First invocation: pre, then a read-only proposal step. The CLI prints the proposal,
  then one machine-readable line with the status and `confirm:<token>`, and exits with
  a dedicated `awaiting_confirmation` exit code. Pick the next code no `skill-bill`
  command uses, document it in CLI help, and pin it in a CLI test. Nothing in the
  worktree, remote, or GitHub changes.
- Second invocation: `skill-bill operation <name> confirm:<token> [select:…]` loads
  the stored proposal and executes exactly that proposal. It does not recompute it.
- Changes: an invocation with the same operation and new instructions produces a new
  proposal and token. The previous unconsumed token for the same operation and repo is
  marked superseded.
- Refusals, each a typed error that changes nothing: unknown token, consumed token,
  superseded token, token for another operation or repo root, or anchors that moved
  since the proposal (HEAD sha, current branch, plus operation-specific anchors).
- Store: an `operation_proposals` SQLite table added by an append-only migration:
  token, operation id, repo root, anchors (JSON), proposal value (the step's prose),
  created_at, superseded_at, consumed_at. Wire keys come from one
  `OperationProposalPayloadKeys` object, placed by the rule in force.
- Record the gate in `../../../agent/decisions.md`: why two invocations, why a stored proposal
  (agent-curated content cannot be recomputed identically), and that verify uses its
  own workflow family instead (subtask 4).

**`operation:update-check`.** Same behaviour as today's `bill-update-check` /
`mcp__skill-bill__update_check`: report installed vs latest, do not mutate. Pre: resolve
repo/runtime version sources. Post: emit telemetry. No agent step, no confirmation.

**`operation:release`.**
- Require `bump:patch|minor|major`. A missing or unknown bump is a usage error naming
  all three. Do not guess a bump.
- Pre-flight: clean worktree, branch up to date with remote. Either failure stops in
  pre with a typed error.
- Proposal: an agent step, run through `PhaseRunner`, curates the changelog from commits
  since the last release, following today's `../../../skills/bill-release/content.md` rules, and
  computes the version. Anchors add the last release tag and the remote branch head.
- Confirm: the runtime creates the annotated tag with the stored changelog and pushes
  it, in-process. No agent step. One confirmation covers tag and push, as today.

**Dispatcher.** Replace SKILL-380's "operations arrive with SKILL-382" refusal in
`../../../skills/skill-bill/content.md` with `operation:<name>` parsing that translates into the
`skill-bill operation <name> …` subcommand. A request carrying both `phase:` and
`operation:` is refused by the dispatcher. Add `operation:update-check`
and `operation:release` to the `/skill-bill` routing table, and the relay rule: when a call exits with
`awaiting_confirmation`, show the proposal, ask the operator once, and call the same
operation with `confirm:<token>`. If the operator asks for changes, re-invoke with those
instructions and relay the new proposal. Never pass `confirm:` without an operator
answer.

No listed-skill deletion.

## Acceptance Criteria

1. `Operation` and `OperationRegistry` exist. Duplicate registration and unknown id each have a typed-error test. The launch-port rule covers the operation packages and fails on a synthetic violation.
2. `skill-bill operation update-check` matches today's update-check outcomes (`up_to_date`, `update_available`, `ahead_of_release`, `unknown`) and writes no `feature_task_workflows` row.
3. `skill-bill operation release` without a bump type is a usage error naming `patch`, `minor`, and `major`. A dirty worktree or a branch behind its remote fails in pre and creates no proposal or tag.
4. The first release invocation exits with the `awaiting_confirmation` code, prints the changelog, version, and token, and creates no tag. `confirm:<token>` creates and pushes an annotated tag whose message is the stored changelog. A second `confirm:` with the same token is refused.
5. A token whose HEAD moved, a superseded token, and a token for another operation are each refused with a typed error, and none creates a tag.
6. The `operation_proposals` migration is append-only and runs over a pre-change database.
7. `/skill-bill operation:update-check` and `/skill-bill operation:release` route to the CLI, and the dispatcher content states the relay rule.
8. SKILL-380 full-run and phase-run fixtures still match. `../../../skills/bill-update-check` and `skills/bill-release` still work.

## Non-goals

- Other operations (subtasks 2–4). Deleting any `../../../skills` tree (SKILL-383).
- Changing CI that reacts to tags. Proposal expiry by time.

## Dependency notes

- First subtask. Recheck SKILL-380's `PhaseRunner`, `PhaseRunEntry`, CLI
  parser, and dispatcher anchors at start.

## Validation strategy

Catch: unknown operation succeeding; update-check inserting a workflow row; tagging on a
dirty tree; tagging without confirmation; a reused or stale token executing; the confirm
step recomputing the changelog. Cover with registry tests, pre-flight tests, a
first-invocation no-tag test, a confirm test against a local bare remote, the refusal
tests, the migration test, and a dispatcher routing test. Run
`cd runtime-kotlin && ./gradlew check` plus CLI, engine, infra-sqlite, infra-skills.
`bill-unit-test-value-check` on changed tests.

## Next path

Continue to `spec_subtask_2_checklist-operations.md`.

## Spec Path

.feature-specs/SKILL-382-runtime-operations/spec_subtask_1_operation-contract-and-release.md
