## [2026-11-08] SKILL-527 phase cards in details pane
Areas: desktop/feature/detail, desktop/core/designsystem, desktop/core/domain, desktop/core/data; runtime phase-card and QA projections
- MEDIUM feature. Subtask and Goal finish inspectors share phase tabs and selected cards in the 380px pane and expanded inline rows. Goal finish replaces the Assessment / QA switch with seven phase tabs.
- PhaseTabs and PhasePeek parts are reusable. Recorded skipped tabs remain selectable; missing cards stay inert. Arrow keys wrap, and Home and End select the first and last available tabs.
- Window-owned phase choices survive paging per goal and subtask, including Goal finish, and clear on database-session changes. Stale choices resolve against available cards.
- Review passes appear inside the review card once, with the separate Review block retained only when no review card exists. Audit results remain below Phases.
- Goal cards show assessment, retained QA, validation, PR and monitor facts. QA includes actual QA and qa_fix rounds, Live, retained frames, screenshot inventory and folded stages.
- Runtime and data projections supply facts and ordering. Phase activity shows the latest five supplied events; monitor activity includes the current cycle and earlier outcomes.
- Footer links use existing spec, branch and GitHub targets. Unsupported review, validation-run and Changes destinations remain omitted.
- Limits: unrecoverable earlier QA evidence stays unavailable; headless runs may have no frame. Paired desktop and runtime must agree on read schema 119.
- QA limitation: validate changed code after QA settled, so the final tree differs from the tree QA tested. This entry claims implementation coverage only.
Feature flag: N/A
Acceptance criteria: 4/4 implemented across the parent feature

## [2026-11-07] SKILL-518 match plan detail design
Areas: desktop/feature/detail, desktop/core/designsystem
- MEDIUM feature. Standalone plans follow the mock's stage strip, merged Now block or blocked callout, Activity, Spec bundle, Transcript and Briefing order. Plan-local headers and the Blocked badge leave other run types unchanged.
- PlanText provides reusable pure stage, output, path and Activity-summary derivations. Fan-out uses units, waves and activity when its stage record is pending; status copy uses recorded saved/total counts.
- Activity has counted filters, Follow, sticky reached-stage headers, folded reads, expandable notes and retained file-opening targets. Stage clicks pause Follow and jump to the group; quiet or blocked runs omit the working tail.
- Spec bundle combines parent files and subtask specs with wave labels and live writing, quiet, blocked and saved states. Parent paths resolve from the report, write activity or the first unit spec's directory.
- Transcript replaces the output tail and Activity button with a newest-pinned three-line preview and expandable browsing. It prefers readable lines, falls back to raw lines, copies their original text and loads older pages only while expanded.
- PlanBriefing is a reusable commonMain parser for saved setup, the digest handed to plan and per-stage UTF-8 sizes. Briefing opens as a teaser, retains exact raw text and falls back to raw rows when setup parsing fails.
- Run-keyed PlanScreenState owns feed, filters, folds and navigation above the lazy list. Activity briefing links expand and scroll to the matching raw row through a screen-owned, replaceable reveal job.
- SbSpinner gains reusable duration, track-alpha and arc-sweep options with existing defaults preserved; the design system adds a plug icon. Live clocks use the UI ticker, and animations respect reduced motion.
- Runtime contracts and event sources are unchanged. Older runs cannot gain historical tool events; digest headings remain heuristic, and paths outside known roots remain absolute.
- Not-started stage cells remain unclickable because the app has no generic toast action. The Now block uses the recorded agent id because no per-stage model name is available.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-06] SKILL-510 desktop QA Unblock card
Areas: desktop/feature/detail; desktop/core/domain; desktop/core/data; runtime-engine work/verify; runtime-ports idestatus models; design/qa-results
- MEDIUM feature. Goal, standalone and Verify QA results show capability remedies, attempted setup and evidence after untested work and before result folds. Entries appear for incomplete or skipped runs, or recorded untested work.
- The reusable desktop Unblock parser respects Markdown fences, preserves snippet bodies for copying and shortens displayed tried/remedy paths.
- Copy for qa.md copies the snippet verbatim. Open qa.md uses the repository-contained default-app opener for `.skill-bill/phases/qa.md`; failure shows a toast, and the app never creates or edits the file.
- Verify carries the selected saved QA report through runtime ports and desktop models, including skipped runs without parsed QA details. QA_REPORT selection and QA_RECEIPT compatibility remain unchanged.
- Verify evidence stays names-only while both qa.md actions remain available. Unblock parsing stays in the desktop view, with no report persistence changes.
- The static QA results mock includes display and app_startup_gate remedies and both actions. The operator must create a missing qa.md file before opening it.
Feature flag: N/A
Acceptance criteria: 4/4 subtask criteria implemented; 5/5 parent criteria implemented across runtime and desktop

## [2026-11-06] SKILL-508 validation page with live checks
Areas: desktop/feature/detail, desktop/core/domain, desktop/core/data, runtime-engine, runtime-ports, runtime-infra
- Standalone validation detail shows an outcome card, foldable report parts, flagged links and runs on the same branch while retaining activity and run controls.
- Report shaping and invocation-scoped fold state support expanding and scrolling to flagged parts. reusable
- Available live checks show progress, outcomes and expandable attempts for the current invocation. Unavailable and older runs retain the report-only layout.
- Peer runs require matching repository and non-null branch correlation; detached HEAD and missing correlation yield no peers. Other phase layouts and the CLI detail schema remain unchanged.
Feature flag: N/A
Acceptance criteria: 5/5 implemented across runtime and desktop

## [2026-11-06] SKILL-506 Verify run detail and actions
Areas: desktop/feature/detail, desktop/feature/changes, desktop/feature/chat, desktop/core/domain, desktop/core/data, desktop/core/datastore, desktop/core/ui
- A Verify document displays run state, outcome, criteria filters, expandable receipts and inline QA. It opens a superseding run and the recorded revision range.
- Fixable findings and criteria route to a combined chat brief or goal intake. Marks persist per workflow and criterion; marked criteria are excluded from selection.
- Verify again starts a separate request for the original intake and verified branch. Verify remains report-only and an existing nonblank goal draft is preserved.
- The reusable QA results view displays Verify evidence as names without file-opening actions because Verify has no QA invocation directory.
- Parked runs keep existing controls; adjusting intake and discarding are out of scope. QA and validation reported capability_absent.
Feature flag: N/A
Acceptance criteria: 5/5 implemented across runtime and desktop

## [2026-11-05] SKILL-504 QA results view
Areas: desktop/feature/detail, desktop/core/domain, desktop/core/data; runtime QA results projection
- Goal and standalone QA share a results section with an app-derived verdict, open findings, retained resources, untested work, folds and target coverage. Live standalone results retain the finding navigation target.
- Reusable `qaResultsView` projects recorded details into rows and verdicts. Evidence chips use file names, report text shortens supported paths, and fold state is scoped to the run.
- Untagged and legacy reports show no check counts. Settled standalone result text remains available in Run details when no report exists.
- Evidence chips require an invocation directory. Path shortening covers the supported roots and stops at whitespace; checklist-only findings may lack observed, expected and steps fields.
Feature flag: N/A
Acceptance criteria: 5/5 implemented across runtime and desktop

## [2026-11-02] SKILL-457 QA live checklist in desktop detail
Areas: desktop/feature/detail, desktop/core/domain, desktop/core/data, desktop/core/designsystem, runtime-engine, runtime-ports
- Standalone QA detail shows recorded targets, plan, step progress, findings, activity and report, with a pager for earlier runs. Runs without a plan show no invented cases or steps.
- Reusable projections derive run selection, progress, case state, activity and merged findings. Case overrides and selected runs survive live reads; ended runs do not display running steps.
- Finding links expand and scroll to their rows. Report and evidence actions use the default application, with evidence paths guarded by invocation directory and parent-segment checks.
- ActivityFeed, removed by SKILL-455, returns in the design system for QA run events and supports newest-first folding. The design system also adds reduced-motion-aware spinner and pulse primitives. reusable
- Limits: evidence containment is a string check and does not resolve symlinks. Settled QA runs with result text but no report no longer show the standalone Result section in QA document order.
- Goal QA warning: the gate reported capability_absent and that repository contents changed after final quality settled, so the tree QA tested is not the final tree.
Feature flag: N/A
Acceptance criteria: 5/5 implemented across runtime and desktop
## [2026-11-01] SKILL-456 subtask inspector review and audit sections
Areas: desktop/feature/detail, desktop/core/data, desktop/core/domain, desktop/core/designsystem, runtime-domain, runtime-engine, runtime-goalrunner
- The selected subtask inspector shows review passes, findings, running lane dispositions, audit criteria and attempts, and execution and run counts scoped to its workflow.
- Read-only status projections load durable results and report the actual degraded source. Page merges retain the latest result per subtask; missing goal status shows an unreadable state.
- Audit attempt projection derives settled attempts from repair state. The dashed label border supports unfinished review lanes. reusable
- Running review progress comes from persisted dispositions, so a first attempt can show zero completed lanes until the batch is stored. Live lane progress is outside this change.
- Panels omit empty execution rows and sections. Liveness appears only for the current subtask. Goal finish and standalone runs keep goal-level values.
- Live goal reviews run pass 1 only. The projection accepts later pass numbers from stored records, and this change does not alter review execution.
- A non-current started subtask with a blank workflow id can remain loading; a raw review decode failure can prevent pass recovery. Audit attempt copy can name audit_plan_fix before a focused re-audit.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-11-01] SKILL-455 recorded Activity timeline
Areas: desktop/feature/detail, desktop/core/domain, desktop/core/data
- Replaced ActivityFeed with subtask attempt cards, day labels, a phase-time summary, and a timeline that compresses idle gaps.
- Cards preserve expansion choices. Blocks opens matching cards. Loop rows and long text expand independently, and timeline segments scroll to their card.
- Goals observe a session-owned runtime timeline while running and read once when stopped. Standalone execution rows use recorded run times and results.
- Goal finish shows untimed assessment facts and the latest update separately from phase totals. Card metadata lists agents. Model labels appear only where recorded for the attempt.
- Removed the unused ActivityFeed component and replaced its block-line regression test with timeline coverage.
- Idle totals use phase spans. Block notices alone never imply idle time. Interaction coverage checks Blocks filtering, card collapse, and timeline navigation.

## [2026-10-31] SKILL-454 desktop changes panel and diff view
Areas: desktop/feature/detail, desktop/core/designsystem
- Detail shows a Changes section after the pipeline section, fed by WorkChangesRepository. DetailViewModel keys observation on the loaded detail's change baseline and the header's running flag, so a baseline or running change re-observes and an idle item stops polling. Only the open item's worktree is read.
- Re-observing the same baseline keeps the shown list instead of showing Loading again. Every ChangesState (loading, empty, no subtask, base unavailable, not a repository, read problem) renders text inside the section without hiding the rest of the detail.
- A single click shows a read-only unified diff in place of the document column; a double click opens the file with the system opener and shows a problem toast on failure. Deleted rows get no double-click handler, and the ViewModel ignores an open for them.
- DetailDiff, shaped like DetailControls, owns the open diff: one read at a time, and a result applies only to the file and base SHA it was read for. A refreshed list re-reads a diff whose row or base SHA changed and keeps the old lines until the new read lands. A file that leaves the list shows "This file no longer has changes".
- Change actions carry the ChangedFile, and rows match by path and status because git can list one path as both deleted and untracked. Row keys include the status.
- DiffView renders hunk separators and lines with old and new number gutters in a keyed LazyColumn, uses only theme tokens, and closes on Esc through ShellEdit or the back button. J/K keep paging items, and the next item opens with no diff.
- SkillBillColors gains diffAddedBg, diffRemovedBg and diffHunkBg for later diff surfaces and palettes. reusable
- Limits: no syntax highlighting, editing, staging or external editor setting; long paths end-ellipsize.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-10-30] SKILL-447 desktop detail
Areas: desktop/feature/detail, desktop/core/ui, desktop/app
- Detail renders its initial header from the route, observes runtime sections, pages each list by its runtime cursor and retains stale sections after refresh failure.
- Standalone run detail shows saved phase launch briefings in pipeline order when available.
- Detail section collapse state persists while paging between items, and goal finish links navigate to section headers.
- Controls use typed outcomes and reservations; incident recovery checks prerequisites and preview identity. Operator decisions also appear for goals without incidents.
- RepositoryWebPages is reusable and reads git configuration without launching git. URL insteadOf rewrites are not resolved.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
