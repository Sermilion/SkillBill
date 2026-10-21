# SKILL-437 - inline-review-keeps-its-pre-skill-421-behavior-with-the-code-quality-lane-only-added

## Mode

decomposed

## Intended Outcome

Inline review behaves exactly as it did before SKILL-421 (#471, 9fb13f174) in prompt, launch and output handling. Inline review means standalone `phase:review` without `mode:` and the full-run inline `code_review` pass. The one addition is that the code-quality lane is always selected as well: its rubric is appended as an extra lane, and its findings carry the code-quality specialist identity and stay non-blocking. Delegated review is unchanged.

## Problem

SKILL-421 changed three things in inline review beyond adding the lane:

1. Prompt narrowing (the observed bug). `StandaloneReviewStrategies.standaloneDirective` and `InlineReviewDirective.compose` append "## Selected local code-quality rubric" with the full owner rubric. Each pack's code-quality rubric has an Ignore section that routes behavioral defects (correctness, security, persistence, reliability, accessibility) to the failure specialists. Inline review has no other lanes, so the single inline agent is told to skip every behavioral defect. Observed 2026-10-21 in capmo-android (standalone review phr-3edf4635-e25b-405a-a185-c8678f833065, review run rvw-20261021-161831-2ehd): the review returned only `bill-kmp-code-review-code-quality` findings on a large behavioral change set.
2. A new blocking failure mode. Both inline paths call `resolveInlineCodeQualityRubric` before launch. A planning failure blocks the whole inline review: `block(...)` in the standalone path, `ParallelCodeReviewRunOutcome.PlanningFailed` in `InlineReviewPass`. Inline review did no planning before #471.
3. An output rewrite. `InlineReviewResultDecoder` replaces the agent's register with `inlineOutputWithNormalizedRegister`, which merges the register and strips section headers such as Actionable, Refuted and Unresolved. Before #471 the raw agent output was the result.

The SKILL-413 rule (345bd6193) is intended and stays: code-quality findings are excluded from the standalone severity verdict in `StandaloneReviewReportAdmission`.

## Acceptance Criteria

1. The standalone inline review directive equals its pre-#471 text plus an appended code-quality lane section. The section states that it adds to the review, that the agent remains responsible for every review area, and that the rubric's routing/Ignore lines handing defects to other lanes apply only to delegated review.
2. The full-run inline review directive (`InlineReviewDirective.compose`) keeps its pre-#471 text unchanged and appends the same additively framed code-quality lane section. The verify operation's inline code-review step, which appends its own copy of the rubric section today, uses the same framing.
3. A failure to resolve the code-quality rubric never blocks standalone or full-run inline review. Inline review runs as it did before, without the quality section, and records a warning that names the failure reason. Delegated review keeps its current blocking behavior.
4. Full-run inline output handling is restored to pre-#471: the agent's raw output is the result, with no normalized-register rewrite. Code-quality findings are still recognized by their specialist identity for non-blocking verdict accounting and display.
5. The SKILL-413 verdict rule, under which code-quality findings do not drive `changes_requested`, is unchanged, and delegated review, specialist selection, delegated lane planning and platform-pack rubric content are unchanged.

## Constraints

- Do not change delegated review, specialist selection, lane planning for delegated review, or platform-pack rubric content.
- Keep the verify operation's code-review step consistent with the inline rule it follows today. Its rubric section gets the same additive framing; otherwise verify is not changed.
- Outside inline review, behavior is preserved. No new review modes or flags.
- No contract, schema, or migration change.

## Non-Goals

- Re-designing inline depth or the delegated fan-out.
- Editing per-pack code-quality rubric text.
- Reverting SKILL-421's platform-pack additions.
- Changing `ParallelCodeReviewRunner` or its planning in runtime-application.

## Decisions

- Verify scope. Verify's inline mode gets the shared additive framing only. Its failure behavior stays as it is: on rubric failure it returns `VerifyCodeReviewOutcome.Failed`. This follows the constraint "do not otherwise change verify", and the operator's definition of inline review covers only the standalone and full-run paths. Verify's output handling is restored as a side effect, because it shares `InlineReviewResultDecoder.decode`. That is consistent with the constraint.
- One subtask. Five production criteria fit one spec, and the three call sites share one framing helper. Splitting would make one subtask depend on a helper another subtask creates.

## Validation Strategy

The validate phase runs `./gradlew spotlessApply`, then `./gradlew check`, in `runtime-kotlin`. If spotless reports a stale JVM-local cache, rerun with `--no-configuration-cache`. Audit checks each acceptance criterion by reading the tree.

## Subtasks

1. `spec_subtask_1_inline-review-additive-code-quality-lane.md`. Covers all five criteria.

## Next Path

```bash
skill-bill goal SKILL-437
```
