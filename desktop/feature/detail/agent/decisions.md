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

## [2026-10-31] Match changed-file rows by path and status
Context: git can list one path twice, once as a deleted index entry and once as an untracked file still on disk.
Decision: Change actions carry the ChangedFile, and DetailViewModel and DetailDiff resolve rows by path and status. Changes row keys include the status.
Reason: Matching by path alone could diff or open the deleted entry when the operator picked the untracked one, and a path-only LazyColumn key would repeat.

## [2026-10-31] Close the diff view through ShellEdit
Context: The shell owns Esc, and the diff view must close on Esc while J/K keep paging items.
Decision: DiffView composes ShellEdit, whose cancel dispatches CloseDiff, only while the diff is shown.
Reason: The shell cancels the top edit before it pops the detail route, so Esc closes the diff first. ShellLayer would also catch Esc but blocks J/K and Ctrl+I.

## [2026-10-31] Use combinedClickable on Changes rows
Context: TerminalPaneChrome avoids combinedClickable because it holds a single click back for the double-click timeout.
Decision: Changes rows use combinedClickable: a single click selects the diff and a double click opens the file.
Reason: A single click replaces the document with the diff view, so without the wait the second click of a double click would land on the diff view instead of the row.

## [2026-10-30] Use the goal pause reason for operator decisions
Context: A goal can await a decision without an incident, while recovery eligibility describes a different condition.
Decision: Show controls from awaitsOperatorDecision, in the incident section when present and separately otherwise.
Reason: Recovery eligibility can forbid retry without indicating that an operator decision is waiting.

## [2026-10-30] Read branch web links from git config
Context: Opening a branch page needs the origin URL, but the desktop process allowlist forbids launching git.
Decision: Resolve the git directory and origin from configuration and open the resulting URL with the desktop browser API.
Reason: This keeps branch navigation within the process allowlist. URL insteadOf rewrites are not supported.

