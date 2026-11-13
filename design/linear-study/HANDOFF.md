# Sona desktop app, Linear design: implementation handoff

This document and the prototype next to it are everything needed to plan and build a new Sona desktop app. Nothing here depends on private links or on the machine where it was written. Paths are relative to the repository root.

- Prototype: `design/linear-study/index.html` (open it in a browser; see [README](README.md)).
- The old desktop app, `desktop-tray/`, was the behavior reference while this was written. It has since been removed; the app built from this handoff is `desktop/`.
- Runtime the app embeds: `runtime-kotlin/` (read `docs/architecture-guidelines.md` and `runtime-kotlin/ARCHITECTURE.md` before changing it).

Written 2026-10-27 against `main` at `1ef0c125b`.

Revised 2026-10-30 to match the Compose app in `desktop/`: collapsible detail sections, stacked and collapsible incident evidence, a folding run reason, spec bundle file order, ShortId for machine ids, the operator decision section without an incident, the refresh hint and stale indicator, editable review budget and compaction override rows, the purge dialog's safe default, menu focus and command menu hover, the main window's opening size, and corrections to the operations metrics, goal-wide pipeline steps and planning wave text. Later the same day: the goal's Subtasks list became a selectable index and the details pane became an inspector for the selected subtask (dependencies, validation, review passes and audit moved there from the document; the pane widens to 380 px), the details pane properties were redesigned (one-line values, short labels, breakdowns instead of joined strings), and the activity and History sections merged into one Activity feed. The inspector has no Status row, because its title row already shows the subtask's icon and state word. Goal assessment findings can be ordered by priority or status.

Revised 2026-11-12 (consistency): Activity, the Changes window and Diff comments are built from design system components and read item data; findings, timed events and chips each have one component (FindingRow, EventLines, Chip); screens write no component markup or inline styles, which `node scripts/check-reuse.mjs` enforces.

Revised 2026-11-12: subtask rows no longer expand. Every row in the Subtasks index, Goal finish included, is one line, and selecting it opens the details panel. The details pane became a floating panel over the document with a header, a drag-resizable left edge and a remembered width. It works at every window width and beside the Specs pane. The 1080 px breakpoint and the inline inspector are gone. This is a design addition: the app still expands the selected row when its pane is hidden (`SubtaskList.kt`) and its `DetailPeek` has a fixed width.

---

## 1. Outcome

A new desktop app that shows and controls Sona work, built to this design, that feels instant: selecting a goal or spec paints in the same frame, the board follows the runtime within a fraction of a second, and an active goal does not make the app stutter.

The current app takes about 5 seconds to open a goal or spec. Section 5.2 explains why, with measurements, and section 5.4 lists the runtime changes that remove the cause.

## 2. Decisions already made

| Decision | Detail |
| --- | --- |
| New app | Built fresh, not a restyle of `desktop-tray`. `desktop-tray` stays the reference for behavior, edge cases and copy. |
| Feature scope | Match the current desktop app where behavior overlaps. Keep added mock functionality for later implementation, including Confirm criteria, the queue popup and the device picker. |
| Stack | Kotlin/JVM with Kotlin Flow for all observation. Compose Multiplatform Desktop is the natural toolkit (the current app uses it and its terminal, tray and notification integrations are proven), but the toolkit is not otherwise fixed by this document. |
| No CLI | The app never runs `skill-bill`, never parses CLI output and has no CLI fallback. |
| Embedded runtime | The app loads the runtime that `install.sh` installed in `.skill-bill/runtime/runtime-cli/lib` under the user's home directory into its own JVM and calls runtime services directly. There is one installed copy; the app ships no runtime jars. |
| No coordinator daemon | Considered and rejected (section 5.7). |
| Dark only | One theme, the tokens in section 6. |
| Config included | The config screen edits real runtime config keys. The runtime needs a write path for most of them (section 5.4, R5). |

## 3. How to read the prototype

The prototype is the visual and interaction spec. Its code is organized so each file answers one question:

| File | Answers |
| --- | --- |
| `ds/tokens.css` | Exact color, type, spacing, radius, shadow, motion and size values. |
| `ds/components.css` | Exact dimensions, paddings and states of every component. |
| `ds/components.js` | The component inventory and each component's inputs. One function per component. |
| `app/screens.js` | What each screen contains, in which order, and which component renders each part. |
| `app/main.js` | Every action, its preconditions, its feedback text, the keyboard map and the command menu. |
| `app/data.js` | The data each screen needs, shaped like the runtime and desktop models. Values are examples. |
| `app/states.js` | Lifecycle examples and the linked States catalog. See `STATE-COVERAGE.md` for sources and checks. |
| `app/library.js` | The tray popover, OS notifications, the component catalog and the source-of-truth table. |

In the browser: **Prototype** is the app, **States** links each work type to its lifecycle and result variants, **Components** shows each component with live examples and the screens that use it, and **Foundations** shows tokens, the status vocabulary and where each feature comes from in this repository.

The prototype renders everything as HTML strings and keeps state in one object. That is a prototyping shortcut, not an architecture to copy.

---

## 4. Product scope

### 4.1 In the design

Each row is a feature the prototype shows, where it lives in the prototype, and the runtime entry point the new app calls. Service names are accessors on `skillbill.di.core.RuntimeComponent` (`runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeComponent.kt`); all listed accessors exist today.

| Feature | Prototype | Runtime entry point |
| --- | --- | --- |
| New task: open chats as tabs (new, close, a confirmation while a chat replies, Reopen from the toast, a count menu when tabs overflow), transcript with suggested actions, composer with quick starts, Send, Queue, Send now and Stop; keys G N, ⌘ T, ⌘ W, ⌘ Tab, ⌘ 1 to 9 | New task view (`#new-task`) | App-owned (`NewTaskChats`); turns go through `ExecutionChatRepository.send(ChatTurnRequest)` with `ChatScope.NewTask`; no runtime call |
| New task home for an empty chat: hero, step line, Recent chats of the picked repository (open badge, discard with Undo), Needs you with running and finished counts | New task view, empty chat | Recent chats: `ExecutionChatRepository.savedNewTaskDrafts()`, `deleteSavedChat(sessionId)`; Needs you and the counts come from the board read below |
| Repository and base branch picker: one pill opens a two-column popup; repository filter past seven repositories, Add repository, branch filter, default, local and remote branches; reading, failed and no-match states | New task composer (`#new-task/picker`); every state on the Components page, TargetPicker | Branches: `RepositoryBranchesRepository.branches(path)` (`RepositoryBranchesRead`); the repository list and added folders are app-owned |
| Chat changes: once a chat changes a file in its checkout, a pill on the composer's right before Stop and Send shows the file count and line totals ("3 files +84 −7"). It opens a popup with Changes, the count, totals and Open diff; up to six files with status letter, name, shortened folder and line counts; "N more files in the diff" past six; and since when, commits and uncommitted edits. A file, Open diff or the more row opens the Changes window scoped to the chat. No pill before the first change. A resumed chat keeps the baseline of its first session and says edits made while it was closed are included. Esc closes | New task composer: `#new-task/state/changes` (pill, popup closed), `#new-task/changes/none`, `few`, `many`, `resumed` | App-owned. The chat records a baseline at its first agent turn (HEAD sha, untracked paths that already exist, a `git stash create` sha when the tree is dirty) and saves it with the chat draft; the list is that baseline against the working tree, refreshed after each turn. The Changes window today opens only for a `WorkItem` (`ChangesWindows.open`), so it needs a chat source with All, Uncommitted and Commits scopes and no subtask strip. New design |
| Queued messages: the "N messages queued" pill opens the queue in send order, Next on the first, remove one with Undo for 5 seconds, Clear all after a second click, keys ↑ ↓ Del | New task composer (`#new-task/queue`) | App-owned (`ChatThread.queued`). The desktop app shows only the "N messages queued" label today, so the popup is new design |
| Resume a saved chat with `/resume`: palette scoped to the composer's repository, day groups, filter on title, branch and agent, preview of the latest four messages, agent CLI sessions, Show all past 30 | New task composer and Search chats on the home (`#new-task/resume`) | `ExecutionChatRepository.savedNewTaskDrafts()` filtered by repository; `openSavedChat(entry)` loads an agent CLI session's lines |
| Work board: Needs you, Goal runs, Standalone phases, Operations, Finished today; text filter | Work view | `ideStatusService.board` / `projectBoardSelection`; ordering, counts and grouping from `IdeStatusPolicy` (see 5.3 for the faster read path) |
| Board item facts: lifecycle, freshness, stalled, pause requested, step, phase execution count, progress, planning progress, durations, model, effort, agent, branch, repository | Rows, detail header | `WorkBoardItem` / `IdeStatusSnapshot` (`runtime-ports/.../idestatus/model/WorkBoardModels.kt`) |
| Detail: subtasks, goal finish, phase pipeline, dependencies, latest activity, Execution, Runs and fixes, Validation, Phase results (review passes, findings, audit), Goal QA, Goal assessment, QA results, execution history, spec bundle files | Detail view | `ideStatusService.detail(WorkDetailRequest)`; lists page by anchor (`WorkDetailList`) |
| QA results, as shown in this prototype in every scenario: the section title with the verdict 8px after it (an 8px dot and the word in the tone) and one sentence the app writes from counts, never from the agent's prose; Needs you (open findings with a red circle, the ref in mono and the state word, what QA observed in two lines; they open to Expected, Steps, evidence files and Copy steps), Left behind (a triangle; Copy pid or path; a process names its program when the run reports one), Not tested and Unblock (a dashed circle; Target, Remedy, Tried, evidence, Copy for qa.md, then Open qa.md); one box of folds for Fixed by QA, Passed, Run details and Full report (Open report.md; an older run's report.md sits in its run directory, and its first paragraph shows above the fold while it is closed); the targets line, each kind in `fg-muted` and its word faint. Groups sit 20px apart. First section of a standalone QA run ("QA results", an open finding reads "Open" and the sentence "QA found"), and the Goal QA section of a goal ("Not fixed", "QA could not fix") | Detail of a QA run, one route per mock scenario: open problem and untested work, the real qa-4f7e0edf report (`#detail/ph-sb-qa`), untested work with Unblock (`#detail/ph-sb-qa-gaps`), clean pass (`#detail/ph-atl-qa`), left something behind (`#detail/ph-sb-qa-left`), nothing to test (`#detail/ph-sb-qa-docs`), did not finish (`#detail/ph-pay-qa`), older report (`#detail/ph-sb-qa-legacy`); fixed after QA's fixes as Goal QA (`#detail/SKILL-416`). The QA runs past the first are in History. Each mock scenario with the mock's own sample data, as Goal QA, closed and with every row open, on the Components page, QaResults | `ideStatusService.detail(WorkDetailRequest)`: the goal's `GoalQaResult` and, per QA execution, the verdict, report text and `QaRunDetails` (assertions, coverage, retained resources, temporary edits). The app parses Unblock entries from the report's Unblock section. A retained process's program name ("Gradle daemon") is new: `QaRunRetainedResource` has only kind and identity. Desktop: `QaResultsView`, `QaResultsSection`, `QaUnblock` |
| Goal QA while it runs, in nine scenarios: in the Live part of the qa phase card in Goal finish (the qa tab is the running phase, so it opens by default) above the results. The stage QA is on with "stage N of 7" and elapsed time, the invocation, a target chip, the qa / qa_fix round strip, the 16:10 preview (Video starts at <stage> and why, waiting for the first window, LIVE with the criterion, display restarting, NO VIDEO with the reason, no display, last frame after QA ends), a hint with the time an earlier run reached video, a warn callout for lost capture or a missing display, the evidence screenshots newest first, and the stage list (Prepare checkout, Compose UI tests, Start private display, Launch app, Exercise running app, Write report, Clean up) with video and headless tags. After QA ends the verdict heads the block and the stages fold to one row | Detail, Goal finish, qa phase card; States catalog "Goal QA live" rows | Preview frames: desktop `QaPreviewReader` over `<invocation dir>/live-preview/Xvfb_screen0`. Stages: new, see R6 in 5.4 |
| Run options (Resume / Run again / Start label, agent choices, cancel allowed) | Detail run actions | `workControlService.options(WorkDetailItem)` |
| Pause after subtask, Pause (stop now) | Detail, context menu, keys P and Shift+P | `workControlService.request(WorkControlTarget, PAUSE or STOP, storeIsCurrent)` |
| Cancel, Resume / Run again with agent | Detail, context menu, key R | `workControlService.request(WorkRunTarget, CANCEL or LAUNCH, agentId)` |
| Incident: diagnosis, cause, next action, prerequisites, evidence, report status | Detail incident section | `WorkDetailItem.incident`, `incidentHistory` |
| Recover with prerequisites, preview, Apply and resume | Detail incident, dialog | `workControlService.recoveryPreview(...)`, `workControlService.recover(...)`; prerequisite kinds: `credentials_configured`, `configuration_fixed`, `project_edit_completed`, `external_service_available` |
| Operator decision notice for a no-change pause: what the pause is and the CLI command that records retry fix, accept and advance, or abandon subtask | Detail incident on a goal waiting for a decision | Read-only; `skill-bill goal operator-decision` or chat records it |
| History: All, Goals, Phases, Operations; filter; retention window | History view | Board read with `finishedWithin` (`WorkBoardWindow`: 6h, 24h, 7d) |
| Specs pane: project picker; the ⋯ panel menu with Collapse all, Reveal specs folder (with its path) and Import work; status tabs All, Active, Pending and Done with counts; a search button that opens "Filter by key, title or file"; the tree with bundles (status, key, title, n/m, ⋯ on hover and on the selected row), spec.md, subtask specs with their state, traceability.md, decomposition-manifest.yaml and the `done` folder. A bundle's chevron expands it and a click selects it; a click on a file selects and opens it. Keys while the tree has focus: ↑ ↓ → ←, J K between specs, Enter opens spec.md, / filters, Esc clears the selection. States: "No specs yet", "No specs match" with Clear filters, "Specs unavailable" with Retry, "This project has no checkout on this machine." | Specs pane (`#specs`, `#specs/project/l-9c1b` for no specs, `#specs/project/l-51d0` for no checkout, `#specs/project/l-a7e2` for unavailable); every bar state on the Components page, SpecBar | `specBrowserService.projects()`, `projectFor(repoRoot)`, `snapshot(projectId, preferredRepoRoot)`. Desktop: `SpecsPane`, `SpecsTree` |
| Spec selection bar and spec menu: selecting a bundle, or a file in it, docks a bar at the bottom of the pane with key, title, n/m and the favorite star (F); a status pill and an agent pill (a menu while a launch is allowed, plain once it runs); ⋯ and one primary action from the runtime's verdict: Start, Resume or Run again, else Pause while it runs, else none; then "Pause recorded. The goal stops after its current subtask." or the reason run controls are off. Start without an agent opens "Run on" first and starts on the pick. Right-click, the row's ⋯, the bar's ⋯ and ⌘K show one list: Open spec, Reveal folder, Copy key, Add or Remove favorite; Export work, Start / Resume / Run again (R), Pause after subtask (P) or Pause requested, Choose agent or "Change agent, now `<id>`" (A); Cancel goal…, Purge run history… and Delete spec files…, each confirming in a dialog with the safe action focused. Items the runtime turns off stay in place and say why ("Pause or cancel the goal first"). Export of a live goal asks to pause first | Specs pane: a bundle in each verdict state (`#specs/SKILL-444` not started, `SKILL-482` not started with run history, `SKILL-442` running, `SKILL-417` pausing, `SKILL-468` paused, `SKILL-438` blocked, `SKILL-401` canceled, `done/SKILL-446` complete, `SKILL-451` unavailable) | `specBrowserService.control(...)` (`SpecBundleControlPolicy`) for verdicts and labels; `workControlService.startGoal(repoRoot, issueKey, agentId)`; pause and cancel as above; `goalRunnerStatusService.purge(GoalRunnerPurgeRequest)`. Desktop: `SpecSelectionBar`, `SpecsMenuModel`, `SpecsPaneModel.commandSection`, `PurgeConfirmationDialog`. Favorites, export, import and delete are desktop features the runtime backs outside `SpecBrowserService` |
| Validation run, as shown in this prototype in every scenario: the header (status pill with a dot: Active, Paused, Finished, Canceled, or Passed and Blocked once checks are reported; "validation" in mono, the phase pill and, while it runs, the fresh pill; the 20px title "Validation on" with the branch in code; repository and agent; run controls by state: Cancel while running, Resume and Cancel while paused, Run again and Copy report when finished, Run again when canceled). The outcome card replaces the Step and Phase elapsed tiles. Its tone follows the lifecycle until the agent reported checks, then the runtime's outcome (Passed, Blocked with "needs an operator", Failed); a finished run without checks uses the app's green completed tone. The card heads with the report's first sentence and the rest of that paragraph, or "Running" and the check with its command and clock, or the current activity, "The report appears here when the agent finishes." and the last agent signal; then the check tally (failed attempts fixed) and one bar segment per check, and the Active, Started, Checks (retried count) or Agent, and Run cells. Under it: the flag strip (parts the report flags, each a button that opens the part and scrolls to it), Checks with the "reported by the agent" tag (every attempt with time, exit code, duration and note; retried checks start open while the run is live; "Waiting for the agent to list its checks." before the first one), Report in parts with Expand all and Collapse all (parts about checks or results start open when no checks were reported), Validation runs on this branch (newest first, shown with two or more runs, the current one tinted with "this run", another opens its run), Activity. Sections without data are not drawn | Detail of a validation phase, one route per mock scenario: running (`#detail/ph-sb-val-running`), finished with repairs (`#detail/ph-sb-val-repairs`), finished with coverage gaps (`#detail/ph-sb-val-413`), finished and blocked by the environment without checks (`#detail/ph-za-val-report`), paused at the provider limit (`#detail/ph-sb-val-limit`), canceled (`#detail/ph-sb-val-411`), running with no checks listed yet (`#detail/ph-sb-val-waiting`), running with live checks unavailable (`#detail/ph-rk-validation`), running with checks (`#detail/ph-sb-validation`), passed with checks (`#detail/ph-sb-validation-done`), blocked with checks (`#detail/ph-za-validation`). The running, waiting, repairs and report-only routes are not on the board or in History. Every part on the Components page, Validation run group, and every card state under RunOutcome | `ideStatusService.detail(WorkDetailRequest)`: the current execution's `liveChecks`, `validationOutcome` and `validationChecks`, the report (`terminal_result`), `validationBranchRuns`. The app splits the report into parts. Desktop: `ValidationDocument`, `ValidationOutcomeCard`, `ValidationChecksSection`, `ValidationReportParts`, `ValidationBranchRunsSection` |
| Monitor run, matching design/monitor-run (Redesign) section by section in every scenario: the header (status pill, "monitor" in mono, the phase pill and, while it runs, the fresh pill; "#482" in faint before the PR title; repository and branch → base; Cancel while it runs and Open PR). The outcome card: the state word and step (Watching CI · monitor, Repairing · monitor_fix, Publishing · commit_push, Resolving conflicts, CI passed, Merged, Repair cap reached, Needs you), one sentence for now and one under it, the breathing agent or poll signal, actions where the run stopped (Monitor again, Open worktree, Open failing job, Open PR, Copy summary), and the Pull request (open, conflicting, merged), Head (repairs published), Checks (passed of reported, then failed, running or unfinished, queued) and Repair cap cells, the cap as three squares that fill orange and turn red when full. Cycles: one row per CI round with CI, Repair and Publish cells (a tone edge, the result in a few words, the head sha and failing check or agent and time, one bar per check for CI; the live cell breathes; empty cells say Starts on a failure, After the fix, Not needed, Cap reached, Nothing to publish); a cell opens its panel below, which starts on the live step or where the run ended. Panels: the CI board (head and poll line, a bar segment per check, tally, Failed, Running or Unfinished, Waiting, Passed folded to one line, the failed check opening its log tail with error lines marked, a conflicting or merged line, the empty-check window, what happens next); the repair (the agent's live signal or explanation, Watch agent, the failure it fixes with its log tail open, Files changed from the edit journal with the file being edited live, Local checks, the Repair outcome badge); the merge repair (base and tip, resolved over conflicted files, each conflicted file with hunk squares and This PR / main notes, files merged cleanly folded); the publish receipt (staged, the commit with one or two parents and its message, the guarded push, the acknowledged head, steps not yet run dashed, the commit's files) or the PR edit (before and after, head unchanged, the grace window). Activity: time, dot, line. Paused, failed and canceled runs keep their cycles; the card speaks for the lifecycle with Monitor again. The Goal finish monitor card holds the compact Cycles grid (a cell opens the monitor or monitor_fix tab) and the CI board; the monitor_fix card holds the repair and the publish receipt | Detail of a monitor phase, one route per mock scenario: CI running (`#detail/ph-sb-mon-ci`), repair running (`#detail/ph-sb-monitor`, in Standalone phases), publishing (`#detail/ph-sb-mon-publish`), merge conflict (`#detail/ph-sb-mon-conflict`), waiting for checks (`#detail/ph-sb-mon-wait`), PR-only repair (`#detail/ph-sb-mon-pr-edit`), CI green (`#detail/ph-sb-mon-green`, in Finished today), cap reached (`#detail/ph-sb-mon-cap`, in Needs you), repair unresolved (`#detail/ph-sb-mon-unresolved`), merged (`#detail/ph-sb-mon-merged`); the other seven are reachable by route only. States catalog: Monitor row and the Monitor variants. Goal finish: `#detail/state-goal-goal-finish-monitor-cycle-2/finish/monitor` and `/finish/monitor_fix`. Every part on the Components page, Monitor run group | New design. Target and identity: the monitor target (`MonitorPullRequestIdentity`, base). Checks: `PullRequestCheck` (name, bucket, link, startedAt) per poll, held in memory only. Log tails: `MonitorFailureLog`, fetched for the repair brief, in memory. Conflict: `PullRequestCiOutcome.Conflicted` and `MonitorFixBrief.recordConflict`, in memory. Heads, published repairs and PR edits: `StandaloneMonitorLedger` (`acknowledgedHeadSha`, `followedHeadShas`, `StandalonePublishedRepair`, `StandalonePullRequestEdit`), in memory. Repair files: `worktree_edit_journal`; agent signal: `agent_activity_stamps`; repair outcome and prose: the monitor_fix output. Not recorded anywhere yet: the cycle list, the workflow and runner of each check, conflicted files and hunks (`git diff --diff-filter=U` in the owned worktree), the This PR / main notes, local gate runs of monitor_fix. Persisting the monitor state (check snapshots, the ledger) comes first so the app can read it and a restarted runtime can show past cycles. Desktop: `MonitorDocument`, `MonitorOutcomeCard`, `MonitorCyclesGrid`, `MonitorCiBoard`, `MonitorRepairPanel`, `MonitorConflictPanel`, `MonitorPublishReceipt` |
| Verify run, as shown in this prototype: the header (status pill, issue key, Verify pill, the 20px title, repository, branch, compared range and intake, run controls, the superseded banner with Open newer run, and the Intake fold for free-text intake); What this work does (the verdict's summary, or the first criterion before a verdict); the outcome card with the verdict or current step and Criteria, Review, Tests, and QA or Flag audit counts, each a button that opens its evidence; Criteria with the app's PASS, PARTIAL and FAIL badges, Evidence, What's missing and Not found, an All, Gaps and Met filter, and Non-goals, Technical constraints and Rollout folds (what extraction returned when it found no numbered criteria); Steps on a rail, each opening its receipt (inputs, diff by directory, flag audit checks, review findings, test value rows and plan, and the QA step's results with only file names for evidence); Activity folded under the steps. Controls by state: Confirm criteria and run (off with no numbered criteria), Adjust intake and Discard while parked; Stop while running; Resume from the stopped step and Run again; Copy report, Open changes and Verify again when finished. On a finished or stopped run, findings, Partial and Missing criteria, test rows that are not Keep and QA findings can be selected (Select all per list), each routed to Chat or Goal (Missing defaults to Goal); Not in this repo and Spec question take a criterion out of fixes and out of every "Same code as" hint; the selection bar names the file line several selected items share; Fix N in chat sends one brief, Start goal with N opens a New task chat with the goal intake on the verified branch. 0AC-85 opens with the mock's starting selection (four selected, #3 and #4 marked) | Detail of a verify operation: running (`#detail/op-verify`), awaiting confirmation (`#detail/op-verify-confirm`, `#detail/op-verify-nocrit`), approved with fixes with QA (`#detail/op-verify-fixes`), request changes, stopped and superseded in History (`op-verify-085`, `op-verify-stopped`, `op-verify-superseded`); every part on the Components page, Verify run group | `ideStatusService.detail(WorkDetailRequest)`: `WorkDetailItem.verifyRun` (`VerifyRunDetails`: steps, criteria, completeness audit grades, review findings, flag audit, unit test value, changed files, verdict, QA, `supersededBy`, `staleReason`). Criterion marks are app settings (`AppSettingsRepository.criterionMarks`). Confirm is the operation's `awaiting_confirmation` round trip (4.2). Desktop: `VerifyDocument`, `VerifyHeader`, `VerifyOutcomeCard`, `VerifyCriteriaSection`, `VerifyStepsTimeline`, `VerifyFix*`, `VerifyAgainLauncher` |
| Plan run (a standalone plan phase): the stage strip (Reading the repo, Splitting the work, Writing subtask specs, each with its id and time; a cell scrolls Activity to its stage); one Now block with the sentence, the latest step and "Last output 3s ago", Elapsed and the agent, orange with "No output for" once the agent goes quiet, a note for an agent without a structured stream, or the blocked callout ("Subtask 3 needs your decision", Retry subtask 3, Open incident); Activity with All, Files, Commands and Notes, Follow and Jump to latest in a ten-row window with a sticky header per stage; Spec bundle with spec.md, the manifest and one row per subtask spec (queued, writing with its latest line, quiet, blocked with its reason, saved and openable) under the wave; Transcript as a three-line window with Expand and Copy; Briefing folded to a teaser (briefing size per stage, three digest lines, the run setup as pills) that opens to the run setup, the preplan digest by heading and each stage's raw briefing with Copy. Implement turns on when every spec is saved | Detail of a plan phase: reading the repo (`#detail/ph-atl-plan`), splitting the work with an agent that does not stream (`#detail/ph-news-plan`), first wave writing (`#detail/ph-sb-plan`), agent went quiet (`#detail/ph-pay-plan`), a blocked subtask (`#detail/ph-rk-plan`), plan ready (`#detail/ph-sb-plan-done`); every part on the Components page, Plan run group | `ideStatusService.detail(WorkDetailRequest)`: `standalonePlan` (`StandalonePlanProgress`: stages with `briefingText`, units, wave, blocked ids and reason), `planActivity`, `planOutputLines`, `planOutputUpdatedAt`. The app parses the run setup and digest from the fixed briefing layout. Implement launches a goal from the plan. Desktop: `PlanDocument`, `PlanStatus`, `PlanNowCard`, `PlanActivity`, `PlanSubtasks`, `PlanOutput`, `PlanImplementation`. Retry subtask has no runtime call yet |
| Phase peek, matching design/phase-peek in every scenario: in the details pane, a subtask's phases (preplan … commit_push) and Goal finish's phases (goal_assessment, qa, qa_fix, validate, pr, monitor, monitor_fix) are tabs. A phase that ran opens one card under the tabs; the selected tab has a ring outside its state color, a running tab a breathing dot, a skipped tab is struck through, phases that have not run stay inert, and arrow keys, Home and End move between the tabs that open. The pane opens on the running or stopped phase, else the last phase that ran. The card: header (status, title, phase id, state word, agent and model, round, start, duration, a 2px edge in the state color), a tinted strip saying why it is blocked, Now while it runs (agent activity, last edit with +/−, check cells, the command; Waiting on while blocked), what it produced (spec file, edit-journal stats and files, criteria, findings, build receipt, checks, history receipt, commit and push, PR, CI checks, fix commit), up to five Activity lines, and a button to the full screen (review, validation run, Changes, GitHub). The goal_assessment card holds the whole assessment (stacked findings, the Default / Priority / Status order, exit reason, fix commit) and the qa card the whole goal QA (Live block while running, then the rounds strip, last frame, screenshots, folded stages and the QA results with Sent to qa_fix while qa_fix runs); the Assessment / QA switch is gone. The subtask inspector's Review block moves into the review card. The details pane is 380px for every goal | Detail details pane: SKILL-417 subtask 02 (review running, `#detail/SKILL-417/2`) and 01 (complete, `#detail/SKILL-417/1`); States catalog rows Subtask blocked at validate and Goal finish, QA running / qa_fix running / validate running / monitor cycle 2 / done. Any card by route: `#detail/<id>/<subtask id or finish>/<phase>`. Components page: PhasePipeline (tabs), PhasePeek, PeekPart | New design. Header: phase records in `feature_task_workflows.artifacts_json` (`status`, `attemptCount`, `startedAt`, `durationMillis`, `resolvedAgentId`, `launchedModel`, `launchedEffort`). Now: `agent_activity_stamps`, `Detail.latestActivity` (event and `WorktreeEdit`), validation gate progress. Produced: the settlement envelope (`feature_task_phase_settlements`: `produced_outputs`, `verdict`), `worktree_edit_journal`, `build_receipt`, `validation_receipt`, `history_receipt`, `commit_push_result`, `pr_url` / `pr_number`, QA outputs, `GOAL_ASSESSMENT_RECORD`. Activity: `ActivityGroup` phases and facts for that phase. Not persisted yet: monitor CI checks (`MonitorFixBrief` keeps failing checks in memory only) |
| QA device, using the switcher row in every scenario: a switcher row at the bottom of the sidebar (glyph with a state pip, the pick over one faint line) opens a 300px popup 8px above it with Let QA choose, connected phones and running emulators, then stopped AVDs; unauthorized, offline and leased devices show their state and fix but cannot be picked; the list is read on open and on Refresh (R), never polled; ↑ ↓ Enter Esc; the pick shows on the QA my branch quick start ("on Pixel 8 Pro") with "QA runs use the device picked in the sidebar." under the quick starts | Sidebar footer (`#qa-device`), one route per mock scenario that opens the popup over that list: `#qa-device/mixed`, `#qa-device/one`, `#qa-device/unauth`, `#qa-device/avdOnly`, `#qa-device/none`, `#qa-device/noAdb`, plus `#qa-device/offline`; every list state on the Components page, DevicePanel | New design with no backing yet: the desktop app has no device picker and `RuntimeComponent` has no device list. The list would be app-owned (`adb devices -l`, `emulator -list-avds`). Lease owners come from the QA device leases, but `QaDeviceLeasePort` can only acquire and release. A QA run started from the app needs a device parameter |
| Build indicator: on a work row, a chip in the meta while the run's agent is inside a build tool command (spinner, short command such as `gradlew assembleDebug`, ticking time; orange past the usual time) or after the build failed (red, "failed"); a passed build stays off the board. Under the run header, a strip: Building, Build passed or Build failed, the full command in mono, the time with "usually 3m 10s", "1m 55s over usual", "first run, no usual time yet" or "finished 2m ago", a bar of elapsed over the usual time while it runs, then project type, tool, pid, start time, exit code and the previous build of the same command. Validation runs get the chip but no strip, because their live card already names the running check. Nothing shows when no build tool command was seen | Work rows and the run document; `#build/<state>/<type>` opens SKILL-417 in each scenario (state: running, slow, passed, failed, first, idle; type: android, kotlin, ts, go, rust, ios, none); BuildChip and BuildStrip on the Components page | New design with no backing yet: no runtime service watches a run's agent process tree. It needs the agent's child processes matched against the repository's platform pack build tools (`gradlew`, `pnpm`/`npm`, `go`, `cargo`, `xcodebuild`), and a per-repository history of build durations for the usual time (median of the last five runs of the same command) |
| Chat waiting chip: beside a chat's activity label, a chip for the shell command the chat waits on: one the agent left running in the background after its turn, or one running for 3 seconds or more in the current turn. Spinner, a short name made from the command (`gradlew check`, `npm dev`), the ticking time; hover shows the agent's description or the command. More than one adds a +N tag. It goes when the command ends or the agent process stops | `CommandChip` on the Components page | App-owned. Claude: stream-json `task_started` (local_bash, backgrounded) and `task_notification` / `task_updated`, linked to the `Bash` call by `tool_use_id`. Every agent: the shell command of the running tool call (Claude `Bash`, Codex `commandExecution`, Cursor `rawInput.command`). Desktop: `ChatActivityPill`, `ChatThread.backgroundCommands`, `commandName` |
| Terminal pane: tabs named per project, new, rename, close, resize; opens in the selected work's repository | Terminal pane | App-owned (pty4j + JediTerm, section 9.5); no runtime call |
| Settings: database path, Claude config directory, history retention, six notification types, start at login | Settings, App group | App preferences; Claude directories from `installAgentService.claudeRoots(...)` |
| Config, Models: tiers, phase tiers, per-agent phase overrides; junie takes no model | Settings, Runtime config | Read: `configResolutionService.resolveExecutionMatrix()`. Write: new (R5) |
| Config, Compaction: enabled, window tokens, trigger percent, per-phase overrides | Settings, Runtime config | Read: `configResolutionService.resolveCompactionSettings()`. Write: new (R5) |
| Config, Project: spec source, Gradle wrapper, review context budget, phase instruction files | Settings, Runtime config | Read: `RepoLocalConfigPort` via the runtime graph, `configResolutionService.resolveSpecType`; phase files read from `<repo>/.skill-bill/phases/`. Write: new (R5) |
| Config, Telemetry and agents: level, proxy URL, batch size; detected agents | Settings, Runtime config | `telemetryService` / `TelemetryConfigStore.writeTelemetryLevel` exist; proxy and batch writes new (R5); agents from `installAgentService.detectAgentTargets(...)` |
| Tray popover: Running, Needs you, Done 24h; Goals, Phases, Operations tabs; Open, Refresh, Settings, Quit | Components page, Surfaces | Same board read; `IdeStatusPolicy.arrange` counts and tray status |
| OS notifications for six lifecycle transitions | Components page, Surfaces | App-owned, driven by board transitions (desktop `TransitionNotifications.kt`) |

### 4.2 Runtime capabilities not designed yet

These exist in the runtime and are reachable through `RuntimeComponent`, but the prototype has no screen for them. Decide per item whether the new app needs them; each needs design before implementation.

| Capability | Runtime entry point |
| --- | --- |
| Goal preflight before start: verdict, confirmation gate, missing spec targets | `goalPreflightService` |
| Goal-wide unaddressed findings ledger | `unaddressedFindingsLedgerService` |
| Goal planning attempt log | `goalPlanningLogService` |
| Resume without starting child runs (clears pause and stale wedges) | `goalRunnerStatusService.resume` |
| Repair, reset, replan, accept-after-hard-reset | `goalRunnerStatusService.repair / reset / replan / accept` |
| Dedicated forms for standalone phase intake beyond the New task chat and start card | New task already launches review, validation, qa, plan, pr and monitor |
| Dedicated proposal forms for release and feature-guard operations outside chat | `operationExecutor`; Verify criteria confirmation is designed through both chat and the Confirm criteria button. PR review fix also confirms in chat |

### 4.3 Out of scope

Skill authoring and scaffolding, platform pack and add-on management, installation and agent linking, review import, triage and stats, learnings, telemetry stats and sync, update, uninstall and doctor. These are developer or installer tools, not work monitoring.

### 4.4 Explicitly not in the product

The first design pass invented these; they were removed and must not come back without a runtime feature behind them: an inbox with read/unread state, structured filter chips and display options, row multi-select and bulk actions, copy-command boxes, undo after an action, a light theme, model typo detection and model suggestion lists, a workspace switcher menu, a per-repository sidebar list.

---

### 4.5 Design sync rule

The current desktop app defines shared styling and behavior where it overlaps a mock. Keep mock additions that extend functionality, and record them as awaiting implementation. Confirm criteria remains available through both a button and chat. Verify awaiting confirmation belongs in Needs you. Marked criteria leave fixes and every shared-code hint.

Build new proposals as standalone mocks. After the operator confirms a proposal, sync it into `design/linear-study` in the same request. Keep its standalone mock until the operator confirms the synced version, then delete it. Standalone mocks already represented here were removed during the design cleanup. Activity, Changes window and Diff comments are now incorporated into this prototype; their standalone copies were removed. The architecture assessment is a separate report and remains in `design/`.

The States catalog is design documentation, not a new desktop navigation destination. It exercises the same renderers as the prototype. `STATE-COVERAGE.md` records its source inventory and verification limits.

## 5. Architecture

### 5.1 Process model and runtime embedding

One JVM. At startup:

1. Resolve the runtime install root: `SKILL_BILL_RUNTIME_DIR` if set, else `.skill-bill/runtime` under the user's home directory (desktop: `RuntimeInstallRoot.kt`).
2. Read `runtime-cli/build-identity.properties` (keys: `commit`, `dirty`, `release_tag`, `install_run_id`, `read_schema`, `incident_detail_version`, `min_incident_detail_read_schema`). Refuse to start when the runtime is missing, unreadable, or not the build the app was built for, and show the "runtime unavailable" state with the reinstall command (desktop: `InstalledRuntimeBuildGate.kt`, `RuntimeBuildProblem`).
3. Load `runtime-cli/lib/*.jar` through an application class loader (desktop: `DesktopBootstrap.kt`). The packaged app contains no runtime jar; keep a build check equivalent to desktop's `verifyPackagedRuntimeFree`.
4. Per selected database, build one `RuntimeComponent` and keep it for the session. Building it costs about 2 seconds of cold JVM time (section 5.2), so never build it per read.

```kotlin
val runtime: RuntimeComponent = RuntimeComponent::class.create(
  RuntimeContext(
    EnvironmentContext(
      databasePath,                       // null or blank uses the runtime's default path precedence
      environment = hostEnvironment,
      userHome = Path.of(userHome),
      repositoryRoot = Path.of(workingDirectory),
    ),
    TransportContext(),
    WorkflowOpsContext(),
    OptionalCallbacks(hostAgentHomesPort = /* Claude config directory chosen in settings */),
  ),
)
```

This is how `desktop-tray/core/data/src/jvmMain/.../runtime/RuntimeSessionProviders.kt` does it. The default database is `.skill-bill/review-metrics.db` under the user's home directory (`DatabasePaths.defaultDbPath`), overridden by the database path setting or `SKILL_BILL_REVIEW_DB`.

The app and the runtime are a matched pair. `install.sh` installs both; an upgrade of one without the other makes the app show the runtime-unavailable state.

### 5.2 Why the current desktop app is slow

Measured 2026-10-27 on a working machine (database 236 MB, WAL 106 MB, 64 tables, 113 indexes), read-only:

| Measurement | Result |
| --- | --- |
| `sqlite3` open plus a trivial query, including process start | 30–70 ms |
| Any runtime read through a fresh JVM (`work list`, `work board`, `work detail`) | 3.0–3.8 s, against 1.2 s for a JVM that only prints its version. About 2 s goes to building the runtime graph and its first read, independent of the query. |
| `worktree_edit_journal` | 228,932 rows across 194 workflows. Everything else is under 1,500 rows. |
| Worktree probe write rate during active runs | Up to 3,516 rows and 214 probe batches per hour |
| Journal WAL | 106 MB beside a 236 MB database: checkpoints are not keeping up |

From the code:

- **Every runtime read opens a new SQLite connection.** `SQLiteDatabaseSessionFactory.read` and `readIfPresent` call `DatabaseRuntime.openExistingReadDbAt` per call. Each open runs connection PRAGMAs, reads the migration ledger, checks the schema version and the desktop read identity, and takes file observations before and after. Each new connection also starts with a cold page cache, so every query rereads pages.
- **Opening a goal costs at least two of those reads.** The detail load runs `ideStatusService.detail` and then `workControlService.options`, each in its own read (`RuntimeWorkAdapter.kt`).
- **Change signals are coarse.** The runtime has two board topics, `goals` and `phases`, plus `activity`. Row triggers on 21 tables bump board topics on every insert, update and delete (`DesktopChangeRevisions.kt`). The high-frequency ones bump both board topics: `worktree_edit_journal`, `agent_activity_stamps` (which also bumps `activity`), `feature_task_runtime_worker_leases`, `ide_status_workflow_execution`, `feature_task_workflows` and `feature_task_phase_settlements`. A worktree probe or a lease heartbeat therefore invalidates every board section and every open detail.
- **Every owner re-reads.** The main window, the tray and the notification coordinator each keep their own board subscription, so one signal fans out to several projections, each paying the per-read cost.

The likely result: while a goal runs, a signal arrives every few seconds, each one triggers several full projections on fresh connections, and a selection's read competes with that churn or is restarted by it. Confirm this before building on it (first step of section 12): record the current app with Java Flight Recorder while a goal runs and open a goal.

```sh
# Example: add to the desktop app's JVM arguments, reproduce, then open the .jfr in JDK Mission Control
-XX:StartFlightRecording=filename=skillbill-desktop.jfr,settings=profile,dumponexit=true
```

### 5.3 Data access design for speed

Rules for the new app:

1. **One runtime graph and one long-lived read path per database session.** Reads reuse an open read-only connection (or a small pool) with a warm page cache. Schema compatibility is verified once at open and again only when the signal's `generation` changes or the database file identity changes (needs runtime change R1).
2. **Reads never hold a transaction open.** A read transaction is short and closes before the result is emitted, so writers can checkpoint the WAL.
3. **Observation is signal-driven, never polled.** The runtime writes `<database>.skill-bill-commit-signal` atomically after each commit (`DesktopCommitSignalPublisher.kt`, contract `DesktopCommitSignal` and `DesktopReadSchema` in `runtime-contracts`). Format, one `key=value` per line: `generation=<uuid>`, then one line per topic with its revision (`goals=…`, `phases=…`, `activity=…`). Watch the file's directory with `WatchService`, filter by file name, parse, and publish a `StateFlow<SignalSnapshot>`. A new `generation` means a full resynchronization. A watcher overflow means reread the file. Do not rely on Room or SQLDelight invalidation: it only sees writes made by its own process, and Sona's writers are other processes.
4. **Each query observes only its topics.** Map the signal to the topic revision the query depends on, `distinctUntilChanged`, then re-query. With runtime change R2, queries also receive the changed item ids and re-read only those rows.
5. **One shared observation per query, not one per owner.** The main window, the tray and the notifications share the same board flow (`stateIn` / `shareIn` in the session scope, keyed by query parameters).
6. **Selection paints from memory.** The clicked row already holds the header facts (title, lifecycle, step, progress, durations, agent, model, branch). Render them in the same frame, then load detail lists in the background. Prefetch the detail of the focused row while the user moves with J and K.
7. **Detail lists load lazily and page by anchor.** Load the visible sections first; load subtasks, validation, phase results and execution history as their sections enter the viewport. Use the runtime's per-list cursors and anchors (`WorkDetailRequest.list`, `anchor`).
8. **Policy stays in the runtime.** The app never sorts work, judges lifecycle or freshness, or weighs a time window. It renders runtime verdicts (`displayGroup`, `displayPriority`, `requiresAttention`, `controlEligible`, `pauseEligible`, `running`, `doneWithinDay`, `membershipDeadline`). This is a rule in `desktop-tray` (`DesktopArchitectureTest`) and should carry over.
9. **Time-windowed facts re-read once at their deadline.** Schedule a single re-query at `membershipDeadline` or `freshnessDeadline` per owner; no repeating timers in data code.
10. **Clocks tick in the UI only.** Elapsed time is `activeDurationMs + (now − activeDurationAsOf)`, recomputed by one UI-level ticker; it never reads the database.
11. **Database work runs on a dedicated dispatcher** (for example `Dispatchers.IO.limitedParallelism(2)`) and never on the UI thread.
12. **Filtering runs in SQL before the limit,** as today, on indexed columns.

A sketch of one observed query:

```kotlin
fun observeBoard(scope: CoroutineScope): StateFlow<BoardState> =
  signals                                      // StateFlow<SignalSnapshot> from the file watcher
    .map { it.revisionOf(Topic.Board) }        // only the topics this query depends on
    .distinctUntilChanged()
    .conflate()                                // a burst of commits becomes one re-query
    .mapLatest { withContext(dbDispatcher) { reads.board(window, pageSize) } }
    .distinctUntilChanged()                    // equal results do not recompose
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), BoardState.Loading)
```

For a detail screen, do not let activity-topic churn restart the first load: paint from the row, load once, and apply later changes as incremental refreshes.

### 5.4 Runtime changes required

The new app needs these changes in `runtime-kotlin`. Each follows `docs/architecture-guidelines.md`, `runtime-kotlin/ARCHITECTURE.md` and `docs/code-principles.md`. Wire keys go in owning `*Keys` objects. A new migration bumps `DesktopReadSchema.VERSION`.

| Id | Change | Why |
| --- | --- | --- |
| R1 | A session-scoped read API: a read session that keeps one read-only connection open, verifies compatibility once, and runs many short read transactions; reopened on generation or file-identity change. | Removes the per-read open and verification cost and keeps the page cache warm. |
| R2 | Finer change signals: separate topics for board structure (lifecycle, step, progress, membership), activity (agent activity stamps, worktree edits, heartbeats) and specs; the signal also carries a bounded list of changed work ids per topic, with an overflow marker that means "re-read all". Worktree probes and lease heartbeats bump only the activity topic, and only when a displayed value changes. | Stops activity writes from invalidating the whole board and every detail. |
| R3 | A board read model: one table with a row per work item holding every fact the board and the detail header show (the `WorkBoardItem` fields plus display group, priority and deadlines), written in the same transaction as the source change. The board becomes one indexed `SELECT` ordered by priority. | Replaces multi-table projection on every read with a single cheap query. Time-dependent facts stay correct through stored deadlines. |
| R4 | WAL and journal hygiene: checkpoint (`wal_checkpoint(TRUNCATE)`) after goal completion and when idle; bound `worktree_edit_journal` to what the UI and goal status read (the latest probe per workflow, or a fixed history). | The 106 MB WAL and the 229k-row journal slow every read. |
| R5 | A config write service: read and write `execution_matrix` (agent tiers, `phase_tiers`, agent phase overrides), `compaction` (enabled, window, trigger percent, per-phase), `telemetry.proxy_url` and `telemetry.batch_size` in the user config. Path precedence is `SKILL_BILL_CONFIG_PATH`, then `.config/skill-bill/config.json`, then `.skill-bill/config.json`, with both paths relative to the user's home directory. Also write `spec_type`, `validation_gate.gradle_wrapper` and `review_context_budget` in the project store `config.yaml`. Validate with the existing parsers (`parseExecutionMatrix`, `parseCompactionSettings`, `parseValidationGateRepoConfig`, `ReviewContextBudgetPolicy`), keep unknown keys, write atomically, and refuse to overwrite a file that changed since it was read. Expose it through `RuntimeComponent`. | Today only telemetry level has a write path (`TelemetryConfigStore`). |
| R6 | QA stage reporting for the goal QA panel (section 4.1). Infer the video stages from what the runtime already sees: registering the `live-preview` directory means Start private display, a registered app process means Launch app, the first non-blank frame means Exercise running app, and `report.md` means Write report. Prepare checkout and Compose UI tests can't be told apart this way, so add a `skill-bill qa stage <id> [detail]` command that the QA pack calls at each stage, next to its `qa resource register` calls. Start with inference for the video stages and add the command for the rest. | Today the panel shows only Waiting, Live or Ended. In the SKILL-516 QA retry, QA spent about seven minutes in headless UI tests while the panel showed nothing, and after the display crashed and restarted without capture it kept saying Waiting. |

R1 to R4 are what make the app fast; R5 is what makes config editable; R6 feeds the goal QA stage list. R2 and R3 are the largest. If they are deferred, R1 plus the app-side rules in 5.3 still remove most of the per-selection cost, but activity churn remains.

### 5.5 Writes and controls

- Controls call runtime services on the database dispatcher and never block the UI.
- Pause and Stop pass a `storeIsCurrent` check so a request against a replaced database is refused.
- Results are distinct and the UI says exactly what happened, never more (desktop `ControlConfirmation`): intent recorded, request accepted, exit confirmed, termination attempted, or a typed refusal (`WorkControlRejection`: unknown target, changed target, ineligible target, not found, ownership mismatch, foreign host, and others). Section 11 has the copy.
- A control does not optimistically change the row. The row changes when the commit signal brings the new state; until then the control shows its in-flight label (for example "Working…", "Pause requested").
- Launch requests (Resume, Run again, Start) are reserved app-wide per work identity, so the detail and the Specs pane disable together until the launch registers or fails (desktop `LaunchRequests`).

### 5.6 App structure

Carry over what works in `desktop-tray`:

- One screen per feature module with a typed `UiState`, `Action` and `Effect` contract, its view model and its UI. Features depend only on core modules, never on each other or on the data layer's internals.
- A data layer that owns the runtime graph, the signal watcher, the read session and the repositories. Repositories expose `Flow`s and suspend functions; nothing above them sees JDBC or runtime types.
- Lifetimes with named owners: process, database session (switching databases rebuilds the runtime graph and every observation), window, screen entry.
- A design-system module holding tokens and components (sections 6 and 7); screens add no styling of their own.
- Architecture tests for module edges and for "no policy in the app".

Suggested modules: `core:designsystem`, `core:ui`, `core:domain`, `core:data` (runtime bridge, signal watcher, read session, repositories), `core:settings`, `feature:work`, `feature:detail`, `feature:history`, `feature:specs`, `feature:terminal`, `feature:settings`, `feature:config`, `feature:tray`, and the `app` shell (windows, navigation, command menu, keyboard, notifications).

### 5.7 Coordinator daemon: considered and rejected

A local service that alone opens the database, with the CLI, goal runners and the app as socket clients, would give a single writer and real push. It is rejected because:

- Reads would not get faster: an in-process read is far below a millisecond once the connection is warm; IPC adds a hop.
- The change notification it would provide already exists (the commit signal); R2 makes it precise.
- Goal runners are long-running processes that write leases, heartbeats and phase records; all of that would become IPC. The runtime's persistence layer would move into the daemon, and every goal would stop when the daemon is down.
- It adds auto-start, upgrades, crash recovery and a versioned protocol.

The seam that keeps it possible later: the app reaches data only through repository interfaces in `core:data`. A future coordinator would replace their implementation without touching screens.

---

## 6. Design system

Linear's structure with Sona's colors: a darker app frame, one inset content panel with a 12 px radius, dense 13 px interface type, hairline borders, and a single amber accent used for focus, the current step and the one primary action.

Sona uses the Ping drawing in `design/sona-logo-linear.html`. The shared `BrandMark` renders the same three solid bands clipped to a disc at every size, in one foreground color. The sidebar, tray, notifications and component examples share this mark. Runtime commands, paths and code identifiers retain their existing names.

### 6.1 Color tokens (dark only)

| Token | Value | Use |
| --- | --- | --- |
| `bg-frame` | `#0B0B0D` | Window frame, sidebar |
| `bg-panel` | `#111114` | Content panel, panes |
| `bg-raised` | `#17171B` | Group headers, buttons, card headers |
| `bg-overlay` | `#1C1C21` | Menus, dialogs, toasts, tray |
| `bg-inset` | `#08080A` | Inputs, terminal |
| `bg-hover` | `rgba(255,255,255,0.035)` | Row and control hover |
| `bg-active` | `rgba(255,255,255,0.065)` | Focused row, active nav, selected tab |
| `bg-selected` | `rgba(233,168,28,0.08)` | Current subtask |
| `scrim` | `rgba(4,4,6,0.6)` | Behind dialogs and the command menu |
| `border-subtle` | `rgba(255,255,255,0.055)` | Row dividers, panel edges |
| `border` | `#26262C` | Controls, boxes |
| `border-strong` | `#383840` | Menus, dialogs, hover borders |
| `fg` | `#EDEDF0` | Primary text |
| `fg-2` | `#C4C4CC` | Secondary text |
| `fg-muted` | `#9C9CA6` | Labels, icons |
| `fg-faint` | `#6A6A74` | Ids, captions, timestamps |
| `accent` | `#E9A81C` | Primary button, focus bar, current step |
| `accent-hover` | `#F3C462` | Primary hover |
| `accent-fg` | `#14100A` | Text on accent |
| `accent-text` | `#F3C462` | Accent-colored text |
| `accent-tint` | `rgba(233,168,28,0.12)` | Current-step fill, attention badge |
| `accent-ring` | `rgba(233,168,28,0.5)` | Focus ring |
| `st-active` | `#E9A81C` | Active |
| `st-planning` | `#6FA8DC` | Planning |
| `st-blocked` | `#E2703C` | Blocked, stalled, needs you |
| `st-failed` | `#F0555B` | Failed, danger |
| `st-done` | `#3FB950` | Completed, passed, fixed |
| `st-paused` | `#9C9CA6` | Paused |
| `st-pending` | `#6A6A74` | Pending, canceled |
| `data-1`…`data-6` | `#E9A81C`, `#6FA8DC`, `#3FB950`, `#E2703C`, `#B48EAD`, `#9C9CA6` | Repository dots and agent avatars only |

The current desktop palette (`desktop-tray/core/designsystem/.../Palette.kt`) uses the same hues.

Shadows: popover `0 0 0 0.5px rgba(0,0,0,.4), 0 8px 24px rgba(0,0,0,.5), 0 2px 6px rgba(0,0,0,.3)`; modal `0 0 0 0.5px rgba(0,0,0,.5), 0 24px 64px rgba(0,0,0,.6)`. Panels are flat.

### 6.2 Type

Inter for interface text, with OpenType features `cv11`, `ss01`, `ss03` (in Compose: `TextStyle(fontFeatureSettings = "cv11, ss01, ss03")`). JetBrains Mono for ids, phase names, shas, paths, model names and config keys. Both fonts already ship in `desktop-tray/core/designsystem/src/commonMain/composeResources/font/`. Space Grotesk is no longer used.

| Token | Size / weight | Use |
| --- | --- | --- |
| `t-2xl` | 24 / 600, tracking −0.012em | Detail title |
| `t-xl` | 20 / 600 | Settings page title |
| `t-lg` | 16 / 400–600 | Command menu input, pane titles, metric values |
| `t-md` | 14 / 500 | Dialog title, empty-state title |
| `t-base` | 13 / 400–500 | Body, rows, navigation |
| `t-sm` | 12 / 400–500 | Buttons, meta, properties |
| `t-xs` | 11.5 / 500 | Labels, group captions |
| `t-2xs` | 10.5 mono / 500 | Phase steps, shas, kbd |

Digits that line up use tabular figures.

### 6.3 Space, radius, size, motion

- Space: 4 px grid (4, 8, 12, 16, 20, 24, 32, 40).
- Radius: 4 (kbd, steps), 6 (buttons, inputs, rows, tabs), 8 (menus, boxes, toasts), 12 (panel, dialogs, tray), full (labels, avatars).
- Sizes: row 36, compact row 32, header 44, sidebar 236, details panel 800 by default for a goal and 560 for other runs (each at most 70% of the work body), 272 at minimum (`--props-w`), resizable up to the work body minus 64, property row 28 with an 80 px label column (`--prop-label-w`), breakdown row 22, activity row 28, Specs pane 400, terminal default 200 (min 120, max 600 in the prototype; desktop allows 120–1200).
- Motion: 90 ms for hover, 160 ms for menus and toggles, easing `cubic-bezier(0.25,0.46,0.45,0.94)`. Respect reduced motion.

### 6.4 Status vocabulary

Shape carries the state and color reinforces it, so a list reads in grayscale. All icons draw in a 14 px square (16 px large variant); circle center (7,7).

| State | Shape | Color |
| --- | --- | --- |
| Active | Ring r=5.5 stroke 1.5, plus a pie: circle r=1.75 stroke 3.5 with dash `f*11, 11` from 12 o'clock, `f` = progress clamped to 0.12–1 | `st-active` |
| Planning | Dashed ring r=5.5 stroke 1.5, dash 2.4/1.9 | `st-planning` |
| Stalled | Dotted ring r=5.5 stroke 1.5, dash 0.6/2.6, round caps | `st-pending` |
| Paused | Ring plus two bars 1.4×4.8 | `st-paused` |
| Blocked | Filled r=6.25 with an exclamation in `bg-panel` | `st-blocked` |
| Failed | Filled r=6.25 with an × in `bg-panel` | `st-failed` |
| Completed | Filled r=6.25 with a check in `bg-panel` | `st-done` |
| Completed, no change | Ring with a check | `st-done` |
| Pending | Ring | `st-pending` |
| Skipped | Dashed ring with a slash | `st-pending` |
| Canceled | Ring r=5.5 stroke 1.5 with an × in `st-pending`, lines (4.9,4.9)–(9.1,9.1) and (9.1,4.9)–(4.9,9.1), stroke 1.5 with round caps | `st-pending` |

Mapping from runtime lifecycle: `ACTIVE` → Active (Planning while `planningState` is not prepared, Stalled when `stalledSince` is set), `PAUSED` → Paused, `BLOCKED` → Blocked, `FAILED` → Failed, `TERMINAL` → Completed, `CANCELED` → Canceled. Subtask statuses: `complete`, `completed_no_change`, `in_progress`, `pending`, `blocked`, `skipped`.

AC-004 amendment: Canceled uses the outlined shape above, replacing the earlier filled circle. Match the updated prototype for Canceled and preserve shape-only distinction in grayscale for IS-002. Failed remains filled.

### 6.5 Icons

Lucide (ISC license) line icons at 16 px with a 1.75 stroke, inheriting the text color. The prototype's set is in `ds/components.js` (`P` map): target, history, fileText, file, folder, terminal, sliders, search, pause, stop, play, rerun, refresh, copy, branch, commit, x, check, chevrons, arrowLeft, external, warn, alert, info, checkCircle, bot, cpu, gauge, repo, shield, user, bell, keyboard, panelLeft, panelRight, layers, zap, diff, pencil, plus.

---

## 7. Components

Every screen is built from these. Dimensions are in `ds/components.css`; inputs are in `ds/components.js`.

| Component | Purpose and key specs |
| --- | --- |
| Icon | 16 px line icon; sizes 12, 14, 16, 20. |
| BrandMark | The Sona Ping mark, 16–32 px. Three solid bands clipped to a disc, using the drawing in `design/sona-logo-linear.html`. |
| StatusIcon | Section 6.4. |
| Kbd | 17 px key cap, shown in tooltips, menus, buttons, command menu. |
| Button | 28 px (24 small). Variants: secondary, primary (accent), ghost, danger (red text), danger-solid. Optional icon and trailing kbd. Disabled at 45% opacity. |
| IconButton | 28 px square (24 small); always has a label for tooltip and accessibility. |
| Tooltip | Appears after 450 ms; text plus shortcut. |
| Segmented | 22 px options in a bordered track; for exclusive choices that apply at once. |
| ViewTabs | 24 px tabs with count and an attention dot. |
| Toggle | 28×16 switch. |
| Checkbox | 14 px; prerequisite confirmation. |
| Input, Field | 30 px input with optional leading icon; mono variant; error state. Field adds label and help. |
| IdTag | Muted issue key, phase or operation id, always shown in full. |
| ShortId | Machine ids: workflow ids, invocation ids, commit shas, evidence references. Shows the first 8 characters plus "…" (the whole id when that saves nothing) in faint 10.5 px mono, the full value in a tooltip, then a small copy button labelled "Copy `<what>`" ("Copy workflow id", "Copy invocation id", "Copy commit", "Copy reference"). Evidence references drop their leading `<kind>:` prefix before shortening, since the kind shows below. Used in the details pane (Workflow, Invocation, the inspector's Commit), subtask commits in the Subtasks index, evidence rows and the recovery dialog's Workflow. Activity entries keep the invocation id in their tooltip only. |
| StaleIndicator | "Stale" in 12 px `fg-muted` with the tooltip "Showing stale work data"; in the view header after a refresh failed. |
| Label | 22 px pill: colored dot, tinted (lifecycle), mono (runtime ids). |
| Avatar | 18 px agent initials: Cl, Cx, Cu, Ju. |
| RepoIcon | 16 px rounded square with the repository initial. |
| ProgressRing | 14 px ring with `n/m`; green when complete; blue for plans saved. |
| Clock | Live duration (section 5.3, rule 10). |
| MetricStrip | Equal cells: label, value, note. |
| PropertyList | Details pane properties. 28 px rows. Label column 80 px (`--prop-label-w`), sized to fit the longest label in the pane, 12 px `fg-muted`, one line with an ellipsis as the fallback; phase-name labels (Goal finish steps) in 11.5 px mono. 12 px gap, then the value cell: 24 px high, 6 px side padding pulled 6 px left so text aligns, one line, 12 px sans `fg-2`, tabular digits, never wraps (ellipsis). Mono only inside ShortId. An actionable value highlights `bg-hover` and shows a tooltip. Rows with a null, empty or zero value are left out; nothing draws "0" or a blank. An optional Breakdown sits under the value in the value column, 4 px bottom padding. |
| Breakdown | A total split by key under a property (Runs, Fixes, Relaunches). 22 px rows: key in 11.5 px mono `fg-faint` on the left (ellipsis), count right-aligned in 12 px `fg-muted` tabular, sorted largest first, zero counts dropped. At most 4 rows, then a ghost toggle "+N more" (11.5 px `fg-faint`, 22 px, `bg-hover` on hover) that reads "Show less" when open. Rows rather than chips: chips wrap at 272 px, rows never do. |
| PhasePipeline | 20 px mono steps joined by 6 px lines; done (check), current (accent), waiting (orange), failed (red), goal-wide (solid 1 px `border` outline). |
| ReasonBlock | Durable reason, mono, left rule in the lifecycle color, Copy button. The text is an ExpandableText (12 px mono `fg-2`, line height 1.55). |
| ExpandableText | Long runtime text folded to 3 lines with an ellipsis: block reasons (the subtask inspector's warn Hint, inline too), finding text and meta (FindingRow, compact titles), evidence summaries, the incident's Cause and Next lines, the audit summary, ReasonBlock (run reason, QA report). A small ghost "Show more" button (chevronDown) sits 2 px under the text only when it overflows; open it reads "Show less" (chevronUp). Short text adds no toggle and no gap. The open state resets when the text changes. Desktop `ExpandableText.kt`, one implementation that ReasonBlock uses. |
| FindingRow | Every finding in the app (revised 2026-11-12; PeekFinding and ReceiptFinding are gone): review passes in the details panel and on standalone review runs, goal assessment, Verify's code review receipt, phase cards. The audit's CriterionRow layout: the ref in the number column (it fits the ref), the severity Badge before the text in two lines, the state as the row Badge (Open, Fixed, Accepted, Declined, False positive, Refuted, Not fixed, Fixing) and a chevron. A click opens the body: the location Chip, the lane and confidence, or the verification. Verify passes its checkbox, Chat or Goal switch and shared-code hint through. |
| EvidenceToggle | The incident's "Evidence · N" caption as a button: 11.5 px faint text and a 12 px chevron (right when collapsed, down when open), `fg-muted` on hover. |
| EvidenceRow | One evidence record, stacked: the reference as a ShortId on its own line, the summary below at full width (12 px `fg-2`, line height 1.45), then the kind (11.5 px faint). Rows sit in a Box with dividers, padding 9 px 12 px. |
| Checklist | Checkbox rows for prerequisites. |
| Menu, MenuItem, Popover | 30 px items with icon or lead, check, shortcut; heads and separators; click-outside and Esc close. |
| CommandPalette, CommandList | 640 px modal at 14% from top; context label, input, grouped results, footer hints. |
| Dialog, Ledger | 460 px modal; Ledger is the deleted/kept two-column list in Purge. |
| ShortcutGrid | The keyboard sheet. |
| Toast, ToastStack | 340 px, bottom right, five seconds; × dismisses it early. Also the OS notification look, without ×. |
| Callout, Hint | Callout for incident state and a blocked plan run (its title can take the tone color); Hint for one line of guidance or validation. |
| EmptyState | Icon, title, body, actions. |
| Sidebar, SidebarItem, SidebarSection, WorkspaceSwitcher | Navigation (section 8). |
| ViewHeader, Pager | 44 px header: breadcrumbs, middle slot (filter or tabs), actions; Pager for previous and next. |
| GroupHeader, ListRow | Sticky 36 px group header; 36 px row (32 compact) with id, status, title, summary, meta, time; focus bar on the left. |
| SubtaskList | The Subtasks index: a Box (hairline border, 8 px radius) that takes keyboard focus, role listbox. Focus ring only for keyboard focus (2 px `accent-ring`). |
| SubtaskRow | One index line, min 36 px, 12 px side padding, 10 px gaps: number (20 px), StatusIcon, title (13 px/500, ellipsis; pending rows 400 `fg-muted`), commit ShortId, live subtask clock, "no change" label. The whole line selects it. Selected: `bg-active` plus a 2 px `accent` bar on the left edge, full row height. Current (the running subtask) keeps the `bg-selected` tint while not selected. Hover `bg-hover` when not selected. A row never expands and has no body: a click selects it and opens the details panel. A row reads as selected only while the panel shows it. A 16 px chevronRight ends the line, `fg-faint` on hover and `accent` while selected. Rows do not show the block reason; the panel does. The Goal finish row ends with its running or stopped phase id in 11.5 px mono in that state's color, then "n/7" phases run in 12 px `fg-faint`; its phase tabs live in the panel. |
| SubtaskChip | A dependency edge: 20 px button, 1 px `border`, 4 px radius, 6 px padding, 4 px gap; a 14 px StatusIcon of that subtask, then its number in 11.5 px mono. Optional edges have a dashed border and the word "optional" in 11.5 px `fg-faint`. Hover `bg-hover` and `border-strong`. Click selects that subtask. Tooltip "Subtask 02: `<title>`" (plus "(optional)"). |
| SubtaskInspector | The selected subtask, section 9.2. 10 px between blocks: title 13 px/500 `fg`, line height 1.4, wraps; block reason as a warn Hint folded past 3 lines (ExpandableText); PropertyList with Commit, Spec file, Depends on, Needed by, Validation (no Status row, since the title row shows the state); then Review (6 px above): a 24 px "Review" caption (11.5 px/500 `fg-faint`), one block per pass with a 22 px line "Pass N · verdict · k unresolved" (Pass N in 12 px/500 `fg-2`, the rest `fg-muted`, tabular) and its compact FindingRows, 6 px between passes; then "Audit · verdict" and its summary in 12 px `fg-muted`, wrapping. "No review yet" in 12 px `fg-faint` when there are no passes. It renders only in the details panel. |
| DetailsPanel | The floating details panel (section 8). Header 44 px, 20 px left and 8 px right padding, hairline below: 14 px StatusIcon, the subject in 13 px/500 `fg` ("Subtask 03", "Goal finish", or "Details" for a run without subtasks), the state word in 11.5 px/500 in the state color, then at the end "3 / 7" in 11.5 px `fg-faint`, small Previous and Next subtask buttons (chevronUp, chevronDown; dimmed at either end) and Close (x). Body: one scroll, padding 16 px 20 px 32 px, sections 24 px apart. Left edge: an 8 px resize grip, role separator, that shows a 2 px `accent` line on hover, keyboard focus and while dragging. |
| EventLines | Every list of timed events (revised 2026-11-12; ActivityFeed and PeekTimeline are gone): Activity groups and the run's events, phase card activity under a PeekPart, monitor and validation activity. A 52 px time column (a clock time, or relative: "4m ago"), a 7 px dot in the tone or a 16 px Avatar or StatusIcon on a 1 px `border-subtle` rail, then the line (actor and object `fg`/500, the verb `fg-muted`, paths in 12 px mono). Earlier rows fold behind a ghost "Show N earlier". |
| Chip | Every file, commit and action chip (revised 2026-11-12; FileChip, QaChip and ShaChip are gone): criterion evidence, a finding's location, a test's files, QA evidence and actions, commit shas. 22 px on `bg-raised` with a 1 px `border`, 6 px radius; `mono` (10.5 px) for paths and shas, otherwise 11.5 px sans; an optional leading icon. Without an action it only names something. |
| TreeRow | Tree row with 18 px indent per level. In a PaneSection (Spec files) it is 28 px, pulled 8 px out on both sides with a 6 px radius, no caret column. |
| PaneSection | A details pane group. Title row 24 px, 11.5 px/500 `fg-faint`, 6 px gap, 2 px above the body. With a status it leads with that 14 px StatusIcon and ends with the state's word in the state color, 11.5 px/500 ("Blocked", "Done", "In progress", "Pending", "No change"). Sections stack 24 px apart with no dividers. |
| DocHeader, Section, Box | Detail building blocks. A detail Section header can collapse its body: the title, count and a 12 px faint chevron (chevronDown open, chevronRight collapsed, `fg-muted` on hover) form one button; actions in the end slot stay put. A collapsed section shows only its header, with no 10 px body gap. |
| SidePane | 400 px right pane with its own 44 px header. |
| TerminalPane | Bottom pane: grip, tab strip, actions, output. |
| SettingsHeader, SettingsGroup, SettingRow, TierBadge, ConfigRow | Settings and config. ConfigRow is any value edited through the inline editor (`.sb-cfg-ed`): the model-matrix layout for tiers and phases, or with a label the setting-row layout (label and key on the left, mono value on the right) for review context budget and per-phase compaction overrides. |
| TrayPopover | 360 px popover: brand, stats, tabs, rows, footer. |

---

## 8. Shell and layout

- **Window:** frame color `bg-frame`; sidebar on the left; the content panel inset 8 px with a 12 px radius and a hairline border. The main window opens unmaximized and centered, sized to show the sidebar, the document column and the details pane comfortably: 1400×900 content dp, capped at 90% of the usable screen.
- **Sidebar (236 px):** brand row with the command menu button; Work (count of live items) and History; a collapsible Board section listing Needs you (amber attention badge), Goal runs, Standalone phases, Operations and Finished today, each with its count; Board navigation icons always use the white foreground color, including inactive rows; at the bottom Specs and Terminal (toggles, highlighted when open), Settings and Keyboard shortcuts.
- **Work area:** the view header and body on the left, the optional Specs pane on the right (it never changes what the main panel shows), the optional terminal docked at the bottom of the main column.
- **Details panel** floating over the right of a detail view's document: `bg-panel`, a 1 px `border` on its left edge and `shadow-modal`. It lies over the document without moving it and stops above the terminal and beside the Specs pane, so both stay usable. Closed by default. Selecting a subtask row or Goal finish opens it, as do ⌘I and the header's Toggle details button; Esc, Close and ⌘I close it. It is not modal: the document stays scrollable and clickable, and clicking another row switches the panel's subject. Default width follows the app's `FloatingPane`: the wide variant, 800 px, for a goal and 560 px for other runs, each capped at 70% of the work body. The operator drags the left edge to resize between 272 px and the work body minus 64 px; with the grip focused ← and → step 24 px, and a double-click returns to the default. The app persists the dragged width in its preferences, like the agent chat pane (`FloatingPane.kt`); the prototype holds it until reload. The app slides it in from the right with `menuSpec()`.
- **Settings** replaces the sidebar with its own navigation and a Back to app button.
- **Narrow windows:** the details panel keeps working at every width, capped at the work body minus 64 px. Below 760 px the sidebar becomes a drawer, the Specs pane covers the panel, and the details panel fills the work body with no resize grip. Subtask rows stay one line at every width.

---

## 9. Screens

Copy in quotes is the app's own wording, taken from `desktop-tray` unless marked as new.

### 9.1 Work

- Header: breadcrumb "Work" (or "Work › Needs you" when a Board section is chosen), a filter input ("Filter work"), then Refresh, Specs and Terminal toggles.
- Body: group headers in runtime order: Needs you, Goal runs, Standalone phases, Operations, Finished today. Each row: id (issue key, phase id or operation id), status icon, title, muted summary, then "Stalled" (tinted orange) when stalled, "Pause requested" when pending, step label (mono; "2/5 plans saved" while planning), repository label, progress ring (goals), agent avatar, and elapsed time (finished time for finished items).
- Empty: "No matching work" with Clear filter; per section "No …" / "Nothing finished today".
- Right-click a row: Open, Open terminal here, Copy reason (when there is one), Pause after subtask, Pause, Resume / Run again, Finalize, Cancel, each enabled by the runtime's verdicts.

### 9.2 Detail

Top to bottom:

1. Chips: lifecycle (tinted), "Stalled", id, issue key for phases and operations, kind ("goal", "phase", "operation"), "fresh" or "stale" ("stale" also after a failed refresh).
2. Title: the runtime's title (`featureTitle`, else summary). The titles in `app/data.js` are examples.
3. Repository location: folder (opens the checkout) and branch (opens the branch on its web host; desktop `RepositoryWebLinks.kt`), or "no branch".
4. Run actions: "Pause after subtask" (or "Pause requested", disabled), "Pause" (danger; stops the runner now), and when runtime options allow launch the launch button with the runtime's label ("Resume", "Run again", "Start") plus "Agent: `<id>`" / "Choose agent", "Finalize" when `canFinalize` is true, then "Cancel", then "Recover" when recovery is offered. Completed standalone rows on Needs you keep Run again / Resume and Finalize until the operator finalizes. Failed and already-finalized rows do not show Finalize. Under them, "Starts the installed agent harness" when a launch is possible. When a refresh failed, every control is disabled and a warning hint reads "Refresh the detail before using run controls." The view header then shows the stale indicator ("Stale", tooltip "Showing stale work data") after the pager, and the screen keeps its last data.
5. "Pause recorded. The goal pauses at its next checkpoint." while a pause is pending.
6. Run reason (ReasonBlock, "Copy reason"), folded to 3 lines with "Show more" when it overflows.
7. Incident (when present, collapsible): status ("Needs your action", "Diagnosis ready", "Investigating block", "Investigation queued", "Partial diagnosis", "Diagnosis unavailable", "Recovery available", "Recovering", "Recovery failed", "Resumed"), cause, next action, certainty; prerequisite checkboxes (Recover is enabled once all are confirmed); operator decision notice when a decision is awaited (section 9.2.1); evidence behind an "Evidence · N" toggle, collapsed by default, with EvidenceRow rows; report status with "Open issue" when submitted. When a decision is awaited and there is no incident, an "Operator decision" section holds the notice in this position instead.
8. Metrics: Subtask n/m ("x complete · y pending") or Goal stage ("Planning", "Goal finish"; planning shows no wave note, because the desktop domain model has no planning wave); Phase with execution label ("pass 2", "gate run 2") or "n/m plans saved"; Goal active (or "Total run time … finished `<time>`" when finished; "Phase elapsed" or "Operation elapsed" for those kinds); Subtask active ("ticking" or "last known duration").
9. Subtasks: an index of the goal's subtasks, then a final Goal finish row. Each row shows number, status, title, commit sha (ShortId), the current-subtask timer and "no change"; no block reason and no body. The Goal finish row ends with its running or stopped phase and "n/7" phases run. Rows never expand (section 7, SubtaskRow): clicking a row, Goal finish included, selects it and opens the details panel. When the panel opens without a chosen row (⌘I, a deep link), the selection is the blocked subtask, else the current subtask, else the first. The selection is kept per item while paging (J/K, pager) and while Subtasks is collapsed. With more than 10 subtasks the section starts collapsed; its header count and progress ring still show progress. While planning, the section reads "n/m plans saved" and "Subtasks appear here once their plans are saved." with no "Planning wave N." text and nothing is selectable.
10. Phase pipeline (goals past planning; standalone phases and operations with more than one step). Operations show the Step metric and elapsed time, a pipeline only with more than one step, and no subtask timers.
11. Activity, for a goal, reads the goal's own records and is built only from design system parts (revised 2026-11-12; the static Activity view and `ds/activity.css` are gone). One group per subtask, then Goal finish: a Fold with the subtask's StatusIcon, number and name, a "running" / "N blocks" note, and its start time, phase time and agents at the end. Blocked and running groups start open. Inside, EventLines rows from the subtask's phase ledger (`phaseLog` as recorded, or the same phase cards the details panel shows, with audit attempts and review passes expanded into their runs and fixes): time, a dot in the state color, the phase, its duration or "running" / "blocked", the verdict, the agent Avatar and model, and the summary as ExpandableText. Repeats of one loop (audit and its fixes, review and its fixes) fold into a nested Fold ("audit loop · audit ×4, audit_plan_fix ×1"). Block incidents are rows in `st-blocked` with their reason; a gap over 10 minutes is an "idle 42m" row. Above the groups: a MetricStrip (Phase time with idle time, Blocks, Subtasks with phases and fixes, the top Model with its share), a tall SegmentBar timeline whose segments open their group, and a MiniRule with the run's range; MiniRule day labels and "idle" rules sit between groups. The header has Expand all / Collapse all and an All / Blocks Segmented. The run's own events follow in a "Goal activity" group as EventLines with icons. Standalone phases and operations show only those events.
    The run events feed, as before, replaces the former activity section ("Goal activity", "Phase activity", "Operation activity") ("Goal activity", "Phase activity", "Operation activity") and the History section. Entries come only from recorded facts, oldest first:
    - per execution in `executionHistory`: "Run N started" at `startedAt`, and when `finishedAt` is set "Run N `<lifecycleState>` `<result>`" with a StatusIcon for that state (completed, failed, interrupted and paused, blocked, canceled). With one execution the actor is "Goal run" for a goal, else the phase or operation id. The invocation id is in the tooltip only;
    - with no execution listed, "`<id>` started" from the item's `startedAt` and "`<id>` completed / failed / canceled" from `finishedAt`;
    - the last worktree edit: "`<agent>` edited `<path>` +i −d" at its `recordedAt`;
    - the latest event: "`<phase>` · `<worker role>` `<summary>`" at its `at`;
    - the last agent activity: "`<agent>` `<lastAgentActivityLabel>`" at `lastAgentActivityAt`.
    Times read relative ("12s ago", "4m ago", "3h ago", "2d ago"). Only the latest 8 show; "Show N earlier" reveals the rest for that item.
12. Goal QA lives in the qa phase card of Goal finish in the details pane, with open findings marked "Not fixed" ("Fixing", under Sent to qa_fix, while qa_fix runs). It is absent from the main document.
13. Goal assessment lives in the goal_assessment phase card of Goal finish in the details pane. It shows "Completed · round n" or "Running · round n", findings with severity, ref, path and line, scenario, verification, state. With two or more findings the header's end slot has a Segmented order: Default (the runtime's order), Priority (blocker, critical, high, major, medium, minor, low, nit, info, then anything else; ties by status) and Status (open, refuted, fixed; ties by priority). Sorts are stable, and the choice holds while paging.
14. A standalone QA run shows the QA results block (4.1) right after its header, before the metrics. A standalone plan has its own document (4.1, Plan run) in place of the metrics, pipeline and Activity.
15. Standalone phases and operations only: their own Validation, Phase results and Spec bundle sections when the runtime returns them, as before (bundle order: `decomposition-manifest.yaml`, `spec.md`, then every other file by name with digit runs compared as numbers). For goals these moved to the details pane.

Run app (SONA-10, design addition). Goals whose repository's platform pack declares an app-run recipe for the device platform (Android today) add a split button to the run actions in item 4: "Run app · `<device>`". The main part runs `skill-bill app run --goal <key> --qa-device <serial:|avd:>`: it builds debug (or the module and variant in the repository's `.skill-bill/app-run.yaml`), takes the device lease, installs, opens the app's own launcher activity and releases the lease. The device is the sidebar QA pick; Let QA choose resolves to the first ready device, else boots the first AVD. The chevron opens DevicePanel under itself and changes the same pick. While a build runs the main part reads "Building…" and the chevron becomes Stop. The button is on only for a completed goal and for a goal in monitoring (the `monitor` step, watching CI on its PR). It is off, with the reason as the status line, in every other state ("Run app turns on when the goal completes or reaches monitoring."), while a CI repair (`monitor_fix`) edits the checkout, when adb is missing and when no device exists. One status line closes the actions column: building (step), "Running on `<device>` · opened `<time>`", "Build failed · `<task>` · nothing was installed", "`<device>` is in use by QA run `<key>`. Nothing was installed. Pick another device." or "Build stopped." Its log link selects the "Run app · `<key>`" terminal tab, which streams the command output and is reused by the next run. A goal in monitoring builds in its goal checkout; a completed goal builds in a temporary worktree on its branch. Open `#run-app/<state>` for each state: ready, avd, building, running, failed, contended, stopped and noadb on NEWS-168 (completed), monitoring on NEWS-174, and blocked (NEWS-176) or active (NEWS-171) for the off button.

Every section header from item 7 down can collapse its body (section 7, Section). Collapse state is kept per section while paging between items (J/K, pager); untoggled sections start from their default (Subtasks collapsed past 10 subtasks, Evidence collapsed).

Details panel, top to bottom:

- The header names the selected subtask with its status and steps through the Subtasks index. The selected subtask's inspector comes first in the body. It has the title, block reason, commit, spec file, dependencies, validation, then Phases: the subtask's phase tabs and the selected phase's card (4.1, Phase peek). Review passes move into the review card when it has one; otherwise they and the audit follow.
- Selecting Goal finish replaces that inspector with the Goal finish phase tabs (goal_assessment, qa, qa_fix, validate, pr, monitor, monitor_fix) and the selected phase's card. The goal_assessment and qa cards hold the full results; this replaces the app's Assessment / QA switch in `DetailsPane.kt`. Each goal keeps its phase choice. Missing results say "No assessment result is available for this run." or "No QA result is available for this run." Every goal opens at 380 px until the operator resizes the panel; the app uses 272 px for a subtask today.
- Execution follows the result, then Runs and fixes. The goal-level spec files (spec.md, decomposition-manifest.yaml, traceability.md) open from the preplan card's Goal spec row; subtask specs open from the plan card. The inspector does not repeat results in the main document.

Subtask rows never expand. With the panel closed no row is selected and the index is a plain list; the first click or ↑/↓ opens the panel on that row. Every phase pill that ran is clickable and expands its card under the pill row; a click on the open pill folds it again, and one card is open at a time. Untouched, only a running, blocked or failed phase starts expanded, so finished subtasks open with every card folded and the line "Select a phase to see what it did." Pills carry `aria-expanded`; arrow keys move between them and open the card they land on. An expanded card has the app's `SubtaskPhaseCard.kt` parts: the header with agent, model, round, start time and duration; why it is blocked; Now or Waiting on with the agent's last activity and edit; what it produced; the last activity; and a footer action. What it produced, per phase: preplan, the goal's shared `preplan_digest` as four Folds (the Verify disclosure), each folded to one line: History and decisions ("3 headings selected"; open, each selected heading with its file, opening that file), Reusable patterns ("2 found"; open, each pattern and where it lives), Add-ons (their short names; open, as Labels) and Validation strategy (the first line; open, the full text) and Goal spec (spec.md, decomposition-manifest.yaml, traceability.md; each opens), with "Shared by every subtask in this goal." under them. Plan shows the subtask_plan record: the sub-spec file (opens it, with its criteria count), a one-line summary, then three Folds: Steps ("5 in order"; open, the steps as the audit's CriterionRows: the step number, its text and one Badge for coverage (the criterion ref, "2 criteria", or "Setup" for a step that covers none); a step opens to the criteria it covers), Criteria coverage ("3 of 3 covered"; open, CheckLines naming the steps that cover each criterion, a cross for one no step covers) and Plan record ("order 2 · repaired"; open, KeyValues for sub-spec, manifest order, spec and payload hashes, and the repair the runtime applied when the payload failed its contract). Open spec file stays in the footer. Implement shows files, lines added and removed, and **Open changes**, which opens the Changes window scoped to that subtask; it does not list files. Simplify shows a short description of what it simplified, then the counts. Audit and Review are composed from the parts Verify already uses, with no new components. Audit is laid out like Review: a Label verdict ("Passed · 3 of 3 criteria met", "Failed · 3 of 5 criteria met", "Attempt 3 · Auditing") and the summary as ExpandableText, then one block per attempt, oldest first. Each block has its line ("Attempt 2 · Gaps found · 2 of 3 criteria uncovered · → audit_implement_fix") and a Box of CriterionRows grading every criterion as that attempt saw it: missing first, a Met or Missing Badge, "Fixed in this attempt" as the hint on the attempt that fixed it, and a click opens what covers it or what is missing (and which fix runs next). A running audit ends with "Attempt N · Auditing". Before review starts on a pending subtask it reads "Runs after implementation". Review shows each pass as "Pass N · verdict · 4 findings · 1 fixed · 2 open", its lanes as Labels (amber with a count when the lane raised findings, green when clean), and its findings as the same CriterionRows the audit uses: the ref, the severity Badge before the text, and the state as the row's Badge (Open, Fixed, Accepted, Declined, False positive, Refuted). A click opens the row to the file as a Chip and the lane with its confidence. A running pass reads "Pass N · Reviewing · 2 of 4 lanes done" with its lanes. Build shows the command and result. Validate shows the verdict, then the first six checks with times and commands; "Show N more checks" (LinkButton) opens the whole list in place and "Show fewer" folds it; then the gate details. write_history shows history written, decisions and paths, then both files it writes as rows that open them: `agent/history.md` ("1 entry") and `agent/decisions.md` ("2 decisions", or "unchanged"). commit_push shows the commit and branch, with Open branch. Goal finish uses the existing goal_assessment and QA cards; pr, monitor and monitor_fix get the pull request and CI checks. Review and audit appear only in their phase cards. Nothing sits under the pills: with every card folded the panel ends with "Select a phase to see what it did." (or "No phase has run yet."), and an open card shows that phase alone. The States catalog adds "Subtask audit failed" and "Subtask audit running". Everything the expanded row used to show (title, block reason, commit, spec file, dependencies, validation, phase tabs and cards, review passes, audit, and Goal finish's tabs, cards and results) is in the panel, with every control it had.

#### 9.2.1 Operator decision notice

Shown when the runtime says the goal awaits an operator decision: inside the incident section, or in its own "Operator decision" section when there is no incident. The app does not record the decision. An info hint says the subtask finished with no code changes and is waiting for a decision, to settle in chat or with the command below it, then resume the goal. The command is `skill-bill goal operator-decision <issue-key> --subtask <n> --decision retry_fix|accept_and_advance|abandon_subtask` in mono with a copy button. Retry fix also needs `--instructions`.

### 9.3 History

Header: tabs All, Goals, Phases, Operations and a filter. One group "Finished within `<retention>`". Rows: id, status, title, kind caption, repository, total run time, finished time. Empty: "No finished work" or "No matching work". Rows open the detail.

### 9.4 Specs pane

The design uses the selection bar shown in this prototype, which the desktop app builds as `SpecSelectionBar`.

- Header: "Specs", project picker ("Choose project"), ⋯ panel menu (Collapse all, Reveal specs folder with its path, Import work…), close ("Close specs pane").
- Filter row: tabs All, Active, Pending and Done with counts, then a search button. Search opens "Filter by key, title or file" with a close button; Esc closes it and clears the text.
- Tree: bundles with status, key, title and progress; expanded bundles list spec.md, subtask specs with status, traceability.md and decomposition-manifest.yaml; a `done` folder with its count. A bundle's chevron expands it and a click on the row selects it; a click on a file selects and opens it; a click on a folder toggles it. Canceled bundles read faint without a count.
- Selection bar, docked at the bottom while a bundle or a file in it is selected: key, title, n/m and the favorite star; status and agent pills, ⋯ and one primary action, driven only by the runtime's `SpecBundleControl` verdicts (Start, Resume or Run again; else Pause while running; else none). Notes: "Pause recorded. The goal stops after its current subtask." and the control's unavailable reason.
- One action list for right-click, both ⋯ buttons and ⌘K, in this order: Open spec, Reveal folder, Copy key, favorite; Export work, launch, Pause after subtask or Pause requested, agent; Cancel goal…, Purge run history…, Delete spec files…. Disabled items say why.
- Dialogs: "Cancel `<KEY>`?" (Keep running, Cancel goal), "Purge `<KEY>` run history?" with the Deleted and Kept ledger (Keep run history, Purge `<KEY>`), "Delete `<KEY>` spec files?" (Keep spec files, Delete spec files), "Pause before exporting". The safe action has focus and Enter.
- Refusals: "Couldn't start: <summary>", "Couldn't purge: <summary>", "Couldn't delete spec files: <summary>".
- States: "No specs yet" ("Plan a feature with /skill-bill phase:plan and its bundle shows up here."), "No specs match" with Clear filters, "Specs unavailable" with Retry, "This project has no checkout on this machine."
- Keys while the tree or the bar has focus: ↑ ↓ → ←, J K between specs, Enter (a spec opens its spec.md), R, P, A, F, /, Shift+F10 for the menu, Esc to clear the selection.
- Speed: build the tree from `specBrowserService.snapshot` once per project, refresh it on spec-directory changes (desktop `NioSpecDirectoryChanges`) and on the specs-related signal, and fetch `control` for the selected bundle in the background; selection itself must not wait on it.

### 9.5 Terminal pane

- A bottom pane in the main window, toggled from the sidebar, the header and Ctrl+`.
- One shell per tab on a PTY: pty4j `org.jetbrains.pty4j:pty4j:0.13.10` (Maven Central) with JediTerm `org.jetbrains.jediterm:jediterm-core` and `jediterm-ui` 3.57 (from the JetBrains intellij-dependencies repository, not Maven Central), as in `desktop-tray`.
- A new tab opens in the repository root of the current selection (a work item or a spec's project checkout), or the home directory.
- Tabs are named after their project (`~` for home); a name gets a number only while another tab shows the same name; a custom name (double-click to rename, blank restores the default) shows as typed and is never numbered.
- Close a tab with its ×; closing the last tab closes the pane. Resize by dragging the top edge or with taller and shorter buttons; the height is remembered. A shell that exits closes its tab.
- Shells live as long as their tab and window; a database switch keeps them.
- While the terminal has focus, the app's single-key shortcuts are off (section 10).

### 9.6 Settings, App group

- General: "Database path, blank uses runtime default" with "Save settings" and "Current database: `<path>`"; "The path is only spaces. Clear it to use the default."; Claude account: the Claude config directories found ("Runs started here use it as CLAUDE_CONFIG_DIR.", "No Claude directory found"); history retention 6h, 24h, 7d; Start at login.
- Notifications: A goal is blocked; A goal or phase fails; A goal completes; A pause takes effect; A standalone phase finishes (off by default); QA could not complete. Keys: `blocked`, `failed`, `goal_completed`, `pause_took_effect`, `standalone_phase_finished`, `qa_incomplete`.

### 9.7 Settings, Runtime config

Each row shows its config key and default. Saving shows "Saved to config.json" (new copy).

- **Models:** agent tabs claude, codex, cursor; junie disabled ("junie cannot honor a model or effort directive"). Tiers Reasoning and Implementation. Phase groups with each phase's tier badge (R or I), resolved model and effort, and an "override" tag. Default phase tiers are `DEFAULT_PHASE_TIERS` in `ExecutionMatrixModels.kt`. Editing a row expands it in place: Tier (phases only; applies to every agent), Model (required, passed as typed), Effort (optional), Save, Cancel, Reset to tier. There is no model catalog check.
- **Compaction:** enabled (default on), window tokens (default 400,000), trigger percent (default 70; the trigger must be at least 200,000 tokens), per-phase overrides. Enabled, window and trigger keep inline controls (toggle, input, range); the "Compact at N%" range and its error hint sit side by side in `.sb-sr-ctl`. Each per-phase override row (value "`<window>` tokens · `<pct>`%") expands the same inline editor as the model rows: Context window, Trigger percent, Save, Cancel, Remove override.
- **Project:** project name and store path, "Open config.yaml"; spec source local or linear (default local); Gradle wrapper (repo-relative, no `.` or `..` segments, blank means the repository's `./gradlew`); phase instruction files in `<repo>/.skill-bill/phases/<phase-id>.md` (committed with the repository, 16 KiB cap, unknown phase ids are skipped); review context budget with defaults from `ReviewContextBudgetPolicy` (byte limits must be positive). Spec source and Gradle wrapper keep inline controls. Each budget row expands the inline editor (one field, Save, Cancel); the group header counts changed values ("n changed").
- **Telemetry and agents:** level off, anonymous, full (default anonymous); proxy URL (blank uses the default endpoint); batch size (default 50); detected agents (read-only); files `.config/skill-bill/config.json` and `.skill-bill/install-selection.json` under the user's home directory.
- **Edge states:** an unreadable config file shows the runtime's error with its key path and never overwrites the file; a file changed on disk while a row is open asks to reload or keep editing; YAML comments do not survive a rewrite, so ask once per file before the first save.

### 9.8 Tray popover and notifications

- Popover: brand, stats Running, Needs you, Done 24h; tabs Goals, Phases, Operations with counts and an attention dot; compact rows; "Finished in the last hour" under Phases and Operations; footer Open Sona, Refresh, Settings, Quit. It opens on the last tab used.
- Notifications fire only on lifecycle transitions (the six keys above), respect the settings, and open the exact item when clicked.
- Platform integration (tray icon, macOS status item, notifications, start at login, KDE behavior) is solved in `desktop-tray`; see its ARCHITECTURE.md.

### 9.9 Problem states

"Loading your work", "Couldn't load your work" ("Check the database path in Settings, then try refreshing."), "Sona runtime unavailable" / "Sona runtime not installed." / "The installed Sona runtime is not the build this app was packaged with." with "Install a matched runtime and desktop pair with `<reinstall command>`, then retry.", "Opening your database", "Sona is switching to the selected database.", and the storage failures missing, access, busy, corrupt, incompatible schema, incompatible record (desktop `ProblemCode`, `StorageFailure`). Failed refreshes keep the last data with a stale indication.

---

### 9.10 New task tabs and home requirements

These requirements retain the standalone brief after its mock was consolidated into this prototype. They describe the intended design, not a new audit of the desktop implementation.

#### Tab strip

- Tabs are rounded on the top corners only (`--r-sm`), have no bottom border, and the selected tab uses the panel background and overlaps the strip's bottom hairline by 1dp, so it joins the content below.
- The strip has 4dp padding on top only. Tabs fill the rest of its 36dp height.
- Tab padding is 10dp start, 4dp end. Unselected title color is fg-muted, hover background is bg-hover. The close button is 20dp and shows on hover and on the selected tab.
- The tab row takes only the width its tabs need. "+" sits right after the last tab. When tabs overflow, the row shrinks to the space left, tabs shrink to 112dp minimum, then the row scrolls, and "+" stays right after the visible edge of the row.
- The overflow chevron with the open-chat count is the only control pushed to the far right, and it shows only when tabs overflow.
- Everything else about tabs from SKILL-511 stays: markers, shortcuts, wheel scrolling, edge fades, overflow menu with filter.

#### Home for an empty chat

- Order: hero, step line, Recent chats, Needs you. Same spacing and type as the prototype (content max width 720dp, 32dp between blocks).
- Recent chats: header "Recent chats" with the total count, and a "Search chats /resume" ghost button on the right that opens the resume palette. Below it, one card with every saved chat for the chat's repository.
- Needs you: header "Needs you" with the count, or "All clear" when nothing is blocked. The right side keeps the "N running · N finished today" link. Below it, one card with every blocked run. No card when nothing is blocked.
- Each card shows at most 5 rows (34dp each) and scrolls past that. The edge with more rows behind it fades, the same way the tab strip fades. With 5 or fewer rows the card fits its content with no fade.
- Rows keep the SKILL-512 row look: glyph, title, faint suffix, badge, time column, discard on hover for chats, orange edge and tint for blocked runs.
- A saved chat that is open in a tab shows an "open" badge and no discard button. Clicking it selects its tab. Clicking any other chat loads it into the selected empty chat.
- Up, Down, Enter and Delete work inside a focused card, and the selected row scrolls into view.
- The All, Needs you and Chats filter control and the merged list are removed.
- First-run examples stay as they are today.

#### Composer on the home

- Review my branch, Run the checks, QA my branch and Open a PR chips sit above the input, as today.

#### Out of scope

- Side list layout changes beyond the current prototype.
- Settings, the resume palette, run-detail chats.
- Transcript replay and other SKILL-511 behavior.

### 9.11 QA device implementation requirements

These requirements retain the standalone device-picker brief. The picker remains a design addition awaiting implementation.

Show connected Android debug devices and AVDs in a dropdown at the bottom of the desktop app sidebar, and let the operator pick the device QA runs use.

#### In scope

- A switcher row as the last item in the sidebar footer (`ShellSidebar.kt`), below Keyboard shortcuts, styled like `WorkspaceSwitcher`. Closed, it shows the current pick and a status dot: "Let QA choose · N ready", or the device name with transport and Android version. The dot is orange when any device is unauthorized or offline.
- The dropdown opens upward and lists:
  - "Let QA choose" first. This is the default and keeps today's behavior.
  - Connected: phones and running emulators from `adb devices -l`. Each row shows state (ready, unauthorized, offline, in use by `<run key>` when a QA device lease holds it), USB or Wi-Fi, and Android version.
  - Emulators: AVDs from `emulator -list-avds` that are not running, marked "Not running · QA boots it".
- Unauthorized, offline, and leased devices can't be picked. Unauthorized shows "Unlock the phone and accept the USB debugging prompt." inline.
- Empty states: no devices attached, no AVDs, and adb not found (with how to fix it).
- Discovery runs when the dropdown opens and on the refresh button or R. No background polling.
- Find adb and the emulator binary the same way the KMP QA pack does: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`.
- The pick persists across app restarts.
- QA runs started from the app use the picked device as the named target. This covers standalone QA and the QA phase of goal runs. "Let QA choose" passes nothing and QA behaves as today.
- Runtime channel for the pick. The QA intake is not usable for this: the `qa` skeleton sets `intakeIsRequirements = true`, so `PhaseRunIntakeResolver` turns intake text into the run's only acceptance criterion, and `goal-qa` takes no operator text at all. Add a separate QA device field (serial or AVD name) to the QA run request and the goal QA phase, apart from the intake. Render it in the QA prompt next to the device lease lines in `QaPromptSections.kt` as the named target, so the KMP pack's existing named-serial and named-AVD rules apply (use it, skip with a reason if unusable, never substitute). Requirements and acceptance criteria stay unchanged.
- The "QA my branch" chip reads "QA my branch on <device>" when a device is picked.
- Keyboard: arrow keys move through pickable rows, Enter picks, Esc closes.

#### Out of scope

- iOS devices and simulators (follow-up).
- Booting or stopping emulators from the UI.
- Logcat, screenshots, or other device actions.
- Force-releasing a QA lease.
- Showing raw serials in QA reports. The packs' sha256 rule stays as is.

#### Done when

- The switcher row and dropdown match the prototype in every device scenario.
- Picking a device and clicking "QA my branch" starts a QA run that uses that device, and the run's acceptance criteria are the same as without a pick.
- A goal run's QA phase uses the picked device.
- A picked device that is unplugged or leased by the time QA starts settles `skipped` with the reason, without falling back to another device.
- "Let QA choose" starts QA exactly as it does today.
- Tests cover the QA device field reaching the QA prompt without changing acceptance criteria.
- Unit tests cover parsing `adb devices -l` and `emulator -list-avds` output, the state classification, and the pickable rules.

### 9.12 Recorded activity

Open `index.html`, choose a goal on Work and scroll to Activity. The grouped Activity design renders inside that detail using the selected goal's sample subtasks, phase records, blocks and recent updates. The States catalog keeps the original historical run, one-subtask, running, 33-subtask and standalone-phase variants for comparison.

The view preserves the recorded Activity design. Subtasks group their phases and attempts, finished groups start closed, and running or blocked groups start open. Repeated audit, review, validation, monitor and QA fixes fold into loop rows. Block incidents retain their recorded reason. The All and Blocks filters, Expand all, phase summaries and timeline segments are interactive. Entries run oldest first, with day labels, clock times and relative-time tooltips. Gaps over ten minutes say idle without assigning a cause.

In contextual goal details, phase time, blocks, subtask progress and known models come from the selected sample records. Clock-only records keep their displayed clock labels; the timeline compares known durations without inferring dates or idle intervals. The historical examples retain their recorded dates and idle intervals. Missing data stays missing. Goal finish has no invented start time or duration. The original historical run and every synthetic variant remain in `app/activity.js`.

### 9.13 Changes window and Diff comments

Open a goal from Work and click Open changes in its Changes section. The detail header has no Open changes button. The detail overview has a change-volume column for every subtask, the running subtask and Open changes. The window supports a searchable subtask picker, All, Running, Outside subtasks and Uncommitted scopes when available, Shift-click and drag for ranges, previous and next subtask, file filtering and a diff with the subtasks that touched the file. A range compares its first start commit with its last end commit. Shortcuts remain S, brackets, A, J, K and slash. The Work board's Changes window with subtask scopes goal retains the 33-subtask sample and its recorded patches in `app/data.js`. `app/flow.js` adapts the selected work item or chat to the same window. The IDE status goal has an illustrative patch; other sample files without patches show an explicit unavailable message.

Comments appear on the selected file inside the Changes window. The States catalog also keeps four standalone Diff comments states that cover writing a review, an added review, agent review and review fixes. Hover a line and use + or C to draft a comment. Pending comments stay in one scope-wide review while switching files. Add review publishes them together. Threads support replies, editing, deleting with confirmation, resolve, decline and reopen. The review and fix examples retain severity, finding references, outcomes and fix commits. J and K select files, N and P navigate comments, and Command or Ctrl with Enter submits the current composer or review.

Revised 2026-11-12: the window and Diff comments are one screen module, `app/review.js`, built from design system components; the static Changes and Diff comments views and their stylesheets are gone. Parts: WindowSheet (the window, modal over the app or inline on the catalog pages), ChangesFrame (toolbar, strip, scope line, file list and diff pane, review bar, keyboard footer), ChangeStrip (one column per subtask, height by lines changed; click, Shift-click and drag), Button with a picker Popover of MenuItems and an Input filter for the scope, Segmented quick scopes, ChatChangeRow file rows under GroupHeader modules with a comment count and "×N" subtasks, SubtaskChip for "Changed by subtasks", DiffView (hunks, lines with their + button, thread slots), CommentThread (Avatar, Badge severity, IdTag finding ref, Label state and outcome, IconButton edit and delete, MarkRow for the delete confirmation and the pending note), InlineComposer for comments, replies and edits, SelectionBar for the pending review, and KeyHints for the shortcuts. The Diff comments sample (SKILL-474) and its four scenarios live in `DATA.REVIEW_SAMPLE`; #changes and #comments/1–4 show the window inline.

The Changes window stays over the selected run or chat. Closing it returns focus to its opening control. Tab stays inside the window and Escape closes the top layer. Goal details, Verify and chat changes use their own sample context. Pending reviews and threads belong to the selected work item, scope and file. The prototype does not persist reviews to disk, invoke an agent or edit repository files. In-memory snapshots preserve view state across navigation until the page reloads.

## 10. Interaction model

Interaction patterns come from Linear; they only trigger the actions above.

| Keys | Action |
| --- | --- |
| ⌘K / Ctrl+K | Command menu |
| ? | Keyboard shortcuts |
| G then W / H / S / , | Work / History / toggle Specs pane / Settings |
| Ctrl+` | Toggle terminal |
| Ctrl+Shift+` | New terminal tab |
| / | Focus the filter |
| J / K or arrows | Next / previous row; in detail, next / previous item |
| Enter | Open the focused row |
| ↑ / ↓ (Subtasks list focused) | Move the subtask selection and open the details panel on it; with the panel closed the first press opens it on the current row. J / K still move between work items |
| ← / → (panel resize grip focused) | Widen or narrow the details panel by 24 px |
| Enter (Subtasks list focused) | Open the selected subtask's spec file (nothing for Goal finish) |
| P | Pause after subtask |
| Shift+P | Pause now |
| R | Resume / Run again |
| ⌘I / Ctrl+I | Toggle the details panel |
| [ | Toggle the sidebar drawer |
| Esc | Close the top layer (menu, dialog, command menu), cancel an open config edit, close the details panel (focus returns to the Subtasks list), leave detail or settings |
| ⌘Enter | Save an open config edit |

- Single-key shortcuts are off while a text field or the terminal has focus.
- Command menu groups: the current item's actions (Open, Pause after subtask, Pause now, Resume, Cancel, Copy reason, Open terminal in `<repo>`), Navigation (Work, each Board section, History, Settings), Panes (Specs, Terminal, New terminal tab, Refresh), every work item by key and title, Config (Models, Compaction, Keyboard shortcuts). Arrow keys move, Enter runs, Esc closes.
- Opening a popover menu moves keyboard focus to its first item. In the command menu, hovering a row moves the highlight to it.
- Destructive actions confirm only where the app already does: Purge (dialog), Specs Cancel (inline), Recover (preview dialog). Pause and Cancel in the detail act immediately, as today.
- Feedback appears as a toast for five seconds (desktop: the window feedback popup) and never claims more than the runtime reported.

## 11. Copy reference for control feedback

| Situation | Text |
| --- | --- |
| Pause after subtask recorded | "Pause recorded. The goal pauses at its next checkpoint." |
| Stop with confirmed exit | "Stop confirmed. The goal runner has exited." |
| Stop without confirmed exit | "Stop signal sent. The goal runner's exit was not confirmed; refresh and check it before retrying." |
| Stop with no process | "Request accepted. No running goal process needed stopping." |
| Spec pause | "Pause recorded. The goal stops after its current subtask." |
| Recovery applied | "Recovery request finished. Incident details were refreshed." |
| Recovery preview stale | "The incident changed. Refresh it and review a new recovery preview." |
| Controls blocked by a failed read | "Refresh the detail before using run controls." |
| Settings saved | "Saved" |

The complete set is in `desktop-tray/feature/detail/.../DetailViewModel.kt` (`ControlConfirmation` mapping), `RunActionButtons.kt`, `IncidentPanel.kt` and `feature/specs/.../SpecsContract.kt`.

---

## 12. Performance budgets and measurement

Targets for the new app, measured on a copy of a large real database (the one measured in 5.2 is a good benchmark) and on a synthetic store with 5,000 terminal records (as in `desktop-tray`'s repository tests):

| Interaction | Target (p95) |
| --- | --- |
| Select a goal or spec: header painted | Same frame (under 16 ms) |
| Select a goal: first detail sections loaded | Under 150 ms |
| Commit signal to board updated | Under 100 ms |
| Filter keystroke to results | Under 50 ms |
| Cold start to first board | Under 2.5 s, including the runtime graph |
| An active goal writing probes and heartbeats | No visible stutter; app CPU near idle between signals |

Measurement plan:

1. Profile the current app with JFR (section 5.2) to confirm where the 5 seconds go.
2. Add timing around each read in the new data layer, behind a diagnostics flag, and log signal-to-render latency.
3. A benchmark harness that replays a burst of commits against a copy of a real database and measures the targets above. Keep it out of the unit test suite: AGENTS.md forbids timing-based tests.

## 13. Suggested plan

Plan with `/skill-bill phase:plan` on the target machine, using this document as the intake. Keep subtasks independent: each applies its rule to whatever is present in the tree, with no ordering between subtasks or bundles. Acceptance criteria go in a list; tests go under test obligations, never in acceptance criteria.

Two bundles fit the work: one for the runtime (R1–R5) and one for the app. Suggested workstreams:

**Runtime**

- R1 Session read API. Acceptance: one connection serves many reads; compatibility verified once per open; reopen on generation or file-identity change; read transactions stay short. Test obligations: repeated reads open one connection; a replaced database file is detected; a writer can checkpoint while the session is open.
- R2 Fine-grained signals. Acceptance: activity writes (worktree probes, activity stamps, heartbeats) bump only the activity topic; board topics change only with displayed board facts; the signal carries changed ids with an overflow marker; `DesktopReadSchema` bumped. Test obligations: a probe insert leaves board revisions unchanged; a lifecycle change bumps the board topic and lists the id.
- R3 Board read model. Acceptance: one row per work item with the board and header facts, written in the source transaction; board read is one indexed query; deadlines stored. Test obligations: each lifecycle transition updates the row in the same transaction; rows match the existing projection for a recorded store.
- R4 WAL and journal hygiene. Acceptance: checkpoint after goal completion and when idle; journal bounded to what readers need. Test obligations: the journal stays bounded after repeated probes; reads that need the latest edit still return it.
- R5 Config write service. Acceptance: writes the keys in 5.4 with existing validation, unknown keys kept, atomic replace, refusal on concurrent change; exposed on `RuntimeComponent`. Test obligations: an invalid value is refused with its key path; a file changed after read is not overwritten; unknown keys survive a write.

**App**

- Bootstrap and runtime bridge (5.1), signal watcher and read session use (5.3), repositories with shared flows.
- Design system: tokens (6) and components (7).
- Shell and navigation (8), command menu and keyboard (10).
- Work (9.1), Detail (9.2, including operator decision), History (9.3).
- Specs pane (9.4), Terminal pane (9.5).
- Settings (9.6), Runtime config (9.7).
- Tray and notifications (9.8), problem states (9.9), packaging and the runtime-free build check.
- Performance harness (12).

## 14. Open questions

- Which capabilities from 4.2 belong in the first release (preflight before Start and the findings ledger are the strongest candidates).
- Whether R2 and R3 are in the first release or follow R1.
- Packaging and distribution of the new app beside or instead of `desktop-tray`, and how `install.sh` installs it as the matched desktop.
- Light theme: not planned.

## 15. Source references

| Topic | Path |
| --- | --- |
| Runtime graph | `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeComponent.kt` |
| Board and detail models | `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/idestatus/model/` |
| Controls | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/control/` |
| Spec browser | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/specbrowser/` |
| Operator decision | `runtime-kotlin/runtime-goalrunner/src/main/kotlin/skillbill/engine/goalrunner/GoalOperatorDecisionService.kt` |
| Commit signal | `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/persistence/DesktopCommitSignal.kt`, `DesktopReadSchema.kt`; `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/DesktopCommitSignalPublisher.kt`, `core/schema/DesktopChangeRevisions.kt` |
| Read sessions | `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/SQLiteDatabaseSessionFactory.kt`, `DatabaseRuntime.kt` |
| Config models | `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/config/model/` |
| Config reads | `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/config/ConfigResolutionService.kt` |
| Telemetry config | `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/telemetry/TelemetryConfigRules.kt`, `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/telemetry/transport/TelemetryConfigStore.kt` |
| Phase instruction files | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/phase/prompt/compose/PhaseProjectInstructions.kt` |
