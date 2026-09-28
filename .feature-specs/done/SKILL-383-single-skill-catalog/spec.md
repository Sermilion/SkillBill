# SKILL-383 - Single skill catalog

## Mode

decomposed

## Start gate

SKILL-380 (phase slot strategies) and SKILL-382 (runtime operations) are merged to
main. Every capability of every old listed skill then already exists as a phase, a
strategy, an operation, or the `/skill-bill` dispatcher. Read
[SKILL-380's spec](../SKILL-380-phase-slot-strategies/spec.md) and
[SKILL-382's spec](../SKILL-382-runtime-operations/spec.md) first.

## Intended outcome

After install, the listed skill catalog is exactly `skill-bill`. Every other tree under
`../../../skills` is retired, and `bill-monitor` is deleted. Pack specialists install as
unlisted sidecars of `skill-bill`.

| Today's listed skill | Replaced by | Where |
| --- | --- | --- |
| `bill-feature` | `/skill-bill` skeleton form | SKILL-380 subtask 12 |
| `bill-feature-spec` | `phase:plan` | SKILL-380 subtask 9 |
| `bill-code-review` | `phase:review` (`inline` / `delegated`) | SKILL-380 subtasks 3, 6, 8 |
| `bill-code-review-inline` | deleted if uncalled, else unlisted `internal-for: skill-bill` | this bundle |
| `bill-code-check` | `phase:validation` (`pack-build`) | SKILL-380 subtask 8 |
| `bill-pr-description` | `phase:pr` / `pr-description` strategy | SKILL-380 subtasks 9, 11 |
| `bill-boundary-history` | `boundary-history` strategy | SKILL-380 subtask 11 |
| `bill-boundary-decisions` | `boundary-history` strategy prompt fragments | SKILL-380 subtask 11 |
| `bill-update-check` | `operation:update-check` | SKILL-382 subtask 1 |
| `bill-release` | `operation:release` | SKILL-382 subtask 1 |
| `bill-unit-test-value-check` | `operation:unit-test-value-check` | SKILL-382 subtask 2 |
| `bill-feature-guard` | `operation:feature-guard` | SKILL-382 subtask 2 |
| `bill-feature-guard-cleanup` | `operation:feature-guard-cleanup` | SKILL-382 subtask 2 |
| `bill-pr-review-fix` | `operation:pr-review-fix` | SKILL-382 subtask 3 |
| `bill-feature-verify` | `operation:verify` | SKILL-382 subtask 4 |
| `bill-monitor` | deleted; `skill-bill goal status` stays CLI-only | this bundle |

Main stays usable after this bundle's PR: `/skill-bill` reaches the skeleton, every
phase, and every operation.

## Acceptance Criteria

1. A test installs into a temporary `HOME` and asserts the listed skill catalog is exactly `skill-bill`. `skills/bill-feature` and `skills/bill-monitor` do not exist. `bill-monitor` is not an operation.
2. No file under `../../../platform-packs` or `skills/` declares `internal-for` a retired skill. Pack specialists install under the `skill-bill` install directory and stay unlisted native-agent inputs, not slash commands.
3. A test installs over a home that has the old skills and asserts their links and installed copies are removed.
4. No production prompt or doc tells an agent or operator to invoke a retired skill by its old name.
5. Telemetry `skill` values, the feature-verify `workflow_name` default, and the workflow skill label stored by `WorkflowStateWrites` that name retired skills keep their current wire values.
6. `../../../AGENTS.md`, `docs/skill-source-generation.md`, `docs/internal-skills-architecture.md`, `docs/getting-started.md`, and the README describe `/skill-bill` as the only listed skill. `runtime-kotlin/agent/decisions.md` records the retirement and the stable telemetry labels.

## Executable scope

Two subtasks, one commit each.

1. **Re-parent sidecars and the quality-check route to `skill-bill`**
   (`spec_subtask_1_sidecar-reparenting.md`).
   - Split condition: install-policy change with its own failure mode (sidecars
     installing nowhere); must be green while the old parents still exist.
2. **Retire listed skills and delete `bill-monitor`**
   (`spec_subtask_2_retire-listed-skills.md`).
   - Split condition: the deletion commit, so install is never half-migrated.

Dependencies: 2→1.

## Fixture ledger

SKILL-380's fixture ledger stays in force. This bundle adds one planned change:

| Fixture | Re-baselined by | Allowed change |
| --- | --- | --- |
| Every step prompt, goal planning prompts, spec-writer output, and phase-run and operation outputs | 2 | retired skill names in prohibition lines and defaults (`bill-code-check`, `bill-code-review`) replaced by `skill-bill phase validation` / `skill-bill phase review` |

## Constraints

- SKILL-380's constraints stay in force: Kotlin style, SKILL-378.2's rules, loud
  failure, local clone for Spotless.
- **No install inside the goal.** No subtask runs `./install.sh` against the real home.
  The goal runs on the installed skills; reinstalling mid-goal would delete skills its
  remaining phases still name. Prove install behaviour in a temporary `HOME`.
- Retire only what already has a home. If a rule in a retiring skill has no home in
  SKILL-380 or SKILL-382, block instead of porting it here.
- Recheck every file anchor at the start of each subtask against the current tree.

## Non-goals

- New phases, strategies, or operations.
- Deleting platform-pack specialist `content.md` or native-agent generation.
- Removing `skill-bill new/show/fill/render` authoring CLI.
- Migrating `bill-monitor` to an operation.
- Renaming telemetry `skill` labels or stored feature-verify `workflow_name` values.
- The prose review-finding pipeline (its own follow-up bundle).

## Validation strategy

- **Per subtask:** `cd runtime-kotlin && ./gradlew check`, plus CLI, application, and
  infra-skills.
- **Install.** A temporary-`HOME` install test for the catalog and sidecar placement,
  and one over a home with the old skills.
- **Docs grep.** The install-catalog test greps production docs for `/bill-monitor` and
  `/bill-feature` as a listed skill.
- **Fixtures.** SKILL-380 and SKILL-382 fixtures match except the ledger's change.
- **Test review.** Changed tests go through the installed `bill-unit-test-value-check`.
- No tests ran during preparation.

## Next path

```bash
skill-bill goal SKILL-383
```

After the PR merges, run `./install.sh` from a local clone. That install is the first to
ship the single `skill-bill` catalog.
