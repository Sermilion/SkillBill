# SKILL-380 Subtask 12 - /skill-bill dispatcher for the skeleton and phases

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

Adds the `/skill-bill` listed skill beside the old ones, so this bundle's work is usable
from a slash command, not only the CLI. SKILL-382 adds the `operation:` forms, and
SKILL-383 retires the old skills.

**Dispatcher (`skills/skill-bill/content.md`).** Keep today's `bill-feature` ceremony:
update-check, intake, token forwarding, preflight, one confirmation question, rehydrate,
launch, relay. Where `bill-feature` says "Invoke `bill-feature-spec`", the dispatcher
calls `skill-bill phase plan <intake>`. The dispatcher accepts `phase:<name>` and
`key:value` tokens and translates them into the `skill-bill phase <name> [<intake>]
[key:value …]` subcommand (subtask 8).

Forms:

- `/skill-bill <intake>` — skeleton (today's `bill-feature`, one confirmation)
- `/skill-bill <intake> phase:<name>` / `/skill-bill phase:<name>`

The `phase:` forms call `skill-bill phase … --agent <currently-executing-agent>` and contain
no product checklists from other skills. An `operation:` token is refused with a
message that operations arrive with SKILL-382, until that bundle adds them.

**Review mode.** The skeleton form keeps today's `code-review:inline|auto` token and
forwards it as `--code-review-mode`. `phase:review` forwards `mode:inline|delegated`
(default `inline`). The dispatcher does not resolve modes; the runtime selection does.

**Install naming rule.** Install discovery admits only `bill-*` directories today.
Admit exactly `skill-bill` as well, everywhere the prefix is enforced:

- `InstallPlanSkillDiscovery` (listed-skill discovery)
- `SkillBillUninstallService` (so uninstall removes the `skill-bill` link and nothing
  else outside `bill-*`)
- `InstallLegacySkillNames` filter
- `AuthoringDiscovery`
- repo-validation skill-reference and README-row patterns, and
  `ScaffoldManifestEditsReadme`

Recheck the census at start. Do not widen the rule to any other name.

**Agent add-on consumer.** `AgentAddonConsumer` has one entry, `BILL_FEATURE`
(`bill-feature`), and add-ons render into the consumer skill. Add the `skill-bill`
consumer and render the `execution-budget` add-on into both skills while both exist.
Persisted add-on selections that record `bill-feature` still decode. SKILL-383 drops the
`bill-feature` consumer when it deletes that skill.

**Skill class registration.** Skills are governed by `orchestration/skill-classes/*.yaml`.
`feature-launch-warning.yaml` matches `exact: bill-feature` and injects its sidecars
(`peak-hours-warner.md`, `shell-ceremony.md`, `telemetry-contract.md`). Add
`exact: skill-bill` so the dispatcher renders with the same sidecars, and update the
`SkillClassLoaderTest` golden. Create the skill through the governed authoring path so
`skill-bill validate` passes on the new tree.

**Docs.** `AGENTS.md` and the README list `/skill-bill` and its forms beside the old
skills.

## Verification ownership

Implement and audit can't run builds, tests, generators, or the fixture capture
(added 2026-09-27). Audit checks each behavioural criterion against the code and the
tests that assert it, and does not list a criterion as remaining just because nothing
has run.
- Validate runs `skill-bill validate` on the tree, the capture command
  `cd runtime-kotlin && SKILL_BILL_SLOTBASELINE_CAPTURE=1 ./gradlew :runtime-engine:test --tests skillbill.engine.featuretask.slotbaseline.SlotBaselineCaptureTest`
  to confirm the full-run and phase-run fixtures are unchanged, then `./gradlew check`.

## Acceptance Criteria

1. `skills/skill-bill/content.md` exists and declares the `feature-launch-warning` class sidecars, and an install test into a temporary `HOME` asserts it lists `skill-bill` together with every old listed skill.
2. `/skill-bill <intake>` with no `phase:` launches the skeleton with the one confirmation gate. A missing spec routes to `phase:plan`, not to `bill-feature-spec`.
3. A routing table test proves every `phase` definition from subtasks 8–9 is reachable, and that `code-review:` and `mode:` tokens reach the runtime unchanged. `phase:` and `operation:` together remains a usage error.
4. Uninstall removes the `skill-bill` link and leaves an unrelated non-`bill-` skill in place.
5. The `execution-budget` add-on renders into `skill-bill` and `bill-feature`, and a persisted selection naming `bill-feature` still decodes.
6. No old listed skill's content or routing is removed, and this subtask's diff changes no fixture file.

## Non-goals

- `operation:` forms (SKILL-382).
- Deleting any `skills/bill-*` tree or re-parenting sidecars (SKILL-383).
- Running `./install.sh` against the real home (parent constraint).

## Dependency notes

- Depends on subtasks 9 and 11, so every `phase:` target exists with its final rules.

## Validation strategy

Catch: `skill-bill` not installing; uninstall removing an unrelated skill; a dispatcher
target missing; an old skill breaking; add-on selections failing to decode. Cover with a
temporary-`HOME` install test, an uninstall test, the routing table test, and a
persisted-selection decode test. Run `cd runtime-kotlin && ./gradlew check` plus CLI and
infra-skills. `bill-unit-test-value-check` on changed tests.

## Next path

Last subtask. `skill-bill goal SKILL-380` opens the PR; run `./install.sh` from a local
clone after merge. Then SKILL-382.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_12_skill-bill-dispatcher.md
