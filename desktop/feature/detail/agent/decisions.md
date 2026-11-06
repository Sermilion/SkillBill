## [2026-11-06] Preserve the selected Verify QA report for Unblock
Context: Verify QA must show the selected saved report's Unblock instructions, including skipped runs without parsed QA details.
Decision: Carry raw report text through the existing Verify projection and desktop models. Retain QA_REPORT selection with QA_RECEIPT compatibility and parse Unblock only in the desktop view.
Reason: The operator's scope amendment requires that saved report and unchanged snippet text. A final verdict, reconstructed findings or a repository file cannot supply that report's instructions.

## [2026-11-06] Keep QA setup instructions operator-owned
Context: Unblock snippets target `.skill-bill/phases/qa.md`, which the runtime reads into later QA prompts.
Decision: Copy snippets verbatim and open the repository file through `openFileUnder` in the default application. Report opening failure without creating or editing the file.
Reason: The requested workflow has the operator create the file and paste chosen setup instructions before the next run. Opening must target the repository copy and bypass editor environment variables.

## [2026-11-06] Show Verify QA evidence as names only
Context: Verify QA results reuse the desktop QA results view, but Verify has no invocation directory for resolving evidence files.
Decision: Render evidence names without file-opening actions for Verify QA.
Reason: The existing evidence-opening path needs an invocation directory to constrain the target.

## [2026-11-06] Preserve the goal intake draft when routing Verify fixes
Context: Verify findings can be routed to a new goal while the operator has an unsent draft.
Decision: Prefill goal intake only when its text is blank. Otherwise retain the draft and report the refusal.
Reason: Replacing a nonblank draft would discard the operator's work.

## [2026-11-06] Start a separate Verify run for re-verification
Context: A completed, stopped or superseded Verify run records an intake and a verified target branch.
Decision: Verify again submits a new Verify request with the intake as target, the recorded target as branch, and the current database session id.
Reason: Re-verification must address the same branch while preserving the original run and respecting session changes.

## [2026-11-05] Derive QA results from recorded fields
Context: The full report buried open problems and untested work, and reports without tags cannot establish check counts.
Decision: Derive the displayed verdict and sentence from recorded fields and counts. Untagged passes remain grey and show no check counts.
Reason: Agent prose can contain detail after a reason token and cannot reliably determine the displayed verdict.

## [2026-11-05] Keep live standalone QA results at the finding slot
Context: Test-plan finding links target results rows while standalone QA is running.
Decision: Show live results after activity when findings or report text exist. Move settled results above the status strip.
Reason: Hiding live results would leave finding links without a scroll target.

## [2026-11-05] Scope QA results fold state to each run
Context: QA rows and folds are independent lazy items, and operators can page between runs.
Decision: Store collapse state above the lazy list under keys scoped to the goal recording or standalone invocation.
Reason: State survives scrolling without carrying open folds to a different run.

## [2026-11-05] Count only unrestored edits as left behind
Context: QA lists temporary edits in a disposable checkout that the runtime deletes.
Decision: Carry every listed edit, but count only edits tagged `[not restored]` as left behind.
Reason: Counting every temporary edit would flag forced-fault runs and name files removed with the checkout.

## [2026-11-05] Show an attempted QA retest as running
Context: A running goal retest can retain an attempted outcome from an earlier result.
Decision: Display Running while the item runs and its outcome is null or attempted.
Reason: Saying QA could not fix a problem would be premature during the retest.

## [2026-11-02] Separate displayed QA runs from live controls
Context: An operator can inspect an older run while a newer QA invocation is active.
Decision: Show the displayed run's commit, criteria, findings and report. A run displays live state only when unfinished and matching the control invocation; status, freshness and Cancel remain tied to the work item and live run.
Reason: Paging must show the selected run's evidence without making an old step appear active or redirecting cancellation away from the active execution.

## [2026-11-02] Guard evidence paths before opening
Context: Evidence paths originate in agent text stored with a QA run and are passed to the desktop's default application opener.
Decision: Require the path to start with the invocation directory and a slash, and reject parent-directory segments. Refusals record `desktop_qa_file_open_refused` and open nothing.
Reason: A sibling path or parent traversal must not pass the directory check. The string guard does not resolve symlinks.

## [2026-11-02] Index lazy detail rows for finding navigation
Context: A finding link can target a row in a collapsed section, where the row has no lazy list index until expansion.
Decision: Rebuild a key-to-index map as detail sections are declared, expand the findings section, and wait up to five frames for the row before scrolling.
Reason: Collapsing sections changes later row positions, and expansion requires recomposition before the target index exists.
## [2026-11-01] Exempt skipped subtasks from results loading
Context: Skipped subtasks have no phase-results entry, while a missing entry for a started subtask can mean its page is loading.
Decision: Omit the empty Review section for skipped subtasks and show loading for started subtasks with no result. Degraded goal status takes precedence for non-pending subtasks.
Reason: Treating every non-pending subtask with no entry as loading would leave skipped subtasks loading indefinitely.

## [2026-11-01] Read running review progress from stored dispositions
Context: The inspector needs review progress, but the review path writes lane dispositions in a batch after lanes return.
Decision: Project progress from stored dispositions and show a lane read failure as degraded status.
Reason: This preserves stored progress on relaunch without adding live writes to review execution. A first attempt can show zero completed lanes until the batch is stored.

## [2026-11-03] Show the settled audit during a running review
Context: Audit runs before review, but the inspector hid a settled audit during review and said "Runs after review", contradicting the phase chips. This reverses the 2026-11-01 decision that kept that supplied design copy.
Decision: Show the stored audit result whenever one exists. The placeholders follow runtime order: Review reads "Runs after audit" and Audit reads "Runs after implementation". Omit the Audit section when review has started and no audit record exists.
Reason: The stored result is true during review and the old copy was not. A placeholder after review starts would describe a step already past.

## [2026-11-01] Omit settled audit summary while an audit is running
Context: A running audit can retain an output artifact from an earlier settled run.
Decision: The subtask audit section omits that summary while the audit is running.
Reason: Showing the stored text under the active audit could present a stale pass as the current result.

## [2026-11-01] Scope selected subtask runs to phase records
Context: The goal-level ledger summary does not consistently scope counts to the selected subtask.
Decision: Show selected-subtask runs from phase attempt counts and omit fixes, relaunches, findings, and kills. Retain goal-level totals for Goal finish and standalone runs.
Reason: Reusing goal-level counts could attribute another subtask's activity to the selected subtask, and this feature adds no ledger read.

## [2026-11-01] Render the recorded workflow timeline in Activity
Context: The approved Activity mockup groups attempts by subtask and keeps Goal finish separate. The old detail feed could not show child phase attempts or blocks outside its retained incident page.
Decision: Observe the runtime timeline for goals. Render one card per recorded child attempt, pending subtask cards, and untimed Goal finish facts. Standalone executions remain one row per run. Keep phase time separate from idle gaps. Show the agent when an attempt has no recorded compatible model, and omit token figures.
Reason: Phase ledger starts and ends establish durations. Goal finish records establish assessment results and the latest running-step update, without supplying step durations. Blocks and long idle gaps split folded loops. The Blocks filter opens its matching cards; users can still collapse them.

## [2026-11-01] List every recorded block in Activity
Context: Activity showed run starts and ends, so a run that blocked and resumed looked like it had gone smoothly. The detail read already carried every block in `incident` and `incidentHistory`, and only the live incident was shown.
Decision: Activity adds a "Blocked" line per incident at its `observedAt`, with its summary, and the cause and resume state in the tooltip.
Reason: These are recorded facts with their own times, so no new read is needed. The runtime keeps the latest 10 past incidents for a run's detail read, so an older block drops off the feed. Resume has no recorded time, so it has no line of its own.

Superseded by: Render the recorded workflow timeline in Activity, 2026-11-01.

## [2026-11-01] Show the step pipeline on each subtask row
Context: A goal showed one "Phase pipeline" section apart from its subtasks, so the reader had to work out which subtask it described. Goal finish already shows its steps on its own row.
Decision: Every subtask row carries the step pipeline. The current subtask uses its live position, a completed subtask shows every step done, and any other shows them pending. The separate section stays only for standalone phases and operations, which have no subtask rows.
Reason: The runtime records the current step for the run, not a step history per subtask, so a blocked or interrupted subtask that is not current cannot show where it stopped.
Revisit when: The runtime exposes per-subtask step state.

## [2026-11-01] The detail screen no longer hosts a diff
Context: SKILL-454 showed changed files and a unified diff inside the detail screen. SKILL-455 moves file lists and diffs into the Changes window.
Decision: The detail Changes section shows only the subtask strip or a change summary plus Open changes. DetailDiff, DiffView and their actions are deleted.
Reason: One diff surface avoids keeping two file lists and two diff readers in sync. Path-and-status row matching now applies in the Changes window.

## [2026-10-31] Match changed-file rows by path and status
Context: git can list one path twice, once as a deleted index entry and once as an untracked file still on disk.
Decision: Change actions carry the ChangedFile, and DetailViewModel and DetailDiff resolve rows by path and status. Changes row keys include the status.
Reason: Matching by path alone could diff or open the deleted entry when the operator picked the untracked one, and a path-only LazyColumn key would repeat.
Superseded by: The detail screen no longer hosts a diff (2026-11-01)

## [2026-10-31] Close the diff view through ShellEdit
Context: The shell owns Esc, and the diff view must close on Esc while J/K keep paging items.
Decision: DiffView composes ShellEdit, whose cancel dispatches CloseDiff, only while the diff is shown.
Reason: The shell cancels the top edit before it pops the detail route, so Esc closes the diff first. ShellLayer would also catch Esc but blocks J/K and Ctrl+I.
Superseded by: The detail screen no longer hosts a diff (2026-11-01)

## [2026-10-31] Use combinedClickable on Changes rows
Context: TerminalPaneChrome avoids combinedClickable because it holds a single click back for the double-click timeout.
Decision: Changes rows use combinedClickable: a single click selects the diff and a double click opens the file.
Reason: A single click replaces the document with the diff view, so without the wait the second click of a double click would land on the diff view instead of the row.
Superseded by: The detail screen no longer hosts a diff (2026-11-01)

## [2026-10-30] Use the goal pause reason for operator decisions
Context: A goal can await a decision without an incident, while recovery eligibility describes a different condition.
Decision: Show controls from awaitsOperatorDecision, in the incident section when present and separately otherwise.
Reason: Recovery eligibility can forbid retry without indicating that an operator decision is waiting.

## [2026-10-30] Read branch web links from git config
Context: Opening a branch page needs the origin URL, but the desktop process allowlist forbids launching git.
Decision: Resolve the git directory and origin from configuration and open the resulting URL with the desktop browser API.
Reason: This keeps branch navigation within the process allowlist. URL insteadOf rewrites are not supported.
