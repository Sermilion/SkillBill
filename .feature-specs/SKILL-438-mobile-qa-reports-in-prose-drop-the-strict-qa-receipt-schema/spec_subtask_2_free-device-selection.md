# SKILL-438 subtask 2 - Pick a free device instead of skipping with ambiguous_target

Parent spec: `.feature-specs/SKILL-438-mobile-qa-reports-in-prose-drop-the-strict-qa-receipt-schema/spec.md`

## Scope

This subtask covers parent requirement 5 and the `ambiguous_target` part of requirement 8. When several authorized targets are connected and none is named, the QA agent no longer skips. It picks a free target it can lease.

Areas touched:

- `runtime-kotlin/.../QaExecutionPolicy.kt`: remove `QaSkipReason.AMBIGUOUS_TARGET` and any runtime branch that produces or maps it.
- `platform-packs/kmp/qa/android-qa.md`: the "Selection" section (currently around lines 29–35) and the AVD-list step (currently around line 45).
- `platform-packs/ios/qa/ios-qa.md`: the target-selection line (currently around line 31).
- The QA run briefing in `QaPromptSections.supplement`, wherever it names `ambiguous_target`.
- `docs/runtime-command-guidance.md`, `docs/observability-policy.md`, and the skill-bill skill content, wherever they name `ambiguous_target`.
- If it is still present on the tree, `orchestration/contracts/qa-receipt-schema.yaml`'s skip-reason enum entry.

## Acceptance Criteria

1. The Android and iOS QA guidance tells the agent what to do when several authorized targets are connected and none is named. The agent takes any free one it can lease with `skill-bill qa lease acquire`, and tries another free target if the chosen one is unusable (for example, out of storage). It then names in its QA report which device or simulator it used and why. A named serial, UDID, or AVD is still honoured.
2. `QaSkipReason` has no `AMBIGUOUS_TARGET` value. No runtime source, contract, QA guidance, QA run briefing, doc, or skill content names `ambiguous_target` as a skip outcome. `agent/history.md` and `agent/decisions.md` are historical records and are excluded.

## Non-Goals

- The QA report format, settlement, and receipt removal. Subtask 1 owns them. Keep whichever output format the guidance describes on the tree this subtask runs on.
- The device lease mechanism (`QaDeviceLeasePort`, `FileSystemQaDeviceLeases`, `qa-device-lease-schema.yaml`) and the `skill-bill qa lease` command behavior.
- Other skip reasons. `UNAUTHORIZED_TARGET`, `OFFLINE_TARGET`, `DEVICE_CONTENDED`, `PRODUCTION_ONLY`, and the rest stay as runtime-authored skips. The guidance still says to skip as device-contended when every authorized target is leased by someone else.

## Dependency Notes

- None. This change doesn't depend on the report format and applies to whatever is on the tree.
- Assumption for implement to confirm: preplan describes `AMBIGUOUS_TARGET` as agent-reported only. If a runtime device probe emits it, that probe stops treating several connected targets as a skip. It leaves target choice to the agent and does not lease on the agent's behalf.

## Test Obligations

- Update any existing test that references `AMBIGUOUS_TARGET` or the `ambiguous_target` string, for example in `QaExecutionPolicy` tests or `runtime-cli/src/repoTest/.../InstallerShellDelegationTest.kt`, so it no longer expects that value. Add no new test: the change removes an enum value and edits guidance text, and audit can confirm both by reading the tree.

## Validation Strategy

The validate phase runs `./gradlew check`, which includes agent-config validation of the platform-pack guidance, and checks both criteria by reading the tree. Implement and audit run no builds or tests. No install, uninstall, or install-sync step belongs to this subtask.

## Implementation Details

Run the tasks in order. Task 1 sets the edit list for tasks 2–7. This subtask runs on whatever tree it finds: if subtask 1 has already landed, some listed files may be gone or reworded. Edit only what is present, and never recreate a deleted file.

### Task 1 — Find every occurrence (serves AC 2)

- Do one case-insensitive search of the working tree for `ambiguous_target`, `AMBIGUOUS_TARGET`, and `ambiguous target`. Cover `runtime-kotlin/`, `orchestration/`, `platform-packs/`, `docs/`, `skills/`, `desktop-tray/`, and the repo-root markdown files.
- Exclude `runtime-kotlin/agent/history.md`, `runtime-kotlin/agent/decisions.md`, and `.feature-specs/`. The bundles under `.feature-specs/` are planning records. They are not runtime source, contract, guidance, briefing, doc, or skill content.
- Each hit goes to one of tasks 2–7. A hit outside those buckets, such as a resource copy under `src/main/resources` or a generated agent config, follows the closest task's rule: remove the skip outcome and point to the pick-a-free-target behavior.
- Assumption to confirm: the digest never found the "skill-bill skill content" file. This search is how implement finds it, so do not search for it separately.

### Task 2 — Remove the runtime enum value (serves AC 2)

- In `QaExecutionPolicy.kt` (engine `featuretask/qa` area), delete `AMBIGUOUS_TARGET("ambiguous_target")` from `QaSkipReason`. Leave `UNAUTHORIZED_TARGET`, `OFFLINE_TARGET`, `DEVICE_CONTENDED`, `PRODUCTION_ONLY`, and the other values as they are.
- Remove every `when` branch, wire-value lookup, mapping, or message keyed on `AMBIGUOUS_TARGET`. If an exhaustive `when` has no `else`, delete the branch. Do not add a catch-all.
- Assumption to confirm from task 1: preplan saw `AMBIGUOUS_TARGET` only as an agent-reported skip. If a runtime device probe or preflight emits it when several targets are connected, change that probe to continue with no target chosen. The agent then selects and leases a target. The runtime does not lease for the agent and does not pick a device.
- Persisted values: a stored receipt or outcome row may hold the literal `ambiguous_target` as a skip reason. If `QaSkipReason` is decoded strictly from stored data, an unknown value must not throw. Check whether the decoder already tolerates unknown values. Only if it does not, map an unknown stored skip reason to the decoder's existing lenient fallback. Do not add a new enum value for it. If no stored data is decoded into `QaSkipReason`, nothing changes here.

### Task 3 — Rewrite Android selection guidance (serves AC 1, AC 2)

In `platform-packs/kmp/qa/android-qa.md`:

- Replace the multi-device `ambiguous_target` skip in the "Selection" section (about lines 29–35) with these rules:
  - If the operator named a serial or AVD, use it.
  - Otherwise, if several authorized targets are connected, pick any free one and lease it with `skill-bill qa lease acquire`.
  - If the leased target is unusable (for example, out of storage, locked, or failing install), release that lease and try another free target.
  - In the QA report, name the device used and why it was chosen.
  - If every authorized target is leased by someone else, skip as device-contended, as the guidance already says.
- In the AVD-list step (about line 45), apply the same rule to AVD choice: if several AVDs are listed and none is named, pick one that can be booted and leased.
- Keep the section headings and file structure unchanged so agent-config validation still passes. Keep whatever report-format words are on the tree, whether receipt or prose; subtask 1 owns that wording.

### Task 4 — Rewrite iOS selection guidance (serves AC 1, AC 2)

- In `platform-packs/ios/qa/ios-qa.md` (about line 31), make the same change as task 3 for simulators and devices.
- A named UDID or simulator is used.
- Otherwise, pick any free booted or bootable simulator, or any free device, and lease it with `skill-bill qa lease acquire`.
- If it is unusable, release it and try another free one.
- Name the target used and why in the report.
- The device-contended skip stays for the case where every target is leased by someone else.
- Keep the "Simulator fallback" and "Verdict" sections, apart from removing any `ambiguous_target` mention from the Verdict skip list.

### Task 5 — QA run briefing (serves AC 2)

- In `QaPromptSections.supplement` (engine `featuretask/slot/qa/QaPromptSections.kt`), remove any `ambiguous_target` wording.
- If the briefing lists skip outcomes, add one sentence: when several free targets are present and none is named, pick one and lease it instead of skipping.
- Keep the `device_contended` instruction. `VerifyQaStep.launch` reuses this text, so do not edit it a second time anywhere else.

### Task 6 — Docs and skill content (serves AC 2)

- In `docs/runtime-command-guidance.md`, `docs/observability-policy.md`, and the skill content file found in task 1, remove `ambiguous_target` as a skip outcome. Where the text lists skip reasons, either drop the entry or replace it with one line on the pick-a-free-target behavior.
- Do not edit `runtime-kotlin/agent/history.md` or `runtime-kotlin/agent/decisions.md`.
- `runtime-kotlin/ARCHITECTURE.md` needs an edit only if task 1 finds the string in it.

### Task 7 — Receipt schema enum, only if present (serves AC 2)

- If `orchestration/contracts/qa-receipt-schema.yaml` is still on the tree, remove `ambiguous_target` from its skip-reason enum.
- Assumption to confirm: a bare enum-value removal needs no contract-version bump. Subtask 1 deletes this schema anyway.
- If `QaSchemaContractVersionTest` or another parity check compares this enum with `QaSkipReason`, the two now match again after task 2. Only change the contract version if that test demands it.
- If the file is gone, skip this task.

### Task 8 — Update existing tests that name the value (serves AC 2; no new tests)

- Edit every test that task 1 found referencing `AMBIGUOUS_TARGET` or `ambiguous_target`. Expected places are `QaExecutionPolicy` tests, QA strategy and lifecycle tests, `runtime-cli/src/repoTest/.../InstallerShellDelegationTest.kt`, and test fixtures such as `QaTestReceipts.kt` if present.
- A test that used `AMBIGUOUS_TARGET` only as an example skip reason switches to another surviving reason, such as `OFFLINE_TARGET` or `DEVICE_CONTENDED`, and keeps its assertion.
- A test that asserted the multi-device skip itself is deleted. That behavior no longer exists.
- If `InstallerShellDelegationTest` pattern-matches installed guidance text for `ambiguous_target`, remove that pattern. Do not add a replacement assertion on the new prose.
- Add no new test. Removing an enum value and editing guidance gives no realistic bug for a test to catch that compilation, task 1's search, and audit's tree read do not already catch.

### Constraints

- Do not touch the lease mechanism: `QaDeviceLeasePort`, `FileSystemQaDeviceLeases`, `qa-device-lease-schema.yaml`, and the behavior of the `skill-bill qa lease` command.
- Do not change the QA report or receipt format, settlement, or other skip reasons.
- Implement runs no build, test, or `check`. The validate phase owns `./gradlew check`, including agent-config validation of the edited platform-pack guidance.
- No install, uninstall, or install-sync step.

## Next Path

```bash
skill-bill goal SKILL-438
```
