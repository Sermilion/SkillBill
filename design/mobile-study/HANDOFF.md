# Sona mobile app: design handoff

## 1. Outcome

A phone app that shows what needs you on your Sona machines and sends the same run controls as the desktop app. It connects over SSH when opened, reads a snapshot, and disconnects when closed. Push notifications tell you when to open it.

## 2. Decisions

| Decision | Detail |
| --- | --- |
| Observe and control only | Goals, phases, operations, chats and agents run on the machine. The phone never runs them. Starting work from a chat or a plan counts as control. |
| SSH transport | The phone holds an ed25519 key generated on the device. The private key stays in the keychain. The host key is trusted on first use and pinned after that. Tailscale or LAN handles reachability. |
| No persistent connection | The app connects on open and on pull to refresh. While a run detail or chat is open it reads again every few seconds, and it stops when the app goes to the background. Nothing runs in the background. |
| Push through Firebase | The desktop app sends a push on the same board transitions it uses for OS notifications. It goes through a small Sona push relay to FCM, and FCM reaches iOS through APNs. The phone registers its FCM token on the machine over SSH during pairing. |
| Diffs are read-only | Scopes, files, a unified diff and published threads. No drafting or publishing comments. |
| One design for iOS and Android | The design uses Sona's own look, as Linear does, rather than Material or Cupertino. Platform differences are limited to the system permission dialog and the share sheet. |
| Dark only | Same tokens as the desktop study. |
| OpenTunnel not used | It needs a live foreground session on the host and allows one client and one command at a time. That fits badly with opening the app whenever you want. Revisit for machines that SSH can't reach. |

## 3. Navigation

Five tabs: Needs you (badge with the count), Work, Chats, Plans, Settings. Pushed screens hide the tab bar and show a sticky action bar when there is something to do. Menus and pickers are bottom sheets. A Needs you row swipes left to show its primary action. Long-press opens the full action sheet.

## 4. Feature mapping

Runtime entry points are the desktop study's (`design/linear-study/HANDOFF.md`, section 4.1). Section 7 covers how the phone reaches them.

| Mobile | Desktop source |
| --- | --- |
| Needs you tab | Board Needs you group (`IdeStatusPolicy`). Derived from board state, with no read or unread state. |
| Work tab: Goal runs, Standalone phases, Operations, Finished today; History with 6h, 24h, 7d | Work board and History views |
| Run detail, in the desktop's order: chips (lifecycle, Stalled, key, issue key, kind, fresh or stale), title, location (checkout path, branch), Run app for Android goals, build strip, pause notice, run reason with copy, incident (heading, cause, next, certainty, prerequisites, evidence, report) or operator decision (command with copy, Decide in chat), metrics (subtask or goal stage, phase with attempt, goal active or total run time, subtask active), subtasks with commit sha, timer, "no change" and the Goal finish row, phase pipeline, Changes (per-subtask strip, running subtask, Open changes), Activity (metrics, timeline, Blocks filter, Expand all, a fold per subtask with phase ledger, fix loops and blocks, Goal finish, run events), Execution (workflow, invocation, agent, model and effort, liveness, started, updated), Runs and fixes (runs, fixes, relaunches with breakdowns, findings, kills). Sections collapse like the desktop's. The nav bar pages to the previous and next item | Detail view (`ideStatusService.detail`) and the details pane |
| Validation run document: status, title on the branch, outcome card (live check and command, tally, last agent signal, or the report headline), cells (Active, Started, Checks, Run), report flags, Checks with every attempt, Report parts, runs on this branch, Activity | `validationDoc` |
| Verify run document: status, issue key, range and intake, actions by state, What this work does, outcome card with Criteria, Review, Tests and QA cells, Criteria with All, Gaps and Met filters and grades, evidence and marks, Non-goals, Technical constraints and Rollout, the nine Steps with receipts | `verifyDoc` |
| Monitor run document: PR and branch, outcome card (Pull request, Head, Checks, Repair cap), Cycles grid, the selected step (CI board, repair with files and local checks, publish), Activity | `monitorDoc` |
| Plan run document: stage strip, Now, Activity with Files, Commands and Notes filters, Spec bundle with subtask spec states, Transcript, Briefing | `planParts` |
| Standalone QA: QA results with Needs you, Left behind, Not tested, Passed, Run details, Full report and coverage. Standalone review: Phase results with passes and findings, Spec bundle | `qaResults`, detail Phase results and Spec bundle |
| Subtask screen: tap a subtask or the Goal finish row. Commit, Spec file, Depends on, Needed by, Validation; block reason; a tab per phase where each phase that ran opens its card (header with agent, model, round, start and duration; why it stopped; Now; what it produced; activity; a link to the full screen). Opens on the running or stopped phase, else the last one that ran. Up and down page through subtasks and Goal finish | Details panel: SubtaskInspector and Phase peek (desktop handoff 4.1 and 9.2). Same records: phase records, settlement envelopes, edit journal, agent activity stamps, phase receipts |
| Pause after subtask, Pause now, Resume or Run again with agent, Cancel, Recover with prerequisites, Confirm criteria | `workControlService` requests, recovery preview and recover |
| Changes and Diff, read-only | Changes window and Diff comments, minus drafting |
| Run chats: one per run, opened with the chat button in the run's nav bar (or Run chat in More, Adjust in run chat, Decide in chat) and listed under Chats › Runs. A context strip names the run and where fixes go. The composer is bound to the run's checkout and branch, with no repository picker. A parked Verify shows its proposal card (Confirm, Adjust, Dismiss; Adjust takes your change in the composer and returns a revised card). A goal waiting on an operator decision shows a decision card (accept_and_advance, retry_fix with instructions, abandon_subtask); the agent records it with sona goal operator-decision. First open shows an intro instead of an empty transcript | Run chats (desktop feature/chat ChatWorkspace, toggleChat(item)); fixes go to the run's original source checkout, never a QA checkout |
| Chats: list, transcript, suggested actions, start card with key acceptance, queue, Stop, repository and branch sheet, chat changes, new task with quick starts | New task chats (`ExecutionChatRepository`) |
| Plans: project, status segments, bundle detail, read-only files, Start, Pause, Run again | Plans pane and spec selection bar |
| Settings › Usage | Usage card and panel |
| Settings › Default agent | Usage panel's Default agent row |
| Settings › Notifications: six types, show run titles | Desktop notification settings and `TransitionNotifier` |

## 5. Mobile-only design

- **Pairing:** welcome, then host, port and user, then the phone key with copy and share, then the host key fingerprint check, then a check of SSH, Sona presence, runtime compatibility and the first board read, then push permission. A failed compatibility check shows its fix.
- **Machines:** several machines, one current. Each shows its state, user@host, Sona version, pinned host key, the phone key and push registration. Switching is in Settings and in a sheet from the Needs you header.
- **Connection states:**
  - Connecting shows a banner.
  - Unreachable keeps the last snapshot on screen with its time.
  - Host key changed blocks the app until you pair again.
  - Key rejected, Sona missing and Sona too old each show their fix.
- **Failed controls:** a toast says the control didn't reach the machine and nothing changed, with Retry. Controls never queue offline.
- **Push privacy:** "Show run titles" off sends only the event kind. Code, diffs and transcripts never go through push.

## 6. Not on mobile

Terminal, General settings (database path, Claude directories, history retention, start at login), runtime config (Models, Compaction, Project, Telemetry and agents), QA device switcher, command menu, keyboard shortcuts, right-click menus, resizable and floating panes, tray popover, diff comment drafting, Add repository, Reveal specs folder, Import work. Section 4.4 of the desktop handoff still applies.

## 7. Open questions

1. **What the phone runs over SSH.** The desktop app calls the runtime inside its own JVM and has no CLI path. The phone has to run something on the host. Candidates are `sona-mcp` over the SSH channel's stdio, or a new `sona` subcommand that prints board, detail and control results as JSON. Both need the read speed work in section 5.3 of the desktop handoff to keep a snapshot under a second.
2. **Who sends pushes when the desktop app is closed.** As designed, pushes stop while the desktop app isn't running, and the Machine screen says so. Sending from the runtime instead would cover headless machines. That needs a runtime hook on board transitions.
3. **Push relay.** It needs hosting, auth from each machine and FCM credentials. `docs/cloudflare-telemetry-proxy` is a possible model. The Firebase service account must not ship in the desktop app.
4. **Live refresh interval** on open run detail and chats. 3 to 5 seconds is assumed. Each read opens an SSH exec channel unless the session is kept open while the app is in the foreground.
5. **Key import.** "Use an existing key instead" is drawn but not designed beyond the button.

## 8. Implementation notes

The desktop modules are Kotlin Multiplatform with only a `jvm()` target today. Shared UI in `core/designsystem`, `core/ui` and the feature modules can add Android and iOS targets. The runtime stays JVM-only on the machine, so the phone needs a new data layer behind the same repository interfaces that talks SSH instead of the embedded runtime.
