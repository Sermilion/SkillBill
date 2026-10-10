# SKILL-414 subtask 3 census

## Confirmations

- **Slot and phase ids.** Used the digest-settled lists without reopening `PhaseSlot.kt` or `SkeletonDefinition.kt`: 11 slot wire values `preplan`, `plan`, `implementation`, `audit`, `code_review`, `quality_gate`, `write_history`, `commit_push`, `pull_request`, `monitor`, `standalone_review`. Standalone-invocable ids: `review`, `validation`, `plan`, `pr`, `monitor`. `verify` is an operation.
- **`.md` branch.** The `.md` suffix lives in the shared predicate as the private `GOAL_INTAKE_SPEC_FILE_SUFFIX`. `MARKDOWN_SUFFIX` does not exist. `looksLikeGoalIntakeToken` is issue-key prefix, `://`, `.feature-specs/`, or a token ending in `.md`. `GoalIntake` does not accept a generic `*.md` path (`ISSUE_KEY.matches` plus `namedDirectory`), so a first token such as `notes.md` routes to goal and fails there. That is accepted routing behaviour, not a `GoalIntake` change.
- **GoalIntake reuse.** `GoalIntake.parseOrNull` reuses `GOAL_INTAKE_URL_MARKER` and `GOAL_INTAKE_FEATURE_SPECS_MARKER`. It does not call `looksLikeGoalIntakeToken`: issue-key matching stays exact (`ISSUE_KEY.matches`) plus `namedDirectory` for slash-free `KEY-feature-slug`. Acceptance is unchanged.
- **Clikt suggestions.** Installed Clikt is 5.1.0 (`runtime-kotlin/gradle/libs.versions.toml`). `Context.Builder.suggestTypoCorrection` defaults to `DEFAULT_CORRECTION_SUGGESTOR` (Jaro-Winkler, threshold 0.8). Unknown subcommands already get closest-name suggestions. No custom edit-distance added.
- **update-check.** Option flags match (`--include-prereleases`, `--format json`). Output does not: `OperationCommand` always appends `Operation invocation ID:` via `completeText`, so JSON stdout is not the update-check contract payload. `CliRuntimeUpdateTest` and `McpSystemToolsTest` decode that payload from the top-level command. Chosen path: keep `UpdateCheckCommand` registered and set `hiddenFromHelp = true`. `skill-bill update-check` still runs. Existing `OperationCommandTest` catalog split (direct vs `operation update-check`) is unchanged. No alias.
- **code-review CLI.** `CodeReviewCommand` is still registered from `CliCommandGroups`. Docs may name it. They must not, and after this subtask do not, call it the driver of `phase review`.
- **Pack routing heading.** The second `## Routing` in `skills/skill-bill/content.md` is not a governed required section. Skill-class injection targets rendered `## Ceremony`. Renamed to `## Pack routing`.
- **feature-launch-warning injection.** Confirmed in `docs/skill-source-generation.md` (skill-class pointer family) and `orchestration/skill-classes/feature-launch-warning.yaml`: `class: feature-launch-warning`, `exact: skill-bill`, pointers `peak-hours-warner`, `shell-ceremony`, `telemetry-contract`, three `ceremony_lines`. Dispatcher Launch now names the full path `orchestration/skill-classes/feature-launch-warning.yaml`. Did not edit `skill-source-generation.md`.
- **Skip list.** `routeIntake` still skips `--db`, `--home`, their `=` forms, and `--verbose`. `completionOption()` is not on the skip list. Existing aliases `feature-verify-stats` and `feature-task-runtime-stats` are unchanged.

## Pinned text

- `FeatureFamilyRenderingIntegrationTest`: mode pin updated from `mode:auto|inline|delegated` to `mode:inline|delegated`; old auto form now asserted absent.
- `CliRuntimeShellCommandsTest`: root help now pins the new description and that `update-check` is not a listed top-level command. The `help output documents nested clikt commands` test no longer runs `workflow --help` or `workflow continue` (those passed only because the removed `workflow` token used to fall through to goal intake). The sibling `removed prose workflow and implement-stats commands are unknown` still covers `workflow` as an unknown command. `verify-workflow --help` and telemetry assertions stay.
- `SkillBillDispatcherRoutingTest` repoTest: `phase review forwards the pr staged and unstaged targets…` reads `section(dispatcher, "Review arguments")` and pins "The accepted `target:` values are `HEAD`, `uncommitted`, `pr`, `staged`, `unstaged`, `last` (maps to HEAD), or a commit, branch, or tag." Token Forwarding still pins `mode:` `{inline, delegated}` and `target:` values, `code-review:` `{inline, auto}`, Launch command line, Intake phrases, "Linear, Jira, and any other connected tracker", no `bill-feature`, and routed `skill-bill phase <name>` names equal to `phaseNames()`.
- `OperationCommandTest`: kept; catalog parity still compares top-level `update-check` with `operation update-check`.
- `CliRuntimeUpdateTest` / `McpSystemToolsTest`: kept as proof that hidden top-level `update-check` still runs.
- `CliGoalIntakeTest.refuseNewGoal`: free-text first tokens now go through an explicit `goal` token. Key/URL `startNewGoal` still uses implicit `routeIntake` prepend.
- README-vs-help consistency test: none found.
- No other goldens pinned the old dispatcher headings (`Invoke the driver`, `Review mode argument`) against `content.md`. `InlineReviewDirectiveTest` pins `## Review mode argument` on `review-directive.md`, a different file, left unchanged.
- No test found that pins the `docs/getting-started.md` review-mode sentence.

## Docs corrections this pass

- `docs/capabilities.md`: `phase:review` documents `mode:inline|delegated` with omission meaning inline and one clause that `auto` is still accepted as inline; `last` is in the target list; `write_history` is a slot, not a phase.
- `skills/skill-bill/content.md`: Launch names `orchestration/skill-classes/feature-launch-warning.yaml`. Forms and Routing says `phase:verify` stops and points to `operation:verify`. No `skill-bill phase verify` string, so the routed-phase regex still matches only real phases.
- `docs/runtime-command-guidance.md`: "It never edits or commits." now precedes "The `code-review` CLI command remains registered; it is not the driver of `phase review`." "It prints the findings register…" follows the slot sentence and refers to `phase review`.
- `docs/getting-started.md`: review mode leads with `mode:inline|delegated`; omission means inline, and `mode:auto` is still accepted as inline. Glossary link unchanged.
- `docs/review-telemetry.md`: Standalone-first list and Event catalog now name `/skill-bill operation:verify` as the standalone verify emitter, plus `phase:review`, `phase:validation` and `phase:pr` for the sibling entries.

## Retired names left unchanged on purpose

- README retired-skill row `bill-feature-verify` → `/skill-bill operation:verify`.
- Retired lists in `docs/runtime-command-guidance.md`, `docs/internal-skills-architecture.md`, and `docs/skill-source-generation.md`.
- Telemetry and workflow ids in `docs/review-telemetry.md`: the `child_steps` example `skill`/`routed_skill` values, the `routed_skill` field row, the Router skills never emit paragraph, and the `bill-feature-verify` workflow id in the MCP and remote-stats sections (lines around 764-811).
- The accurate `skill-bill code-review --review-session-id` mention in `docs/review-telemetry.md`.
- `InlineReviewDirectiveTest`'s pin of `## Review mode argument` in the engine's separate `review-directive.md` resource.

## Tests added

- `GoalIntakeTokenPredicateTest` beside `IssueAndFeature.kt`.
- `RouteIntakeTokensTest` in `runtime-cli` `cli.core`: `APP-123` plus `Add CSV export` prepends `goal` and keeps both tokens; `phase:review` splits; `CliRuntime` `phse review` unknown-command; `CliRuntime` `phase verify` / `phase:verify` operation hint.
- `PhaseInvocationParserTest`: `verify` usage error names `skill-bill operation verify`.
