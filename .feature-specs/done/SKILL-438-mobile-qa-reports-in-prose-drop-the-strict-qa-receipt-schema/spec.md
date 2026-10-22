# SKILL-438 - mobile-qa-reports-in-prose-drop-the-strict-qa-receipt-schema

## Mode

decomposed

## Intended Outcome

SKILL-438 Mobile QA reports in prose: drop the strict QA receipt schema

## Problem

A real QA run was thrown away by schema validation. capmo-android ENG-1587, `skill-bill phase qa`, invocation phr-70d3bca5-fa0c-4ed6-a81d-32cbae3f6240: the agent exercised 7 flows on a device and found a Major bug, but `QaReceiptSettlement.settle()` recorded `incomplete` with "Exercised flows: none" because the receipt broke `qa-receipt-schema.yaml`: one assertion description was 559 characters (cap 500), `fallbacks[0].kind` was `device_substitution` (not in the enum), and `fallbacks[0].reason` exceeded 200 characters. The rejected receipt is still at `~/.skill-bill/qa/capmo-android-3acb7759764db481/phr-70d3bca5-fa0c-4ed6-a81d-32cbae3f6240/receipt.json` (read-only reference; do not modify it).

Contributing causes:
- `QaPromptSections.supplement` points the agent at `orchestration/contracts/qa-receipt-schema.yaml`, a path relative to the skill-bill repo that does not exist in the target repository.
- `../../../platform-packs/kmp/qa/android-qa.md` documents only `simulator_substitution`; `target_substitution` is undocumented.
- `android-qa.md:33` and `:45` (and the iOS guidance equivalent) tell the agent to skip with `ambiguous_target` when more than one authorized device is connected and none is named. An earlier ENG-1587 verify run skipped QA entirely for that reason with a phone and an emulator connected.

## Principle (operator decision, overrides the general runtime-contract rules in AGENTS.md for this agent output)

skill-bill works in prose. Agent-written QA output gets no strict schema, no enums, no length caps, and no validation or repair loop. The runtime never discards a QA report because of its format.

## Requirements

1. The QA agent writes a prose markdown report (`report.md` in the invocation directory) instead of the JSON receipt. The platform pack guidance (`android-qa.md`, `ios-qa.md`) describes its shape in prose: an opening verdict line (passed, findings, incomplete, or skipped, with the reason in words), the device or simulator used and why, the flows exercised and what was observed, each finding as its own section headed `### <finding-id> <title>` with steps, expected, observed, and evidence file paths, what was not tested and why, the acceptance criteria and whether each passed or failed, and cleanup.
2. The runtime keeps the report verbatim and extracts only what it needs, leniently: the verdict word, the finding ids from the finding headings, and each finding section text. Unknown words, extra sections, long text, and missing optional parts are accepted. A report with no recognizable verdict settles `incomplete` and the full report is still printed, stored, and shown. Only a missing report file (or a stopped agent) settles without report text.
3. The full goal run keeps working end to end: goal finalization `qa -> qa_fix -> build -> qa` with three repair attempts across relaunches; `qa_fix` receives each open finding section verbatim plus the evidence paths named in it; `fixed` / `not_fixed` tracking matches findings by id across QA runs; findings left after the third attempt reach the PR body; `skill-bill goal status` still prints `qa_findings: fixed=<n> not_fixed=<m>` with the open ids; QA never blocks the goal; `qa:off` still skips without probing a device. Workflows already in flight with stored JSON receipts must still load (read old records leniently or quarantine and regenerate per existing persistence rules).
4. `operation:verify qa:on` and standalone `phase qa` keep their behavior and print the prose report (verdict, report, warnings, cleanup) instead of the structured flows/assertions/criteria rendering. The verify verdict still treats open QA findings and skipped/incomplete QA as before.
5. Device selection: when several authorized targets are connected and none is named, the agent picks any free one it can lease (`skill-bill qa lease acquire`), tries another when one is unusable (for example out of storage), and names in the report which device it used and why. `ambiguous_target` is no longer a skip outcome. A named serial is still honoured.
6. Delete what this replaces rather than keeping it alongside: `orchestration/contracts/qa-receipt-schema.yaml` and its schema port/locator/parity test, `QaReceiptValidator` schema checks, `QaReceiptNormalization` claim downgrades, the structured flow/assertion/fallback/target model, and the criteria-matrix rendering. Keep identity binding (the runtime knows the tested HEAD/tree itself; do not make the agent echo it) and HEAD/tree/worktree change warnings, device lease release, and evidence living under the invocation directory.
7. Desktop tray: the QA panels (`feature/detail` QA findings and criteria, `RuntimeWorkMapper`, work models) show the prose report and the extracted findings with fixed/not_fixed state instead of the structured criteria matrix.
8. Update `../../../docs/runtime-command-guidance.md` QA paragraphs and the skill-bill skill content that describe receipts, schema validation, downgrade rules and `ambiguous_target`.

## Acceptance Criteria

1. A QA report with very long text, unknown words, and extra sections settles with its stated verdict and findings; nothing is discarded for format.
2. A goal finalization test drives QA (prose report with findings) -> qa_fix -> build -> QA (prose report passed) and records the findings as fixed; a second test leaves a finding open after three attempts and it reaches the PR body as not fixed.
3. Standalone `phase qa` and `operation:verify qa:on` print the prose report.
4. With two free devices connected and none named, the guidance directs the agent to pick one; no `ambiguous_target` skip remains in guidance or runtime.
5. No QA receipt JSON schema, enum, or length cap remains for agent-written output.
6. Full project validation passes.

## Non-Goals

- Changing other agent contracts (review, plan, monitor) in this goal.
- Changing the device lease mechanism (`QaDeviceLeasePort`, `FileSystemQaDeviceLeases`, `qa-device-lease-schema.yaml`).
- Modifying the rejected ENG-1587 `receipt.json` or any other receipt already on disk.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection.

## Decomposition

The operator's suggested split was "runtime first, then the desktop tray". The plan deviates from it:

- **Subtask 1, prose QA report** (requirements 1–4, 6, 7, and the receipt parts of 8): runtime settlement, goal loop, verify and standalone printing, persistence, deletions, guidance and docs for the report shape, and the desktop tray. The desktop maps the runtime-ports QA report model (`StandaloneQaReport`, `PhaseRunResult.Completed.qaReport`, `FeatureTaskQaOutcome`) that this subtask replaces. A runtime-only commit would either leave the desktop build broken or keep the criteria matrix alive until a later commit. So the tray change ships in the same commit.
- **Subtask 2, free device selection** (requirement 5 and the `ambiguous_target` parts of 8): drops the `ambiguous_target` skip from the runtime enum, guidance, docs, and skill content. It doesn't depend on the report format, so it stands alone on any tree.

The subtasks have no dependency on each other. Each applies its rule to what is present on the tree it runs on.

The six parent criteria are operator-authored and kept unchanged. Parent criteria 1, 3, and 5 map to subtask 1 production criteria. Parent criterion 2 maps to subtask 1's goal-loop criterion, and its two tests are listed under subtask 1's Test Obligations. Parent criterion 4 maps to subtask 2. Parent criterion 6 is the validate phase's gate for both subtasks.

## Validation Strategy

The validate phase runs the repository's full gate (`./gradlew check`, which includes agent-config validation) plus the desktop-tray build and tests, then checks every acceptance criterion above against the tree. No install, uninstall, or install-sync step is part of this goal.

## Next Path

```bash
skill-bill goal SKILL-438
```
