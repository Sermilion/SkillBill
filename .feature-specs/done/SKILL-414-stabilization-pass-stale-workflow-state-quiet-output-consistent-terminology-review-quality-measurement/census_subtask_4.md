# SKILL-414 subtask 4 census

## Lane attribution

Precision uses `ImportedFinding.laneSkillName` when present, otherwise the
bucket `unattributed`. Recall per lane uses each expected entry's `lane` field.
Matching is file path plus line window, not severity, category, or lane.
Confirmed on `ReviewParser.parseReview` / `ImportedFinding` in
`../../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/parsing/ReviewParser.kt`
and `skillbill/review/model/ReviewModels.kt`. No production edit.

## Gradle project path and runner command

`runtime-domain` has `src/test/kotlin` and no `repoTest`. Scorer and runner are
under `../../../runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review/eval`.
YAML reading uses `jackson.dataformat.yaml`, already on that module's test
classpath. No production dependency and no CLI command.

Confirmed command, from `../../../runtime-kotlin`:

```text
SKILL_BILL_REVIEW_EVAL_REGISTER_DIR=<dir> ./gradlew :runtime-domain:test --tests 'skillbill.review.eval.ReviewEvalOnDemandRunnerTest'
```

## Standalone review ids

`skill-bill phase review` prints the agent's findings register plus a verdict
line. `StandaloneReviewStrategy` mints a run id internally and does not inject
`Review run ID` or `Review session ID` into `registerOutput`. The test-side
scorer prepends `rvw-eval-placeholder` and `rvs-eval-placeholder` when those
lines are missing. Production `ReviewParser` is unchanged. If parsing still
throws, the whole register is a curation item and scores are partial.

## First case pointers

`../../../evals/review/capmo-android-pr-3110/case.yaml` has repo `capmo-android`, PR
`3110`, branch `feat/FP-5545-accept-into-open-entry`, commit `98b49fb47`, and
`register_command: skill-bill phase review target:98b49fb47`.

`base_revision` is empty. No local `capmo-android` checkout was found under
`IdeaProjects` or as a sibling tree, so the first parent of `98b49fb47` could
not be read. Operator must supply `base_revision`.

## Expected findings that need curation

No imported review run, review-metrics row, or other recorded register for PR
3110 was found. Verified P0/P1 details are not in this repository. Zero
`true_positive` rows were written. Do not invent them.

| id | Why excluded | Operator must supply |
| --- | --- | --- |
| `custom-text-resurrection` | Named `non_issue` with rationale `device QA: not reachable`. File, line, category, and lane are unknown. `status: needs_curation`. | file, line or range, category, lane |
| verified P0/P1 set | None identified in a local checkout or recorded review data. | each verified P0/P1 as a `true_positive` with id, file, line or range, severity, category, lane, and rationale |

## Production surface

No change to `ReviewParser`, review templates, report schemas, review
validation, completion, verdicts, repair rounds, or telemetry. Evaluation
scores do not gate review completion or validation.
