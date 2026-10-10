# Review evaluation set

On-demand measurement of existing prose review output. Reviewers keep writing
prose. This set does not add a finding template, category field, report schema,
production parser change, or review-validation rule. Scores never gate review
completion or validation.

## Case format

Each case is a directory under `evals/review/<case-id>/`.

`case.yaml` holds revision pointers only. Do not paste diffs or source from the
evaluated repository.

| Field | Meaning |
| --- | --- |
| `repo` | Repository name |
| `pr` | Pull request number |
| `branch` | Branch name |
| `commit` | Head commit of the reviewed revision |
| `base_revision` | First parent of `commit`, when known. Leave empty when the operator must supply it. |
| `register_command` | Command that produces a findings register against that revision |

`expected-findings.yaml` is a `findings:` list. Each entry has:

| Field | Meaning |
| --- | --- |
| `id` | Stable case-local id |
| `file` | Path used for matching |
| `line` | Line number, or a `start-end` range. `line_start` / `line_end` are accepted aliases. |
| `severity` | Recorded severity, such as `P0` or `P1` |
| `category` | Recorded category when known |
| `lane` | Lane used for recall |
| `label` | `true_positive` or `non_issue` |
| `rationale` | Why the label was chosen |
| `status` | Optional. `needs_curation` excludes the entry from scoring |
| `window` | Optional match window in lines. Default is 5 |

## Scoring

The scorer lives in the `runtime-domain` test source set. It calls
`ReviewParser.parseReview` as a best-effort reader and does not change production
parsing.

Matching is same file plus line window. Default window is ±5 lines, overridable
per expected entry. A reported line or `start-end` range matches when it
overlaps `[start - window, end + window]`. Overlap is distance 0; otherwise
distance is the gap between the ranges. Each reported finding matches at most
one expected entry, the closest in-window hit. Each expected entry matches at
most once. Severity, category, and lane are not match keys.

Counts:

- True positive: a reported finding matched a `true_positive` entry
- Missed true positive: an unmatched `true_positive` entry
- False positive: a reported finding matched a `non_issue` entry
- Unlabeled: a located reported finding with no expected match. Listed for
  curation. Excluded from true positives, false positives, and the precision
  denominator

`needs_curation` expected entries are listed and excluded from scoring. They do
not by themselves mark scores partial.

Ratios:

- Precision = true positives / (true positives + false positives)
- Recall = matched true positives / expected `true_positive` count after
  excluding `needs_curation`
- A zero denominator yields `0.0`

Lane attribution:

- Precision buckets use `ImportedFinding.laneSkillName` when present, otherwise
  `unattributed`
- Recall buckets use each expected entry's `lane` field

Scores are labelled partial when extraction is incomplete: `parseReview` threw,
a reported finding had a missing or unparseable `location`, or leftover prose
had no extractable finding locations. Partial scores are still printed. They do
not fail the runner.

## Limits of best-effort extraction

`ReviewParser.parseReview` requires `Review run ID` and `Review session ID`
lines. A `skill-bill phase review` register is the agent's findings prose plus a
verdict line. The standalone review strategy does not inject those id lines into
the printed register. The test-side scorer prepends placeholder eval ids when
they are missing so findings can still be read. Production parsing is unchanged.

Finding locations are read as PLAYBOOK `file:line` (a trailing `-end` is
accepted). Delegated merger output that uses `path="..." | line=N` does not
parse as `file:line` and goes to curation with partial scores. Categories are
not required. Missing metadata or prose the scorer cannot interpret becomes a
curation item, never an invalid review and never a reason to rerun one.

## How to run

Produce a register from a checkout of the case repository:

```text
skill-bill phase review target:<commit>
```

Save the printed findings register as `<case-id>.md` in a directory you choose.
For the first case that file is `capmo-android-pr-3110.md`.

Then, from `runtime-kotlin/`:

```text
SKILL_BILL_REVIEW_EVAL_REGISTER_DIR=<dir> ./gradlew :runtime-domain:test --tests 'skillbill.review.eval.ReviewEvalOnDemandRunnerTest'
```

The runner test skips unless `SKILL_BILL_REVIEW_EVAL_REGISTER_DIR` is set. When
set, it scores each case under `evals/review/` that has a matching
`<case-id>.md` in that directory and prints per-lane precision and recall.
Partial scores and curation lists do not fail the test.

Scorer unit tests run with `./gradlew check` as part of `:runtime-domain:test`.
The on-demand runner stays skipped there. Evaluation results never gate review
completion or validation.
