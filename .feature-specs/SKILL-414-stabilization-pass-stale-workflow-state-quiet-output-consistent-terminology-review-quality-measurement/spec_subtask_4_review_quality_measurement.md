# SKILL-414 Subtask 4 - Measure review quality

Parent spec: `.feature-specs/SKILL-414-stabilization-pass-stale-workflow-state-quiet-output-consistent-terminology-review-quality-measurement/spec.md` (Area 4).

## Scope

Kotlin paths are under `runtime-kotlin/`. Other paths are repo-relative.

### Per-finding category

1. **Format.** Extend the required bullet finding format in `orchestration/review-orchestrator/PLAYBOOK.md` with a `category=<wire_value>` field after Confidence:
   - `- [F-001] <Severity> | <Confidence> | category=<wire_value> | <file:line> | <description>`
   - commits variant: `- [F-001] <Severity> | <Confidence> | category=<wire_value> | commits=<sha>[,<sha>] | <file:line> | <description>`
   - List the allowed `ReviewIssueCategory` wire values next to the format. Implement reads the enum in `runtime-domain/.../review/model` for the full list.
   - Grep for `[F-001]` and update every other place that states the format (skill-class yaml such as `orchestration/skill-classes/code-review-specialist.yaml` and `code-review-orchestrator.yaml`, Kotlin prompt or contract constants, fixtures) to the same line.
   - Only the format line and the category vocabulary change. Finding rules and lane scopes stay as they are.
2. **Parser.** The bullet branch of `parseReviewFindings` (`runtime-domain/.../review/parsing/`) reads an optional `category=` field into `ImportedFinding.issueCategory`. An unknown value becomes `OTHER`, so the existing fallback chain in `resolveReviewIssueCategory` applies. Bullets without the field parse exactly as today. An explicit per-finding category then wins over the review-wide routed-label fallback through the existing `explicitCategory` path.
3. **Telemetry.** Confirm that `ReviewFinishedPayloads.reviewFindingDetails` passes the per-finding category through at the default `anonymous` level; only location, description and note are blanked there. Change nothing about telemetry levels.

### Review evaluation set

4. **Cases.** Add `evals/review/` with a `README.md` and one directory per case. Each case has:
   - `case.yaml`: repo, PR, branch, commit, base revision, and the command an operator runs to produce a review register for it.
   - `expected-findings.yaml`: per entry an id, file, line or line range, severity, category, lane, `label: true_positive | non_issue`, a short rationale, and an optional `status: needs_curation`.
   - Store revision pointers, not diffs or source excerpts. Cases reference private repositories such as capmo-android, and this repository must not copy their code.
5. **Scorer.** Write a pure Kotlin scorer. It parses a review register with `ReviewParser.parseReview` and matches each reported finding to an expected entry on the same file within a line window (default ±5, overridable per entry). It reports:
   - true positives, missed true positives, and reported findings matching a `non_issue` (false positives)
   - unlabeled reported findings, listed so a curator can label them
   - precision (true positives / reported) and recall (matched / expected true positives), per lane and overall

   Entries marked `needs_curation` are excluded from scoring and listed in the output.

   **Lane attribution.** Use the per-finding specialist or routed-skill attribution if `ReviewParser` exposes one. Otherwise bucket reported findings as `unattributed` for precision, and compute recall per lane from the expected entries. Record which source was used in `census_subtask_4.md`.
6. **On-demand runner.** Running the eval is on demand and not part of `./gradlew check`. Producing a register needs an agent review of the case revision.
   - Put the scorer and an on-demand runner test in the test source set of the module that owns `ReviewParser`. Use that module's repo-reading test source set (such as `repoTest`) if it has one. Do not add production code or a CLI command.
   - The runner test skips unless `SKILL_BILL_REVIEW_EVAL_REGISTER_DIR` is set. When set, it scores each case that has a `<case-id>.md` register in that directory and prints the per-lane report.
   - `evals/review/README.md` documents the steps: produce the register with `skill-bill phase review` against the case revision, then run the Gradle test filter with the env var.
   - Implement confirms the Gradle project path and source-set conventions, and records the final command in the README.
7. **First case** `evals/review/capmo-android-pr-3110/`: repo `capmo-android`, PR `3110`, branch `feat/FP-5545-accept-into-open-entry`, commit `98b49fb47`.
   - Populate its verified P0/P1 findings as `true_positive` entries.
   - Add the custom-text-resurrection P1 as a `non_issue` with rationale "device QA: not reachable".
   - The verified finding details are not in this repository. Implement looks for a local capmo-android checkout and recorded review data for that PR, such as imported review runs in the local skill-bill database.
   - Any entry whose file, line or category cannot be sourced is written with `status: needs_curation`, and `census_subtask_4.md` lists what the user must supply. Do not invent findings.

## Acceptance Criteria

1. `orchestration/review-orchestrator/PLAYBOOK.md` states the bullet finding format with a `category=<wire_value>` field, in both the plain and commits variants, and lists the allowed `ReviewIssueCategory` wire values. Every other location that states the finding format states the same line.
2. The bullet finding parser sets `ImportedFinding.issueCategory` from a `category=` field, maps an unknown value to `OTHER`, and parses bullets without the field as before.
3. Parser tests assert three cases: a bullet with `category=` resolves to that category even when the review routes to a different specialist; a bullet without the field keeps the previous fallback result; and a commits-variant bullet with a category parses both commits and category.
4. A test asserts that the review-finished telemetry payload at the `anonymous` level carries each finding's own category while location and description stay blanked.
5. `evals/review/README.md` documents the case format, the scoring rules, and the on-demand command.
6. A pure scorer exists in a test source set and reports true positives, false positives against `non_issue` entries, unlabeled findings, precision and recall per lane and overall, and excludes `needs_curation` entries.
7. Scorer unit tests use canned registers to assert correct precision and recall. They cover a line-window match, a reported finding hitting a `non_issue` counting as a false positive, and an unlabeled finding excluded from both true and false positives.
8. An on-demand runner test exists that is skipped unless `SKILL_BILL_REVIEW_EVAL_REGISTER_DIR` is set.
9. `evals/review/capmo-android-pr-3110/` holds `case.yaml` with the repo, PR, branch and commit above, and `expected-findings.yaml` containing the custom-text-resurrection P1 as a `non_issue` with the device-QA rationale. The verified P0/P1 findings are present as `true_positive` entries, or each unavailable one is marked `needs_curation` and listed in `census_subtask_4.md`.
10. No file under `evals/review/` contains diff hunks or source excerpts from the evaluated repository.

## Test Obligations

- AC 3: per-finding category wins over the review-wide fallback. This is the bug behind ~1,048 uncategorized findings. Old bullets still parse, guarding compatibility with older registers.
- AC 4: guards the telemetry boundary. The category flows per finding while privacy blanking is unchanged.
- AC 7: guards scorer arithmetic, which every future eval result depends on.
- No test for the YAML case files beyond what the scorer tests load.

## Non-Goals

- No change to review finding rules, severities or lane scopes.
- No telemetry privacy or level change; `anonymous` stays the default.
- No user-facing command for evals and no eval run inside `./gradlew check`.
- No automated agent review inside the eval runner.

## Dependency Notes

Independent of subtasks 1-3. Snapshot or golden fixtures that pin the finding-format text are updated in this subtask.

## Validation Strategy

The validate phase runs `./gradlew check`: parser, telemetry and scorer tests, render snapshots of the edited orchestration content, and agent-config validation. The on-demand runner stays skipped there. Implement and audit run nothing. Audit reads the PLAYBOOK, parser, eval files and `census_subtask_4.md` against each criterion.

## Next Path

```bash
skill-bill goal SKILL-414
```
