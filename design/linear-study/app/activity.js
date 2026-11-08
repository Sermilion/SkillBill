(function () {
  'use strict';
  window.DesignSurfaces.register("activity", "<section>\n  <div class=\"caption\">Recorded activity</div>\n  <div class=\"states\" id=\"states\" role=\"group\" aria-label=\"Sample state\"></div>\n</section>\n<div class=\"win\"><div id=\"root\"></div></div>\n<p class=\"note\" id=\"note\"></p>\n<p class=\"note\"><b>Rules.</b> Oldest first, with a day label when the date changes. Clock times are the default; hover one for how long ago it was. Blocked and running subtasks start open, finished ones closed. Repeats of a loop, such as audit and its fixes, fold into one row. A gap longer than 10 minutes between phases shows as idle, because the record says nothing ran, not why. Click a block or a summary to read all of it. Click a timeline segment to jump to its subtask.</p>\n<p class=\"note\"><b>Only recorded values appear here.</b> Left out because the runtime does not record them: token usage reported by the model, cost, and the model and effort of any attempt but a phase's latest, which shows its agent instead. Goal finish steps have no start or finish times, so that card shows no time or duration and is not part of phase time, and its only steps are the assessment result and the latest update of the running step.</p>", function (document, host, cleanup, setTimeout, saved) {
const D = {"finish": {"rounds": 5, "exit": "converged", "major": 2, "minor": 2, "latest": {"step": "monitor_fix", "op": "The 'monitor_fix' child is active and producing output; source progress is not confirmed.", "at": "2026-11-01T10:27:37.701Z", "agent": "codex", "model": "gpt-6-luna", "effort": "high", "alive": true}}, "goal": "SKILL-455 · Changes window with subtask scopes", "groups": [{"n": 1, "name": "Runtime change scope reads", "status": "complete", "s": "2026-10-31T20:34:51.666Z", "e": "2026-10-31T21:12:59.602Z", "reason": null, "ph": [{"p": "implement", "n": 1, "s": "2026-10-31T20:34:56.331Z", "e": "2026-10-31T20:40:09.479Z", "a": "claude", "m": "claude-opus-5-5", "f": "low", "t": "Added committed, uncommitted and outside-commit git reads plus the WorkChangeScopesService on RuntimeComponent, with tests; nothing compiled or run.", "v": null}, {"p": "simplify", "n": 1, "s": "2026-10-31T20:40:11.357Z", "e": "2026-10-31T20:43:23.718Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Three local simplifications in the git/changes adapter files; engine, ports, DI and tests left unchanged.", "v": null}, {"p": "audit", "n": 4, "s": "2026-10-31T20:47:41.655Z", "e": "2026-10-31T20:48:43.002Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "All five interaction scenarios trace through production code to their stated outcomes; none broken.", "v": "satisfied"}, {"p": "review", "n": 1, "s": "2026-10-31T20:48:47.844Z", "e": "2026-10-31T20:52:29.853Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Mode: requested `auto`, resolved to `inline` (first review pass of a subtask). Reviewed `eac9679a..2b3d78ce` in one pass, 13 files. Lanes applied: bill-kotlin-code-review-api-contracts, architecture, performance, persistence, platform-correctness, reliability, testing, ui, ux-accessibility and code-quality. `design/App/Changes Window.html` is untracked and not in the commit, so the lanes routed to it had nothing to…", "v": "approved"}, {"p": "verify_findings", "n": 1, "s": "2026-10-31T20:52:30.660Z", "e": "2026-10-31T20:53:22.919Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "All four review findings (F-001..F-004) verified as real; each fix is already present in HEAD 476a9568.", "v": null}, {"p": "validate", "n": 1, "s": "2026-10-31T20:53:24.016Z", "e": "2026-10-31T21:11:18.806Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Every CI-required check runnable on this Linux host passes after repairs: three detekt findings, a spillover-filename violation, a WorkChangeScopesService defect, and the desktop FakePort compile break.", "v": null}, {"p": "write_history", "n": 1, "s": "2026-10-31T21:11:19.635Z", "e": "2026-10-31T21:12:41.454Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Drafted a SKILL-455 subtask 1 history entry and one decision entry for runtime-kotlin/runtime-infra/workflow; no files written.", "v": null}, {"p": "commit_push", "n": 1, "s": "2026-10-31T21:12:42.223Z", "e": "2026-10-31T21:12:52.684Z", "a": "claude", "m": null, "f": null, "t": null, "v": null}]}, {"n": 2, "name": "Desktop change scope data", "status": "blocked", "s": "2026-10-31T21:13:51.642Z", "e": "2026-10-31T21:13:51.642Z", "reason": "Goal-subtask planning import conflicts with the stored shared preplan or subtask plan. This occurs when a shared preplan was regenerated after the child was hydrated, making the previously-imported planning bytes stale. Recover this subtask's child without discarding sibling planning or completed commits: 'skill-bill goal replan SKILL-455 --subtask 2'. Planning failure: existing child planning import conflicts with request: stored import provenance differs from the hydration request at decomposition_manifest_hash Recover with: 'skill-bill goal reset SKILL-455 --hard --yes' (prior subtask commit 14e7a2734da049a4f456cf24b2d1dbea7e05eea4 is the review base).", "ph": []}, {"n": 2, "name": "Desktop change scope data", "status": "complete", "s": "2026-10-31T21:18:38.359Z", "e": "2026-10-31T21:52:28.858Z", "reason": null, "ph": [{"p": "implement", "n": 1, "s": "2026-10-31T21:18:42.536Z", "e": "2026-10-31T21:26:54.070Z", "a": "codex", "m": "gpt-6-luna", "f": "high", "t": "Implemented the desktop change scope models, runtime data repository, shared git coordination and coverage, without running tests or builds.", "v": null}, {"p": "simplify", "n": 1, "s": "2026-10-31T21:26:54.734Z", "e": "2026-10-31T21:27:18.894Z", "a": "codex", "m": "gpt-6.1-terra", "f": "high", "t": "Reviewed the scoped implementation and retained it without simplification edits.", "v": null}, {"p": "audit_plan_fix", "n": 1, "s": "2026-10-31T21:28:20.477Z", "e": "2026-10-31T21:29:10.666Z", "a": "codex", "m": null, "f": null, "t": "Planned AC-002 repairs for worktree-specific diff bases and diagnostics on internal patch failures.", "v": null}, {"p": "audit_implement_fix", "n": 1, "s": "2026-10-31T21:29:11.959Z", "e": "2026-10-31T21:30:37.067Z", "a": "codex", "m": null, "f": null, "t": "Repaired AC-002 worktree base isolation and patch failure diagnostics; AC-003 and AC-004 remain for their selected repair phases.", "v": null}, {"p": "audit", "n": 3, "s": "2026-10-31T21:30:37.397Z", "e": "2026-10-31T21:31:03.238Z", "a": "codex", "m": null, "f": null, "t": "Focused AC-002 production audit found no remaining material source gaps.", "v": "satisfied"}, {"p": "audit_plan_fix", "n": 2, "s": "2026-10-31T21:31:04.209Z", "e": "2026-10-31T21:31:52.427Z", "a": "codex", "m": null, "f": null, "t": "Planned AC-003 repairs for Outside memo retention, interrupted working stats, and concurrent committed cache misses.", "v": null}, {"p": "audit_implement_fix", "n": 2, "s": "2026-10-31T21:31:52.768Z", "e": "2026-10-31T21:34:19.359Z", "a": "codex", "m": null, "f": null, "t": "Completed all three saved AC-003 production repairs and closed the mutable interrupted-stats sibling path; tests and build checks were not run.", "v": null}, {"p": "audit", "n": 4, "s": "2026-10-31T21:34:19.999Z", "e": "2026-10-31T21:34:39.358Z", "a": "codex", "m": null, "f": null, "t": "The focused AC-003 production audit found no remaining material gaps.", "v": "satisfied"}, {"p": "audit_plan_fix", "n": 3, "s": "2026-10-31T21:34:40.782Z", "e": "2026-10-31T21:35:18.249Z", "a": "codex", "m": null, "f": null, "t": "Planned AC-004 repairs for complete attribution evidence, renamed files, and per-line hunk exclusivity.", "v": null}, {"p": "audit_implement_fix", "n": 3, "s": "2026-10-31T21:35:18.943Z", "e": "2026-10-31T21:39:12.768Z", "a": "codex", "m": null, "f": null, "t": "Completed all three planned AC-004 production repairs and source closure; tests and build validation remain with their owning phases.", "v": null}, {"p": "audit", "n": 5, "s": "2026-10-31T21:39:13.077Z", "e": "2026-10-31T21:39:39.968Z", "a": "codex", "m": null, "f": null, "t": "AC-004 remains open because selectable subtasks in goals without range support receive no stats.", "v": "advance"}, {"p": "audit_plan_fix", "n": 4, "s": "2026-10-31T21:39:41.375Z", "e": "2026-10-31T21:40:24.370Z", "a": "codex", "m": "gpt-6.1-terra", "f": "high", "t": "Planned range-independent per-subtask stats reads and cache seeding for AC-004.", "v": null}, {"p": "audit_implement_fix", "n": 4, "s": "2026-10-31T21:40:24.693Z", "e": "2026-10-31T21:42:19.884Z", "a": "codex", "m": "gpt-6-luna", "f": "high", "t": "Removed range-availability gates from individual subtask stats and unified selection, cache seeding, and read behavior.", "v": null}, {"p": "audit", "n": 6, "s": "2026-10-31T21:42:20.223Z", "e": "2026-10-31T21:42:52.087Z", "a": "codex", "m": null, "f": null, "t": "Focused AC-004 production audit completed with no remaining material gaps.", "v": "satisfied"}, {"p": "audit", "n": 7, "s": "2026-10-31T21:42:52.391Z", "e": "2026-10-31T21:43:30.500Z", "a": "codex", "m": "gpt-6.1-terra", "f": "high", "t": "The five interaction traces and three accepted criteria hold in the current production tree.", "v": "satisfied"}, {"p": "review", "n": 1, "s": "2026-10-31T21:43:35.381Z", "e": "2026-10-31T21:44:35.808Z", "a": "codex", "m": "gpt-6.1-terra", "f": "high", "t": "Reviewed `615d3daaffcd31a73ebd4f756db03f4e243fd83a` against its specified first parent at inline depth. Repaired the missing `ScopeSubtask` import, the session constructor call, and stale Outside results when `HEAD` changes. Added a regression test for the Outside refresh.\n\nAuthoring evidence: inspected the desktop module build files, convention plugin, version catalog, Gradle wrapper, and desktop CI workflow. They…", "v": "approved"}, {"p": "verify_findings", "n": 1, "s": "2026-10-31T21:44:37.025Z", "e": "2026-10-31T21:44:54.341Z", "a": "codex", "m": "gpt-6.1-terra", "f": "high", "t": "The preceding review reported no remaining findings.", "v": null}, {"p": "validate", "n": 1, "s": "2026-10-31T21:44:55.360Z", "e": "2026-10-31T21:51:45.471Z", "a": "codex", "m": "gpt-6.1-terra", "f": "high", "t": "Validated the repaired desktop scope code and all runnable project gates.", "v": null}, {"p": "write_history", "n": 1, "s": "2026-10-31T21:51:46.110Z", "e": "2026-10-31T21:52:13.909Z", "a": "codex", "m": "gpt-6.1-terra", "f": "high", "t": "Prepared the desktop boundary history draft and decision supersession guidance.", "v": null}, {"p": "commit_push", "n": 1, "s": "2026-10-31T21:52:14.602Z", "e": "2026-10-31T21:52:20.928Z", "a": "codex", "m": null, "f": null, "t": null, "v": null}]}, {"n": 3, "name": "Changes window with file list and diff", "status": "complete", "s": "2026-10-31T21:52:30.292Z", "e": "2026-11-01T07:07:13.369Z", "reason": "Phase output reported status 'blocked'. AC-003 and IS-004 lack the input needed for the first-open failure case. IS-004 opens the window directly on a committed subtask whose end commit is missing and requires the operator to click another file-specific chip after the scope read fails. ChangeScopeObservations.observeScope returns BaseUnavailable without a ChangeSet; ChangesWindowViewModel starts with selectedPath null, and subtasksTouching requires a selected path. ChangesWindowEntry carries only the item and scope. Neither the window nor its callers supply a previously selected file, and the failed scope cannot provide one. The criterion separately requires 'Select a file' when nothing is selected. Stats for other subtasks do not establish which file was selected or belonged to the failed scope; inventing a selection from them would not satisfy the requirement. The missing historical…", "ph": [{"p": "implement", "n": 1, "s": "2026-10-31T21:52:35.091Z", "e": "2026-10-31T21:55:46.934Z", "a": "codex", "m": "gpt-6-luna", "f": "high", "t": "Implemented the SKILL-455 Changes window, scoped file list and diff, and detail entry point.", "v": null}, {"p": "simplify", "n": 1, "s": "2026-10-31T21:55:47.527Z", "e": "2026-10-31T21:56:25.295Z", "a": "codex", "m": "gpt-6.1-terra", "f": "high", "t": "Simplified the scope-read flow and recorded a likely missing import for the next phase.", "v": null}, {"p": "audit_plan_fix", "n": 1, "s": "2026-10-31T21:57:44.404Z", "e": "2026-10-31T21:58:26.287Z", "a": "codex", "m": null, "f": null, "t": "Planned AC-001 repairs for detail-owned observation, baseline fallback, and coherent scope transitions.", "v": null}, {"p": "audit_implement_fix", "n": 1, "s": "2026-10-31T21:58:27.109Z", "e": "2026-10-31T22:00:38.733Z", "a": "codex", "m": null, "f": null, "t": "Repaired AC-001 detail, goal-observer, scope-read, and diff-read transitions; AC-002 and AC-003 retain separately owned production findings.", "v": null}, {"p": "audit", "n": 3, "s": "2026-10-31T22:00:39.018Z", "e": "2026-10-31T22:01:01.146Z", "a": "codex", "m": null, "f": null, "t": "AC-001 production requirements are satisfied in the current source.", "v": "satisfied"}, {"p": "audit_plan_fix", "n": 2, "s": "2026-10-31T22:01:02.184Z", "e": "2026-10-31T22:01:48.276Z", "a": "codex", "m": null, "f": null, "t": "Planned AC-002 list projection and badge refresh repairs against the current source.", "v": null}, {"p": "audit_implement_fix", "n": 2, "s": "2026-10-31T22:01:48.576Z", "e": "2026-10-31T22:03:12.467Z", "a": "codex", "m": null, "f": null, "t": "Completed the AC-002 list, selection, failure, and stats projection repairs; no tests or builds were run.", "v": null}, {"p": "audit", "n": 4, "s": "2026-10-31T22:03:12.765Z", "e": "2026-10-31T22:03:36.147Z", "a": "codex", "m": null, "f": null, "t": "Focused AC-002 production audit found no remaining material gaps.", "v": "satisfied"}, {"p": "audit_plan_fix", "n": 3, "s": "2026-10-31T22:03:37.095Z", "e": "2026-10-31T22:04:14.730Z", "a": "codex", "m": null, "f": null, "t": "Planned AC-003 repairs for live chips, file header, and the detail renderer import.", "v": null}, {"p": "audit_implement_fix", "n": 3, "s": "2026-10-31T22:04:15.044Z", "e": "2026-10-31T22:06:13.727Z", "a": "codex", "m": null, "f": null, "t": "Closed the planned AC-003 production gaps and preserved the existing shared renderer behavior.", "v": null}, {"p": "audit", "n": 5, "s": "2026-10-31T22:06:14.020Z", "e": "2026-10-31T22:06:37.524Z", "a": "codex", "m": null, "f": null, "t": "The focused AC-003 production audit found no remaining material gaps.", "v": "satisfied"}, {"p": "audit", "n": 6, "s": "2026-10-31T22:06:37.807Z", "e": "2026-10-31T22:07:15.580Z", "a": "codex", "m": null, "f": null, "t": "The audit found that an unavailable base removes the subtask chips needed to change scope.", "v": "advance"}, {"p": "audit_plan_fix", "n": 5, "s": "2026-11-01T06:31:17.134Z", "e": "2026-11-01T06:32:57.424Z", "a": "claude", "m": null, "f": null, "t": "Plan keeps the selected file, its diff header and its subtask chips when a scope's list read ends in a non-Listed state, so chip navigation keeps working and the file stays selected.", "v": null}, {"p": "audit_implement_fix", "n": 4, "s": "2026-11-01T06:32:57.746Z", "e": "2026-11-01T06:33:34.151Z", "a": "claude", "m": null, "f": null, "t": "AC-003 IS-004 repaired: selection, diff and chips survive a non-Listed scope result.", "v": null}, {"p": "audit", "n": 7, "s": "2026-11-01T06:33:34.461Z", "e": "2026-11-01T06:35:35.505Z", "a": "claude", "m": null, "f": null, "t": "AC-003 targeted audit: the IS-004 refresh trace is repaired, but the shared renderer DiffLines.kt imports a weight function that does not exist, so it will not compile.", "v": "advance"}, {"p": "audit_plan_fix", "n": 6, "s": "2026-11-01T06:35:36.523Z", "e": "2026-11-01T06:36:53.412Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Plan removes the two unresolved `androidx.compose.foundation.layout.weight` imports in DiffLines.kt and ChangesScreen.kt, the only members of AC-003's remaining defect class.", "v": null}, {"p": "audit_implement_fix", "n": 5, "s": "2026-11-01T06:36:53.822Z", "e": "2026-11-01T06:37:19.994Z", "a": "claude", "m": "claude-opus-5-5", "f": "low", "t": "Removed the unresolved `import androidx.compose.foundation.layout.weight` from DiffLines.kt and ChangesScreen.kt to close AC-003.", "v": null}, {"p": "audit", "n": 8, "s": "2026-11-01T06:37:20.379Z", "e": "2026-11-01T06:38:06.512Z", "a": "claude", "m": null, "f": null, "t": "Focused AC-003 audit: the unresolved weight import gap is repaired and every AC-003 production requirement holds in source.", "v": "satisfied"}, {"p": "audit", "n": 9, "s": "2026-11-01T06:38:06.901Z", "e": "2026-11-01T06:39:40.690Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "IS-004 holds; AC-001 and AC-002 re-checks hold at HEAD be0c3a08 plus the two uncommitted import deletions.", "v": "satisfied"}, {"p": "review", "n": 1, "s": "2026-11-01T06:39:45.194Z", "e": "2026-11-01T06:45:28.972Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "I reviewed commit `1eed8a5f92b9` against `a83b614d4eae` in one inline pass and fixed what I found. No Blocker or Major findings remain. Nothing was compiled or tested; validate owns that.\n\n**Authoring evidence.** Requested mode: none given, so the inline depth applied. Lanes: bill-kmp-code-review-architecture, -persistence, -platform-correctness, -ui, -ux-accessibility, -code-quality and…", "v": "approved"}, {"p": "verify_findings", "n": 1, "s": "2026-11-01T06:45:29.770Z", "e": "2026-11-01T06:46:26.366Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "The review's six findings were real defects, but every fix is already in HEAD 86328bf or the worktree, so nothing remains to repair: no_findings_verified.", "v": null}, {"p": "validate", "n": 1, "s": "2026-11-01T06:46:27.134Z", "e": "2026-11-01T07:05:06.209Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Every required project check passes after fixing one compile error and two test bugs, and all six test probes killed their mutations.", "v": null}, {"p": "write_history", "n": 1, "s": "2026-11-01T07:05:07.028Z", "e": "2026-11-01T07:07:07.703Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Drafted a desktop/feature/changes history entry and one decision for the SKILL-455 Changes window; nothing written to files.", "v": null}, {"p": "commit_push", "n": 1, "s": "2026-11-01T07:07:08.532Z", "e": "2026-11-01T07:07:13.369Z", "a": "claude", "m": null, "f": null, "t": null, "v": null}]}, {"n": 4, "name": "Scope selection, keyboard and detail strip", "status": "complete", "s": "2026-11-01T07:07:23.699Z", "e": "2026-11-01T07:50:45.120Z", "reason": null, "ph": [{"p": "implement", "n": 1, "s": "2026-11-01T07:07:27.180Z", "e": "2026-11-01T07:16:28.076Z", "a": "claude", "m": "claude-opus-5-5", "f": "low", "t": "Implemented SKILL-455 subtask 4: shared SubtaskStrip, window scope picker/toolbar/scope bar/keys/Open button, and the detail strip replacing the in-detail diff.", "v": null}, {"p": "simplify", "n": 1, "s": "2026-11-01T07:16:28.681Z", "e": "2026-11-01T07:17:44.928Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Made two behavior-preserving simplifications in feature/changes (removed a one-child Box wrapper, dropped a redundant QuickButtonUi lookup) and declined the rest, giving a reason for each.", "v": null}, {"p": "audit_plan_fix", "n": 1, "s": "2026-11-01T07:24:42.148Z", "e": "2026-11-01T07:25:36.562Z", "a": "claude", "m": null, "f": null, "t": "AC-001 repair plan: switch DONE and INTERRUPTED strip bars from fgFaint to the fgMuted token, a one-line edit at SubtaskStrip.kt:123 with no other sibling paths.", "v": null}, {"p": "audit_implement_fix", "n": 1, "s": "2026-11-01T07:25:37.208Z", "e": "2026-11-01T07:25:57.876Z", "a": "claude", "m": null, "f": null, "t": "Changed the DONE and INTERRUPTED strip bars in SubtaskStrip to use the muted token (fgMuted), closing the AC-001 gap.", "v": null}, {"p": "audit", "n": 3, "s": "2026-11-01T07:25:58.146Z", "e": "2026-11-01T07:26:33.668Z", "a": "claude", "m": null, "f": null, "t": "Focused audit of AC-001: the bar color repair is in place and every production requirement of the criterion is met.", "v": "satisfied"}, {"p": "audit_plan_fix", "n": 2, "s": "2026-11-01T07:26:34.437Z", "e": "2026-11-01T07:27:52.722Z", "a": "claude", "m": null, "f": null, "t": "AC-002 repair plan: one item. The picker row detail should depend on whether the subtask is selectable, not on whether stats exist yet.", "v": null}, {"p": "audit_implement_fix", "n": 2, "s": "2026-11-01T07:27:52.977Z", "e": "2026-11-01T07:28:23.600Z", "a": "claude", "m": null, "f": null, "t": "AC-002 picker row detail now says \"not started\" only for non-selectable rows.", "v": null}, {"p": "audit", "n": 4, "s": "2026-11-01T07:28:23.842Z", "e": "2026-11-01T07:29:21.709Z", "a": "claude", "m": null, "f": null, "t": "AC-002 (scope picker) production behavior is met after the repair; the row-detail gap is fixed.", "v": "satisfied"}, {"p": "audit_plan_fix", "n": 3, "s": "2026-11-01T07:29:22.551Z", "e": "2026-11-01T07:30:49.686Z", "a": "claude", "m": null, "f": null, "t": "AC-004 repair plan: count an open picker as text focus in the window key handler in ChangesScreen.kt, so the picker search gets every key except Esc.", "v": null}, {"p": "audit_implement_fix", "n": 3, "s": "2026-11-01T07:30:50.893Z", "e": "2026-11-01T07:31:18.206Z", "a": "claude", "m": null, "f": null, "t": "AC-004 repaired: the window key handler treats an open picker as text focus.", "v": null}, {"p": "audit", "n": 5, "s": "2026-11-01T07:31:19.323Z", "e": "2026-11-01T07:32:06.076Z", "a": "claude", "m": null, "f": null, "t": "Focused AC-004 audit: the repair treats an open picker as text focus, which closes the earlier gap, and every other production requirement in AC-004 is met.", "v": "satisfied"}, {"p": "audit_plan_fix", "n": 4, "s": "2026-11-01T07:32:06.918Z", "e": "2026-11-01T07:33:13.436Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Two-item repair plan for AC-005: the detail strip ignores Shift-clicks, and when it has no onDrag it picks on release only for a click that never became a drag.", "v": null}, {"p": "audit_implement_fix", "n": 4, "s": "2026-11-01T07:33:13.711Z", "e": "2026-11-01T07:33:52.315Z", "a": "claude", "m": "claude-opus-5-5", "f": "low", "t": "AC-005 repaired: in the detail strip, Shift-click and drag no longer open the Changes window.", "v": null}, {"p": "audit", "n": 6, "s": "2026-11-01T07:33:52.565Z", "e": "2026-11-01T07:34:30.805Z", "a": "claude", "m": null, "f": null, "t": "AC-005 focused audit: the current source meets every production requirement, and the Shift-click and drag gaps are fixed.", "v": "satisfied"}, {"p": "audit", "n": 7, "s": "2026-11-01T07:34:31.062Z", "e": "2026-11-01T07:36:20.356Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "All five interaction scenarios hold and the AC-001, AC-003 and AC-004 re-checks hold against the current tree.", "v": "satisfied"}, {"p": "review", "n": 1, "s": "2026-11-01T07:36:23.315Z", "e": "2026-11-01T07:40:31.419Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "I didn't format or lint anything in this phase, because the desktop build has no tool that can be run safely on just these files. Here is what I checked:\n\n- **Config sources:** `AGENTS.md`, `desktop/build.gradle.kts`, `desktop/build-logic/convention` (`QualityConventionPlugin.kt` only writes architecture manifests), `desktop/gradle/libs.versions.toml` (Compose 1.12.1, with no ktlint, detekt or spotless), and the…", "v": "approved"}, {"p": "verify_findings", "n": 1, "s": "2026-11-01T07:40:31.941Z", "e": "2026-11-01T07:41:16.993Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "All three review findings (F-001, F-002, F-003) were already fixed in the reviewed head, so all are rejected and no_findings_verified is reported.", "v": null}, {"p": "validate", "n": 1, "s": "2026-11-01T07:41:17.498Z", "e": "2026-11-01T07:47:19.125Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "All required desktop, runtime and agent-config checks pass after one test fixture repair; six mutation probes all killed.", "v": null}, {"p": "write_history", "n": 1, "s": "2026-11-01T07:47:19.851Z", "e": "2026-11-01T07:50:31.814Z", "a": "claude", "m": "claude-opus-5-5", "f": "high", "t": "Drafted desktop boundary history and four decisions for SKILL-455 subtask 4; no files written.", "v": null}, {"p": "commit_push", "n": 1, "s": "2026-11-01T07:50:32.809Z", "e": "2026-11-01T07:50:37.680Z", "a": "claude", "m": null, "f": null, "t": null, "v": null}]}], "stops": [{"at": "2026-10-31T21:13:24.528Z", "why": "AWAITING_OPERATOR_DECISION", "st": "blocked"}, {"at": "2026-10-31T21:13:52.326Z", "why": "BLOCKED", "st": "blocked"}, {"at": "2026-10-31T21:15:57.736Z", "why": "BLOCKED", "st": "blocked"}, {"at": "2026-10-31T22:08:19.355Z", "why": "BLOCKED", "st": "blocked"}, {"at": "2026-11-01T08:38:33.431Z", "why": "PULL_REQUEST_FAILED", "st": "blocked"}, {"at": "2026-11-01T08:53:33.774Z", "why": "superseded_by_new_run", "st": "abandoned"}, {"at": "2026-11-01T09:04:33.414Z", "why": "PULL_REQUEST_FAILED", "st": "blocked"}], "runs": [{"s": "2026-10-31T20:34:50.135Z", "e": "2026-10-31T21:13:24.528Z", "why": "AWAITING_OPERATOR_DECISION"}, {"s": "2026-10-31T21:13:49.116Z", "e": "2026-10-31T21:13:52.326Z", "why": "BLOCKED"}, {"s": "2026-10-31T21:15:55.399Z", "e": "2026-10-31T21:15:57.736Z", "why": "BLOCKED"}, {"s": "2026-10-31T21:18:35.329Z", "e": "2026-10-31T22:08:19.355Z", "why": "BLOCKED"}, {"s": "2026-11-01T06:31:12.953Z", "e": "2026-11-01T08:38:33.431Z", "why": "PULL_REQUEST_FAILED"}, {"s": "2026-11-01T08:47:02.652Z", "e": "2026-11-01T08:53:33.774Z", "why": "superseded_by_new_run"}, {"s": "2026-11-01T08:53:33.774Z", "e": "2026-11-01T09:04:33.414Z", "why": "PULL_REQUEST_FAILED"}, {"s": "2026-11-01T09:31:50.704Z", "e": null, "why": null}], "incidents": [{"at": "2026-10-31T21:13:50.500Z", "phase": "implement", "n": 1, "why": "Goal-subtask planning import conflicts with the stored shared preplan or subtask plan. This occurs when a shared preplan was regenerated after the child was hydrated, making the previously-imported planning bytes stale. Recover this subtask's child without discarding sibling planning or completed commits: 'skill-bill goal replan SKILL-455 --subtask 2'. Planning failure: existing child planning import conflicts with request: stored import provenance differs from the hydration request at decomposition_manifest_hash", "disp": null}, {"at": "2026-10-31T22:07:54.748Z", "phase": "audit_plan_fix", "n": 4, "why": "AC-003 and IS-004 lack the input needed for the first-open failure case. IS-004 opens the window directly on a committed subtask whose end commit is missing and requires the operator to click another file-specific chip after the scope read fails. ChangeScopeObservations.observeScope returns BaseUnavailable without a ChangeSet; ChangesWindowViewModel starts with selectedPath null, and subtasksTouching requires a selected path. ChangesWindowEntry carries only the item and scope. Neither the window nor its callers supply a previously selected file, and the failed scope cannot provide one. The criterion separately requires 'Select a file' when nothing is selected. Stats for other subtasks do not establish which file was selected or belonged to the failed scope; inventing a selection from them would not satisfy the requirement. The missing historical file list cannot be recovered when the…", "disp": "needs_user_action"}]};
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const ms = t => new Date(t).getTime();
let NOW = ms(D.finish.latest.at);
const REAL_NOW = NOW;
const LONG_GAP = 600000;
const clock = t => new Date(t).toISOString().slice(11,16);
const dayOf = t => new Date(t).toLocaleDateString('en-US',{month:'short',day:'numeric',timeZone:'UTC'});
function dur(m){ if(m<1000)return '<1s'; const s=Math.round(m/1000); if(s<60)return s+'s'; const n=Math.round(s/60); if(n<60)return n+'m'; const h=Math.floor(n/60); return h+'h '+String(n%60).padStart(2,'0')+'m'; }
function ago(t){ return dur(NOW-ms(t))+' ago'; }
const modelName = m => (m||'').replace(/^claude-/,'').replace(/^gpt-/,'gpt-');
const AG = {claude:'Cl',codex:'Cx',cursor:'Cu',junie:'Ju'};
const LOOPS = {audit:'audit',audit_plan_fix:'audit',audit_implement_fix:'audit',review:'review',verify_findings:'review',review_fix:'review',validate:'validate',validate_fix:'validate',monitor:'monitor',monitor_fix:'monitor',qa:'qa',qa_fix:'qa'};
const CHEV = '<svg class="chev" width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m6 4 4 4-4 4"/></svg>';
const plural = (n,w) => `${n} ${w}${n===1?'':'s'}`;

function foldLoops(phases){
  const out=[]; let cur=null;
  for(const p of phases){
    const fam=LOOPS[p.p]||p.p;
    if(cur&&cur.fam===fam&&p.e&&(!p.state||p.state==='complete')&&cur.items.every(item=>!item.state||item.state==='complete')){cur.items.push(p);continue;}
    cur={fam,items:[p]}; out.push(cur);
  }
  return out;
}
function modelCell(p){
  if(!p.a) return '<span class="mdl"></span>';
  const label=p.label||(p.m?`${modelName(p.m)}${p.f?' · '+p.f:''}`:p.a);
  return `<span class="mdl" title="${esc(p.a)}${p.m?' · '+esc(p.m):''}${p.f?' · effort '+esc(p.f):''}"><span class="ag">${AG[p.a]||esc(p.a[0])}</span>${esc(label)}</span>`;
}
function phaseRow(p){
  const running=p.state ? p.state === 'running' : !p.e, verdict=p.v&&p.v!=='null'?`<span class="v">${esc(p.v)}</span>`:'';
  return `<div class="row"><span class="tm" title="${p.clockLabel ? esc(p.clockLabel) : esc(ago(p.e||p.s))}">${p.clockLabel ? esc(p.clockLabel) : clock(p.e||p.s)}</span><span class="dot ${running?'running':p.state ? 'd-'+p.state : 'd-done'}"></span><span class="ph">${esc(p.p)}${p.state && !['complete','running'].includes(p.state) ? `<span class="x">${esc(p.state)}</span>` : ''}${p.n>1?`<span class="x">#${p.n}</span>`:''}${verdict}</span><span class="dur">${running || p.durationKnown === false ? '' : dur(ms(p.e)-ms(p.s))}</span>${modelCell(p)}${p.t?`<div class="sum" data-more>${esc(p.t)}</div>`:''}</div>`;
}
let foldId=0;
function foldRow(f){
  if(f.items.length===1) return phaseRow(f.items[0]);
  const counts={}; f.items.forEach(p=>counts[p.p]=(counts[p.p]||0)+1);
  const label=Object.entries(counts).map(([k,n])=>`${k} ×${n}`).join(', ');
  const first=f.items[0], last=f.items[f.items.length-1], id='f'+(++foldId);
  const total=f.items.reduce((a,p)=>a+(ms(p.e)-ms(p.s)),0);
  const agents=[...new Set(f.items.map(p=>p.a))];
  const m={a:agents[0],label:agents.join(' + ')};
  return `<div class="row click" data-sub="${id}"><span class="tm" title="started ${esc(ago(first.s))}">${last.clockLabel ? esc(last.clockLabel) : clock(last.e)}</span><span class="dot d-done"></span><span class="ph">${esc(f.fam)} loop<span class="x">${esc(label)}</span></span><span class="dur">${dur(total)}</span>${modelCell(m)}${last.t?`<div class="sum">${esc(last.t)}</div>`:''}</div><div class="sub" id="${id}">${f.items.map(phaseRow).join('')}</div>`;
}
function blockEv(b,top){
  const x=[b.phase,b.n?'attempt '+b.n:null,b.disp==='needs_user_action'?'needs your decision':null].filter(Boolean).join(' · ');
  return `<div class="ev block${top?' top':''}"><span class="tm" title="${b.at ? esc(ago(b.at)) : ''}">${b.at ? clock(b.at) : ''}</span><span class="dot d-blocked"></span><div><span class="h">${esc(b.title||'Blocked')}${x?`<span class="x">${esc(x)}</span>`:''}</span></div>${b.why?`<div class="why" data-more>${esc(b.why)}</div>`:''}</div>`;
}
function waitEv(m,solo){ return `<div class="wait${solo?' solo':''}" title="No phase ran during this time">idle ${dur(m)}</div>`; }

function factRow(f){
  return `<div class="row"><span class="tm" ${f.at?`title="${esc(f.when||ago(f.at))}"`:''}>${f.at?clock(f.at):''}</span><span class="dot ${f.running?'running':f.status?'d-'+f.status:'d-done'}"></span><span class="ph">${esc(f.name)}${f.x?`<span class="x">${esc(f.x)}</span>`:''}</span><span class="dur"></span>${f.a?modelCell(f):'<span class="mdl"></span>'}${f.t?`<div class="sum">${esc(f.t)}</div>`:''}</div>`;
}
function groupBody(g){
  const items=(g.before||[]).map(factRow); let prevEnd=null;
  const blocks=(g.blocks||[]).slice().sort((a,b)=>ms(a.at)-ms(b.at));
  let bi=0; const seq=[];
  for(const p of g.ph){
    while(bi<blocks.length&&ms(blocks[bi].at)<=ms(p.s)) seq.push({block:blocks[bi++]});
    seq.push(p);
  }
  while(bi<blocks.length) seq.push({block:blocks[bi++]});
  // split into runs of phases so blocks and waits break up loop folding
  let run=[];
  const flush=()=>{ if(run.length){ foldLoops(run).forEach(f=>items.push(foldRow(f))); run=[]; } };
  for(const s of seq){
    if(s.block){ flush(); items.push(blockEv(s.block)); prevEnd=ms(s.block.at); continue; }
    if(!g.contextual&&prevEnd&&ms(s.s)-prevEnd>600000){ flush(); items.push(waitEv(ms(s.s)-prevEnd)); }
    run.push(s); prevEnd=s.e?ms(s.e):null;
  }
  flush();
  (g.after||[]).forEach(f=>items.push(factRow(f)));
  return items.join('')||`<div class="empty">${g.status === 'pending' ? 'Not started.' : 'No phases recorded.'}</div>`;
}
function activeMs(g){ return g.ph.reduce((a,p)=>a+(p.elapsed ?? ((p.e?ms(p.e):NOW)-ms(p.s))),0); }
function groupCard(g){
  const blocked=g.status==='blocked', running=g.status==='running';
  const nblocks=(g.blocks||[]).length;
  const open=filter==='blocks'||(g.open??(['blocked','running','paused','failed','stopped'].includes(g.status)));
  const dot=running?'dot running':blocked?'dot d-blocked':'dot d-'+g.status;
  const tag=running?'<span class="tag run">running</span>':blocked?'<span class="tag">blocked</span>':nblocks?`<span class="tag">${plural(nblocks,'block')}</span>`:!['complete','pending'].includes(g.status)?`<span class="tag state-${esc(g.status)}">${esc(g.status)}</span>`:'';
  const a=activeMs(g), wall=g.s?((g.e?ms(g.e):NOW)-ms(g.s)):0;
  const shown=a?dur(a):wall?dur(wall):'';
  const models=g.agents||[...new Set(g.ph.map(p=>p.a).filter(Boolean))];
  return `<div class="grp${open?' open':''}${running?' isrun':''}" id="g-${esc(g.id)}"><div class="ghead" role="button" tabindex="0" aria-expanded="${open}">${CHEV}<span class="${dot}"></span><span class="num">${esc(g.n??'··')}</span><span class="nm">${esc(g.name)}${tag}</span><span class="meta t" title="${g.contextual ? '' : g.s?esc(ago(g.s)):''}">${g.contextual ? esc(g.clockLabel || '') : g.s?clock(g.s):''}</span><span class="meta" title="${wall&&a?'Working time; '+dur(wall)+' start to finish':''}">${shown}</span><span class="meta t">${esc(models.join(' + '))}</span></div><div class="gbody">${groupBody(g)}</div></div>`;
}
function workSpans(g){
  const spans=[]; let cur=null;
  for(const p of g.ph){
    const s=ms(p.s), e=p.e?ms(p.e):NOW;
    if(cur&&s-cur.e>LONG_GAP){spans.push(cur);cur=null;}
    if(!cur)cur={s,e,work:0};
    cur.e=Math.max(cur.e,e);cur.work+=e-s;
  }
  if(cur)spans.push(cur);
  return spans;
}
function timeline(run){
  if (run.contextual) {
    const groups = run.groups.filter(group => group.ph.some(phase => phase.durationKnown));
    if (!groups.length) return '';
    return `<div class="tl" aria-label="Recorded phase durations">${groups.map(group => `<div class="b ${group.status === 'running' ? 'run' : ''}" data-go="g-${esc(group.id)}" style="flex:${Math.max(activeMs(group), 1)} 1 0" title="${esc(group.name)} · ${dur(activeMs(group))}"></div>`).join('')}</div>`;
  }
  const parts=[]; let prev=null, firstT=null, lastT=null;
  const wait=m=>parts.push(`<div class="b wait" title="idle ${dur(m)}"></div>`);
  for(const g of run.groups.filter(g=>g.s||g.finish)){
    const go=`data-go="g-${esc(g.id)}"`, label=`${g.n?g.n+' · ':''}${g.name}`;
    const spans=workSpans(g);
    const bar=(sp,cls)=>{
      if(prev&&sp.s-prev>LONG_GAP) wait(sp.s-prev);
      parts.push(`<div class="b ${cls}" ${go} style="flex:${Math.max(sp.work,60000)} 1 0" title="${esc(label)} · ${dur(sp.work)}"></div>`);
      prev=sp.e; firstT=firstT??sp.s; lastT=sp.e;
    };
    const blocks=(g.blocks||[]).filter(b=>b.at).slice().sort((x,y)=>ms(x.at)-ms(y.at));
    const stop=b=>{parts.push(`<div class="b stop" ${go} title="${esc(b.title||'Blocked')} ${clock(b.at)}"></div>`);prev=Math.max(prev||0,ms(b.at));firstT=firstT??ms(b.at);lastT=Math.max(lastT||0,ms(b.at));};
    let bi=0;
    if(!spans.length) blocks.forEach(stop);
    spans.forEach((sp,i)=>{
      while(bi<blocks.length&&ms(blocks[bi].at)<=sp.s) stop(blocks[bi++]);
      bar(sp,g.finish?'fin':'');
      while(bi<blocks.length&&(i===spans.length-1||ms(blocks[bi].at)<spans[i+1].s)) stop(blocks[bi++]);
    });
    if(g.status==='running'){ parts.push(`<div class="b run" ${go} style="flex:0 0 8px" title="running now"></div>`); }
  }
  if(!parts.length) return '';
  return `<div class="tl" aria-label="Run timeline">${parts.join('')}</div><div class="tllab"><span>${firstT?dayOf(firstT)+' '+clock(firstT):''}</span><span>${run.live?'now':(lastT?dayOf(lastT)+' '+clock(lastT):'')}</span></div>`;
}
function blockCount(run){ return run.groups.reduce((n,g)=>n+(g.blocks||[]).length,0); }
function waited(run){
  const all=run.groups.flatMap(workSpans).sort((a,b)=>a.s-b.s); let t=0, end=null;
  for(const sp of all){ if(end&&sp.s-end>LONG_GAP) t+=sp.s-end; end=Math.max(end||0,sp.e); }
  return t;
}
function stats(run){
  const gs=run.groups, ph=gs.flatMap(g=>g.ph);
  const active=gs.reduce((a,g)=>a+activeMs(g),0), stops=blockCount(run), wait=run.contextual ? 0 : waited(run);
  const models={}; ph.filter(p=>p.m).forEach(p=>{const k=modelName(p.m);models[k]=(models[k]||0)+(p.elapsed ?? ((p.e?ms(p.e):NOW)-ms(p.s)));});
  const top=Object.entries(models).sort((a,b)=>b[1]-a[1]);
  if (run.contextual && !top.length && run.model) top.push([modelName(run.model), 0]);
  const done=gs.filter(g=>g.n!=null&&g.status==='complete').length, tot=new Set(gs.filter(g=>g.n!=null).map(g=>g.n)).size;
  const fixes=ph.filter(p=>/fix/.test(p.p)).length;
  const c=(k,v,sub,w)=>`<div class="stat"><div class="k">${k}</div><div class="v${w?' warn':''}" title="${esc(v)}">${esc(v)}</div><div class="s" title="${esc(sub)}">${esc(sub)}</div></div>`;
  return `<div class="stats">${c('Phase time',ph.length ? dur(active) : 'Not recorded', wait?dur(wait)+' idle':run.contextual ? 'known phase records' : 'no long gaps')}${c('Blocks',String(stops),stops?run.stopNote||'stopped for help':'none',stops>0)}${c(run.unitLabel||'Subtasks',run.unitValue||`${done} of ${tot}`,`${plural(ph.length,'phase')} · ${plural(fixes,'fix').replace('fixs','fixes')}`)}${c('Models',top[0]?top[0][0]:'none',top.length>1?`+ ${top.slice(1).map(t=>t[0]).join(', ')}`:(top[0]?'only model':''))}</div>`;
}
let filter = saved ? saved.filter : 'all';
function feed(run){
  let out='', lastDay=null;
  const gs=run.groups.filter(g=>filter==='all'||g.status==='blocked'||(g.blocks||[]).length);
  gs.forEach((g,i)=>{
    const when=g.s;
    if(when&&!run.contextual){ const d=dayOf(when); if(d!==lastDay){ out+=`<div class="day">${d}</div>`; lastDay=d; } }
    const prev=gs[i-1];
    if(!run.contextual&&prev&&prev.e&&g.s&&filter==='all'&&ms(g.s)-ms(prev.e)>LONG_GAP) out+=waitEv(ms(g.s)-ms(prev.e),true);
    out+=groupCard(g);
  });
  return out||'<div class="empty">No blocks in this run.</div>';
}
function render(){
  const run = host.getAttribute('work') ? window.DesignFlow.activityFor(host.getAttribute('work')) : STATES[state];
  document.getElementById('note').innerHTML=run.note;
  NOW=run.now||REAL_NOW;
  const nb=blockCount(run);
  document.getElementById('root').innerHTML=`<div class="hd"><h3>Activity</h3><span class="ct">${run.groups.reduce((count, group) => count + group.ph.length + (group.before || []).length + (group.after || []).length, 0)}</span><span class="sp"></span><button class="link" id="exp">Expand all</button><div class="seg" role="group" aria-label="Filter"><button data-f="all" aria-pressed="${filter==='all'}">All</button><button data-f="blocks" aria-pressed="${filter==='blocks'}">Blocks<span class="n">${nb}</span></button></div></div>${stats(run)}${timeline(run)}<div class="feed">${feed(run)}</div>`;
}

/* real data: SKILL-455 */
function realRun(live){
  const stopTitle={BLOCKED:'Blocked',PULL_REQUEST_FAILED:'Pull request failed',AWAITING_OPERATOR_DECISION:'Waiting for your decision'};
  const inc=D.incidents;
  const groups=D.groups.map(g=>({...g,id:`${g.n}-${g.s}`}));
  const g2a=groups[1], g3=groups[3];
  g2a.blocks=[{at:inc[0].at,why:inc[0].why,phase:inc[0].phase,title:'Blocked',disp:inc[0].disp}];
  g3.blocks=[{at:inc[1].at,why:inc[1].why,phase:inc[1].phase,n:inc[1].n,title:'Blocked',disp:inc[1].disp}];
  g2a.e=D.stops[1].at; g2a.status='blocked';
  groups[0].blocks=[];
  const pr=D.stops.filter(s=>s.why==='PULL_REQUEST_FAILED');
  const F=D.finish, L=F.latest;
  const fs=[F.major?plural(F.major,'major finding'):null,F.minor?plural(F.minor,'minor finding'):null].filter(Boolean).join(', ');
  const fin={id:'finish',n:null,name:'Goal finish',status:'running',finish:true,s:null,e:null,ph:[],agents:[L.agent].filter(Boolean),
    before:[{name:'goal_assessment',x:`${plural(F.rounds,'round')}, ${F.exit}`,t:fs?`Found ${fs}.`:null}],
    blocks:pr.map(s=>({at:s.at,title:'Pull request failed'})),
    after:[{name:L.step,x:'latest update',at:L.at,running:true,a:L.agent,m:L.model,f:L.effort,t:L.op}]};
  const subs=groups.filter(g=>(g.blocks||[]).length).length;
  return {groups:[...groups,fin],live:true,stopNote:`${subs} subtasks, ${pr.length} PRs`,
    note:'<b>Real data.</b> SKILL-455 exactly as the runtime recorded it, from phase ledgers, phase records, phase results, subtask events, block incidents and goal run sessions.'};
}
/* samples built from real phases */
function realPhases(n){ return D.groups.find(g=>g.n===n&&g.ph.length).ph; }
function oneSubtask(){
  const g=D.groups[D.groups.length-1];
  return {groups:[{...g,n:1,id:'one',open:true}],note:'<b>Sample, one subtask.</b> Real phases from subtask 4. There is nothing to compare against, so the only subtask starts open and the timeline stays useful for where the time went.'};
}
function running(){
  const ph=realPhases(4), cut=Math.floor(ph.length*.6);
  const shown=ph.slice(0,cut).map(p=>p);
  const nowP={...ph[cut],e:null,t:null};
  const g1=D.groups[0], g2=D.groups[2];
  return {now:ms(ph[cut].s)+240000,groups:[{...g1,id:'r1'},{...g2,id:'r2'},{n:3,name:'Scope selection, keyboard and detail strip',status:'running',s:ph[0].s,e:null,ph:[...shown,nowP],id:'r3'},{n:4,name:'Polish and QA',status:'pending',s:null,e:null,ph:[],id:'r4',open:false}],live:true,
    note:'<b>Sample, running now.</b> Real phases cut short. The running subtask stays open with a pulsing dot, its current phase has no duration yet, and subtasks that have not started show with a hollow dot.'};
}
function longRun(){
  const base=D.groups.filter(g=>g.ph.length); const groups=[]; const shift=(t,m)=>t?new Date(ms(t)+m).toISOString():t;
  let off=0; const names=['Runtime change baseline record','Subtask start SHA capture','Merge-base fallback','Git numstat reader','Untracked line counter','Rename and binary parsing','Typed change outcomes','Oversized diff guard','Per-file patch read','Worktree resolution','RuntimeComponent wiring','Desktop domain models','Change repository port','Runtime change mapping','Unified diff parser','Hunk attribution','Scope model','Scope observations','Stats cache','Git read lock','Diff colors'];
  const t0=ms(base[0].s);
  for(let i=0;i<33;i++){
    const n=i+1;
    if(i>=21){ groups.push({n,name:'Planned subtask '+n,status:'pending',s:null,e:null,ph:[],id:'l'+n,open:false}); continue; }
    const g=base[i%base.length]; const span=ms(g.ph[g.ph.length-1].e)-ms(g.ph[0].s); const m=t0+off-ms(g.ph[0].s);
    const running=i===20; const ph=g.ph.map(p=>({...p,s:shift(p.s,m),e:shift(p.e,m)}));
    const blk=i===7?[{at:ph[ph.length-1].e,why:'Sample block: validation needs a decision from you.',phase:'validate',disp:'needs_user_action'}]:[];
    groups.push({...g,n,name:names[i],status:running?'running':'complete',s:ph[0].s,e:running?null:ph[ph.length-1].e,ph:running?[...ph.slice(0,5),{...ph[5],e:null}]:ph,blocks:blk,id:'l'+n,reason:null,open:undefined});
    off+=Math.max(span,600000)+ (i===7?3600000*2:30000);
  }
  const last=groups[20].ph[groups[20].ph.length-1];
  return {now:ms(last.s)+300000,groups,live:true,unitValue:'20 of 33',note:'<b>Sample, 33 subtasks.</b> Real phases repeated across a long run. Everything finished is closed, so the list stays one row per subtask, and Blocks narrows it to what went wrong.'};
}
function phaseRun(){
  const r=realPhases(1).find(p=>p.p==='review');
  return {now:ms(r.e),groups:[{n:null,name:'Standalone review',status:'complete',s:r.s,e:r.e,ph:[r],id:'p',open:true}],unitLabel:'Runs',unitValue:'1',
    note:'<b>Sample, a standalone phase or operation.</b> These runs record one start, finish and result, with no phase ledger, so each run is one row. A real phase from subtask 1 stands in for the run.'};
}
const STATES={real:realRun(),one:oneSubtask(),running:running(),long:longRun(),phase:phaseRun()};
const LABELS={real:'SKILL-455 (real)',one:'1 subtask',running:'Running now',long:'33 subtasks',phase:'Standalone phase'};
let state = saved ? saved.state : Object.hasOwn(STATES, host.getAttribute('scenario')) ? host.getAttribute('scenario') : 'real';
document.getElementById('states').innerHTML=Object.entries(LABELS).map(([k,l])=>`<button data-s="${k}" aria-pressed="${k===state}">${l}</button>`).join('');
document.getElementById('states').onclick=e=>{const k=e.target.dataset.s;if(!k)return;state=k;filter='all';document.querySelectorAll('#states button').forEach(b=>b.setAttribute('aria-pressed',b.dataset.s===k));render();};
document.getElementById('root').addEventListener('click',e=>{
  const t=e.target;
  if(t.dataset.f){filter=t.dataset.f;render();return;}
  if(t.id==='exp'){const gs=document.querySelectorAll('.grp');const anyClosed=[...gs].some(g=>!g.classList.contains('open'));gs.forEach(g=>{g.classList.toggle('open',anyClosed);g.querySelector('.ghead').setAttribute('aria-expanded',anyClosed)});t.textContent=anyClosed?'Collapse all':'Expand all';return;}
  if(t.dataset.go){const g=document.getElementById(t.dataset.go);if(g){g.classList.add('open');g.scrollIntoView({behavior:'smooth',block:'center'});}return;}
  if(t.classList.contains('why')||(t.classList.contains('sum')&&t.hasAttribute('data-more'))){t.classList.toggle('full');return;}
  const row=t.closest('.row.click');
  if(row){document.getElementById(row.dataset.sub).classList.toggle('open');return;}
  const h=t.closest('.ghead');
  if(h){const g=h.parentElement;const o=g.classList.toggle('open');h.setAttribute('aria-expanded',o);}
});
document.getElementById('root').addEventListener('keydown',e=>{if((e.key==='Enter'||e.key===' ')&&e.target.classList.contains('ghead')){e.preventDefault();e.target.click();}});
if (host.hasAttribute('embedded')) {
  document.getElementById('states').parentElement.hidden = true;
  document.querySelectorAll('.note').forEach((note) => { note.hidden = true; });
}
render();
if (saved) document.querySelectorAll('.grp').forEach((group) => {
  const open = saved.open.includes(group.id);
  group.classList.toggle('open', open);
  group.querySelector('.ghead').setAttribute('aria-expanded', open);
});
return () => ({ state, filter, open: [...document.querySelectorAll('.grp.open')].map((group) => group.id) });
  });
})();
