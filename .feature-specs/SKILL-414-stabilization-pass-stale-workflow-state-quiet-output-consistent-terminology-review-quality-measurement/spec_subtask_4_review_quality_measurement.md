# SKILL-414 Subtask 4 - Measure review quality

Parent spec: `.feature-specs/SKILL-414-stabilization-pass-stale-workflow-state-quiet-output-consistent-terminology-review-quality-measurement/spec.md` (Area 4).

## Scope

Kotlin paths are under `runtime-kotlin/`. Other paths are repo-relative.

### Prose review output

Reviewers write findings as prose. This subtask adds no required field, delimiter, finding template, category vocabulary, report schema, or production parser change. Existing review output and completion behavior stay unchanged.

Categories may appear in prose when they help explain a finding. Reviewers do not have to supply them, and runtime completion does not depend on extracting them. Measurement must accommodate the report the reviewer wrote. Missing metadata or prose the scorer cannot interpret becomes a curation item, never an invalid review or a reason to rerun it.

Telemetry continues to use the categories available through its existing paths. This subtask does not change telemetry levels or privacy blanking.

### Review evaluation set

1. **Cases.** Add `evals/review/` with a `README.md` and one directory per case. Each case has:
   - `case.yaml`: repo, PR, branch, commit, base revision, and the command an operator runs to produce a review register for it.
   - `expected-findings.yaml`: per entry an id, file, line or line range, severity, category, lane, `label: true_positive | non_issue`, a short rationale, and an optional `status: needs_curation`.
   - Store revision pointers, not diffs or source excerpts. Cases reference private repositories such as capmo-android, and this repository must not copy their code.
2. **Scorer.** Write a pure Kotlin scorer. It uses the existing `ReviewParser.parseReview` only as a best-effort measurement reader, without changing production parsing or imposing an output format on reviewers. It matches each extracted finding to an expected entry on the same file within a line window (default ±5, overridable per entry). It reports:
   - true positives, missed true positives, and reported findings matching a `non_issue` (false positives)
   - unlabeled reported findings, listed so a curator can label them
   - precision (true positives / reported) and recall (matched / expected true positives), per lane and overall

   Entries marked `needs_curation` are excluded from scoring and listed in the output. Reports or passages that cannot be interpreted, and findings without enough location metadata to match, are listed for curation. They do not fail the runner or cause an agent review to rerun. If extraction is incomplete, label the scores as partial; do not report a complete result.

   **Lane attribution.** Use the per-finding specialist or routed-skill attribution if `ReviewParser` exposes one. Otherwise bucket reported findings as `unattributed` for precision, and compute recall per lane from the expected entries. Record which source was used in `census_subtask_4.md`.
3. **On-demand runner.** Running the eval is on demand and not part of `./gradlew check`. Producing a register needs an agent review of the case revision.
   - Put the scorer and an on-demand runner test in the test source set of the module that owns `ReviewParser`. Use that module's repo-reading test source set (such as `repoTest`) if it has one. Do not add production code or a CLI command.
   - The runner test skips unless `SKILL_BILL_REVIEW_EVAL_REGISTER_DIR` is set. When set, it scores each case that has a `<case-id>.md` register in that directory and prints the per-lane report.
   - `evals/review/README.md` documents the steps: produce the register with `skill-bill phase review` against the case revision, then run the Gradle test filter with the env var.
   - Implement confirms the Gradle project path and source-set conventions, and records the final command in the README.
4. **First case** `evals/review/capmo-android-pr-3110/`: repo `capmo-android`, PR `3110`, branch `feat/FP-5545-accept-into-open-entry`, commit `98b49fb47`.
   - Populate its verified P0/P1 findings as `true_positive` entries.
   - Add the custom-text-resurrection P1 as a `non_issue` with rationale "device QA: not reachable".
   - The verified finding details are not in this repository. Implement looks for a local capmo-android checkout and recorded review data for that PR, such as imported review runs in the local skill-bill database.
   - Any entry whose file, line or category cannot be sourced is written with `status: needs_curation`, and `census_subtask_4.md` lists what the user must supply. Do not invent findings.

## Acceptance Criteria

1. Reviewers continue to write prose. No required category field, new finding template, report schema, production parser change, or new review validation is introduced.
2. `evals/review/README.md` documents the case format, scoring rules, on-demand command, and the limits of best-effort extraction from prose.
3. A pure scorer exists in a test source set and reports true positives, false positives against `non_issue` entries, unlabeled findings, precision and recall per lane and overall, and excludes `needs_curation` entries. Uninterpretable prose and missing matching metadata produce curation items rather than failures. Incomplete extraction produces explicitly partial scores.
4. Scorer unit tests use canned registers to assert correct precision and recall. They cover a line-window match, a reported finding hitting a `non_issue` counting as a false positive, and an unlabeled finding excluded from both true and false positives. A prose report without extractable finding locations produces curation output and partial scores without failing.
5. An on-demand runner test exists that is skipped unless `SKILL_BILL_REVIEW_EVAL_REGISTER_DIR` is set.
6. `evals/review/capmo-android-pr-3110/` holds `case.yaml` with the repo, PR, branch and commit above, and `expected-findings.yaml` containing the custom-text-resurrection P1 as a `non_issue` with the device-QA rationale. The verified P0/P1 findings are present as `true_positive` entries, or each unavailable one is marked `needs_curation` and listed in `census_subtask_4.md`.
7. No file under `evals/review/` contains diff hunks or source excerpts from the evaluated repository.
8. Review completion, verdicts, repair rounds, and telemetry privacy behavior stay unchanged. Evaluation results never gate review completion or validation.

## Test Obligations

- AC 4: guards scorer arithmetic and catches rejection of ordinary prose or missing metadata by the measurement reader.
- No test for the YAML case files beyond what the scorer tests load.

## Non-Goals

- No change to review finding rules, severities or lane scopes.
- No required structured review output, strict schema parsing, production parser changes, or new review validation.
- No new blocking reason or metadata-based repair round.
- No precision or recall threshold gates review completion or validation.
- No telemetry privacy or level change; `anonymous` stays the default.
- No user-facing command for evals and no eval run inside `./gradlew check`.
- No automated agent review inside the eval runner.

## Dependency Notes

Independent of subtasks 1-3. Review prompts, finding-format declarations, and production parsing remain unchanged.

## Validation Strategy

The validate phase runs the existing repository checks through `./gradlew check`, including scorer tests. These checks validate the implementation, not reviewer prose. The on-demand runner stays skipped there. Implement and audit run nothing. Audit reads the eval files and `census_subtask_4.md` against each criterion and confirms that review prompts, production parsing, and completion gates remain unchanged.

## Next Path

```bash
skill-bill goal SKILL-414
```
