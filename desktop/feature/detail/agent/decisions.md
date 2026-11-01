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

