# SKILL-383 Subtask 2 - Move census

Each deleted `skills/<name>/content.md` body (below its frontmatter) is copied byte for
byte into the listed target. Engine resources live under
`../../../runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine` (abbreviated `engine/`).
The only edits are the drops and name replacements listed here. Check with
`git diff -M HEAD~1 -- skills/ runtime-kotlin/runtime-engine/src/main/resources/`.

## bill-feature

- Target: `../../../skills/skill-bill/content.md` (already moved by SKILL-380 subtask 12).
- Gate, Rehydrate, Launch and Relay were already identical. Intake, Update Check, Token
  Forwarding and Preflight were already present with the SKILL-380 wording (`skill-bill phase
  plan` in place of `bill-feature-spec`). No section was missing.
- SKILL-380 rewordings already in the dispatcher, kept as they are: "proceed to Intake" reads
  "proceed to Forms and Routing"; Intake "Establish:" reads "For the full run, establish:";
  Token Forwarding is reworded for the phase and operation forms; Preflight "Invoke
  `bill-feature-spec`" reads "run `skill-bill phase plan <intake> --agent
  <currently-executing-agent>`".
- Name replacements: "keeps the `bill-feature` ceremony" reads "keeps the governed feature
  ceremony"; the intro sentence "`bill-feature` is the only feature entry point" is merged
  into the dispatcher intro.
- Dropped (reason): the `# Feature Entry` title. The dispatcher has its own title.

## bill-feature-spec

- Targets: `engine/featuretask/slot/preplan/feature-spec-intake.md` (Intake Contract, Mode
  Selection), loaded by `AgentPreplanStrategy`; `engine/featuretask/slot/plan/feature-spec-directive.md`
  (opening paragraph, Subtask Sizing, Service / Spec Source Mode, Linear Mode Preparation,
  Local Mode, Spec Format Contract, Output Rules without the manifest template, Handoff
  Contract Inputs), loaded by `AgentPlanStrategy`. Both load only when decomposition is not
  suppressed.
- Dropped (allowed): Shared Preparation Path; the Output Rules manifest template, including
  its one-line lead-in "Write the manifest file directly from the template below..."; Goal
  Runner Boundary. The runtime writer owns bundle writing.
- Dropped (reason): the three Output Rules bullets that tell the agent to write `spec.md`,
  the `spec_subtask_*.md` files and the manifest. The runtime writer writes the bundle from
  the decomposition package, and `SPEC_BUNDLE_REQUIREMENT` in the same prompt says not to
  write spec files.
- Name replacements: none. The two remaining `bill-feature-spec` mentions describe the
  procedure and do not tell the agent to invoke or avoid a skill.
- Kotlin: `SPEC_BUNDLE_REQUIREMENT` asks for one or more subtasks (copy wins over "at least
  two").

## bill-code-review

- Engine target: `engine/featuretask/slot/codereview/review-directive.md`, the heading
  "## Review mode argument" plus its depth half (inline, delegated and auto semantics,
  `context:feature-remediation` bounding, `blocker_dispositions`). Loaded by
  `InlineReviewStrategy` and `DelegatedReviewStrategy`.
- Dispatcher target: `../../../skills/skill-bill/content.md` Phase Review section, with the `mode:`
  recognition paragraph of Review mode argument, Review target argument, Invoke the driver,
  Present the register.
- Dropped (reason): No-argument invocation. `skill-bill phase review` with no target reviews
  uncommitted changes when the worktree is dirty and HEAD otherwise, so printing help would
  contradict it. The Phase Review intro states that default instead.
- Dropped (reason): the `context:feature-remediation` recognition paragraph. The phase parser
  accepts only `mode:` and `target:`; the runtime sets remediation bounding itself, and the
  engine `review-directive.md` keeps the bounding rules.
- Name replacements (engine target): "one review subagent launched by the driver as the
  declared `bill-code-review-inline` native agent" reads "one review session". That native
  agent is deleted with `bill-code-review-inline`.
- Dropped (reason): Removed parallel lane argument. The dispatcher's Token Forwarding
  already stops on `parallel-review:`. The old skill spelled the token `parallel:<agent>`;
  the dispatcher keeps only the `parallel-review:` spelling.
- Kept but no longer authoritative: Invoke the driver names `skill-bill code-review` flags
  (`--execution-mode`, `--scope`, `--diff-file`, `--baseline-untracked-*`). The Phase Review
  intro routes the call through `skill-bill phase review`, which owns those flags now.
- Name replacements: none inside the copied text.
- Dropped (reason): the `# Code review entry` title. Each target section has its own heading.

## bill-code-review-inline

- Target: `engine/featuretask/slot/codereview/inline-review-directive.md`, the Depth
  section, loaded by `InlineReviewStrategy`.
- Deleted because the SKILL-380 subtask 8 census found no production caller of the inline
  lane shape (rechecked in this subtask: only `DelegatedReviewStrategy` builds a
  `ParallelCodeReviewRequest`, always `DELEGATED`).
- Dropped (reason): Role, Evidence completeness, Authoritative Inputs, Commit-Focused
  Sequencing, No Builds Or Test Execution, and Output. They are the launch contract of the
  deleted broker-paged worker: a broker-only toolset (`read_evidence`,
  `request_expansion`) with no shell or file tools, no approval while broker units remain
  undelivered, a read-only session, parent-supplied rubric paths, and a free-form
  `specialist=`/`commits=` register. The `InlineReviewStrategy` session has no evidence
  broker, inspects the diff with git, fixes Blocker and Major findings in place, and emits
  the runtime register, so each of those sections would contradict its prompt, and the
  evidence rule would block every approval. The runtime prompt keeps the scope rule
  ("Do not use `origin/main...HEAD`, a merge base, ...") for non-scoped targets.
- Name replacements: none. The only self-reference was in the dropped Role section.
- Rewordings (reason): "Full broker evidence still" reads "Full evidence still", and
  "skipping remaining discover pages" reads "skipping remaining changed files". The
  `InlineReviewStrategy` session has no evidence broker or discover pages.

## bill-code-check

- Engine target: `engine/featuretask/slot/qualitygate/packbuild/quality-check-directive.md`
  (Purpose, Repair Window, Pack validation_gate), loaded by `PackBuildStrategy` on repair
  turns.
- Dispatcher target: `../../../skills/skill-bill/content.md` Routing section after Phase Validation.
- Name replacements: Purpose "repair-window contract on `bill-code-check`" and Repair Window
  "Do not invoke ... `bill-code-check`" name `skill-bill phase validation`.
- Rewordings (reason): Repair Window "Run the pack collect-all gate once" reads "The runtime
  runs the pack gate once and hands you that output", and "run one cache-bypassing
  collect-all confirmation" reads "stop: the runtime reruns the gate". Pack validation_gate
  "Select the dominant pack ... Collect-all is exactly ... Confirmation is exactly ..." reads
  "The runtime selects the dominant pack ... and runs exactly that pack's `validation_gate`
  commands". The repair turn that loads this resource forbids the agent to run collect-all;
  the runtime runs and reruns the gate.
- Dropped (reason): the Routing sentence "Telemetry `routed_skill` is always
  `bill-code-check`". The runtime sets that label mechanically
  (`QUALITY_CHECK_ROUTED_SKILL`), and the name would be an unknown skill reference once the
  tree is gone.
- Dropped (reason): the `# Quality Check Router` title. The resource opens at `## Purpose`,
  and the runtime prompt supplies its own heading.

## bill-pr-description

- Target: `engine/featuretask/slot/pullrequest/pr-description-directive.md`, loaded by
  `PrDescriptionStrategy`.
- Dropped (allowed): Telemetry. The runtime emits `pr_description_generated`.
- Name replacements: "the matching `bill-pr-description` section in
  `.agents/skill-overrides.md`" reads "the `skill-bill` section". Repository validation
  rejects override sections for skills that no longer exist.
- Rewordings (reason): "Confirm the resolved base with the user if it is ambiguous" reads
  "If the resolved base is ambiguous ..., use `main`"; step 6 "**Present** the result to the
  user" reads "**Return** the title and description as this step's value"; "ask the user
  which one to use" reads "use the first one in the search order above". The PR step runs
  headless with no user to ask.

## bill-boundary-history

- Target: `engine/featuretask/slot/writehistory/boundary-history-directive.md`, loaded by
  `BoundaryHistoryStrategy`. Dropped: none. Name replacements: none.

## bill-boundary-decisions

- Target: `engine/featuretask/slot/writehistory/boundary-decisions-directive.md`, loaded by
  `BoundaryHistoryStrategy`. Dropped: none.
- Name replacements: "suggest `bill-boundary-history` instead" reads "suggest an
  `../../../agent/history.md` entry instead".

## bill-pr-review-fix

- Targets: `engine/operation/prreviewfix/analysis-directive.md` gets `## Phase 1 — Analysis`,
  after its runtime intro. `engine/operation/prreviewfix/thread-directive.md` gets the title,
  Overview, Inputs and everything after Phase 1, before its final `## Value`.
- Dropped: none.
- Name replacements: `bill-code-check` becomes `skill-bill phase validation`;
  `bill-boundary-history` becomes "the `boundary-history` strategy" (Record learnings, the
  Risks table, and the runtime Learnings line).

## bill-unit-test-value-check

- Target: `engine/operation/unittestvalue/review-directive.md`, replacing
  `UnitTestValueCheckPromptRules` STANCE and RUBRIC. Dropped: none. Name replacements: none.

## bill-update-check

- Target: `../../../skills/skill-bill/content.md` Update Check Operation section and the
  `operation:update-check` row. The operation has no agent step.
- Name replacements: `skill-bill update-check` becomes `skill-bill operation update-check`
  (three times). The title `# Update Check Content` reads `## Update Check Operation`, and
  the lead-in "Run the runtime command:" reads "For `operation:update-check`, run the runtime
  command:", so the section reads correctly inside the dispatcher. Dropped: none.

## bill-release

- Target: `engine/operation/release/release-directive.md`, loaded by `ReleaseOperation`. The
  old `changelog-directive.md` is folded in: its runtime lines (read-only rule, the
  `{{version}}`, `{{previous_tag}}` and `{{commit_log}}` placeholders, the final-value
  contract) stay at the top; its restated categorize, format and exclude rules are dropped
  because the copied Step 4 carries them.
- Dropped (reason): Step 1 Pre-flight checks, Step 6 Present everything and confirm once,
  Step 7 Create and push the tag, and the rule "The annotated tag message should be exactly
  `Release vX.Y.Z`". The runtime checks the tree and remote before this step, confirms with
  the operator, creates and pushes the tag, and uses the changelog verbatim as the tag
  message; this step is read-only. The remaining steps keep their numbers.
- Name replacements: none.

## bill-feature-verify

- Target: `engine/operation/verify/verify-directive.md`, read by `VerifyPromptSections` by
  heading.
- Dropped (allowed): Workflow State, Continuation Mode, Telemetry.
- Name replacements: `bill-code-review` becomes `skill-bill phase review` (twice);
  `bill-unit-test-value-check` becomes `operation:unit-test-value-check`;
  `bill-feature-guard` becomes `operation:feature-guard`.
- The opening paragraphs still name `bill-feature-verify` as the workflow label, which stays
  stable.

## bill-feature-guard

- Target: `engine/operation/featureguard/guard-directive.md`. Dropped: none. Name
  replacements: none.

## bill-feature-guard-cleanup

- Target: `engine/operation/featureguardcleanup/cleanup-directive.md`. Dropped: none.
- Name replacements: Step 4 and one checklist line, `bill-code-check` becomes
  `skill-bill phase validation`.

## bill-monitor

- Moves nowhere. `skill-bill goal status` stays CLI-only; its help no longer names
  `bill-monitor`.
