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

## Implementation Details

Plan for the implement phase. Do not compile, run tests, or run `./gradlew check` here; validate owns those. Do not run `./install.sh` or any install refresh. Do not change production `ReviewParser`, review templates, report schemas, review validation, completion, verdicts, repair rounds, or telemetry. Evaluation scores never gate review completion or validation.

Settled from the preplan digest. `ReviewParser.parseReview(text): ImportedReview` lives in `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/parsing/ReviewParser.kt`. It requires `Review run ID` and `Review session ID` or throws `IllegalArgumentException`. Findings come from `parseReviewFindings`: bullets via `findingPattern`, else table rows. `ImportedFinding` carries `findingId`, `severity`, `confidence`, `location` (PLAYBOOK shape `file:line`), `description`, `findingText`, `issueCategory`, and `laneSkillName` (specialist group or provenance). `ImportedReview.specialistReviews` and `planLanes` exist and are not match keys.

Lane attribution, recorded in `census_subtask_4.md` at implement time: precision uses `ImportedFinding.laneSkillName` when present, else the bucket `unattributed`. Recall per lane uses each expected entry's `lane` field. Matching is file path plus line window, not severity, category, or lane.

`runtime-domain` has `src/test/kotlin/skillbill/review/parsing/` and no `repoTest`. Scorer and runner go under `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review/eval/`. No production code, no CLI command.

Assumptions for implement to confirm, each from digest gaps rather than new discovery:

- Gradle project path is the `runtime-domain` test source set. Implement confirms the project path and writes the exact on-demand command in `evals/review/README.md` and `census_subtask_4.md`. Planned shape: `SKILL_BILL_REVIEW_EVAL_REGISTER_DIR=<dir> ./gradlew <project>:test --tests '<runner class>'`.
- `case.yaml` `base_revision` is required by this spec and absent from the digest. Implement fills it from the first parent of `98b49fb47` in a local capmo-android checkout. If that checkout is missing, leave `base_revision` empty and list it in `census_subtask_4.md` as operator-supplied. Do not invent a SHA.
- Matching: a reported finding matches at most one expected entry, the closest same-file line inside the window; each expected entry matches at most once. Default window ±5 lines, overridable per expected entry. If the expected entry has a line range, a reported line matches when it falls in `[start - window, end + window]`.
- Precision = true positives / (true positives + false positives). False positives are reported findings that match a `non_issue` entry. Unlabeled reported findings are listed and excluded from true positives, false positives, and the precision denominator. Recall = matched true positives / expected `true_positive` count after excluding `needs_curation`. If a denominator is 0, the ratio is `0.0`.
- Scores are labelled partial when extraction is incomplete: `parseReview` threw, a reported finding has a missing or unparseable `location`, or any reported passage could not be interpreted. `needs_curation` expected entries are excluded from scoring and listed; they do not by themselves mark scores partial.
- YAML reading uses whatever YAML library the `runtime-domain` test classpath already has. Do not add a production dependency for evals.
- Verified P0/P1 details are not in this repository. Implement must not invent findings. Search a local capmo-android checkout and recorded review data for PR 3110, including imported review runs in the local skill-bill database. Fully located verified P0/P1 become `true_positive`. Identified findings that lack file, line, or category are written with `status: needs_curation` and listed in the census. If no verified findings can be identified, write zero `true_positive` rows and list that gap in the census for the operator to supply.
- The custom-text-resurrection P1 is always present as `label: non_issue` with rationale `device QA: not reachable`. If its file and line cannot be sourced, add `status: needs_curation` so it is excluded from scoring until curated.

Constraints that apply to every task: mocks use `relaxUnitFun = true` if any mock is needed; environment maps, if any, are non-empty; no `WORKFLOW_STATE_CONTRACT_VERSION` bump; no comments in authored Kotlin except interface KDoc; no diff hunks or source excerpts under `evals/review/`.

### Task 1. Pure scorer in the test source set

Serves AC-001, AC-003, AC-008.

Paths and symbols: new types under `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review/eval/`. Call `ReviewParser.parseReview` only as a best-effort reader. Do not edit `ReviewParser.kt` or any production review module.

Behavior:

- Load a register string plus an expected-findings list.
- Catch `parseReview` throws and treat the whole register as uninterpretable: curation items, partial scores, no exception out of the scorer.
- Parse `location` as `file:line`. Missing or unparseable location becomes a curation item and marks scores partial.
- Match on same file and line window as above.
- Report true positives, missed true positives, reported findings matching `non_issue` (false positives), unlabeled reported findings, precision and recall per lane and overall.
- Exclude `needs_curation` expected entries from scoring and list them.
- Label incomplete extraction as partial. Never fail, never rerun a review, never impose a finding template.

test_obligations: none in this task. Arithmetic and rejection behavior are Task 2.

### Task 2. Scorer unit tests with canned registers

Serves AC-004.

Paths: the same `skillbill.review.eval` test package. Canned register strings only. Do not load `evals/review/` case YAML in these tests beyond what a scorer helper already needs for expected-entry shape.

Four tests, one per named bug:

1. Line-window match. Bug: a finding on the same file a few lines off the expected line is counted as a miss even though it is inside ±5. Assert it is a true positive and that precision and recall are 1 for that lane and overall.
2. `non_issue` hit is a false positive. Bug: a reported finding that matches a `non_issue` expected entry is counted as a true positive or ignored. Assert it is a false positive, precision drops, recall is unchanged for the remaining `true_positive` set.
3. Unlabeled finding excluded from both true and false positives. Bug: an extra reported finding with no expected match inflates the false-positive count or the precision denominator. Assert it is listed as unlabeled and excluded from TP, FP, and precision.
4. Prose without extractable finding locations. Bug: a register that parses as a review but has no `file:line` locations fails the scorer or reports a complete score of zero without curation. Assert curation output, partial scores, and no thrown failure. Include a fixture that would make `parseReview` throw (missing run/session ids) only if it stays inside this same non-failing curation path; the required named case is missing locations, not the throw.

No sibling tests that re-cover the same branch with different literals. No mock-interaction tests. No test that `parseReview` production code is unmodified; audit reads that.

test_obligations: the four tests above.

### Task 3. On-demand runner test

Serves AC-005, AC-008.

Paths: a runner test in `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review/eval/`. Skip unless `SKILL_BILL_REVIEW_EVAL_REGISTER_DIR` is set. When set, score each case under `evals/review/` that has `<case-id>.md` in that directory (`capmo-android-pr-3110.md` for the first case) and print per-lane precision and recall. Do not produce a register, do not invoke `skill-bill phase review`, do not fail the test on partial scores or curation lists.

Realistic bug: the runner participates in `./gradlew check` and fails when the env var is unset. One skip/assume test method is enough; when the var is set, that same test is the on-demand run. Empty env-map rule does not apply; this reads a process env var, not a constructed map.

test_obligations: the skip-unless-env runner test.

### Task 4. Eval README

Serves AC-002, AC-007.

Path: `evals/review/README.md`. Document case format (`case.yaml` and `expected-findings.yaml` fields), scoring rules including unlabeled exclusion, `needs_curation` exclusion, partial labelling, and lane attribution (`laneSkillName` else `unattributed`), the on-demand Gradle filter plus `SKILL_BILL_REVIEW_EVAL_REGISTER_DIR` with the command implement confirms, and the limits of best-effort extraction from prose (`parseReview` requiring run and session ids, PLAYBOOK `file:line`, no required category). Steps: produce the register with `skill-bill phase review` against the case revision, then run the Gradle test filter with the env var. State that evals are not part of `./gradlew check` beyond scorer unit tests, and that scores never gate review completion or validation.

test_obligations: none. Documentation.

### Task 5. First case pointers

Serves AC-006, AC-007.

Paths: `evals/review/capmo-android-pr-3110/case.yaml` and `evals/review/capmo-android-pr-3110/expected-findings.yaml`.

`case.yaml`: repo `capmo-android`, PR `3110`, branch `feat/FP-5545-accept-into-open-entry`, commit `98b49fb47`, `base_revision` per the assumption above, and the command an operator runs to produce a register (`skill-bill phase review` against that revision). Pointers only.

`expected-findings.yaml`: custom-text-resurrection P1 as `non_issue` with rationale `device QA: not reachable`, plus verified P0/P1 as `true_positive` or `needs_curation` per the assumption above. Each entry has id, file, line or range, severity, category, lane, `label: true_positive | non_issue`, rationale, optional `status: needs_curation`. No diff hunks, no source excerpts, no copied capmo-android code.

test_obligations: none. Spec forbids YAML-case tests beyond what scorer tests load.

### Task 6. Census

Serves AC-003, AC-006.

Path: `.feature-specs/SKILL-414-stabilization-pass-stale-workflow-state-quiet-output-consistent-terminology-review-quality-measurement/census_subtask_4.md`.

Record: lane attribution source (`laneSkillName` else `unattributed`); confirmed Gradle project path and exact runner command; `base_revision` filled or listed as operator-supplied; every `needs_curation` expected entry and what the operator must supply; that no production parser, template, schema, or review-validation change was made.

test_obligations: none. Audit reads this file.

## Validation Strategy

The validate phase runs the existing repository checks through `./gradlew check`, including scorer tests. These checks validate the implementation, not reviewer prose. The on-demand runner stays skipped there. Implement and audit run nothing. Audit reads the eval files and `census_subtask_4.md` against each criterion and confirms that review prompts, production parsing, and completion gates remain unchanged.

## Implementation Details

### Planning basis and assumptions for implement to confirm

The upstream preplan digest supplied to the plan phase describes subtask 2 (quiet CLI output) and carries no facts about this subtask. This plan therefore rests on this sub-spec and the parent spec (Area 4). Implement must confirm each assumption below before relying on it and record any correction in `census_subtask_4.md`:

- A1. `ReviewParser.parseReview` exists in one `runtime-kotlin` module and reads the PLAYBOOK bullet format `- [F-001] Sev | Conf | file:line | desc` and table rows. Implement locates the owning module, its signature (input text plus any required context), its return type, whether it throws on unparseable input, and the per-finding fields (id, severity, confidence, file, line, description, category, specialist or routed-skill label).
- A2. The parent spec says bullet findings take the first routed specialist label for the whole review. That is a review-wide label, not per-finding attribution. Unless implement finds a genuine per-finding specialist field, reported findings bucket as `unattributed` for per-lane precision. Implement records the source used in `census_subtask_4.md`.
- A3. The owning module may have a `repoTest` source set (as `runtime-core` does for architecture tests), wired into `./gradlew check`, and test code there can see the `ReviewParser` it calls (public API, or `internal` through an associated compilation). If both hold, all new Kotlin goes in `repoTest`. Otherwise it goes in the module's `test` source set, and the runner resolves the repository root the way existing repo-reading tests do.
- A4. The module's test classpath already has a YAML reader (snakeyaml, kaml, or the one the runtime uses for contracts) and JUnit 5 assumptions. Use those and add no new dependency. If no YAML reader is on the test classpath, add it as a test-only dependency of that module and note it in the census.
- A5. `census_subtask_4.md` lives in this spec bundle directory, next to this file.
- A6. `phase review` prints its findings register to stdout, so the operator can redirect it to `<case-id>.md`.

### Hard constraints (all tasks)

- No production code, CLI command, parser change, finding template, category requirement, report schema, review validation, PLAYBOOK, skill `content.md`, telemetry, or completion-gate change (AC-1, AC-8). Every new Kotlin file is in a test source set.
- No `//` or non-KDoc block comments in Kotlin. KDoc only on interfaces.
- No mocks are needed. If one is used, it must use `relaxUnitFun = true`.
- Declare YAML keys once in a private test-side `ReviewEvalCaseKeys` object, and the env var once in a test-side `ReviewEvalEnvironmentKeys` object (`SKILL_BILL_REVIEW_EVAL_REGISTER_DIR`). Do not repeat key literals.
- No file under `evals/review/` holds a diff hunk, code line, or source excerpt from the evaluated repository. Rationales are short, original prose (AC-7).
- Keep files small (scorer, loader, report model and rendering in separate files) so detekt and file-size rules pass if they also cover test sources.
- No installer or install-sync commands, and no compile or test runs in implement. The validate phase runs `./gradlew check`.

### Scoring rules (decided here, documented in the README)

- Matching: a reported finding matches an expected entry when the normalized file paths are equal (strip a leading `./`, convert `\` to `/`) and the reported line falls within the entry's line or `line_start..line_end` widened by the window. The default window is ±5, both ends inclusive, overridable per entry with `line_window`. If several entries match, the entry with the nearest line wins; ties go to the entry listed first.
- Many-to-one: every reported finding that matches a `true_positive` entry counts as a true positive. Recall counts distinct matched entries.
- A reported finding that matches a `non_issue` entry is a false positive.
- A reported finding that matches no scoreable entry is unlabeled. It is listed for the curator and excluded from both true and false positives.
- Precision = true positives / (true positives + false positives), meaning labeled reported findings. Recall = distinct matched `true_positive` entries / scoreable `true_positive` entries. A metric with a zero denominator is reported as `n/a` (null), never 0 or 1. This reads the spec's "true positives / reported" as reported findings that hit a labeled entry, which is what AC-3/AC-4 require when unlabeled findings are excluded from both counts.
- Per-lane: recall is grouped by the expected entry's `lane`. Precision is grouped by the reported finding's lane attribution (A2), or `unattributed`. Overall rolls up every lane.
- Entries with `status: needs_curation`, and entries without a file or line for any other reason, are excluded from scoring and listed as curation items.
- Partial: the report carries `partial: true` with explicit reasons whenever any of these hold:
  - `extraction_incomplete`: the parser threw, returned zero findings for a non-blank register, returned findings without a file or line (each listed as a curation item), or found fewer findings than the `[F-<n>]` ids in the text. Unparsed passages are listed as curation items.
  - `expected_entries_need_curation`: one or more expected entries were excluded.

  The scorer never throws for reviewer prose. Only malformed case YAML in this repository fails loudly.

### Ordered tasks

1. **Locate and confirm (A1–A6).** Serves AC-1, AC-3, AC-5.
   - Find the `ReviewParser` owner, its API and fields, its source sets, the check wiring, the YAML library, the JUnit version, and how repo-reading tests resolve the repo root.
   - Read-only. No production edits.

2. **Case model and loader.** Serves AC-3, AC-6.
   - Paths: `<module>/src/<repoTest|test>/kotlin/skillbill/<parser-package>/eval/ReviewEvalCase.kt` and `ReviewEvalCaseLoader.kt`. Use the package next to `ReviewParser`, under an `eval` subpackage.
   - Data classes:
     - `ReviewEvalCase(caseId, repo, pr, branch, commit, baseRevision?, command)`.
     - `ExpectedFinding(id, file?, lineStart?, lineEnd?, lineWindow = 5, severity?, category?, lane?, label: TRUE_POSITIVE|NON_ISSUE, rationale, needsCuration: Boolean)`, with a `label` enum that carries a `wireValue`.
   - The loader parses `case.yaml` and `expected-findings.yaml` (a top-level `findings:` list). It accepts `line` or `line_start`/`line_end`, and allows a null file or line on any entry. It fails loudly on an unknown `label` or `status` value and on missing `id` or `rationale`.

3. **Pure scorer and report.** Serves AC-3.
   - Paths: `ReviewEvalScorer.kt` and `ReviewEvalReport.kt` in the same package.
   - `fun scoreReview(register: String, expected: List<ExpectedFinding>): ReviewEvalReport` calls `ReviewParser.parseReview` inside `runCatching`. It maps parsed findings to an internal `ReportedFinding(id?, file?, line?, lane)`, then applies the scoring rules above.
   - `ReviewEvalReport` holds:
     - `partial`, `partialReasons`
     - `overall: LaneScore` and `lanes: Map<String, LaneScore>`
     - `truePositives`, `missedTruePositives`, `falsePositives`, `unlabeled`
     - `curationItems: List<CurationItem(kind, detail)>` and `excludedEntries`
   - `LaneScore` holds `truePositives`, `falsePositives`, `matchedExpected`, `expectedTruePositives`, `precision: Double?` and `recall: Double?`.
   - `fun ReviewEvalReport.render(caseId): String` produces the plain-text per-lane report the runner prints, with `PARTIAL` and its reasons in the header when partial.

4. **Scorer unit tests (canned registers).** Serves AC-4. Path: `ReviewEvalScorerTest.kt` next to the scorer. Exactly two tests:
   - **Arithmetic, window, non_issue, and unlabeled.**
     - Expected entries arrive through the loader from an inline YAML string, so the loader path is covered without a separate test:
       - TP `A`: `Foo.kt:44`, lane `security`
       - TP `B`: `Bar.kt:10`, lane `testing`, not reported
       - non_issue `N`: `Baz.kt:20`
       - `C`: `needs_curation` with no line
     - Canned PLAYBOOK-format register with three findings:
       - `Foo.kt:49` matches A at the inclusive +5 window edge.
       - `Baz.kt:21` hits N.
       - `Qux.kt:3` is unlabeled.
     - Assert overall TP=1, FP=1, precision 0.5, recall 0.5.
     - Assert recall per lane: `security` 1.0, `testing` 0.0.
     - Assert the unlabeled list holds `Qux.kt:3` and is not counted in TP or FP, and that C is in the excluded entries.
     - Assert `partial` is true with reason `expected_entries_need_curation`, and `extraction_incomplete` is absent.
     - Realistic bugs caught: an exclusive window edge, unlabeled findings counted in precision, needs_curation entries in the recall denominator, a non_issue hit not counted as FP.
   - **Prose without extractable locations.**
     - Register: plain paragraphs that describe a concern with no `file:line`.
     - Expected: one TP entry.
     - Assert no exception, `partial` true with reason `extraction_incomplete`, a non-empty curation list, precision null, and recall 0.0 for the scoreable entry.
     - Realistic bug caught: the measurement reader rejecting or crashing on ordinary prose, or reporting a complete score.
   - No other tests. The YAML case files get no dedicated test.

5. **On-demand runner test.** Serves AC-5.
   - Path: `ReviewEvalRunnerTest.kt`.
   - A single test. It calls `Assumptions.assumeTrue(System.getenv(ReviewEvalEnvironmentKeys.REGISTER_DIR) != null)`, so it is skipped under `./gradlew check`.
   - When set, for each `evals/review/<case-id>/` with a matching `<register-dir>/<case-id>.md`, it loads the case, scores it, and prints `render(caseId)` to stdout. It prints a line for each case without a register.
   - It never asserts on scores and never launches an agent review.

6. **Eval files and README.** Serves AC-2, AC-6, AC-7.
   - **`evals/review/README.md`:**
     - Purpose: on-demand measurement only, never gating review completion or validation.
     - Case format: every `case.yaml` and `expected-findings.yaml` field, `needs_curation`, and `line_window`.
     - The scoring rules above, including precision, recall, unlabeled, n/a, lane attribution, and partial reasons.
     - Best-effort extraction limits: prose is not required to follow any format, unparsed passages become curation items, review-wide specialist labels do not count as per-finding lanes.
     - The no-source-excerpt rule.
     - The on-demand steps:
       1. In a checkout of the case repo at the case commit, run the `case.yaml` command (`skill-bill phase review target:<commit> --agent <agent> > <register-dir>/<case-id>.md`).
       2. Run `SKILL_BILL_REVIEW_EVAL_REGISTER_DIR=<register-dir> ./gradlew :<module>:<repoTest|test> --tests '*ReviewEvalRunnerTest*'` from `runtime-kotlin/`, with the project path and task confirmed in task 1.
   - **`evals/review/capmo-android-pr-3110/case.yaml`:**
     - Fields: `repo: capmo-android`, `pr: 3110`, `branch: feat/FP-5545-accept-into-open-entry`, `commit: 98b49fb47`, `base_revision`, and the review command.
     - If the base revision cannot be sourced from a local capmo-android checkout, write it as `null` and list it in the census.
   - **`evals/review/capmo-android-pr-3110/expected-findings.yaml`:**
     - Implement searches read-only for a local capmo-android checkout and for imported review runs or findings for PR 3110 in the local skill-bill database, and sources the verified P0/P1 findings from there as `true_positive` entries (file, line, severity, category, lane, short original rationale).
     - Always include the custom-text-resurrection P1 as `label: non_issue` with rationale `device QA: not reachable`. Mark it `status: needs_curation` if its file or line cannot be sourced.
     - Never invent findings. Any entry or field that cannot be sourced gets `status: needs_curation`. If no verified findings can be found at all, add no placeholder TP entries and let the census record that the list is pending.

7. **Census.** Serves AC-3, AC-6.
   - Write `census_subtask_4.md` in this bundle with:
     - the confirmed `ReviewParser` module, API and source set, and the lane attribution source used (A2)
     - the final Gradle command
     - the sources searched for PR 3110 data and what each yielded
     - every `needs_curation` entry and missing field (including `base_revision` if null) with what the user must supply
     - any assumption from A1–A6 that turned out different

8. **Unchanged-surface check.** Serves AC-1, AC-8.
   - Before handing off, confirm the diff touches only test source sets, `evals/review/`, and this bundle's census.
   - No changes to production `ReviewParser`, PLAYBOOK, review skills, completion or verdict logic, repair rounds, or telemetry.

### Tests to add or run

- Add: `ReviewEvalScorerTest` (two tests, task 4) and `ReviewEvalRunnerTest` (skipped by default, task 5).
- Run: nothing in implement. The validate phase runs `./gradlew check`, which executes the scorer tests and skips the runner.

## Next Path

```bash
skill-bill goal SKILL-414
```
