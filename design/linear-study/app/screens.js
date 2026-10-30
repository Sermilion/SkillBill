/* Screens: composed only from SB components (ds/components.js) and the layout primitives.
 * Feature scope follows the desktop app (desktop-tray feature/*) and the runtime config models. */
(function () {
  'use strict';
  const S = window.SB, D = window.DATA;
  const esc = S.esc;
  const repoOf = (n) => D.REPOS[n] || { name: n, path: '~', color: 'var(--data-6)', web: '' };
  const agentOf = (n) => D.AGENTS[n];
  let rows = [];

  /* ---------- vocabulary ---------- */
  const LIFE = {
    active: ['Active', 'var(--st-active)'], paused: ['Paused', 'var(--st-paused)'], blocked: ['Blocked', 'var(--st-blocked)'],
    failed: ['Failed', 'var(--st-failed)'], terminal: ['Completed', 'var(--st-done)'], canceled: ['Canceled', 'var(--st-pending)'], idle: ['Idle', 'var(--st-pending)'],
  };
  const SECTIONS = [
    ['needs', 'Needs you', 'blocked'], ['goals', 'Goal runs', 'active'], ['phases', 'Standalone phases', 'active'],
    ['operations', 'Operations', 'active'], ['finished', 'Finished today', 'done'],
  ];
  const SUB_STATE = { complete: 'done', completed_no_change: 'no_change', in_progress: 'active', pending: 'pending', blocked: 'blocked', skipped: 'skipped', paused: 'paused', planning: 'planning' };
  const kindWord = (it) => (it.kind === 'operation' ? 'operation' : it.kind === 'goal' ? 'goal' : 'phase');

  function iconState(it) {
    if (it.lifecycle === 'terminal') return 'done';
    if (it.lifecycle === 'active' && it.planning) return 'planning';
    if (it.lifecycle === 'active' && it.stalled) return 'stale';
    return it.lifecycle === 'idle' ? 'pending' : it.lifecycle;
  }
  const frac = (it) => (it.planning ? it.planned / it.total : it.total ? it.done / it.total : 0.5);
  const statusIcon = (it, lg) => S.StatusIcon(iconState(it), frac(it), { lg });
  const clock = (it) => S.Clock({ ms: it.goalMs, live: it.live, t0: D.T0, secs: it.kind !== 'goal' });
  const subClock = (it) => S.Clock({ ms: it.subMs, live: it.live, t0: D.T0, secs: true });
  const repoLabel = (n) => S.Label({ text: n, color: repoOf(n).color });
  const agentAvatar = (it) => (it.agent ? S.Avatar({ name: it.agent, initials: agentOf(it.agent).initials, color: agentOf(it.agent).color, tip: `${it.agent}${it.model ? ' · ' + it.model : ''}${it.effort ? ' · ' + it.effort : ''}` }) : '');
  const lifeLabel = (it) => S.Label({ text: LIFE[it.lifecycle][0], color: LIFE[it.lifecycle][1], tint: true, dot: false });
  const stepText = (it) => (it.planning ? `${it.planned}/${it.total} plans saved` : it.step + (it.exec ? ' · ' + it.exec : ''));
  const rowKey = (it) => (it.kind === 'goal' ? it.id : it.kind === 'operation' ? it.op : it.phase);
  const itemBy = (id) => D.items.find((x) => x.id === id);
  function lookup(id) {
    const it = itemBy(id);
    if (it) return it;
    const h = D.history.find((x) => x.id === id);
    if (!h) return null;
    return { id: h.id, kind: h.kind, phase: h.label, op: h.label, repo: h.repo, lifecycle: h.lifecycle, section: 'history', title: h.title, step: h.label || 'finished', summary: LIFE[h.lifecycle][0], goalMs: h.ms, live: false, fresh: true, finishedAt: h.at, started: '—', updated: h.at, runOptions: { canLaunch: h.lifecycle === 'failed' || h.lifecycle === 'canceled', canCancel: false, launchLabel: 'Run again', agentId: null }, subtasks: [], steps: [] };
  }

  /* ---------- run controls (feature/detail RunActionButtons) ---------- */
  const resumable = (it) => it.lifecycle !== 'active' && it.lifecycle !== 'terminal';
  const controllable = (it) => it.kind === 'goal' && it.lifecycle === 'active';
  const pauseEligible = (it) => controllable(it) && !it.pauseRequested;
  function recoveryOffered(st, it) {
    const inc = it.incident;
    if (!inc || inc.recovery !== 'available' || st.recovered.has(it.id)) return false;
    return (inc.prerequisites || []).every((p) => st.prereqs.has(it.id + ':' + p.id));
  }
  /* A failed detail refresh keeps the older data on screen and turns every control off until a refresh succeeds. */
  function runActions(st, it) {
    const ro = it.refreshFailed ? {} : (it.runOptions || {});
    const agent = st.chosenAgent[it.id] || ro.agentId;
    const out = [];
    if (it.kind === 'goal') {
      out.push(S.Button({ label: it.pauseRequested ? 'Pause requested' : 'Pause after subtask', icon: 'pause', act: 'pause-soft', arg: it.id, disabled: it.refreshFailed || !pauseEligible(it), kbd: !it.refreshFailed && pauseEligible(it) ? 'P' : null }));
      out.push(S.Button({ label: 'Pause', icon: 'stop', variant: 'danger', act: 'pause-now', arg: it.id, disabled: it.refreshFailed || !controllable(it), kbd: !it.refreshFailed && controllable(it) ? '⇧ P' : null, tip: 'Stops the goal runner now' }));
    }
    if (resumable(it)) {
      out.push(S.Button({ label: (it.runOptions || {}).launchLabel || 'Resume', icon: 'play', variant: ro.canLaunch ? 'primary' : 'secondary', act: 'launch', arg: it.id, disabled: !ro.canLaunch, kbd: ro.canLaunch ? 'R' : null }));
      out.push(S.Button({ label: agent ? `Agent: ${agent}` : 'Choose agent', icon: 'bot', act: 'agent-menu', arg: it.id, disabled: !ro.canLaunch }));
    }
    out.push(S.Button({ label: 'Cancel', icon: 'x', variant: 'danger', act: 'cancel', arg: it.id, disabled: !ro.canCancel }));
    if (it.incident && it.incident.recovery === 'available' && !st.recovered.has(it.id)) out.push(S.Button({ label: 'Recover', icon: 'refresh', act: 'recover', arg: it.id, disabled: it.refreshFailed || !recoveryOffered(st, it), tip: recoveryOffered(st, it) ? null : 'Confirm the prerequisites first' }));
    return `<div class="l-col g-2"><div class="l-row g-2 l-wrap">${out.join('')}</div>${ro.canLaunch && resumable(it) ? `<span class="t-faint" style="font-size:var(--t-xs)">Starts the installed agent harness</span>` : ''}${it.refreshFailed ? S.Hint({ tone: 'warn', text: 'Refresh the detail before using run controls.' }) : ''}</div>`;
  }

  /* ---------- shell ---------- */
  function sidebar(st) {
    const count = (sec) => D.items.filter((x) => x.section === sec).length;
    const live = D.items.filter((x) => x.section !== 'finished').length;
    const navOn = (v, sec) => (st.view === v && (sec === undefined || st.section === sec)) || (v === 'work' && sec === undefined && st.view === 'detail');
    return S.Sidebar({
      top: S.WorkspaceSwitcher({ name: 'Skill Bill', actions: [S.IconButton({ icon: 'search', label: 'Command menu', kbd: '⌘ K', act: 'cmdk', size: 'sm' })] }),
      items: [
        S.SidebarItem({ icon: 'target', label: 'Work', count: live, on: navOn('work', null) || st.view === 'detail', act: 'nav', arg: 'work', tip: 'Work', kbd: 'G W' }),
        S.SidebarItem({ icon: 'history', label: 'History', on: st.view === 'history', act: 'nav', arg: 'history', tip: 'History', kbd: 'G H' }),
        S.SidebarSection({ label: 'Board', act: 'sec-toggle', arg: 'board', closed: st.closed.has('board'), items: SECTIONS.map(([id, label, state]) => S.SidebarItem({
          lead: S.StatusIcon(state, 0.5), label, child: false, act: 'section', arg: id, on: st.view === 'work' && st.section === id,
          attention: id === 'needs' && count(id) ? count(id) : null, count: id === 'needs' ? null : count(id),
        })) }),
      ],
      footer: [
        S.SidebarItem({ icon: 'fileText', label: 'Specs', act: 'specs-toggle', on: st.specsOpen, tip: 'Specs pane', kbd: 'G S' }),
        S.SidebarItem({ icon: 'terminal', label: 'Terminal', act: 'term-toggle', on: st.termOpen, tip: 'Terminal', kbd: '⌃ `' }),
        S.SidebarItem({ icon: 'sliders', label: 'Settings', act: 'nav', arg: 'settings', tip: 'Settings', kbd: 'G ,' }),
        S.SidebarItem({ icon: 'keyboard', label: 'Keyboard shortcuts', act: 'shortcuts', tip: 'Keyboard shortcuts', kbd: '?' }),
      ],
    });
  }
  function paneToggles(st) {
    return [
      S.IconButton({ icon: 'refresh', label: 'Refresh', act: 'refresh' }),
      S.IconButton({ icon: 'fileText', label: st.specsOpen ? 'Close specs pane' : 'Specs', kbd: 'G S', act: 'specs-toggle', on: st.specsOpen }),
      S.IconButton({ icon: 'terminal', label: st.termOpen ? 'Close terminal' : 'Terminal', kbd: '⌃ `', act: 'term-toggle', on: st.termOpen }),
    ];
  }
  function workArea(st, header, body) {
    return `<div class="sb-work"><div class="sb-work-main">${header}<div class="sb-work-body">${body}</div>${st.termOpen ? terminal(st) : ''}</div>${st.specsOpen ? specsPane(st) : ''}</div>`;
  }
  function shell(st, main) {
    return `<div class="sb-app${st.sideOpen ? ' side-open' : ''}">${st.view === 'settings' ? settingsNav(st) : sidebar(st)}<main class="sb-main">${main}</main></div>`;
  }

  /* ---------- Work board (feature/work) ---------- */
  function matches(it, q) {
    if (!q) return true;
    const hay = [it.id, it.issue, it.title, it.repo, it.branch, it.phase, it.op, it.summary].filter(Boolean).join(' ').toLowerCase();
    return q.toLowerCase().split(/\s+/).every((t) => hay.includes(t));
  }
  function workRow(st, it, i) {
    const meta = [];
    if (it.stalled) meta.push(S.Label({ text: 'Stalled', color: 'var(--st-blocked)', tint: true, dot: false, tip: `No progress for ${it.stalled}` }));
    if (it.pauseRequested) meta.push(S.Label({ text: 'Pause requested', color: 'var(--st-paused)', dot: true }));
    if (it.lifecycle !== 'terminal') meta.push(S.Label({ text: stepText(it), mono: true, dot: false, tip: it.kind === 'goal' ? 'Current phase' : 'Current step' }));
    meta.push(`<span class="hide-sm">${repoLabel(it.repo)}</span>`);
    if (it.kind === 'goal' && it.total) meta.push(it.planning ? S.ProgressRing(it.planned, it.total, { color: 'var(--st-planning)', tip: 'Plans saved' }) : S.ProgressRing(it.done, it.total, { tip: 'Subtasks complete' }));
    meta.push(agentAvatar(it));
    return S.ListRow({ id: rowKey(it), status: statusIcon(it), title: it.kind === 'goal' ? it.title : it.title, sub: esc(it.summary), meta, time: it.finishedAt ? esc(it.finishedAt) : clock(it), act: 'open', arg: it.id, ctx: 'item:' + it.id, focus: st.focus === i, rowIdx: i, faded: it.section === 'finished' });
  }
  function work(st) {
    rows = [];
    const sections = st.section ? SECTIONS.filter((s) => s[0] === st.section) : SECTIONS;
    let any = false;
    const body = sections.map(([id, label, state]) => {
      const list = D.items.filter((x) => x.section === id && matches(x, st.filter));
      if (!list.length && !st.section) return '';
      any = any || list.length > 0;
      const empty = !list.length ? S.EmptyState({ icon: 'checkCircle', title: st.filter ? 'No matching work' : (id === 'finished' ? 'Nothing finished today' : `No ${label.toLowerCase()}`) }) : '';
      return S.GroupHeader({ lead: S.StatusIcon(state, 0.5), label, count: list.length }) + list.map((it) => workRow(st, it, rows.push('item:' + it.id) - 1)).join('') + empty;
    }).join('');
    const empty = !any && !st.section ? S.EmptyState({ icon: 'search', title: 'No matching work', body: 'Nothing on the board matches the filter.', actions: [S.Button({ label: 'Clear filter', act: 'filter-clear' })] }) : '';
    const label = st.section ? SECTIONS.find((s) => s[0] === st.section)[1] : null;
    const header = S.ViewHeader({
      crumbs: label ? [{ label: 'Work', act: 'nav', arg: 'work' }, { label }] : [{ label: 'Work' }],
      middle: `<div style="width:min(280px,40vw)">${S.Input({ id: 'work-filter', icon: 'search', placeholder: 'Filter work', value: st.filter, input: 'work-filter' })}</div>`,
      actions: paneToggles(st),
    });
    return workArea(st, header, `<div class="sb-scroll" data-scroll="work">${body || empty}</div>`);
  }

  /* Operator decision for a subtask the goal paused on (GoalOperatorDecisionService.record) */
  const DECISIONS = [['retry_fix', 'Retry fix'], ['accept_and_advance', 'Accept and advance'], ['abandon_subtask', 'Abandon subtask']];
  function operatorDecision(st, it) {
    const d = st.opDecision[it.id] || { decision: 'retry_fix', instructions: '', recorded: false };
    if (d.recorded) return S.Hint({ tone: 'success', icon: 'check', text: `Decision recorded for subtask ${it.current}: ${DECISIONS.find((x) => x[0] === d.decision)[1].toLowerCase()}. Resume the goal to apply it.` });
    const retry = d.decision === 'retry_fix';
    const blank = retry && !d.instructions.trim();
    return `<div class="l-col g-3" style="padding:12px;border:1px solid var(--border-subtle);border-radius:var(--r-md)">
      <div class="l-row g-2" style="font-size:var(--t-sm)"><b style="font-weight:500">Decide subtask ${it.current}</b><span class="t-faint">Recorded now, applied when the goal resumes</span></div>
      ${S.Segmented({ act: 'op-decision', value: d.decision, items: DECISIONS.map(([id, label]) => ({ id, label })) })}
      ${retry ? S.Field({ for: 'op-instructions', label: 'Instructions for the retry', control: S.Input({ id: 'op-instructions', value: d.instructions, placeholder: 'What should the agent change?', input: 'op-instructions' }), help: 'Required for Retry fix.' }) : ''}
      <div class="l-row g-2">${S.Button({ label: 'Record decision', variant: 'primary', act: 'op-record', arg: it.id, disabled: blank })}</div>
    </div>`;
  }

  /* ---------- Detail (feature/detail) ---------- */
  /* Every detail section can collapse. A toggle is stored per section key, so it holds while paging between items;
   * untoggled sections start from `def` (Subtasks start collapsed past 10 subtasks; Evidence starts collapsed). */
  const isCollapsed = (st, key, def) => (key in st.secCollapse ? st.secCollapse[key] : !!def);
  const secBlock = (st, key, o, def) => S.Section(Object.assign({}, o, { collapsed: isCollapsed(st, key, def), toggle: 'sec-collapse', arg: `${key}|${def ? 1 : 0}` }));
  /* Spec bundle order: decomposition-manifest.yaml, spec.md, then every other file by name with digit runs read as numbers. */
  const fileName = (p) => p.split('/').pop();
  const fileRank = (n) => (n.startsWith('decomposition') ? 0 : n === 'spec.md' ? 1 : 2);
  const natural = (a, b) => a.localeCompare(b, 'en', { numeric: true, sensitivity: 'base' });
  /* Goal assessment order: Default keeps the runtime's order; Priority and Status sort stably, each breaking ties by the other. */
  const SEV_RANK = ['blocker', 'critical', 'high', 'major', 'medium', 'minor', 'low', 'nit', 'info'];
  const sevRank = (f) => { const i = SEV_RANK.indexOf(String(f.severity).toLowerCase()); return i < 0 ? SEV_RANK.length : i; };
  const stateRank = (f) => ({ OPEN: 0, REFUTED: 1, FIXED: 2 }[f.state] ?? 0);
  const assessOrder = (findings, order) => order === 'priority' ? findings.slice().sort((a, b) => sevRank(a) - sevRank(b) || stateRank(a) - stateRank(b))
    : order === 'status' ? findings.slice().sort((a, b) => stateRank(a) - stateRank(b) || sevRank(a) - sevRank(b)) : findings;
  const bundleOrder = (files) => files.slice().sort((a, b) => fileRank(fileName(a)) - fileRank(fileName(b)) || natural(fileName(a), fileName(b)));
  function pipelineSteps(it) {
    const p = it.pipeline;
    return D.PHASE_ORDER.map((id) => {
      if (p.done.includes(id)) return { label: id, state: 'done' };
      if (id === p.now) return { label: id, state: p.failed ? 'fail' : it.lifecycle === 'blocked' ? 'wait' : it.lifecycle === 'active' ? 'now' : '', count: it.exec };
      return { label: id, state: '' };
    });
  }
  function incidentPanel(st, it) {
    const inc = it.incident;
    if (!inc) return '';
    const tone = inc.recovery === 'available' ? 'info' : 'warn';
    const recovered = st.recovered.has(it.id);
    const body = [
      `<div class="l-col g-1">${S.ExpandableText({ html: `<span class="t-faint">Cause:</span> ${esc(inc.cause)}` })}${S.ExpandableText({ html: `<span class="t-faint">Next:</span> ${esc(inc.nextAction)}` })}<span class="t-faint" style="font-size:var(--t-xs)">certainty ${esc(inc.certainty)}${inc.unavailableReason ? ' · recovery unavailable: ' + esc(inc.unavailableReason.toLowerCase().replace(/_/g, ' ')) : ''}</span></div>`,
      inc.prerequisites && !recovered ? `<div class="l-col g-2" style="margin-top:8px">${S.Checklist({ act: 'prereq', items: inc.prerequisites.map((p) => ({ id: it.id + ':' + p.id, text: p.text, checked: st.prereqs.has(it.id + ':' + p.id) })) })}</div>` : '',
    ].join('');
    const decision = it.awaitsDecision ? operatorDecision(st, it) : '';
    const evOpen = !isCollapsed(st, 'incident-evidence', true);
    const evidence = inc.evidence.length ? `<div class="l-col g-2">${S.EvidenceToggle({ label: 'Evidence', count: inc.evidence.length, open: evOpen, act: 'sec-collapse', arg: 'incident-evidence|1' })}${evOpen ? S.Box(inc.evidence.map((e) => S.EvidenceRow(e))) : ''}</div>` : '';
    return secBlock(st, 'incident', { title: 'Incident', end: [S.Label({ text: recovered ? 'Recovering' : inc.heading, color: recovered ? 'var(--st-active)' : tone === 'info' ? 'var(--st-planning)' : 'var(--st-blocked)', dot: true })], body:
      S.Callout({ tone: recovered ? 'info' : tone, title: recovered ? 'Recovering' : inc.heading, body }) +
      decision +
      evidence +
      `<div class="l-row g-2 l-wrap t-muted" style="font-size:var(--t-sm)"><span>Report: ${esc(inc.report)}</span>${/issue #/.test(inc.report) ? S.Button({ label: 'Open issue', icon: 'external', size: 'sm', variant: 'ghost', act: 'toast', arg: 'Opened the issue in your browser' }) : ''}</div>` });
  }
  /* A decision awaited with no incident gets its own section where the incident would be. */
  function decisionPanel(st, it) {
    if (it.incident) return incidentPanel(st, it);
    return it.awaitsDecision ? S.Section({ title: 'Operator decision', body: operatorDecision(st, it) }) : '';
  }
  /* ---------- Subtask index and inspector ---------- */
  /* Selection key per goal: the subtask id as a string, or 'finish' for the Goal finish row. The default is the blocked
   * subtask, else the current one, else the first. A choice is kept per item while paging. */
  const pad = (n) => String(n).padStart(2, '0');
  const isFinished = (it) => it.lifecycle === 'terminal' || it.section === 'history' || it.section === 'finished';
  const hasSubtasks = (it) => it.kind === 'goal' && !it.planning && (it.subtasks || []).length > 0;
  const subKeys = (it) => (hasSubtasks(it) ? it.subtasks.map((s) => String(s.id)).concat('finish') : []);
  function defaultSub(it) {
    const blocked = it.subtasks.find((s) => s.status === 'blocked');
    if (blocked) return String(blocked.id);
    const cur = !isFinished(it) && it.subtasks.find((s) => s.id === it.current);
    return String((cur || it.subtasks[0]).id);
  }
  function selectedSub(st, it) {
    if (!hasSubtasks(it)) return null;
    const k = st.subSel[it.id];
    return k && subKeys(it).includes(k) ? k : defaultSub(it);
  }
  /* The details pane shows unless it is toggled off, the Specs pane is open, or the window is narrower than 1080px.
   * Where it does not show, the inspector opens inside the selected row instead. */
  const paneShown = (st) => st.propsOpen && !st.specsOpen && !st.narrow;
  const specArg = (it, s) => `${it.id}/${s.spec}`;
  const subChip = (it, d) => {
    const t = it.subtasks.find((x) => x.id === d.id);
    return { num: pad(d.id), optional: d.optional, lead: S.StatusIcon(SUB_STATE[t.status], 0.5), act: 'sub-select', arg: `${it.id}|${d.id}`, tip: `Subtask ${pad(d.id)}: ${t.title}${d.optional ? ' (optional)' : ''}` };
  };
  function subtaskInspector(it, s, inline) {
    const neededBy = it.subtasks.filter((x) => x.deps.some((d) => d.id === s.id)).map((x) => ({ id: x.id, optional: x.deps.find((d) => d.id === s.id).optional }));
    return S.SubtaskInspector({
      title: inline ? null : s.title, blocked: s.blocked, commit: s.commit,
      spec: s.spec, specAct: 'open-file', specArg: specArg(it, s),
      deps: s.deps.map((d) => subChip(it, d)), neededBy: neededBy.map((d) => subChip(it, d)),
      validation: s.validation, reviews: s.reviews, audit: s.audit,
    });
  }
  /* Goal finish: pr, monitor, monitor_fix with their states, then one-line pointers to Goal QA and Goal assessment. */
  const FINISH_WORD = { done: 'Done', pending: 'Pending', active: 'Running', failed: 'Failed' };
  function finishInspector(it) {
    const state = (id) => (it.finishDone ? 'done' : 'pending');
    const rows = D.GOAL_FINISH.map((id) => ({ label: id, mono: true, value: `${S.StatusIcon(state(id), 0.5)}<span>${FINISH_WORD[state(id)]}</span>` }));
    if (it.goalQa) { const q = it.goalQa, fixed = q.findings.filter((f) => f[2]).length; rows.push({ label: 'Goal QA', value: `<span>${esc(q.outcome)} · ${fixed} of ${q.findings.length} fixed</span>`, act: 'anchor', arg: 'sec-goal-qa', tip: 'Show Goal QA' }); }
    if (it.assessment) { const a = it.assessment; rows.push({ label: 'Assessment', value: `<span>${a.state === 'COMPLETED' ? 'Completed' : 'Running'} · round ${a.round} · ${a.findings.length} findings</span>`, act: 'anchor', arg: 'sec-goal-assessment', tip: 'Show Goal assessment' }); }
    return `<div class="sb-insp">${S.PropertyList(rows)}</div>`;
  }
  function subtaskSection(st, it, inline) {
    const sel = selectedSub(st, it);
    const open = st.subOpen[it.id] !== false;
    const finished = isFinished(it);
    const rows = it.subtasks.map((s) => {
      const key = String(s.id);
      const current = !finished && s.id === it.current;
      const meta = [s.commit ? S.ShortId({ value: s.commit, label: 'commit' }) : '', current && it.live ? subClock(it) : '', s.status === 'completed_no_change' ? S.Label({ text: 'no change', dot: false }) : ''];
      return S.SubtaskRow({ num: pad(s.id), state: SUB_STATE[s.status], frac: 0.5, title: s.title, current, selected: key === sel, meta, act: 'sub-select', arg: `${it.id}|${key}`, body: inline && key === sel ? subtaskInspector(it, s, true) : '', expanded: open });
    });
    rows.push(S.SubtaskRow({ num: '··', state: it.finishDone ? 'done' : 'pending', title: 'Goal finish', selected: sel === 'finish', meta: [], act: 'sub-select', arg: `${it.id}|finish`, body: S.PhasePipeline(D.GOAL_FINISH.map((id) => ({ label: id, state: it.finishDone ? 'done' : '', goal: true }))) + (inline && sel === 'finish' ? finishInspector(it) : ''), expanded: open }));
    return secBlock(st, 'subtasks', { title: 'Subtasks', count: `${it.done}/${it.total}`, end: [S.ProgressRing(it.done, it.total, { text: false })], body: S.SubtaskList({ label: `Subtasks of ${it.id}`, rows }) }, it.subtasks.length > 10);
  }
  function inspectorPane(st, it) {
    const sel = selectedSub(st, it);
    if (sel === 'finish') return S.PaneSection({ title: 'Goal finish', status: { state: it.finishDone ? 'done' : 'pending' }, body: finishInspector(it) });
    const s = it.subtasks.find((x) => String(x.id) === sel);
    return S.PaneSection({ title: `Subtask ${pad(s.id)}`, status: { state: SUB_STATE[s.status], frac: 0.5 }, body: subtaskInspector(it, s, false) });
  }
  /* Spec files: the goal-level files only (subtask specs open from the inspector), manifest, spec.md, then by name. */
  function specFilesPane(it) {
    const subSpecs = new Set((it.subtasks || []).map((s) => s.spec));
    const files = bundleOrder((it.specBundle || []).filter((f) => !subSpecs.has(fileName(f)) && !/^spec_subtask_/.test(fileName(f))));
    if (!files.length) return '';
    return S.PaneSection({ title: 'Spec files', body: files.map((f) => S.TreeRow({ level: 0, file: true, lead: S.Icon(f.endsWith('.yaml') ? 'file' : 'fileText', 'sm'), title: fileName(f), meta: [], act: 'open-file', arg: `${it.id}/${f}`, rowIdx: -1 })).join('') });
  }

  /* ---------- Details pane: Execution, Runs and fixes ---------- */
  const sum = (m) => Object.values(m || {}).reduce((a, b) => a + b, 0);
  function breakdownRow(st, it, label, key, map) {
    const total = sum(map);
    if (!total) return null;
    const rows = Object.entries(map);
    return { label, value: `<span>${total}</span>`, sub: rows.length > 1 || rows[0][0] ? S.Breakdown({ rows, open: st.bdOpen.has(`${it.id}:${key}`), act: 'bd-toggle', arg: `${it.id}:${key}` }) : '' };
  }
  function executionPane(it) {
    return S.PaneSection({ title: 'Execution', body: S.PropertyList([
      it.workflow ? { label: 'Workflow', value: S.ShortId({ value: it.workflow, label: 'workflow id' }) } : null,
      it.invocation ? { label: 'Invocation', value: S.ShortId({ value: it.invocation, label: 'invocation id' }) } : null,
      it.agent ? { label: 'Agent', value: `${S.Avatar({ name: it.agent, initials: agentOf(it.agent).initials, color: agentOf(it.agent).color, size: 'sm' })}<span>${esc(it.agent)}</span>` } : null,
      it.model ? { label: 'Model', value: `<span>${esc(it.model)}${it.effort ? ' · ' + esc(it.effort) : ''}</span>` } : null,
      it.liveness ? { label: 'Liveness', value: `<span>${esc(it.liveness)}</span>` } : null,
      it.started && it.started !== '—' ? { label: 'Started', value: `<span>${esc(it.started)}</span>` } : null,
      { label: 'Updated', value: `<span>${esc(it.updated)}</span>` },
    ]) });
  }
  function runsPane(st, it) {
    const a = it.attempts;
    if (!a) return '';
    const rows = [
      breakdownRow(st, it, 'Runs', 'runs', a.phaseRuns),
      breakdownRow(st, it, 'Fixes', 'fixes', a.fixIterations),
      breakdownRow(st, it, 'Relaunches', 'relaunches', a.relaunches),
      a.findings ? { label: 'Findings', value: `<span>${a.findings}</span>`, tip: 'Findings in scope' } : null,
      a.kills ? { label: 'Kills', value: `<span>${a.kills}</span>`, tip: 'Supervisor kills' } : null,
    ].filter(Boolean);
    return rows.length ? S.PaneSection({ title: 'Runs and fixes', body: S.PropertyList(rows) }) : '';
  }

  /* ---------- Activity: one feed from the recorded facts, oldest first ---------- */
  /* Sources (DetailModels): executionHistory (lifecycleState, startedAt, finishedAt, result), the item's startedAt and
   * finishedAt when no execution is listed, the last worktree edit, the latest event (phase, worker role, summary) and the
   * last agent activity label. Nothing else is drawn. */
  const EXEC_STATE = { completed: 'done', failed: 'failed', interrupted: 'paused', paused: 'paused', blocked: 'blocked', canceled: 'canceled', active: 'active' };
  function activityEntries(it) {
    const out = [];
    const avatar = () => (it.agent ? S.Avatar({ name: it.agent, initials: agentOf(it.agent).initials, color: agentOf(it.agent).color, size: 'sm' }) : S.StatusIcon(iconState(it), frac(it)));
    const self = it.kind === 'goal' ? 'Goal' : rowKey(it);
    if ((it.executions || []).length) {
      it.executions.forEach((e, i) => {
        const who = it.executions.length > 1 ? `Run ${i + 1}` : (it.kind === 'goal' ? 'Goal run' : self);
        out.push({ lead: S.StatusIcon('active', 0.12), actor: who, action: 'started', ago: e.startAgo, tip: `${who} started ${e.start} · ${e.inv}` });
        if (e.end) out.push({ lead: S.StatusIcon(EXEC_STATE[e.life] || 'pending', 1), actor: who, action: e.life, object: e.result, mono: true, ago: e.endAgo, tip: `${who} ${e.life} ${e.end}${e.result ? ' · ' + e.result : ''} · ${e.inv}` });
      });
    } else if (it.startedAgo != null) {
      out.push({ lead: S.StatusIcon('active', 0.12), actor: self, action: 'started', ago: it.startedAgo, tip: `${self} started ${it.started}` });
      if (it.finishedAgo != null) out.push({ lead: statusIcon(it), actor: self, action: LIFE[it.lifecycle][0].toLowerCase(), ago: it.finishedAgo, tip: `${self} ${LIFE[it.lifecycle][0].toLowerCase()} ${it.finishedAt || ''}` });
    }
    const l = it.latest || {};
    if (l.edit) out.push({ lead: avatar(), actor: it.agent || 'Agent', action: 'edited', object: `${l.edit.path} +${l.edit.ins} −${l.edit.del}`, mono: true, ago: l.edit.ago });
    if (l.event) out.push({ lead: statusIcon(it), actor: `${l.event.phase} · ${l.event.role}`, action: l.event.summary || '', ago: l.event.ago });
    if (l.activity) out.push({ lead: avatar(), actor: it.agent || 'Agent', action: l.activity.label, ago: l.activity.ago });
    if (!out.length && !isFinished(it) && it.summary) out.push({ lead: statusIcon(it), actor: self, action: it.summary, ago: null });
    return out.sort((a, b) => (b.ago == null ? -1 : b.ago) - (a.ago == null ? -1 : a.ago));
  }
  const FEED_MAX = 8;
  function activitySection(st, it) {
    const all = activityEntries(it);
    if (!all.length) return '';
    const showAll = st.feedAll.has(it.id);
    const hidden = showAll ? 0 : Math.max(0, all.length - FEED_MAX);
    return secBlock(st, 'activity', { title: 'Activity', count: all.length, body: S.ActivityFeed({ entries: all.slice(hidden), hidden, act: 'feed-all', arg: it.id }) });
  }

  function detail(st) {
    const it = lookup(st.detailId) || D.items[2];
    const r = repoOf(it.repo);
    const goal = it.kind === 'goal';
    const finished = isFinished(it);
    const liveList = D.items.filter((x) => x.section !== 'finished');
    const idx = liveList.indexOf(it);
    const sec = SECTIONS.find((s) => s[0] === it.section);
    const pane = paneShown(st);
    const subs = hasSubtasks(it);

    const location = `<div class="l-row g-1 l-wrap">${S.Button({ label: r.path, icon: 'folder', size: 'sm', variant: 'ghost', act: 'toast', arg: `Opened ${r.path}` })}${it.branch ? S.Button({ label: it.branch, icon: 'branch', size: 'sm', variant: 'ghost', act: 'toast', arg: `Opened ${r.web}/tree/${it.branch}` }) : `<span class="t-faint" style="font-size:var(--t-sm)">no branch</span>`}</div>`;
    const metrics = S.MetricStrip([
      goal ? (it.planning ? { label: 'Goal stage', value: 'Planning' } : finished ? { label: 'Subtasks', value: `${it.done || 0}`, unit: `/ ${it.total || 0}`, note: 'complete' } : { label: 'Subtask', value: `${it.current}`, unit: `/ ${it.total}`, note: `${it.done} complete · ${it.total - it.done - 1} pending` }) : { label: 'Step', value: `<span class="mono" style="font-size:var(--t-md)">${esc(it.step)}</span>`, note: it.exec ? esc(it.exec) : '&nbsp;' },
      goal ? { label: 'Phase', value: esc(it.planning ? 'plan' : it.step), note: it.planning ? `${it.planned}/${it.total} plans saved` : esc(it.exec || ' ') } : null,
      finished ? { label: 'Total run time', value: S.fmtDur(it.goalMs), note: `finished ${esc(it.finishedAt || '')}` } : { label: goal ? 'Goal active' : it.kind === 'operation' ? 'Operation elapsed' : 'Phase elapsed', value: clock(it), note: it.live ? 'ticking' : 'last known duration' },
      goal && it.subMs != null && !finished ? { label: 'Subtask active', value: subClock(it), note: it.live ? 'ticking' : 'last known duration' } : null,
    ]);

    const parts = [];
    parts.push(S.DocHeader({
      eyebrow: [lifeLabel(it), it.stalled ? S.Label({ text: 'Stalled', color: 'var(--st-blocked)', tint: true, dot: false }) : '', S.IdTag(rowKey(it)), it.issue && !goal ? S.IdTag(it.issue) : '', S.Label({ text: kindWord(it), dot: false }), S.Label({ text: it.fresh && !it.refreshFailed ? 'fresh' : 'stale', color: it.fresh && !it.refreshFailed ? 'var(--st-done)' : 'var(--fg-faint)' })],
      title: it.title, sub: location, actions: [runActions(st, it)],
    }));
    if (it.pauseRequested) parts.push(S.Hint({ tone: 'info', icon: 'pause', text: 'Pause recorded. The goal pauses at its next checkpoint.' }));
    if (it.reason) parts.push(S.ReasonBlock({ label: 'Run reason', text: it.reason, color: LIFE[it.lifecycle][1], open: st.reasonOpen.has(it.id), act: 'reason-toggle', arg: it.id }));
    parts.push(decisionPanel(st, it));
    parts.push(metrics);
    if (subs) parts.push(subtaskSection(st, it, !pane));
    if (goal && it.planning) parts.push(secBlock(st, 'subtasks', { title: 'Subtasks', count: `${it.planned}/${it.total} plans saved`, body: S.Hint({ text: 'Subtasks appear here once their plans are saved.' }) }));
    if (goal && it.pipeline && !it.planning) parts.push(secBlock(st, 'pipeline', { title: 'Phase pipeline', body: S.PhasePipeline(pipelineSteps(it)) }));
    if (!goal && (it.steps || []).length > 1) parts.push(secBlock(st, 'pipeline', { title: 'Phase pipeline', body: S.PhasePipeline(it.steps.map(([label, state]) => ({ label, state, count: state === 'now' ? it.exec : null }))) }));
    parts.push(activitySection(st, it));
    /* Standalone phases and operations keep their own validation, phase results and spec bundle in the document. */
    if (!goal && it.validation) parts.push(secBlock(st, 'validation', { title: 'Validation', body: S.Box(it.validation.map(([n, ok, checks]) => S.FindingRow({ ref: `Subtask ${n}`, title: `${ok ? 'passed' : 'failed'} · ${checks} checks`, state: ok ? 'passed' : 'failed', stateColor: ok ? 'var(--st-done)' : 'var(--st-failed)' }))) }));
    if (!goal && it.phaseResults) {
      const pr = it.phaseResults;
      const passes = pr.passes.map((p) => `<div class="l-col g-2"><div class="l-row g-2" style="font-size:var(--t-sm)"><b style="font-weight:500">Review pass ${p.n}</b><span class="t-muted">${esc(p.verdict)}</span><span class="t-faint">· ${p.open} unresolved</span></div>${p.findings.length ? S.Box(p.findings.map(([sev, text, where]) => S.FindingRow({ severity: sev, title: text, meta: `<span class="mono">${esc(where)}</span>` }))) : ''}</div>`).join('');
      parts.push(secBlock(st, 'phase-results', { title: 'Phase results', body: `<div class="l-col g-4">${passes}${pr.audit ? `<div class="l-row g-2" style="font-size:var(--t-sm)"><b style="font-weight:500">Audit</b><span class="t-muted">${esc(pr.audit.verdict)}</span>${S.ExpandableText({ text: `· ${pr.audit.summary}`, cls: 't-faint' })}</div>` : ''}</div>` }));
    }
    if (it.goalQa) {
      const q = it.goalQa, fixed = q.findings.filter((f) => f[2]).length;
      parts.push(secBlock(st, 'goal-qa', { id: 'sec-goal-qa', title: 'Goal QA', count: q.outcome, body: `<div class="l-col g-2"><span class="t-muted" style="font-size:var(--t-sm)">${fixed === q.findings.length ? `QA found ${q.findings.length} findings; all fixed after ${q.fixAttempts} fix attempt${q.fixAttempts === 1 ? '' : 's'}.` : `QA could not fix ${q.findings.length - fixed} findings. Review them yourself.`}</span>${S.Box(q.findings.map(([id, title, ok]) => S.FindingRow({ ref: id, title, state: ok ? 'Fixed' : 'Not fixed', stateColor: ok ? 'var(--st-done)' : 'var(--st-failed)' })))}</div>` }));
    }
    if (it.assessment) {
      const a = it.assessment;
      parts.push(secBlock(st, 'goal-assessment', { id: 'sec-goal-assessment', title: 'Goal assessment', count: `${a.state === 'COMPLETED' ? 'Completed' : 'Running'} · round ${a.round}`, end: a.findings.length > 1 ? [S.Segmented({ act: 'assess-order', value: st.assessOrder || 'default', items: [{ id: 'default', label: 'Default' }, { id: 'priority', label: 'Priority' }, { id: 'status', label: 'Status' }] })] : [], body: S.Box(assessOrder(a.findings, st.assessOrder).map((f) => S.FindingRow({ severity: f.severity, ref: f.ref, title: f.scenario, meta: `<span class="mono">${esc(f.path)}:${f.line}</span> · ${esc(f.verification.toLowerCase())}`, state: f.state === 'FIXED' ? 'Fixed' : f.state === 'REFUTED' ? 'Refuted' : 'Open', stateColor: f.state === 'FIXED' ? 'var(--st-done)' : 'var(--fg-faint)' }))) }));
    }
    if (it.qaReport) parts.push(S.ReasonBlock({ label: 'QA report', text: it.qaReport, color: 'var(--st-done)', open: st.reasonOpen.has(it.id + ':qa'), act: 'reason-toggle', arg: it.id + ':qa' }));
    if (!goal && it.specBundle) parts.push(secBlock(st, 'spec-bundle', { title: 'Spec bundle', body: S.Box(bundleOrder(it.specBundle).map((f) => S.TreeRow({ level: 0, file: true, lead: S.Icon(f.endsWith('.yaml') ? 'file' : 'fileText', 'sm'), title: fileName(f), meta: [], act: 'open-file', arg: f, rowIdx: -1 }))) }));

    const props = executionPane(it) + runsPane(st, it) + (subs ? inspectorPane(st, it) : '') + (goal ? specFilesPane(it) : '');

    const header = S.ViewHeader({
      crumbs: [{ label: 'Work', act: 'nav', arg: 'work' }, sec ? { label: sec[1], act: 'section', arg: sec[0] } : { label: 'History', act: 'nav', arg: 'history' }, { label: rowKey(it) }],
      actions: [idx >= 0 ? S.Pager({ index: idx, total: liveList.length }) : '', it.refreshFailed ? S.StaleIndicator() : '', ...paneToggles(st), S.IconButton({ icon: 'panelRight', label: 'Toggle details', kbd: '⌘ I', act: 'toggle-props', on: st.propsOpen })],
    });
    const body = `<div class="sb-split"><div class="sb-scroll" data-scroll="detail"><div class="sb-doc">${parts.join('')}</div></div>${pane ? `<aside class="sb-pane${subs ? ' sb-pane--wide' : ''}" aria-label="Details">${props}</aside>` : ''}</div>`;
    return workArea(st, header, body);
  }

  /* ---------- History (feature/history) ---------- */
  function history(st) {
    rows = [];
    const kind = { all: null, goals: 'goal', phases: 'phase', operations: 'operation' }[st.historyTab];
    const list = D.history.filter((h) => (!kind || h.kind === kind) && matches(h, st.filter));
    const body = list.length ? S.GroupHeader({ label: `Finished within ${st.prefs.retention}`, count: list.length }) + list.map((h) => {
      const i = rows.push('item:' + h.id) - 1;
      return S.ListRow({ id: h.kind === 'goal' ? h.id : h.label, status: S.StatusIcon(h.lifecycle === 'terminal' ? 'done' : h.lifecycle), title: h.title, sub: h.kind === 'goal' ? '' : h.kind === 'operation' ? 'operation' : 'standalone phase', meta: [`<span class="hide-sm">${repoLabel(h.repo)}</span>`, `<span class="tnum t-faint" style="font-size:var(--t-sm);width:56px;text-align:right">${S.fmtDur(h.ms)}</span>`], time: esc(h.at), act: 'open', arg: h.id, focus: st.focus === i, rowIdx: i });
    }).join('') : S.EmptyState({ icon: 'history', title: st.filter ? 'No matching work' : 'No finished work', body: st.filter ? null : `Nothing finished within ${st.prefs.retention}.` });
    const header = S.ViewHeader({
      crumbs: [{ label: 'History' }],
      middle: `<div class="l-row g-3">${S.ViewTabs({ act: 'history-tab', value: st.historyTab, items: [{ id: 'all', label: 'All' }, { id: 'goals', label: 'Goals' }, { id: 'phases', label: 'Phases' }, { id: 'operations', label: 'Operations' }] })}<div style="width:200px">${S.Input({ id: 'history-filter', icon: 'search', placeholder: 'Filter history', value: st.filter, input: 'work-filter' })}</div></div>`,
      actions: paneToggles(st),
    });
    return workArea(st, header, `<div class="sb-scroll" data-scroll="history">${body}</div>`);
  }

  /* ---------- Specs pane (feature/specs) ---------- */
  function specRun(st, sp) { return st.specRuns[sp.key] || sp.run; }
  function specControl(st, sp) {
    const run = specRun(st, sp);
    return {
      run, startLabel: run === 'paused' ? 'Resume' : run === 'canceled' ? 'Run again' : 'Start',
      canStart: ['not_started', 'paused', 'canceled'].includes(run) && sp.status !== 'abandoned',
      canPause: run === 'running', canCancel: ['running', 'pausing', 'paused'].includes(run),
      canPurge: ['paused', 'canceled', 'complete'].includes(run),
    };
  }
  function specTree(st, project) {
    const q = (st.specQuery || '').toLowerCase();
    const statusOk = (sp) => st.specStatus === 'all' || (st.specStatus === 'active' && ['in_progress', 'blocked'].includes(sp.status)) || (st.specStatus === 'pending' && sp.status === 'pending');
    const match = (sp) => !q || sp.key.toLowerCase().includes(q) || sp.title.toLowerCase().includes(q) || sp.subtasks.some((t) => t.toLowerCase().includes(q));
    const vis = project.id === 'l-f2ea' ? D.specs.filter((sp) => statusOk(sp) && match(sp) && st.specStatus !== 'done') : [];
    const out = [];
    vis.forEach((sp) => {
      const path = sp.key;
      const open = st.specOpen.has(path) || (q && match(sp));
      const icon = sp.status === 'in_progress' ? S.StatusIcon('active', 0.5) : sp.status === 'blocked' ? S.StatusIcon('blocked') : sp.status === 'abandoned' ? S.StatusIcon('canceled') : S.StatusIcon('pending');
      out.push(S.TreeRow({ level: 0, expandable: true, open, focus: st.specSel === path, muted: sp.status === 'abandoned', lead: icon, id: sp.key, title: sp.title, meta: sp.status === 'abandoned' ? [] : [`<span class="tnum t-faint" style="font-size:var(--t-xs)">${sp.done}/${sp.total}</span>`], act: 'spec-click', arg: path, rowIdx: -1 }));
      if (open) {
        out.push(S.TreeRow({ level: 1, file: true, focus: st.specSel === path + '/spec.md', lead: S.Icon('fileText', 'sm'), title: 'spec.md', meta: [], act: 'spec-click', arg: path + '/spec.md', rowIdx: -1 }));
        sp.subtasks.forEach((t, j) => out.push(S.TreeRow({ level: 1, file: true, focus: st.specSel === `${path}/${j}`, lead: S.StatusIcon(SUB_STATE[sp.subState[j]], 0.5), id: String(j + 1).padStart(2, '0'), title: t, meta: [], act: 'spec-click', arg: `${path}/${j}`, rowIdx: -1 })));
        out.push(S.TreeRow({ level: 1, file: true, lead: S.Icon('file', 'sm'), title: 'traceability.md', meta: [], act: 'spec-click', arg: path + '/traceability.md', rowIdx: -1 }));
        out.push(S.TreeRow({ level: 1, file: true, lead: S.Icon('file', 'sm'), title: 'decomposition-manifest.yaml', meta: [], act: 'spec-click', arg: path + '/decomposition-manifest.yaml', rowIdx: -1 }));
      }
    });
    if (project.id === 'l-f2ea' && (st.specStatus === 'all' || st.specStatus === 'done')) {
      const open = st.specOpen.has('done');
      out.push(S.TreeRow({ level: 0, expandable: true, open, lead: S.Icon('folder', 'sm'), title: 'done', meta: [`<span class="tnum t-faint" style="font-size:var(--t-xs)">${D.doneSpecCount}</span>`], act: 'spec-click', arg: 'done', focus: st.specSel === 'done', rowIdx: -1 }));
      if (open) D.doneSpecs.filter(([k, t]) => !q || k.toLowerCase().includes(q) || t.toLowerCase().includes(q)).forEach(([k, t]) => out.push(S.TreeRow({ level: 1, expandable: true, lead: S.StatusIcon('done'), id: k, title: t, meta: [], act: 'noop', rowIdx: -1 })));
    }
    if (!out.length) return project.id === 'l-f2ea'
      ? S.EmptyState({ icon: 'search', title: 'No specs match', body: 'Nothing in this project matches the filter and status.', actions: [S.Button({ label: 'Clear filters', act: 'spec-clear' })] })
      : S.EmptyState({ icon: 'fileText', title: 'No specs yet', body: 'Plan a feature with <span class="mono">/skill-bill phase:plan</span> and its bundle shows up here.' });
    return out.join('');
  }
  function specsPane(st) {
    const project = D.projects.find((p) => p.id === st.specProject) || D.projects[0];
    const sel = st.specSel || '';
    const bundleKey = sel.split('/')[0];
    const sp = D.specs.find((s) => s.key === bundleKey);
    let toolbar;
    if (!sp) toolbar = S.Hint({ text: 'Select a spec folder to start, pause or cancel it.' });
    else if (sel.includes('/')) toolbar = S.Hint({ text: `Actions run a whole spec folder, not one subtask. Select ${sp.key} to control it.` });
    else {
      const c = specControl(st, sp);
      const agent = st.chosenAgent['spec:' + sp.key] || 'claude';
      toolbar = `<div class="sb-tools-sel">${S.IdTag(sp.key)}<b>${esc(sp.title)}</b>${S.Label({ text: c.run.replace('_', ' '), color: c.run === 'running' ? 'var(--st-active)' : c.run === 'paused' ? 'var(--st-blocked)' : 'var(--fg-faint)' })}</div>
        <div class="l-row g-2 l-wrap">${S.Button({ label: c.startLabel, icon: 'play', size: 'sm', variant: c.canStart ? 'primary' : 'secondary', disabled: !c.canStart, act: 'spec-start', arg: sp.key })}${S.Button({ label: `Agent: ${agent}`, icon: 'bot', size: 'sm', disabled: !c.canStart, act: 'agent-menu', arg: 'spec:' + sp.key })}${S.Button({ label: c.run === 'pausing' ? 'Pause requested' : 'Pause', icon: 'pause', size: 'sm', disabled: !c.canPause, act: 'spec-pause', arg: sp.key })}${S.Button({ label: 'Cancel', icon: 'x', size: 'sm', variant: 'danger', disabled: !c.canCancel, act: 'spec-cancel', arg: sp.key })}<span class="l-grow"></span>${S.IconButton({ icon: 'trash', label: c.canPurge ? `Purge ${sp.key} run history` : 'Pause or cancel the goal before purging', act: c.canPurge ? 'spec-purge' : 'noop', arg: sp.key, size: 'sm' })}</div>
        ${st.specConfirmCancel === sp.key ? S.Callout({ tone: 'error', body: `Cancel ${esc(sp.key)}? The run stops and the goal is marked canceled. Its branch and commits stay.`, actions: [S.Button({ label: 'Keep running', size: 'sm', act: 'spec-keep' }), S.Button({ label: 'Cancel goal', size: 'sm', variant: 'danger-solid', act: 'spec-cancel-confirm', arg: sp.key })] }) : ''}
        ${c.run === 'pausing' ? S.Hint({ tone: 'info', text: 'Pause recorded. The goal stops after its current subtask.' }) : ''}`;
    }
    return S.SidePane({
      title: 'Specs',
      head: `<button type="button" class="sb-pick" data-act="project-menu">${S.RepoIcon(repoOf(project.name))}${esc(project.name)}${S.Icon('chevronDown')}</button>`,
      actions: [S.IconButton({ icon: 'chevronUp', label: 'Collapse all', act: 'spec-collapse', size: 'sm' }), S.IconButton({ icon: 'x', label: 'Close specs pane', act: 'specs-toggle', size: 'sm' })],
      body: `<div class="sb-toolbar">${toolbar}</div>
        <div class="sb-filters">${S.Input({ id: 'spec-q', icon: 'search', placeholder: 'Filter by key, title or file', value: st.specQuery, input: 'spec-q' })}${S.Segmented({ act: 'spec-status', value: st.specStatus, items: [{ id: 'all', label: 'All' }, { id: 'active', label: 'Active' }, { id: 'pending', label: 'Pending' }, { id: 'done', label: 'Done' }] })}</div>
        <div class="sb-scroll" data-scroll="specs">${specTree(st, project)}</div>
        <div class="sb-pane-foot"><span>${esc(project.store)}</span>${S.Button({ label: 'Reveal', size: 'sm', variant: 'ghost', act: 'toast', arg: 'Revealed the specs folder' })}</div>`,
    });
  }

  /* ---------- Terminal pane (feature/terminal) ---------- */
  function terminal(st) {
    const tab = st.termTabs.find((t) => t.id === st.termActive);
    const body = tab
      ? `<span class="m">${esc(tab.directory)}</span>\n<span class="p">➜</span>  <span class="d">${esc(tab.name)}</span>${tab.branch ? ` <span class="b">(${esc(tab.branch)})</span>` : ''} <span class="cur"></span>`
      : '';
    if (!tab) return S.TerminalPane({ tabs: [], height: st.termHeight, body: S.EmptyState({ icon: 'terminal', title: 'No terminal tabs', actions: [S.Button({ label: 'New terminal tab', icon: 'plus', act: 'term-new' })] }) });
    return S.TerminalPane({ tabs: st.termTabs, activeId: st.termActive, renaming: st.termRenaming, height: st.termHeight, body });
  }

  /* ---------- Settings (feature/settings) and runtime config ---------- */
  const SETTINGS_PAGES = [
    ['App', [['general', 'user', 'General'], ['notifications', 'bell', 'Notifications']]],
    ['Runtime config', [['models', 'cpu', 'Models'], ['compaction', 'gauge', 'Compaction'], ['project', 'repo', 'Project'], ['telemetry', 'shield', 'Telemetry and agents']]],
  ];
  function settingsNav(st) {
    return S.Sidebar({
      top: `<div class="l-col g-2" style="padding:2px 0 8px">${S.Button({ label: 'Back to app', icon: 'arrowLeft', variant: 'ghost', act: 'settings-back', kbd: 'esc' })}</div>`,
      items: SETTINGS_PAGES.map(([sec, pages]) => S.SidebarSection({ label: sec, items: pages.map(([id, icon, label]) => S.SidebarItem({ icon, label, on: st.settingsPage === id, act: 'settings-page', arg: id })) })),
      footer: [],
    });
  }
  function settingsPage(st) {
    const page = st.settingsPage;
    const content = { general: setGeneral, notifications: setNotifications, models: setModels, compaction: setCompaction, project: setProject, telemetry: setTelemetry }[page](st);
    const label = SETTINGS_PAGES.flatMap((s) => s[1]).find((p) => p[0] === page)[2];
    return S.ViewHeader({ crumbs: [{ label: 'Settings', act: 'settings-page', arg: 'general' }, { label }], actions: [] }) + `<div class="sb-scroll" data-scroll="settings"><div class="sb-set">${content}</div></div>`;
  }
  function setGeneral(st) {
    return S.SettingsHeader({ title: 'General', body: 'Where the app reads Skill Bill’s work, which Claude account runs use, and how long finished work stays listed.' }) +
      S.SettingsGroup({ title: 'Database', rows: [S.SettingRow({ stack: true, control: `<div class="l-col g-2" style="width:100%">${S.Field({ for: 'db-path', label: 'Database path, blank uses runtime default', control: `<div class="l-row g-2">${S.Input({ id: 'db-path', value: st.prefs.dbDraft, mono: true, placeholder: 'Runtime default', input: 'db-path' })}${S.Button({ label: 'Save settings', variant: 'primary', act: 'db-save', disabled: st.prefs.dbDraft === st.prefs.dbPath })}</div>` })}${st.prefs.dbDraft && !st.prefs.dbDraft.trim() ? S.Hint({ tone: 'error', text: 'The path is only spaces. Clear it to use the default.' }) : ''}${S.Hint({ text: `Current database: ${st.prefs.dbPath || '~/.skill-bill/review-metrics.db'}` })}</div>` })] }) +
      S.SettingsGroup({ title: 'Claude account', rows: [S.SettingRow({ label: 'Claude config directory', desc: 'Runs started here use it as CLAUDE_CONFIG_DIR.', control: `<button type="button" class="sb-pick" data-act="claude-menu">${S.Icon('folder')}<span class="mono">${esc(st.prefs.claudeDir)}</span>${S.Icon('chevronDown')}</button>` })] }) +
      S.SettingsGroup({ title: 'History retention', rows: [S.SettingRow({ label: 'Keep finished work for', desc: 'How long History and the tray list finished work.', control: S.Segmented({ act: 'pref-retention', value: st.prefs.retention, items: ['6h', '24h', '7d'].map((x) => ({ id: x, label: x })) }) })] }) +
      S.SettingsGroup({ rows: [S.SettingRow({ label: 'Start at login', desc: 'Keeps the tray icon running in the background.', control: S.Toggle({ on: st.prefs.login, act: 'pref-toggle', arg: 'login', label: 'Start at login' }) })] });
  }
  function setNotifications(st) {
    return S.SettingsHeader({ title: 'Notifications', body: 'A notification fires when work changes lifecycle state. Clicking one opens that item.' }) +
      S.SettingsGroup({ title: 'Notify me when', rows: D.NOTIFICATIONS.map(([id, label]) => S.SettingRow({ label, control: S.Toggle({ on: st.prefs.notify[id], act: 'pref-notify', arg: id, label }) })) }) +
      S.SettingsGroup({ title: 'Preview', rows: [S.SettingRow({ stack: true, control: S.Toast({ system: true, app: 'Skill Bill', when: 'now', title: 'INF-7 is blocked', body: 'Review left 2 Blocker findings on subtask 3 · infra-terraform' }) })] });
  }

  /* Models: execution_matrix in ~/.config/skill-bill/config.json */
  function tierOf(st, id) { return st.phaseTiers[id] || D.PHASE_GROUPS.flatMap((g) => g[1]).find((p) => p[0] === id)[1]; }
  const dirText = (d) => d.model + (d.effort ? ' · ' + d.effort : '');
  function modelEditor(st) {
    const d = st.draft;
    const isPhase = d.target.kind === 'phase';
    const hasOverride = isPhase && !!st.matrix[st.agent].phases[d.target.id];
    const blank = !d.model.trim();
    return `${isPhase ? S.Field({ for: 'ed-tier', label: 'Tier', control: S.Segmented({ act: 'ed-tier', value: d.tier, items: [{ id: 'reasoning', label: 'Reasoning' }, { id: 'implementation', label: 'Implementation' }] }), help: 'Saved under execution_matrix.phase_tiers. It applies to every agent.' }) : ''}
      <div class="l-row g-3 l-wrap l-top"><div class="l-grow" style="min-width:200px">${S.Field({ for: 'ed-model', label: 'Model', control: S.Input({ id: 'ed-model', value: d.model, mono: true, input: 'ed-model', error: blank }), help: blank ? 'A model is required. To drop an override, use Reset to tier.' : 'Passed to the agent as typed.' })}</div><div style="width:150px">${S.Field({ for: 'ed-effort', label: 'Effort', control: S.Input({ id: 'ed-effort', value: d.effort || '', mono: true, placeholder: 'unset', input: 'ed-effort' }), help: 'Optional.' })}</div></div>
      <div class="l-row g-2 l-wrap">${S.Button({ label: 'Save', variant: 'primary', act: 'ed-save', disabled: blank, kbd: '⌘ ↵' })}${S.Button({ label: 'Cancel', variant: 'ghost', act: 'ed-cancel' })}<span class="l-grow"></span>${hasOverride ? S.Button({ label: 'Reset to tier', variant: 'ghost', icon: 'rerun', act: 'ed-reset' }) : ''}</div>`;
  }
  function setModels(st) {
    const head = S.SettingsHeader({ title: 'Models', body: 'Each phase runs on its tier’s model unless the agent has an override for that phase. Saved as <span class="mono">execution_matrix</span> in <span class="mono">~/.config/skill-bill/config.json</span>.' });
    const tabs = `<div class="l-row g-3 l-wrap">${S.Segmented({ act: 'cfg-agent', value: st.agent, items: D.AGENT_CHOICES.map((a) => ({ id: a, label: a, disabled: !D.AGENTS[a].modelCapable, tip: D.AGENTS[a].modelCapable ? null : 'junie cannot honor a model or effort directive' })) })}</div>`;
    const m = st.matrix[st.agent];
    const open = (k) => st.cfgOpen === k;
    const tierRow = (t) => S.ConfigRow({ phase: t === 'reasoning' ? 'Reasoning tier' : 'Implementation tier', tier: t, value: dirText(m[t]), override: true, open: open('tier:' + t), act: 'cfg-open', arg: 'tier:' + t, tags: [], editor: open('tier:' + t) ? modelEditor(st) : '' });
    const phaseRow = ([id]) => {
      const o = m.phases[id];
      const t = tierOf(st, id);
      const tags = [];
      if (o) tags.push(S.Label({ text: 'override', color: 'var(--accent)', tint: true, dot: false }));
      if (st.phaseTiers[id]) tags.push(S.Label({ text: 'tier changed', dot: false }));
      return S.ConfigRow({ phase: id, tier: t, value: dirText(o || m[t]), override: !!o, open: open('phase:' + id), act: 'cfg-open', arg: 'phase:' + id, tags, editor: open('phase:' + id) ? modelEditor(st) : '' });
    };
    return head + tabs + S.SettingsGroup({ title: 'Tiers', rows: [tierRow('reasoning'), tierRow('implementation')] }) + D.PHASE_GROUPS.map(([label, list]) => S.SettingsGroup({ title: label, rows: list.map(phaseRow) })).join('');
  }
  /* Budget rows and per-phase compaction overrides open the same inline editor as the model rows (.sb-cfg-ed). */
  const fmtCount = (n) => Number(n).toLocaleString('en-US');
  function fieldEditor(st, fields, removeLabel) {
    const d = st.draft;
    const err = d.error ? S.Hint({ tone: 'error', text: d.error }) : '';
    return `${fields.map(([key, label]) => `<div style="width:140px">${S.Field({ for: 'ed-' + key, label, control: S.Input({ id: 'ed-' + key, value: d[key], mono: true, input: 'ed-field', error: !!d.error }) })}</div>`).join('')}${err}
      <div class="l-row g-2 l-wrap">${S.Button({ label: 'Save', variant: 'primary', act: 'ed-save', kbd: '⌘ ↵' })}${S.Button({ label: 'Cancel', variant: 'ghost', act: 'ed-cancel' })}<span class="l-grow"></span>${removeLabel ? S.Button({ label: removeLabel, variant: 'ghost', icon: 'trash', act: 'ed-remove' }) : ''}</div>`;
  }
  function setCompaction(st) {
    const c = st.compaction;
    const trigger = Math.floor(c.window / 100) * c.pct;
    const tooLow = trigger < 200000;
    return S.SettingsHeader({ title: 'Compaction', body: 'Compacts an agent’s context before it fills. Saved as <span class="mono">compaction</span> in config.json.' }) +
      S.SettingsGroup({ rows: [
        S.SettingRow({ label: 'Compact long phases', desc: 'enabled · default on', control: S.Toggle({ on: c.enabled, act: 'cmp-toggle', label: 'Compact long phases' }) }),
        S.SettingRow({ label: 'Context window', desc: 'window_tokens · default 400,000', control: `<div style="width:140px">${S.Input({ id: 'cmp-window', value: c.window.toLocaleString('en-US'), mono: true, input: 'cmp-window' })}</div>` }),
        S.SettingRow({ stack: true, label: `Compact at ${c.pct}%`, desc: `trigger_pct · default 70 · compacts at ${trigger.toLocaleString('en-US')} tokens`, control: `<input type="range" class="sb-range" id="cmp-pct" min="1" max="100" step="1" value="${c.pct}" data-input="cmp-pct" aria-label="Compact at percent">${tooLow ? S.Hint({ tone: 'error', text: 'The trigger must be at least 200,000 tokens.' }) : ''}` }),
      ] }) +
      S.SettingsGroup({ title: 'Per-phase overrides', end: [S.Button({ label: 'Add phase override', icon: 'plus', size: 'sm', act: 'toast', arg: 'Pick a phase to override' })], rows: Object.keys(c.phases).length
        ? Object.entries(c.phases).map(([id, o]) => { const k = 'cmpphase:' + id; return S.ConfigRow({ label: id, desc: `compaction.phases.${id}`, value: `${fmtCount(o.window)} tokens · ${o.pct}%`, override: true, open: st.cfgOpen === k, act: 'cfg-open', arg: k, tags: [], editor: st.cfgOpen === k ? fieldEditor(st, [['window', 'Context window'], ['pct', 'Trigger percent']], 'Remove override') : '' }); })
        : [S.SettingRow({ stack: true, control: S.Hint({ text: 'compaction.phases is empty, so every phase uses the setting above.' }) })] });
  }
  function setProject(st) {
    const project = D.projects[0];
    const gw = st.prefs.gradleWrapper;
    const gwBad = gw && (gw.startsWith('/') || gw.split('/').some((s) => s === '..' || s === '.'));
    return S.SettingsHeader({ title: 'Project', body: `Settings in the project store’s <span class="mono">config.yaml</span> (<span class="mono">${esc(project.store.replace('/specs', ''))}</span>), plus the phase instructions committed in the repository.` }) +
      `<div class="l-row g-2">${S.RepoIcon(repoOf(project.name))}<b style="font-weight:500">${esc(project.name)}</b><span class="l-grow"></span>${S.Button({ label: 'Open config.yaml', icon: 'external', size: 'sm', act: 'toast', arg: 'Opened config.yaml in your editor' })}</div>` +
      S.SettingsGroup({ rows: [
        S.SettingRow({ label: 'Spec source', desc: 'spec_type · default local', control: S.Segmented({ act: 'pref-spectype', value: st.prefs.specType, items: [{ id: 'local', label: 'Local files' }, { id: 'linear', label: 'Linear' }] }) }),
        S.SettingRow({ stack: true, control: `<div class="l-col g-2" style="width:100%">${S.Field({ for: 'gradle', label: 'Gradle wrapper', control: S.Input({ id: 'gradle', value: gw, mono: true, placeholder: 'gradlew', input: 'gradle', error: gwBad }), help: 'validation_gate.gradle_wrapper. Repo-relative, no “.” or “..” segments. Leave empty to use the repository’s ./gradlew.' })}${gwBad ? S.Hint({ tone: 'error', text: 'Use a repo-relative path without “.” or “..” segments.' }) : ''}</div>` }),
      ] }) +
      S.SettingsGroup({ title: 'Phase instructions', rows: [
        S.SettingRow({ labelHtml: '<span class="mono">.skill-bill/phases/review.md</span>', label: 'review.md', desc: '1.2 KiB of 16 KiB · read at launch for the review phase', control: S.Button({ label: 'Open', icon: 'external', size: 'sm', act: 'open-file', arg: '.skill-bill/phases/review.md' }) }),
        S.SettingRow({ stack: true, control: S.Hint({ text: 'Add <span class="mono">&lt;phase-id&gt;.md</span> to <span class="mono">.skill-bill/phases/</span> in the repository. Files are committed, so the team shares them.' }) }),
      ] }) +
      budgetGroup(st);
  }
  const budgetText = (key, n) => (key.endsWith('_bytes') ? (n % 1048576 === 0 ? `${n / 1048576} MiB` : n % 1024 === 0 ? `${n / 1024} KiB` : `${fmtCount(n)} B`) : fmtCount(n));
  function budgetGroup(st) {
    const changed = st.budget.filter(([, , v, def]) => v !== def).length;
    return S.SettingsGroup({ title: 'Review context budget', end: changed ? [S.Label({ text: `${changed} changed`, color: 'var(--accent)' })] : null, rows: st.budget.map(([key, label, value, def]) => {
      const k = 'budget:' + key;
      return S.ConfigRow({ label, desc: `${key}${value !== def ? ` · default ${budgetText(key, def)}` : ''}`, value: budgetText(key, value), override: value !== def, open: st.cfgOpen === k, act: 'cfg-open', arg: k, tags: [], editor: st.cfgOpen === k ? fieldEditor(st, [['value', label]]) : '' });
    }), foot: 'Byte limits must be positive.' });
  }
  function setTelemetry(st) {
    const agent = (n, installed) => S.SettingRow({ labelHtml: `<span class="l-row g-2">${S.Avatar({ name: n, initials: agentOf(n).initials, color: agentOf(n).color })}<span>${n}</span></span>`, label: n, desc: agentOf(n).modelCapable ? null : 'Cannot take a model or effort setting', control: S.Label({ text: installed ? 'detected' : 'not detected', color: installed ? 'var(--st-done)' : 'var(--fg-faint)' }) });
    return S.SettingsHeader({ title: 'Telemetry and agents', body: 'Machine-wide settings in <span class="mono">~/.config/skill-bill/config.json</span>.' }) +
      S.SettingsGroup({ title: 'Telemetry', rows: [
        S.SettingRow({ label: 'Level', desc: 'telemetry.level · default anonymous', control: S.Segmented({ act: 'pref-telemetry', value: st.prefs.telemetry, items: [{ id: 'off', label: 'Off' }, { id: 'anonymous', label: 'Anonymous' }, { id: 'full', label: 'Full' }] }) }),
        S.SettingRow({ label: 'Proxy URL', desc: 'telemetry.proxy_url · blank uses the default endpoint', control: `<div style="width:220px">${S.Input({ id: 'tel-proxy', value: '', mono: true, placeholder: 'default' })}</div>` }),
        S.SettingRow({ label: 'Batch size', desc: 'telemetry.batch_size · default 50', control: `<div style="width:80px">${S.Input({ id: 'tel-batch', value: '50', mono: true })}</div>` }),
      ] }) +
      S.SettingsGroup({ title: 'Agents', end: [S.Label({ text: 'read-only', dot: false })], rows: [agent('claude', true), agent('codex', true), agent('cursor', true), agent('junie', false)] }) +
      S.SettingsGroup({ title: 'Files', rows: ['~/.config/skill-bill/config.json', '~/.skill-bill/install-selection.json'].map((f) => S.SettingRow({ labelHtml: `<span class="mono" style="font-size:var(--t-sm)">${f}</span>`, label: f, control: S.Button({ label: 'Open', icon: 'external', size: 'sm', act: 'open-file', arg: f }) })) });
  }

  /* ---------- page ---------- */
  function app(st) {
    const views = { work, detail, history, settings: settingsPage };
    return shell(st, (views[st.view] || work)(st));
  }

  window.Screens = {
    app, work, detail, history, settingsPage, specsPane, terminal, tierOf, lookup, itemBy, subKeys, selectedSub, paneShown,
    statusIcon, stepText, clock, rowKey, repoOf, agentOf, LIFE, SECTIONS, resumable, controllable, pauseEligible, recoveryOffered,
    get rows() { return rows; },
  };
})();
