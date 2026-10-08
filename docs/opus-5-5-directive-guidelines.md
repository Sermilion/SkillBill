# Opus 5.5 directive guidelines

Every `opus-5-5-<family>.md` directive resource follows these rules, including the
directive for any new slot or phase. They adapt
[Getting the most out of Opus 5.5](https://claude.dev/blog/getting-the-most-out-of-opus-5-5/)
to headless runtime phases. The article's advice is written for an operator at a
prompt. A phase worker has no operator to ask, so "stop and ask" here means
"block with a reason".

Directives shape the agent's instructions only. The slot contract still owns phase
ids, output validation, authority, retries, repair caps, and checkpoints. See
[Authoring model-specific phase strategies](model-specific-phase-strategies.md).

## What Opus 5.5 does differently

It thinks before every reply. It works longer on its own. It reports plainly on what
it did. Directives that compensate for older models' habits now get in its way.

## Rules

1. **Name the finish line.** State what "done" means for this phase as an end state
   in the tree or the output, such as "Stop when the acceptance criteria for this
   subtask are met in the tree." Write one sentence and avoid open-ended "keep
   improving" wording.
2. **Name the only reasons to block.** State the conditions that end the phase
   short of done, such as a concrete external obstacle, missing evidence, or an
   exhausted attempt cap. Anything else continues without confirmation.
3. **No thinking prompts.** Never write "think carefully", "think step by step",
   "think hard", or similar. Thinking depth comes from effort configuration, not
   from prose.
4. **Keep going between steps.** Do not ask the worker to pause and report midway.
   A status note goes in the same turn as the next action. Report once the phase
   is done or blocked.
5. **Guard destructive actions explicitly.** List what the phase must never do on
   its own: delete data outside its scope, force-push, rewrite published history,
   or change anything outside the repository. These are block conditions, not
   judgment calls.
6. **Verify delegated evidence.** When a strategy fans out to subagents or units,
   the directive requires checking each report's evidence before the wave or unit
   counts as settled. Unverified results stay unsettled.
7. **Keep long-run progress in a file.** Long phases outlive context summarization.
   Point the worker at the runtime-private checklist and treat it as an aid, never
   as authority over workflow tasks.
8. **Lead the report with what needs a human.** Phase prose opens with anything
   blocked on the operator, then what changed, then what was found.
9. **Mark what could not be confirmed.** Unknown is a reported state, never a
   silent pass. Say where the worker looked.
10. **Reviews list only blocking problems.** For each finding, give the file and
    line, why it is wrong, and how to show it fails. Do not pad the register with
    style notes or speculative concerns.
11. **Ask for rationale, not reasoning.** Request a short explanation of a decision
    ("explain each material decision in one or two sentences"). Never ask the
    worker to reproduce its internal reasoning. That is a safeguard flag category,
    and a flagged session can be moved to an older model.
12. **Name rejected design patterns concretely.** When a phase produces UI or visual
    output, list the specific patterns to avoid. A generic instruction like "avoid
    a generic look" only swaps one default for another.
13. **Do not mark answers as settled in multi-pass phases.** Audit, repair, and
    monitor loops can uncover an earlier mistake. Let later passes revisit earlier
    conclusions when new evidence contradicts them.

## Shape of a directive

Keep each resource short (about six to twelve lines) and in this order:

1. The contract elements this variant must keep (one sentence).
2. Scope: what the phase touches and what it must not touch.
3. The finish line (rule 1) and block conditions (rules 2 and 5).
4. The evidence and reporting rules that apply (rules 6 to 11).

Do not restate the canonical prompt. A directive adds only what changes for
Opus 5.5.

## Example: a loop with an attempt cap

A phase that watches PR CI, repairs failures, and gives up after a fixed number of
attempts applies the rules like this:

- Done: every required check on the PR head commit passes.
- Block: the attempt cap is spent, a failure is outside the repository (an
  infrastructure outage or a missing secret), or a fix would need a destructive
  action.
- Never force-push or rewrite the PR's history.
- Each attempt cites the failing check, its log evidence, and the fix applied.
- The blocking report opens with why CI cannot be fixed and what the operator
  must do.

## Not applicable to runtime phases

The article also covers the model picker, attachments, fast mode, and how to
recover a flagged session in the apps. None of these shape directive text. Model
identity is resolved and recorded by the runtime, as described in the strategy
authoring guide.
