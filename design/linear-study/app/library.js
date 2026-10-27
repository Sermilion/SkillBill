/* Components + Foundations pages, and product surfaces outside the main window (tray, OS notifications).
 * Demos call the same SB functions the screens use; nothing here is a hand-drawn copy. */
(function () {
  'use strict';
  const S = window.SB, D = window.DATA, X = window.Screens;
  const esc = S.esc;
  const it = (id) => D.items.find((x) => x.id === id);

  /* ---------- surfaces (feature/work TrayPopoverContent) ---------- */
  function tray(tab) {
    const t = tab || 'goals';
    const live = (k) => D.items.filter((x) => x.kind === k && x.section !== 'finished');
    const done = (k) => D.items.filter((x) => x.kind === k && x.section === 'finished');
    const row = (x) => S.ListRow({ compact: true, id: X.rowKey(x), status: X.statusIcon(x), title: x.repo, sub: esc(x.pauseRequested ? 'Pause requested' : x.lifecycle === 'active' ? X.stepText(x) : X.LIFE[x.lifecycle][0].toLowerCase()), meta: x.kind === 'goal' && x.total ? [x.planning ? S.ProgressRing(x.planned, x.total, { color: 'var(--st-planning)', text: false }) : S.ProgressRing(x.done, x.total, { text: false })] : [], time: x.finishedAt ? esc(x.finishedAt) : X.clock(x), act: 'tray-open', arg: x.id, faded: x.section === 'finished' });
    const kind = { goals: 'goal', phases: 'phase', operations: 'operation' }[t];
    const rows = live(kind).map(row);
    if (kind !== 'goal' && done(kind).length) rows.push(`<div class="sb-menu-head" style="padding:8px 12px 4px">Finished in the last hour</div>`, ...done(kind).map(row));
    const running = D.items.filter((x) => x.lifecycle === 'active').length;
    const needs = D.items.filter((x) => x.section === 'needs').length;
    const doneDay = D.items.filter((x) => x.section === 'finished').length;
    return S.TrayPopover({
      stats: [{ value: running, label: 'Running', color: 'var(--accent-text)' }, { value: needs, label: 'Needs you', color: 'var(--st-blocked)' }, { value: doneDay, label: 'Done 24h' }],
      tabs: S.ViewTabs({ act: 'noop', value: t, items: [{ id: 'goals', label: 'Goals', count: live('goal').length, attention: needs > 0 }, { id: 'phases', label: 'Phases', count: live('phase').length }, { id: 'operations', label: 'Operations', count: live('operation').length }] }),
      rows,
      footer: [S.Button({ label: 'Open Skill Bill', variant: 'ghost', size: 'sm', act: 'mode', arg: 'prototype' }), `<span class="l-row g-1">${S.IconButton({ icon: 'refresh', label: 'Refresh', size: 'sm', act: 'noop' })}${S.IconButton({ icon: 'sliders', label: 'Settings', size: 'sm', act: 'noop' })}${S.Button({ label: 'Quit', variant: 'ghost', size: 'sm', act: 'noop' })}</span>`],
    });
  }
  /* One per allowed transition: blocked, failed, goal_completed, pause_took_effect, standalone_phase_finished, qa_incomplete */
  function osNotifications() {
    return [
      S.Toast({ system: true, app: 'Skill Bill', when: 'now', title: 'INF-7 is blocked', body: 'Review left 2 Blocker findings on subtask 3 · infra-terraform' }),
      S.Toast({ system: true, app: 'Skill Bill', when: '12m', title: 'DSN-31 failed', body: 'validate failed on subtask 6 · design-tokens' }),
      S.Toast({ system: true, app: 'Skill Bill', when: '2h', title: 'SKILL-416 completed', body: '3/3 subtasks · skill-bill · 2h 04m' }),
      S.Toast({ system: true, app: 'Skill Bill', when: '2h', title: 'PAY-19 paused', body: 'Your pause took effect after subtask 4 · payments-svc' }),
      S.Toast({ system: true, app: 'Skill Bill', when: '2h', title: 'validation finished', body: 'skill-bill · fix/standalone-validation-discovery' }),
      S.Toast({ system: true, app: 'Skill Bill', when: '3h', title: 'QA could not complete on ATL-399', body: 'QA ended incomplete because of an error · atlas-web' }),
    ].join('');
  }

  /* ---------- component registry ---------- */
  const REG = [
    ['Primitives', [
      ['Icon', 'Lucide line icons at 16px, 1.75 stroke. Inherit currentColor.', () => `<div class="l-row g-3 l-wrap t-muted">${['target', 'history', 'fileText', 'terminal', 'sliders', 'search', 'pause', 'stop', 'play', 'refresh', 'copy', 'branch', 'folder', 'bot'].map((n) => S.Icon(n)).join('')}</div>`],
      ['StatusIcon', 'Shape carries the state, color reinforces it. Active fills with progress; dashed is planning, dotted is stalled.', () => `<div class="l-row g-4 l-wrap">${['pending', 'planning', 'active', 'stale', 'paused', 'blocked', 'failed', 'done', 'no_change', 'skipped', 'canceled'].map((s) => `<span class="l-row g-2" style="font-size:var(--t-sm)">${S.StatusIcon(s, s === 'active' ? 0.66 : 0.5, { lg: true })}<span class="t-2">${esc(s === 'stale' ? 'Stalled' : S.STATES[s].label)}</span></span>`).join('')}</div>`],
      ['Kbd', 'Keyboard hint shown in tooltips, menus, buttons and the command menu.', () => `<div class="l-row g-3 l-wrap">${S.Kbd('⌘ K')}${S.Kbd('G W')}${S.Kbd('P')}${S.Kbd('⇧ P')}${S.Kbd('⌃ `')}${S.Kbd('esc')}</div>`],
      ['BrandMark', 'The SB monogram, reduced for 18–20px.', () => `<div class="l-row g-3">${S.BrandMark(16)}${S.BrandMark(20)}${S.BrandMark(32)}</div>`],
      ['Tooltip', 'After 450ms on anything with a label; shows the shortcut when there is one.', () => `<div class="l-row g-3"><span class="sb-tip" style="position:static">${S.Tooltip('Pause after subtask', 'P')}</span>${S.IconButton({ icon: 'terminal', label: 'Terminal', kbd: '⌃ `', act: 'noop', boxed: true })}</div>`],
    ]],
    ['Actions', [
      ['Button', 'Primary for the one next step (Resume, Start). Danger for Pause, Cancel and Purge.', () => `<div class="l-row g-2 l-wrap">${S.Button({ label: 'Pause after subtask', icon: 'pause', kbd: 'P', act: 'noop' })}${S.Button({ label: 'Pause', icon: 'stop', variant: 'danger', kbd: '⇧ P', act: 'noop' })}${S.Button({ label: 'Resume', icon: 'play', variant: 'primary', kbd: 'R', act: 'noop' })}${S.Button({ label: 'Agent: codex', icon: 'bot', act: 'noop' })}${S.Button({ label: 'Cancel', icon: 'x', variant: 'danger', act: 'noop' })}${S.Button({ label: 'Pause requested', icon: 'pause', disabled: true })}</div>`],
      ['IconButton', 'Icon-only control; always labeled.', () => `<div class="l-row g-1">${S.IconButton({ icon: 'refresh', label: 'Refresh', act: 'noop' })}${S.IconButton({ icon: 'fileText', label: 'Specs', kbd: 'G S', act: 'noop', on: true })}${S.IconButton({ icon: 'terminal', label: 'Terminal', kbd: '⌃ `', act: 'noop' })}${S.IconButton({ icon: 'panelRight', label: 'Toggle details', kbd: '⌘ I', act: 'noop' })}</div>`],
      ['Segmented', 'Exclusive options: retention, spec status filter, telemetry level, agent.', () => `<div class="l-col g-3">${S.Segmented({ act: 'noop', value: '24h', items: ['6h', '24h', '7d'].map((x) => ({ id: x, label: x })) })}${S.Segmented({ act: 'noop', value: 'all', items: [{ id: 'all', label: 'All' }, { id: 'active', label: 'Active' }, { id: 'pending', label: 'Pending' }, { id: 'done', label: 'Done' }] })}</div>`],
      ['ViewTabs', 'Slices of one list: History’s All / Goals / Phases / Operations, the tray’s tabs.', () => S.ViewTabs({ act: 'noop', value: 'all', items: [{ id: 'all', label: 'All' }, { id: 'goals', label: 'Goals' }, { id: 'phases', label: 'Phases' }, { id: 'operations', label: 'Operations' }] })],
      ['Toggle', 'Saves on change: notifications, start at login, compaction.', () => `<div class="l-row g-3">${S.Toggle({ on: true, act: 'noop', label: 'On' })}${S.Toggle({ on: false, act: 'noop', label: 'Off' })}</div>`],
      ['Checkbox', 'Confirms an incident prerequisite before Recover is offered.', () => `<div class="l-row g-3">${S.Checkbox({ on: true, act: 'noop', label: 'Confirmed' })}${S.Checkbox({ on: false, act: 'noop', label: 'Not confirmed' })}</div>`],
    ]],
    ['Data display', [
      ['IdTag', 'Issue key, phase id or operation id.', () => `<div class="l-row g-3">${S.IdTag('SKILL-417')}${S.IdTag('validation')}${S.IdTag('verify')}</div>`],
      ['Label', 'Repository, step, lifecycle (tinted), freshness, Stalled.', () => `<div class="l-row g-2 l-wrap">${S.Label({ text: 'skill-bill', color: 'var(--data-1)' })}${S.Label({ text: 'review · pass 2', mono: true, dot: false })}${S.Label({ text: 'Blocked', color: 'var(--st-blocked)', tint: true, dot: false })}${S.Label({ text: 'Stalled', color: 'var(--st-blocked)', tint: true, dot: false })}${S.Label({ text: 'fresh', color: 'var(--st-done)' })}${S.Label({ text: 'operation', dot: false })}</div>`],
      ['Avatar', 'The agent running the work: claude, codex, cursor, junie.', () => `<div class="l-row g-2">${D.AGENT_CHOICES.map((a) => S.Avatar({ name: a, initials: D.AGENTS[a].initials, color: D.AGENTS[a].color })).join('')}</div>`],
      ['RepoIcon', 'Repository mark in rows, breadcrumbs and the Specs project picker.', () => `<div class="l-row g-2">${Object.values(D.REPOS).map((r) => S.RepoIcon(r)).join('')}</div>`],
      ['ProgressRing', 'Subtasks complete, or plans saved while planning.', () => `<div class="l-row g-4">${S.ProgressRing(1, 3)}${S.ProgressRing(2, 5, { color: 'var(--st-planning)' })}${S.ProgressRing(3, 3)}</div>`],
      ['Clock', 'Active time from active_duration_ms; ticks locally between reads.', () => `<div class="l-row g-4 tnum">${S.Clock({ ms: it('SKILL-417').goalMs, live: true, t0: D.T0 })}${S.Clock({ ms: it('SKILL-417').subMs, live: true, t0: D.T0, secs: true })}${S.Clock({ ms: it('INF-7').goalMs, live: false })}</div>`],
      ['MetricStrip', 'Subtask, phase, goal active and subtask active above the detail.', () => S.MetricStrip([{ label: 'Subtask', value: '2', unit: '/ 3', note: '1 complete · 1 pending' }, { label: 'Phase', value: 'review', note: 'pass 2' }, { label: 'Goal active', value: S.Clock({ ms: it('SKILL-417').goalMs, live: true, t0: D.T0 }), note: 'ticking' }])],
      ['PropertyList', 'Execution and Runs and fixes panels; goal activity.', () => `<div style="max-width:320px">${S.PropertyList([{ label: 'Workflow', value: '<span class="mono">wftr-20261010-193855-mpxi</span>' }, { label: 'Model', value: '<span class="mono">claude-opus-5-5 · high</span>' }, { label: 'Liveness', value: 'live' }])}</div>`],
      ['PhasePipeline', 'The goal’s phase order: done, current (amber), waiting (orange), failed (red). Dashed steps run once per goal.', () => S.PhasePipeline([{ label: 'audit', state: 'done' }, { label: 'review', state: 'now', count: 'pass 2' }, { label: 'build', state: '' }, { label: 'validate', state: 'fail' }, { label: 'pr', state: '', goal: true }])],
      ['ReasonBlock', 'The run’s durable reason, in full, with Copy reason.', () => S.ReasonBlock({ label: 'Run reason', text: it('INF-7').reason, color: 'var(--st-blocked)' })],
      ['FindingRow', 'Review, QA and goal assessment findings; validation rows; evidence.', () => S.Box([S.FindingRow({ severity: 'Blocker', title: 'Sensitive variables are written to the plan output', meta: '<span class="mono">modules/backend/main.tf:48</span>' }), S.FindingRow({ ref: 'QA-1', title: 'Phase rows show a stale clock after the phase finishes', state: 'Fixed', stateColor: 'var(--st-done)' }), S.FindingRow({ severity: 'Major', ref: 'GA-002', title: 'A phase finished at midnight is counted in both days.', meta: '<span class="mono">BoardQuery.kt:88</span> · confirmed', state: 'Fixed', stateColor: 'var(--st-done)' })])],
      ['Checklist', 'Incident prerequisites the operator confirms.', () => S.Checklist({ act: 'noop', items: [{ id: 'a', text: 'Commit or stash local changes in design-tokens.', checked: false }] })],
    ]],
    ['Inputs', [
      ['Input', 'Filters, database path, model, gradle wrapper. Mono for paths and ids.', () => `<div class="l-col g-2" style="max-width:320px">${S.Input({ id: 'demo-in-1', icon: 'search', placeholder: 'Filter work' })}${S.Input({ id: 'demo-in-2', value: 'claude-opus-5-5', mono: true })}${S.Input({ id: 'demo-in-3', value: '../gradlew', mono: true, error: true })}</div>`],
      ['Field', 'Label, control and help.', () => `<div style="max-width:360px">${S.Field({ for: 'demo-f', label: 'Database path, blank uses runtime default', control: S.Input({ id: 'demo-f', mono: true, placeholder: 'Runtime default' }) })}</div>`],
    ]],
    ['Lists', [
      ['ListRow', 'One work item: key, status, title, summary, then step, repository, progress, agent and time. Right-click for its actions.', () => S.Box(['INF-7', 'SKILL-417', 'ph-sb-validation'].map((id, i) => { const x = it(id); return S.ListRow({ id: X.rowKey(x), status: X.statusIcon(x), title: x.title, sub: esc(x.summary), meta: [x.stalled ? S.Label({ text: 'Stalled', color: 'var(--st-blocked)', tint: true, dot: false }) : '', S.Label({ text: X.stepText(x), mono: true, dot: false })], time: X.clock(x), act: 'noop', focus: i === 1 }); }))],
      ['GroupHeader', 'Board section: Needs you, Goal runs, Standalone phases, Operations, Finished today.', () => S.Box([S.GroupHeader({ lead: S.StatusIcon('blocked'), label: 'Needs you', count: 3 }), S.GroupHeader({ lead: S.StatusIcon('active', 0.5), label: 'Operations', count: 2 })])],
      ['SubtaskRow', 'A goal subtask with status, commit, and its block reason when blocked.', () => S.Box([S.SubtaskRow({ num: '02', state: 'done', title: 'Point every module at the remote backend', meta: ['<span class="mono t-faint" style="font-size:var(--t-2xs)">7f02be9</span>'] }), S.SubtaskRow({ num: '03', state: 'blocked', current: true, title: 'Move workspace variables out of tfvars files', meta: [], body: S.Hint({ tone: 'warn', text: 'Review left 2 Blocker findings after 3 fix passes' }) })])],
      ['TreeRow', 'Spec store rows: bundles, subtask specs, files, the done folder.', () => S.Box([S.TreeRow({ level: 0, expandable: true, open: true, lead: S.StatusIcon('active', 0.5), id: 'SKILL-442', title: 'Cross-criterion audit, test-value probes', meta: ['<span class="tnum t-faint" style="font-size:var(--t-xs)">2/8</span>'], act: 'noop', rowIdx: -1 }), S.TreeRow({ level: 1, file: true, lead: S.Icon('fileText', 'sm'), title: 'spec.md', meta: [], act: 'noop', rowIdx: -1 }), S.TreeRow({ level: 0, expandable: true, lead: S.Icon('folder', 'sm'), title: 'done', meta: ['<span class="tnum t-faint" style="font-size:var(--t-xs)">41</span>'], act: 'noop', rowIdx: -1 })])],
    ]],
    ['Navigation and panes', [
      ['Sidebar', 'Work and History, the board sections, then the Specs and Terminal toggles and Settings.', () => `<div style="height:360px;display:flex;border:1px solid var(--border-subtle);border-radius:var(--r-md);overflow:hidden;width:240px">${S.Sidebar({ top: S.WorkspaceSwitcher({ name: 'Skill Bill', actions: [] }), items: [S.SidebarItem({ icon: 'target', label: 'Work', count: 11, on: true, act: 'noop' }), S.SidebarItem({ icon: 'history', label: 'History', act: 'noop' }), S.SidebarSection({ label: 'Board', items: [S.SidebarItem({ lead: S.StatusIcon('blocked'), label: 'Needs you', attention: 3, act: 'noop' }), S.SidebarItem({ lead: S.StatusIcon('active', 0.5), label: 'Goal runs', count: 4, act: 'noop' })] })], footer: [S.SidebarItem({ icon: 'fileText', label: 'Specs', act: 'noop' }), S.SidebarItem({ icon: 'terminal', label: 'Terminal', act: 'noop', on: true })] })}</div>`],
      ['SidebarItem', 'Nav row with a count or an attention badge.', () => `<div style="width:220px" class="l-col g-1">${S.SidebarItem({ lead: S.StatusIcon('blocked'), label: 'Needs you', attention: 3, act: 'noop' })}${S.SidebarItem({ icon: 'target', label: 'Work', count: 11, on: true, act: 'noop' })}</div>`],
      ['SidebarSection', 'Collapsible group label.', () => `<div style="width:220px">${S.SidebarSection({ label: 'Board', items: [S.SidebarItem({ lead: S.StatusIcon('done'), label: 'Finished today', count: 4, act: 'noop' })] })}</div>`],
      ['WorkspaceSwitcher', 'Brand row at the top of the sidebar.', () => `<div style="width:220px">${S.WorkspaceSwitcher({ name: 'Skill Bill', actions: [S.IconButton({ icon: 'search', label: 'Command menu', kbd: '⌘ K', act: 'noop', size: 'sm' })] })}</div>`],
      ['ViewHeader', 'Breadcrumbs, filter, then Refresh and the pane toggles. 44px everywhere.', () => S.Box([S.ViewHeader({ crumbs: [{ label: 'Work', act: 'noop' }, { label: 'Needs you', act: 'noop' }, { label: 'INF-7' }], actions: [S.Pager({ index: 0, total: 11 }), S.IconButton({ icon: 'terminal', label: 'Terminal', act: 'noop' })] })])],
      ['Pager', 'Previous and next item from a detail view (K and J).', () => S.Pager({ index: 0, total: 11 })],
      ['SidePane', 'Right-hand pane beside any view. Holds the Specs browser; it never changes what the main panel shows.', () => `<div style="height:200px;display:flex;border:1px solid var(--border-subtle);border-radius:var(--r-md);overflow:hidden">${S.SidePane({ title: 'Specs', head: '<button type="button" class="sb-pick">skill-bill</button>', actions: [S.IconButton({ icon: 'x', label: 'Close specs pane', act: 'noop', size: 'sm' })], body: `<div class="sb-toolbar">${S.Hint({ text: 'Select a spec folder to start, pause or cancel it.' })}</div>` })}</div>`],
      ['TerminalPane', 'Bottom terminal in the main window. One tab per shell, named after its project and numbered only while names repeat. Double-click to rename; drag the top edge to resize.', () => `<div style="display:flex;flex-direction:column;border:1px solid var(--border-subtle);border-radius:var(--r-md);overflow:hidden">${S.TerminalPane({ tabs: [{ id: 1, label: 'skill-bill' }, { id: 2, label: 'skill-bill 2' }, { id: 3, label: 'infra-terraform', customName: 'state migration' }], activeId: 1, height: 150, body: '<span class="m">~/IdeaProjects/skill-bill</span>\n<span class="p">➜</span>  <span class="d">skill-bill</span> <span class="b">(main)</span> <span class="cur"></span>' })}</div>`],
    ]],
    ['Overlays', [
      ['Menu', 'Dropdowns and the right-click menu, with checks and shortcuts.', () => `<div class="doc-stage">${S.Popover({ x: 0, y: 0, body: S.Menu({ items: [{ icon: 'external', label: 'Open', kbd: '↵', act: 'noop' }, { icon: 'terminal', label: 'Open terminal here', act: 'noop', hl: true }, '-', { icon: 'pause', label: 'Pause after subtask', kbd: 'P', act: 'noop' }, { icon: 'stop', label: 'Pause', kbd: '⇧ P', danger: true, act: 'noop' }] }) })}</div>`],
      ['MenuItem', 'One menu or command row.', () => `<div style="width:280px">${S.MenuItem({ lead: S.Avatar({ name: 'codex', initials: 'Cx', color: D.AGENTS.codex.color, size: 'sm' }), label: 'codex', checked: true, act: 'noop' })}</div>`],
      ['Popover', 'The positioned layer that holds a Menu; agent choice, project picker, Claude directory.', () => `<div class="doc-stage">${S.Popover({ x: 0, y: 0, body: S.Menu({ items: [{ head: 'Run on' }, ...D.AGENT_CHOICES.map((a) => ({ lead: S.Avatar({ name: a, initials: D.AGENTS[a].initials, color: D.AGENTS[a].color, size: 'sm' }), label: a, act: 'noop', checked: a === 'codex' }))] }) })}</div>`],
      ['CommandPalette', '⌘K: the current item’s actions, navigation, panes, every work item, config.', () => `<div class="doc-stage">${S.CommandPalette({ context: S.Label({ text: 'INF-7', dot: false }), list: S.CommandList([{ head: 'INF-7', items: [{ id: 'a', icon: 'play', label: 'Resume', kbd: 'R' }, { id: 'b', icon: 'copy', label: 'Copy reason' }] }, { head: 'Panes', items: [{ id: 'c', icon: 'terminal', label: 'Open terminal', kbd: '⌃ `' }] }], 0) })}</div>`],
      ['CommandList', 'Grouped, filtered command rows.', () => `<div style="max-width:420px">${S.CommandList([{ head: 'Work', items: [{ id: 'x', lead: S.StatusIcon('blocked'), label: 'INF-7', html: '<span class="t-faint" style="margin-right:8px">INF-7</span>Migrate state backend', end: 'infra-terraform' }] }], 0)}</div>`],
      ['Dialog', 'Recovery preview (Apply and resume) and Purge.', () => `<div class="doc-stage">${S.Dialog({ title: 'Re-record goldens and resume', body: S.PropertyList([{ label: 'Effects', value: 'Runs validate_fix on subtask 6, then resumes the goal.' }, { label: 'Phase', value: '<span class="mono">validate</span>' }]), actions: [S.Button({ label: 'Cancel', variant: 'ghost', act: 'noop' }), S.Button({ label: 'Apply and resume', variant: 'primary', act: 'noop' })] })}</div>`],
      ['Ledger', 'Deleted and kept, in the Purge dialog.', () => S.Ledger({ leftTitle: 'Deleted', left: ['Goal and subtask run history', 'Checkpoint refs'], rightTitle: 'Kept', right: ['Every spec file in the folder', 'Your branch and its commits'] })],
      ['ShortcutGrid', 'The sheet behind “?”.', () => S.ShortcutGrid([['Command menu', '⌘ K'], ['Toggle terminal', '⌃ `'], ['Pause after subtask', 'P'], ['Resume', 'R']])],
      ['Toast', 'Feedback after a control request, worded like the app’s messages. Also renders OS notifications.', () => `<div class="l-col g-2">${S.Toast({ state: 'paused', title: 'Pause recorded', body: 'The goal pauses at its next checkpoint.' })}${S.Toast({ system: true, app: 'Skill Bill', when: 'now', title: 'INF-7 is blocked', body: 'Review left 2 Blocker findings on subtask 3' })}</div>`],
      ['ToastStack', 'Bottom-right stack for live toasts.', () => `<div class="t-faint" style="font-size:var(--t-sm)">Positioned layer; trigger any control in the prototype.</div>`],
    ]],
    ['Feedback', [
      ['Callout', 'Incident state, inline cancel confirmation.', () => `<div class="l-col g-2">${S.Callout({ tone: 'warn', title: 'Needs your action', body: 'Read the two Blocker findings, then change the subtask spec or resume.' })}${S.Callout({ tone: 'error', body: 'Cancel SKILL-442? The run stops and the goal is marked canceled. Its branch and commits stay.', actions: [S.Button({ label: 'Keep running', size: 'sm', act: 'noop' }), S.Button({ label: 'Cancel goal', size: 'sm', variant: 'danger-solid', act: 'noop' })] })}</div>`],
      ['Hint', 'One line of status or guidance.', () => `<div class="l-col g-2">${S.Hint({ tone: 'info', text: 'Pause recorded. The goal stops after its current subtask.' })}${S.Hint({ text: 'Actions run a whole spec folder, not one subtask. Select SKILL-442 to control it.' })}${S.Hint({ tone: 'error', text: 'The path is only spaces. Clear it to use the default.' })}</div>`],
      ['EmptyState', 'Loading, problem and empty states, with the app’s wording.', () => `<div class="l-row g-4 l-wrap l-top">${S.Box([S.EmptyState({ icon: 'alert', title: 'Couldn’t load your work', body: 'Check the database path in Settings, then try refreshing.', actions: [S.Button({ label: 'Retry', act: 'noop' })] })])}${S.Box([S.EmptyState({ icon: 'warn', title: 'Skill Bill runtime unavailable', body: 'The installed Skill Bill runtime is not the build this app was packaged with. Install a matched runtime and desktop pair with <span class="mono">./install.sh --from-source</span>, then retry.', actions: [S.Button({ label: 'Retry', variant: 'primary', act: 'noop' })] })])}${S.Box([S.EmptyState({ icon: 'fileText', title: 'No specs yet', body: 'Plan a feature with <span class="mono">/skill-bill phase:plan</span> and its bundle shows up here.' })])}</div>`],
    ]],
    ['Detail', [
      ['DocHeader', 'Chips, title, repository location, run controls.', () => S.DocHeader({ eyebrow: [S.Label({ text: 'Blocked', color: 'var(--st-blocked)', tint: true, dot: false }), S.IdTag('INF-7'), S.Label({ text: 'goal', dot: false })], title: 'Migrate state backend to a remote workspace', sub: S.Button({ label: '~/dev/infra-terraform', icon: 'folder', size: 'sm', variant: 'ghost', act: 'noop' }) })],
      ['Section', 'Titled block with a count.', () => S.Section({ title: 'Validation', count: '2', body: '<div class="t-faint" style="font-size:var(--t-sm)">Section body</div>' })],
      ['Box', 'Hairline container for rows.', () => S.Box([S.FindingRow({ ref: 'Subtask 1', title: 'passed · 4 checks', state: 'passed', stateColor: 'var(--st-done)' })])],
      ['PaneSection', 'Titled group in the details pane.', () => `<div style="max-width:280px">${S.PaneSection({ title: 'Runs and fixes', body: S.PropertyList([{ label: 'Phase runs', value: '<span class="mono">implement 1 · review 2</span>' }]) })}</div>`],
    ]],
    ['Settings', [
      ['SettingsHeader', 'Page title and what it controls.', () => S.SettingsHeader({ title: 'Compaction', body: 'Compacts an agent’s context before it fills.' })],
      ['SettingsGroup', 'Bordered group of rows.', () => S.SettingsGroup({ title: 'History retention', rows: [S.SettingRow({ label: 'Keep finished work for', control: S.Segmented({ act: 'noop', value: '24h', items: [{ id: '6h', label: '6h' }, { id: '24h', label: '24h' }, { id: '7d', label: '7d' }] }) })] })],
      ['SettingRow', 'Label and config key on the left, control on the right.', () => S.SettingsGroup({ rows: [S.SettingRow({ label: 'Batch size', desc: 'telemetry.batch_size · default 50', control: '<span class="mono t-muted">50</span>' })] })],
      ['TierBadge', 'R reasoning, I implementation.', () => `<div class="l-row g-2">${S.TierBadge('reasoning')}${S.TierBadge('implementation')}</div>`],
      ['ConfigRow', 'A phase in execution_matrix; expands into the editor.', () => S.SettingsGroup({ rows: [S.ConfigRow({ phase: 'review', tier: 'reasoning', value: 'claude-opus-5-5 · xhigh', override: true, act: 'noop', tags: [S.Label({ text: 'override', color: 'var(--accent)', tint: true, dot: false })] }), S.ConfigRow({ phase: 'implement', tier: 'implementation', value: 'claude-sonnet-5-5 · high', act: 'noop', tags: [] })] })],
    ]],
    ['Surfaces', [
      ['TrayPopover', 'Tray popover: Running, Needs you and Done 24h, then Goals, Phases and Operations tabs; footer Open Skill Bill, Refresh, Settings, Quit.', () => `<div class="l-row g-6 l-wrap l-top">${tray('goals')}${tray('phases')}${tray('operations')}</div>`],
    ]],
  ];

  const SCREEN_ORDER = ['Work', 'Detail', 'History', 'Specs pane', 'Terminal', 'Settings · General', 'Settings · Notifications', 'Settings · Models', 'Settings · Compaction', 'Settings · Project', 'Settings · Telemetry and agents', 'Command menu', 'Menus', 'Dialogs', 'Toasts', 'Tooltip', 'Tray', 'Notifications'];
  function usedIn(name) {
    const set = S.usage[name];
    if (!set || !set.size) return `<span class="t-faint" style="font-size:var(--t-xs)">Not used by a screen yet</span>`;
    return [...set].sort((a, b) => SCREEN_ORDER.indexOf(a) - SCREEN_ORDER.indexOf(b)).map((s) => S.Label({ text: s, dot: false })).join('');
  }

  function components() {
    const all = REG.flatMap(([, items]) => items.map((i) => i[0]));
    const index = S.Sidebar({ top: `<div class="doc-index-hd">${all.length} components</div>`, items: REG.map(([group, items]) => S.SidebarSection({ label: group, items: items.map(([name]) => S.SidebarItem({ label: name, child: true, act: 'anchor', arg: 'c-' + name })) })), footer: [] });
    const cards = REG.map(([group, items]) => `<h2 class="doc-h2">${esc(group)}</h2>` + items.map(([name, desc, demo]) => `
      <article class="doc-card" id="c-${name}">
        <header class="doc-card-hd"><div class="l-row g-2"><code class="doc-name">SB.${esc(name)}</code></div><p>${esc(desc)}</p><div class="l-row g-1 l-wrap"><span class="t-faint" style="font-size:var(--t-xs);margin-right:4px">Used in</span>${usedIn(name)}</div></header>
        <div class="doc-stage-wrap">${demo()}</div>
      </article>`).join('')).join('');
    return `<div class="doc-split">${index}<div class="doc-main" data-scroll="lib"><div class="doc-inner">
      <div class="doc-intro"><h1>Component library</h1><p>Every screen in the prototype is assembled from these ${all.length} functions in <span class="mono">ds/components.js</span> and the tokens in <span class="mono">ds/tokens.css</span>. Screens add no styling of their own. The “Used in” labels are computed: the prototype renders every screen at load, records each component it calls, and lists those screens here.</p></div>
      ${cards}</div></div></div>`;
  }

  /* ---------- foundations ---------- */
  const TOKENS = [
    ['Surfaces', ['--bg-frame', '--bg-panel', '--bg-raised', '--bg-overlay', '--bg-inset']],
    ['Text', ['--fg', '--fg-2', '--fg-muted', '--fg-faint']],
    ['Lines and states', ['--border-subtle', '--border', '--border-strong', '--bg-hover', '--bg-active', '--bg-selected']],
    ['Accent', ['--accent', '--accent-hover', '--accent-text', '--accent-tint']],
    ['Lifecycle', ['--st-active', '--st-planning', '--st-blocked', '--st-failed', '--st-done', '--st-paused', '--st-pending']],
    ['Data (labels)', ['--data-1', '--data-2', '--data-3', '--data-4', '--data-5', '--data-6']],
  ];
  /* Where each feature in the prototype comes from */
  const SOURCES = [
    ['Work board: Needs you, Goal runs, Standalone phases, Operations, Finished today; text filter; Refresh', 'desktop-tray feature/work · WorkContract, WorkContent'],
    ['Detail: chips, repository location, run reason, metrics, subtasks, goal finish, phase pipeline, dependencies, activity, Execution, Runs and fixes, Validation', 'feature/detail · DetailContent, DetailPresentation'],
    ['Pause after subtask, Pause, Resume / Run again with Choose agent, Cancel', 'feature/detail · RunActionButtons, DetailContract (ControlKind)'],
    ['Incident: diagnosis, cause, next action, prerequisites, evidence, report, Recover → Apply and resume', 'feature/detail · IncidentPanel, RunActionButtons; WorkModels Incident*'],
    ['Operator decision for a paused subtask: retry fix (with instructions), accept and advance, abandon subtask', 'runtime-engine · GoalOperatorDecisionService (not in desktop-tray yet)'],
    ['Phase results (review passes, audit), Goal QA, Goal assessment, QA report, execution history, spec bundle', 'feature/detail · PhaseResults, QaReport, PlanReport, SpecBundleFileList'],
    ['History: All / Goals / Phases / Operations, filter, “Finished within 24h”', 'feature/history · HistoryContract (HistoryFilter)'],
    ['Specs pane: project picker, filter, All / Active / Pending / Done, tree, collapse all, Start / Pause / Cancel / Purge, agent choice, reveal', 'feature/specs · SpecsContract, SpecRunToolbar, PurgeConfirmationDialog'],
    ['Terminal: bottom pane, tabs named per project, new / rename / close, resize', 'feature/terminal · TerminalContract'],
    ['Settings: database path, Claude config directory, retention, notifications (6), start at login', 'feature/settings · SettingsContract; AppSettings'],
    ['Tray: Running / Needs you / Done 24h, Goals / Phases / Operations, Open Skill Bill, Refresh, Settings, Quit', 'feature/work · TrayContract, TrayPopoverContent'],
    ['Models: execution_matrix tiers, phase_tiers, per-agent phase overrides; junie takes no model', 'runtime-domain · ExecutionMatrixModels; MODEL_DIRECTIVE_CAPABLE_AGENTS'],
    ['Compaction: enabled, window_tokens 400,000, trigger_pct 70, 200,000 floor, per-phase', 'runtime-domain · CompactionSettingsModels'],
    ['Project: spec_type, validation_gate.gradle_wrapper, review_context_budget, .skill-bill/phases (16 KiB)', 'runtime-domain · RepoLocalConfigModels, ValidationGateRepoConfigModels; PhaseProjectInstructions'],
    ['Telemetry: level off / anonymous / full, proxy_url, batch_size 50', 'runtime-domain · TelemetryConfigRules, TelemetryConstants'],
  ];
  function foundations() {
    const sw = TOKENS.map(([group, list]) => `<div class="l-col g-2"><h3 class="doc-h3">${esc(group)}</h3><div class="doc-swatches">${list.map((t) => `<div class="doc-swatch"><i style="background:var(${t})"></i><code>${t}</code><span data-token="${t}"></span></div>`).join('')}</div></div>`).join('');
    const type = [['--t-2xl', '24 / 600', 'Detail title', 'Migrate state backend to a remote workspace'], ['--t-xl', '20 / 600', 'Settings title', 'Models'], ['--t-lg', '16 / 400', 'Command input', 'Type a command or search…'], ['--t-md', '14 / 500', 'Dialog title', 'Purge SKILL-438?'], ['--t-base', '13 / 400–500', 'Body, rows, nav', 'Review left 2 Blocker findings on subtask 3'], ['--t-sm', '12 / 400–500', 'Buttons, meta', 'review · pass 2 · 1h 36m'], ['--t-xs', '11.5 / 500', 'Labels, captions', 'Finished within 24h'], ['--t-2xs', '10.5 mono', 'Steps, shas, kbd', 'monitor_fix · a41c9e2']];
    const typeRows = type.map(([tok, spec, role, sample]) => `<div class="doc-type"><code>${tok}</code><span class="t-faint">${spec}</span><span class="t-muted">${role}</span><span class="doc-type-s" style="font-size:var(${tok});${tok === '--t-2xs' ? 'font-family:var(--font-mono)' : ''}${['--t-2xl', '--t-xl'].includes(tok) ? ';font-weight:600;letter-spacing:-.01em' : ''}">${esc(sample)}</span></div>`).join('');
    const space = [1, 2, 3, 4, 5, 6, 8, 10].map((n) => `<div class="doc-space"><code>--sp-${n}</code><i style="width:var(--sp-${n})"></i><span class="t-faint">${n * 4}px</span></div>`).join('');
    const radii = [['--r-xs', 'Kbd, steps'], ['--r-sm', 'Buttons, inputs, tabs'], ['--r-md', 'Menus, boxes, toasts'], ['--r-lg', 'Panel, dialogs, tray'], ['--r-full', 'Labels, avatars']].map(([t, use]) => `<div class="doc-radius"><i style="border-radius:var(${t})"></i><code>${t}</code><span class="t-faint">${use}</span></div>`).join('');
    const status = [['active', 'Ring with a pie · amber', 'ACTIVE; the pie fills with subtask progress'], ['planning', 'Dashed ring · blue', 'ACTIVE while plans are being saved'], ['stale', 'Dotted ring', 'ACTIVE but stalled: no progress past the runtime’s threshold'], ['paused', 'Ring with bars · slate', 'PAUSED'], ['blocked', 'Filled · orange · !', 'BLOCKED; needs you'], ['failed', 'Filled · red · ×', 'FAILED'], ['done', 'Filled · green · ✓', 'TERMINAL, or a complete subtask'], ['canceled', 'Filled · grey · ×', 'CANCELED'], ['pending', 'Empty ring', 'A pending subtask'], ['no_change', 'Ring · green · ✓', 'Subtask completed_no_change'], ['skipped', 'Dashed ring with slash', 'Subtask skipped']].map(([s, shape, meaning]) => `<div class="doc-status">${S.StatusIcon(s, 0.5, { lg: true })}<code>${s}</code><span class="t-2">${shape}</span><span class="t-muted">${meaning}</span></div>`).join('');
    const icons = S.ICONS.map((n) => `<div class="doc-icon">${S.Icon(n)}<code>${n}</code></div>`).join('');
    const principles = [
      ['Only what Skill Bill does', 'Every control maps to a desktop-app action or a runtime config key. Nothing is drawn for features that don’t exist.'],
      ['Keyboard first', 'Each action shows its shortcut where it lives, and ⌘K reaches all of them. Shortcuts call the same real actions as the buttons.'],
      ['Dense and quiet', '13px type, 36px rows, hairline borders. Amber marks focus, the current step and the one next action.'],
      ['Shape carries state', 'Lifecycle icons differ by shape first, so the board reads in grayscale.'],
      ['List, detail, panes', 'The board, one item’s detail with its Execution pane, the Specs pane beside it, the terminal below.'],
      ['Feedback says exactly what happened', 'Toasts use the app’s own wording: a pause is “recorded”, a stop is “confirmed”, never stronger than the runtime reports.'],
    ].map(([t, b]) => `<div class="doc-principle"><b>${esc(t)}</b><p>${esc(b)}</p></div>`).join('');
    const sources = SOURCES.map(([f, src]) => `<div class="doc-status" style="grid-template-columns:minmax(0,1.3fr) minmax(0,1fr)"><span class="t-2">${esc(f)}</span><code style="white-space:normal">${esc(src)}</code></div>`).join('');
    return `<div class="doc-main" data-scroll="found"><div class="doc-inner">
      <div class="doc-intro"><h1>Foundations</h1><p>Skill Bill’s colors in Linear’s structure. The near-black surfaces and amber accent come from the existing designs; type, density, the status vocabulary and the interaction model follow Linear. The feature set is the desktop app’s and the runtime’s, nothing more.</p></div>
      <h2 class="doc-h2">Principles</h2><div class="doc-principles">${principles}</div>
      <h2 class="doc-h2">Source of truth</h2><p class="doc-p">What the prototype shows and where it comes from in this repository.</p><div class="doc-card"><div class="doc-statuses">${sources}</div></div>
      <h2 class="doc-h2">Color</h2><div class="l-col g-6">${sw}</div>
      <h2 class="doc-h2">Status vocabulary</h2><div class="doc-card"><div class="doc-statuses">${status}</div></div>
      <h2 class="doc-h2">Type</h2><p class="doc-p">Inter with Linear’s character variants (cv11, ss01, ss03); JetBrains Mono for runtime ids, phase names, shas, paths and config keys.</p><div class="doc-card">${typeRows}</div>
      <h2 class="doc-h2">Space and radius</h2><div class="doc-grid2"><div class="doc-card">${space}</div><div class="doc-card">${radii}</div></div>
      <h2 class="doc-h2">Elevation</h2><div class="doc-grid3"><div class="doc-elev" style="background:var(--bg-panel);border:1px solid var(--border-subtle)">Panel<span>flat, hairline</span></div><div class="doc-elev" style="background:var(--bg-overlay);border:1px solid var(--border-strong);box-shadow:var(--shadow-pop)">Popover<span>--shadow-pop</span></div><div class="doc-elev" style="background:var(--bg-overlay);border:1px solid var(--border-strong);box-shadow:var(--shadow-modal)">Dialog<span>--shadow-modal</span></div></div>
      <h2 class="doc-h2">Icons</h2><div class="doc-card"><div class="doc-icons">${icons}</div></div>
      <h2 class="doc-h2">Layout primitives</h2><p class="doc-p">Screens lay out with <span class="mono">.l-row</span>, <span class="mono">.l-col</span>, <span class="mono">.l-grow</span>, <span class="mono">.l-wrap</span> and the gap scale <span class="mono">.g-1</span>…<span class="mono">.g-8</span>. Frame: 236px sidebar, an inset panel with a 12px radius, a 272px details pane or a 400px Specs pane on the right, the terminal docked at the bottom. Below 1080px the details pane folds away; below 760px the sidebar becomes a drawer and the Specs pane covers the panel.</p>
    </div></div>`;
  }

  window.Library = { components, foundations, tray, osNotifications, REG };
})();
