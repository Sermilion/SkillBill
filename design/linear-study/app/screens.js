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
    failed: ['Failed', 'var(--st-failed)'], terminal: ['Completed', 'var(--st-done)'], canceled: ['Canceled', 'var(--st-pending)'], idle: ['Pending', 'var(--st-pending)'],
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
  /* A monitor run is keyed by its PR ("monitor #482"), as design/monitor-run names it. */
  const rowKey = (it) => (it.kind === 'goal' ? it.id : it.kind === 'operation' ? it.op : it.monRun ? `${it.phase} #${it.monRun.pr.num}` : it.phase);
  const itemBy = (id) => D.items.find((x) => x.id === id);
  function lookup(id) {
    const it = itemBy(id) || (D.valRoutes || []).find((x) => x.id === id) || (D.stateExamples || []).find((x) => x.id === id);
    if (it) return it;
    const h = D.history.find((x) => x.id === id);
    if (!h) return null;
    return Object.assign({ id: h.id, kind: h.kind, phase: h.label, op: h.label, repo: h.repo, lifecycle: h.lifecycle, section: 'history', title: h.title, step: h.label || 'finished', summary: LIFE[h.lifecycle][0], goalMs: h.ms, live: false, fresh: true, finishedAt: h.at, started: '—', updated: h.at, runOptions: { canLaunch: h.lifecycle === 'failed' || h.lifecycle === 'canceled', canCancel: false, launchLabel: 'Run again', agentId: null }, subtasks: [], steps: [] }, h.item || {});
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
    /* A plan launched on its own offers Implement, on once every spec is saved; a ready plan has nothing left to cancel. */
    const plan = isPlanRun(it), ready = plan && planReady(it);
    if (plan) out.push(S.Button({ label: 'Implement', icon: 'play', variant: ready && !st.implemented.has(it.id) ? 'primary' : 'secondary', act: 'plan-implement', arg: it.id, disabled: !ready || st.implemented.has(it.id) }));
    if (!ready) out.push(S.Button({ label: 'Cancel', icon: 'x', variant: 'danger', act: 'cancel', arg: it.id, disabled: !ro.canCancel }));
    if (it.incident && it.incident.recovery === 'available' && !st.recovered.has(it.id)) out.push(S.Button({ label: 'Recover', icon: 'refresh', act: 'recover', arg: it.id, disabled: it.refreshFailed || !recoveryOffered(st, it), tip: recoveryOffered(st, it) ? null : 'Confirm the prerequisites first' }));
    const run = runAppView(st, it);
    if (run) out.push(run.button);
    return `<div class="l-col g-2"><div class="l-row g-2 l-wrap">${out.join('')}</div>${ro.canLaunch && resumable(it) ? `<span class="t-faint t-xs">Starts the installed agent harness</span>` : ''}${plan && !ready ? `<span class="t-faint t-xs">Implement turns on when every spec is saved</span>` : ''}${it.refreshFailed ? S.Hint({ tone: 'warn', text: 'Refresh the detail before using run controls.' }) : run && !it.refreshFailed ? run.status : ''}</div>`;
  }

  /* ---------- Run app (SONA-10) ---------- */
  /* Shown on goals whose routed pack declares an app-run recipe for the device platform (sample: `appRun` on the repository).
   * The device is the sidebar QA pick; Let QA choose resolves to the first ready device, else the first stopped AVD. */
  function runAppTarget(st) {
    const list = deviceList(st);
    if (!list) return { problem: 'adb not found. Install Android SDK Platform-Tools, or set ANDROID_HOME.' };
    const picked = st.qaDev.selected !== 'auto' && list.find((d) => d.id === st.qaDev.selected);
    const d = picked || list.find((x) => x.state === 'ready') || list.find((x) => x.state === 'stopped');
    if (!d) return { problem: 'No device. Plug in a phone with USB debugging on, or create an AVD.' };
    return { device: { id: d.id, name: d.name, mono: d.kind === 'emu', boots: d.state === 'stopped', serial: d.kind === 'emu' ? 'emulator-5556' : 'R5CT1234ABC', wire: d.kind === 'emu' && !d.running ? `avd:${d.name}` : `serial:${d.kind === 'emu' ? 'emulator-5556' : 'R5CT1234ABC'}` }, auto: !picked };
  }
  const runAppEligible = (it) => it && it.kind === 'goal' && (repoOf(it.repo).appRun || []).includes('android');
  /* Run app is on for a completed goal and for a goal in monitoring (watching CI on its PR). Every other state is off: the
   * code is still changing, and an agent writing in the checkout would race the build. A CI repair (monitor_fix) edits the
   * checkout, so it turns the button off until monitoring resumes. */
  const runAppMonitoring = (it) => it.lifecycle === 'active' && it.step === 'monitor';
  function runAppBlocker(st, it, target) {
    if (it.refreshFailed) return 'Refresh the detail before using run controls.';
    if (it.lifecycle === 'active' && it.step === 'monitor_fix') return 'Off while a CI repair edits the checkout. Run app turns back on when monitoring resumes.';
    if (it.lifecycle !== 'terminal' && !runAppMonitoring(it)) return 'Run app turns on when the goal completes or reaches monitoring.';
    return target.problem || null;
  }
  function runAppView(st, it) {
    if (!runAppEligible(it)) return null;
    const target = runAppTarget(st), run = st.appRun[it.id] || { state: 'idle' };
    const reason = runAppBlocker(st, it, target);
    const busy = run.state === 'building';
    const device = busy ? run.device : target.device;
    const where = it.lifecycle === 'terminal' ? `Builds in a temporary worktree on ${it.branch}` : 'Builds in the goal checkout while CI runs';
    const tip = device ? `${where}, then opens it on ${device.name}${device.boots ? ' after booting it' : ''}${target.auto ? ' (Let QA choose picked it)' : ''}` : null;
    const button = S.RunAppButton({ id: it.id, device, tip, state: busy ? 'building' : reason ? 'off' : 'idle', reason,
      expanded: !!st.popover && st.popover.type === 'devices' && st.popover.runApp === it.id });
    const name = (d) => `<b>${S.esc(d.name)}</b>`;
    const status = busy ? S.RunAppStatus({ state: 'building', tab: run.tab, html: `Building for ${name(run.device)} · <code>${S.esc(run.step)}</code>` })
      : run.state === 'running' ? S.RunAppStatus({ state: 'running', tab: run.tab, html: `Running on ${name(run.device)} · opened ${S.esc(run.at)}` })
      : run.state === 'failed' ? S.RunAppStatus({ state: 'failed', tab: run.tab, html: `Build failed · <code>:app:compileDebugKotlin</code> · nothing was installed on ${name(run.device)}` })
      : run.state === 'contended' ? S.RunAppStatus({ state: 'contended', tab: run.tab, html: `${name(run.device)} is in use by QA run <b>${S.esc(run.holder)}</b>. Nothing was installed. Pick another device.` })
      : run.state === 'stopped' ? S.RunAppStatus({ state: 'stopped', tab: run.tab, text: 'Build stopped. Nothing new was installed.' })
      : reason ? S.RunAppStatus({ state: 'off', text: reason }) : '';
    return { button, status };
  }

  /* ---------- shell ---------- */
  function sidebar(st) {
    const usage = usageData(st);
    const count = (sec) => D.items.filter((x) => x.section === sec).length;
    const live = D.items.filter((x) => x.section !== 'finished').length;
    const navOn = (v, sec) => (st.view === v && (sec === undefined || st.section === sec)) || (v === 'work' && sec === undefined && st.view === 'detail');
    return S.Sidebar({
      top: S.WorkspaceSwitcher({ name: 'Sona', actions: [S.IconButton({ icon: 'search', label: 'Command menu', kbd: '⌘ K', act: 'cmdk', size: 'sm' })] }),
      items: [
        S.SidebarItem({ icon: 'pencil', label: 'New task', count: st.nt.chats.length > 1 ? st.nt.chats.length : null, on: st.view === 'newtask', act: 'nav', arg: 'newtask', tip: 'New task', kbd: 'G N' }),
        S.SidebarItem({ icon: 'target', label: 'Work', count: live, on: navOn('work', null) || st.view === 'detail', act: 'nav', arg: 'work', tip: 'Work', kbd: 'G W' }),
        S.SidebarItem({ icon: 'history', label: 'History', on: st.view === 'history', act: 'nav', arg: 'history', tip: 'History', kbd: 'G H' }),
        S.SidebarSection({ label: 'Board', act: 'sec-toggle', arg: 'board', closed: st.closed.has('board'), items: SECTIONS.map(([id, label, state]) => S.SidebarItem({
          lead: S.StatusIcon(state, 0.5, { monochrome: true }), label, child: false, act: 'section', arg: id, on: st.view === 'work' && st.section === id,
          attention: id === 'needs' && count(id) ? count(id) : null, count: id === 'needs' ? null : count(id),
        })) }),
      ],
      footer: [
        S.SidebarItem({ icon: 'fileText', label: 'Specs', act: 'specs-toggle', on: st.specsOpen, tip: 'Specs pane', kbd: 'G S' }),
        S.SidebarItem({ icon: 'terminal', label: 'Terminal', act: 'term-toggle', on: st.termOpen, tip: 'Terminal', kbd: '⌃ `' }),
        S.SidebarItem({ icon: 'sliders', label: 'Settings', act: 'nav', arg: 'settings', tip: 'Settings', kbd: 'G ,' }),
        S.SidebarItem({ icon: 'keyboard', label: 'Keyboard shortcuts', act: 'shortcuts', tip: 'Keyboard shortcuts', kbd: '?' }),
        S.UsageCard(Object.assign({}, usage, { act: 'usage-toggle', expanded: !!st.popover && st.popover.type === 'usage' })),
        deviceSwitcher(st),
      ],
    });
  }

  /* The card follows the open run or selected chat. A tab picked in that context holds until the context's agent changes, and a
   * context with no known agent keeps the last provider shown. */
  function usageData(st) {
    const contextual = st.view === 'detail' || st.view === 'newtask';
    const run = st.view === 'detail' ? lookup(st.detailId) : null;
    const agent = st.view === 'newtask' ? ntChat(st).agent : run && (run.agent || (run.runOptions || {}).agentId);
    const fromContext = contextual ? D.USAGE.providers.find((p) => p.toLowerCase() === agent) : null;
    const override = st.usage.override && st.usage.override.agent === agent ? st.usage.override.provider : null;
    const provider = override || fromContext || (contextual ? st.usage.last || st.usage.provider : st.usage.provider);
    if (fromContext && !override) st.usage.last = fromContext;
    const scenario = st.usage.scenario;
    const data = (provider === 'Cursor' || provider === 'Junie') && !['signedout', 'loading'].includes(scenario) ? D.USAGE[provider.toLowerCase()]
      : provider === 'Claude' && D.USAGE.claude[scenario] ? D.USAGE.claude[scenario]
      : D.USAGE.scenarios[scenario];
    const colors = Object.fromEntries(D.USAGE.providers.map((p) => [p, D.AGENTS[p.toLowerCase()].color]));
    const agents = D.AGENT_CHOICES.map((name) => ({ name, label: name.slice(0, 1).toUpperCase() + name.slice(1), initials: D.AGENTS[name].initials, color: D.AGENTS[name].color }));
    return Object.assign({}, data, { provider, contextAgent: contextual ? agent : null, color: colors[provider], colors, providers: D.USAGE.providers, agents, defaultAgent: st.prefs.defaultAgent });
  }

  /* ---------- QA device (sidebar footer; QA device design, variant A "Switcher row") ---------- */
  /* `list` is one of D.QA_DEVICES.lists (null when adb is not found); `selected` is a device id or 'auto'. Let QA choose
   * keeps today's behavior: the QA pack picks a free ready device or boots an AVD. A stopped AVD can be picked and QA boots
   * it when the run starts. Unauthorized, offline and leased devices show but cannot be picked. */
  const DEVICE_AUTO = { id: 'auto', kind: 'auto', name: 'Let QA choose' };
  const DEVICE_STATE = { ready: ['var(--st-done)', 'Ready'], leased: ['var(--st-planning)', 'In use by '], unauthorized: ['var(--st-blocked)', 'Unauthorized'], offline: ['var(--fg-faint)', 'Offline'], stopped: ['var(--fg-faint)', 'Not running · QA boots it'] };
  const DEVICE_FIX = { unauthorized: 'Unlock the phone and accept the USB debugging prompt.', offline: 'Reconnect the device, then refresh the list.' };
  const deviceConnected = (d) => d.kind === 'phone' || (d.kind === 'emu' && d.running);
  const devicePickable = (d) => d.kind === 'auto' || d.state === 'ready' || d.state === 'stopped';
  /* Popup order: Let QA choose, connected devices, then stopped AVDs. The arrow keys follow it. */
  const deviceOptions = (list) => (list ? [DEVICE_AUTO, ...list.filter(deviceConnected), ...list.filter((d) => !deviceConnected(d))] : []);
  const deviceIcon = (d) => (d.kind === 'auto' ? 'devices' : d.kind === 'emu' ? 'emulator' : 'phone');
  const deviceList = (st) => D.QA_DEVICES.lists[st.qaDev.list];
  function deviceTrigger(list, selected) {
    if (!list) return { icon: 'phone', title: 'No devices', sub: 'adb not found', pip: 'err' };
    const sel = deviceOptions(list).find((d) => d.id === selected) || DEVICE_AUTO;
    const warn = list.some((d) => d.state === 'unauthorized' || d.state === 'offline');
    if (sel.kind === 'auto') {
      const ready = list.filter((d) => d.state === 'ready').length;
      return { icon: 'devices', title: sel.name, sub: ready ? `${ready} ready` : list.length ? 'No ready device · QA boots an AVD' : 'No devices', pip: warn ? 'warn' : ready ? 'ready' : 'off' };
    }
    return { icon: deviceIcon(sel), title: sel.name, mono: sel.kind === 'emu', sub: sel.state === 'stopped' ? 'Not running · QA boots it' : [sel.transport, sel.api].filter(Boolean).join(' · '), pip: warn ? 'warn' : sel.state === 'ready' ? 'ready' : 'off' };
  }
  function deviceSwitcher(st) {
    return S.DeviceSwitcher(Object.assign(deviceTrigger(deviceList(st), st.qaDev.selected), { id: 'qa-device', act: 'dev-toggle', expanded: !!st.popover && st.popover.type === 'devices' }));
  }
  function deviceOption(d, i, o) {
    const s = DEVICE_STATE[d.state];
    const facts = d.kind === 'auto' ? [{ text: 'Any free ready device, or boot an AVD' }] : [d.transport === 'Wi-Fi' ? { icon: 'wifi', text: 'Wi-Fi' } : d.transport ? { text: d.transport } : null, d.api ? { text: d.api } : null].filter(Boolean);
    return S.DeviceOption({ index: i, icon: deviceIcon(d), name: d.name, mono: d.kind === 'emu', state: s ? { color: s[0], text: d.state === 'leased' ? s[1] + d.lease : s[1], lease: d.state === 'leased' } : null, facts, fix: DEVICE_FIX[d.state], selected: d.id === o.selected, kb: i === o.kb, disabled: !devicePickable(d), act: o.act || 'dev-pick', arg: d.id });
  }
  /* `o`: { list, selected, kb (keyboard index into deviceOptions), checking (a refresh is running), act }. */
  function devicePanelView(o) {
    if (!o.list) return S.DevicePanel({ checking: o.checking, problem: { title: 'adb not found', html: 'Install Android SDK Platform-Tools, or set <code>ANDROID_HOME</code> so the app can find <code>platform-tools/adb</code>.' } });
    const opts = deviceOptions(o.list);
    const connected = o.list.filter(deviceConnected), avds = o.list.filter((d) => !deviceConnected(d));
    const row = (d) => deviceOption(d, opts.indexOf(d), o);
    return S.DevicePanel({
      checking: o.checking, sub: connected.length ? `${connected.length} connected` : '', lead: row(DEVICE_AUTO),
      groups: [
        { head: 'Connected', rows: connected.map(row), empty: 'No phone or running emulator. Plug in a phone with USB debugging on.' },
        { head: 'Emulators', rows: avds.map(row), empty: 'No AVDs. Create one in Android Studio’s Device Manager.' },
      ],
    });
  }
  const devicePanel = (st) => devicePanelView({ list: deviceList(st), selected: st.qaDev.selected, kb: st.qaDev.kb, checking: st.qaDev.checking });
  /* The picked device's name for the "QA my branch" quick start; null while QA chooses. */
  const qaDeviceName = (st) => { const l = deviceList(st); const d = l && l.find((x) => x.id === st.qaDev.selected); return d ? d.name : null; };
  function paneToggles(st) {
    return [
      S.IconButton({ icon: 'refresh', label: 'Refresh', act: 'refresh' }),
      S.IconButton({ icon: 'fileText', label: st.specsOpen ? 'Close specs pane' : 'Specs', kbd: 'G S', act: 'specs-toggle', on: st.specsOpen }),
      S.IconButton({ icon: 'terminal', label: st.termOpen ? 'Close terminal' : 'Terminal', kbd: '⌃ `', act: 'term-toggle', on: st.termOpen }),
    ];
  }
  function workArea(st, header, body) {
    return S.WorkArea({ header, body, terminal: st.termOpen ? terminal(st) : '', side: st.specsOpen ? specsPane(st) : '' });
  }
  function shell(st, main) {
    return S.AppShell({ sideOpen: st.sideOpen, side: st.view === 'settings' ? settingsNav(st) : sidebar(st), main });
  }

  /* ---------- Work board (feature/work) ---------- */
  function matches(it, q) {
    if (!q) return true;
    const hay = [it.id, it.issue, it.title, it.repo, it.branch, it.phase, it.op, it.summary].filter(Boolean).join(' ').toLowerCase();
    return q.toLowerCase().split(/\s+/).every((t) => hay.includes(t));
  }
  /* ---------- Build indicator ----------
   * The build a run's agent is inside, or its last build. SKILL-417 follows st.build (the #build/<state>/<type> routes);
   * other runs keep the fixed samples in D.BUILD.runs. Null when no build tool command was seen. */
  function buildOf(st, it) {
    const fixed = D.BUILD.runs[it.id];
    if (fixed) return Object.assign({ t0: D.T0 }, fixed);
    if (it.id !== D.BUILD.focus) return null;
    const pick = (st && st.build) || D.BUILD.live;
    const t = D.BUILD.types[pick.type];
    if (!t || pick.state === 'idle') return null;
    const b = Object.assign({ t0: D.T0, pid: 48213, started: '14:28' }, t);
    const sec = 1000, M = 60000;
    if (pick.state === 'running') Object.assign(b, { state: 'running', ms: Math.round(t.usual * 0.62), prev: ['done', S.fmtDur(t.usual - 8 * sec, true), '18m ago'] });
    if (pick.state === 'first') Object.assign(b, { state: 'running', ms: Math.round(t.usual * 0.4), usual: null });
    if (pick.state === 'slow') Object.assign(b, { state: 'running', ms: t.usual + 1 * M + 55 * sec, prev: ['done', S.fmtDur(t.usual + 4 * sec, true), '18m ago'] });
    if (pick.state === 'passed') Object.assign(b, { state: 'passed', ms: t.usual - 6 * sec, exit: 0, ago: '2m ago' });
    if (pick.state === 'failed') Object.assign(b, { state: 'failed', ms: Math.round(t.usual * 0.3), exit: 1, ago: '1m ago' });
    return b;
  }
  function workRow(st, it, i) {
    const meta = [];
    const build = buildOf(st, it);
    if (build && (build.state === 'running' || build.state === 'failed')) meta.push(S.BuildChip(build));
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
      middle: S.Input({ id: 'work-filter', icon: 'search', placeholder: 'Filter work', value: st.filter, input: 'work-filter', width: 'min(280px, 40vw)' }),
      actions: paneToggles(st),
    });
    return workArea(st, header, S.ScrollArea({ id: 'work', body: body || empty }));
  }

  /* A no-change pause waits for an operator decision. The app only explains it: the operator settles it in chat or runs the
   * command, then resumes the goal. */
  function operatorDecision(st, it) {
    const cmd = `sona goal operator-decision ${it.id} --subtask ${it.current} --decision retry_fix|accept_and_advance|abandon_subtask`;
    return `<div class="l-col g-2">
      ${S.Hint({ tone: 'info', text: `Subtask ${it.current} finished with no code changes and is waiting for your decision. Settle it in chat, or run this command and then resume the goal. Pass --instructions with retry_fix.` })}
      <div class="l-row g-2"><span class="mono t-muted t-sm l-grow">${esc(cmd)}</span>${S.IconButton({ icon: 'copy', label: 'Copy decision command', act: 'copy', arg: cmd, size: 'sm' })}</div>
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
      if (id === p.now) return { label: id, state: p.failed || it.lifecycle === 'failed' ? 'fail' : it.lifecycle === 'canceled' ? 'skipped' : it.lifecycle === 'active' && it.live ? 'now' : 'wait', count: it.exec };
      return { label: id, state: '' };
    });
  }
  function incidentPanel(st, it) {
    const inc = it.incident;
    if (!inc) return '';
    const tone = inc.recovery === 'available' ? 'info' : 'warn';
    const recovered = st.recovered.has(it.id);
    const body = [
      `<div class="l-col g-1">${S.ExpandableText({ html: `<span class="t-faint">Cause:</span> ${esc(inc.cause)}` })}${S.ExpandableText({ html: `<span class="t-faint">Next:</span> ${esc(inc.nextAction)}` })}<span class="t-faint t-xs">certainty ${esc(inc.certainty)}${inc.unavailableReason ? ' · recovery unavailable: ' + esc(inc.unavailableReason.toLowerCase().replace(/_/g, ' ')) : ''}</span></div>`,
      inc.prerequisites && !recovered ? `<div class="l-col g-2">${S.Checklist({ act: 'prereq', items: inc.prerequisites.map((p) => ({ id: it.id + ':' + p.id, text: p.text, checked: st.prereqs.has(it.id + ':' + p.id) })) })}</div>` : '',
    ].join('');
    const decision = it.awaitsDecision ? operatorDecision(st, it) : '';
    const evOpen = !isCollapsed(st, 'incident-evidence', true);
    const evidence = inc.evidence.length ? `<div class="l-col g-2">${S.EvidenceToggle({ label: 'Evidence', count: inc.evidence.length, open: evOpen, act: 'sec-collapse', arg: 'incident-evidence|1' })}${evOpen ? S.Box(inc.evidence.map((e) => S.EvidenceRow(e))) : ''}</div>` : '';
    return secBlock(st, 'incident', { title: 'Incident', end: [S.Label({ text: recovered ? 'Recovering' : inc.heading, color: recovered ? 'var(--st-active)' : tone === 'info' ? 'var(--st-planning)' : 'var(--st-blocked)', dot: true })], body:
      S.Callout({ tone: recovered ? 'info' : tone, title: recovered ? 'Recovering' : inc.heading, body }) +
      decision +
      evidence +
      `<div class="l-row g-2 l-wrap t-muted t-sm"><span>Report: ${esc(inc.report)}</span>${/issue #/.test(inc.report) ? S.Button({ label: 'Open issue', icon: 'external', size: 'sm', variant: 'ghost', act: 'toast', arg: 'Opened the issue in your browser' }) : ''}</div>` });
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
  /* The details panel floats over the document, so it shows whenever it is open, at any window width and beside the Specs
   * pane. Subtask rows never expand: selecting one opens the panel. Width: the operator's dragged width, else 380px for a
   * goal and 272px for other runs. */
  const paneShown = (st) => !!st.propsOpen;
  const PANE_MIN = 272;
  /* Default width as the app's FloatingPane: the wide variant (800px) for a goal, 560px otherwise, both capped at 70% of
   * the work body. A dragged width (st.paneW) replaces it. */
  const paneWidth = (st, it) => (st.paneW ? `${st.paneW}px` : `min(${it.kind === 'goal' ? 800 : 560}px, 70%)`);
  const specArg = (it, s) => `${it.id}/${s.spec}`;
  const subChip = (it, d) => {
    const t = it.subtasks.find((x) => x.id === d.id);
    return { num: pad(d.id), optional: d.optional, lead: S.StatusIcon(SUB_STATE[t.status], 0.5), act: 'sub-select', arg: `${it.id}|${d.id}`, tip: `Subtask ${pad(d.id)}: ${t.title}${d.optional ? ' (optional)' : ''}` };
  };
  /* ---------- Audit and Review records (the app's SubtaskAuditResult and ReviewPass) ----------
   * A subtask with `auditResult` / `review` uses them as recorded. Others are derived from the compact sample (`audit`,
   * `reviews`) and the subtask's phase states, so every subtask shows the same parts: criteria, attempts, lanes, refs,
   * statuses and confidence. */
  const AREA_OF = (t) => (/secret|sensitive|credential|token|password|leak/i.test(t) ? 'security' : /test|assert/i.test(t) ? 'testing'
    : /twice|poll|slow|cache|per /i.test(t) ? 'performance' : /module|attached|layer|depend|parent|snapshot/i.test(t) ? 'architecture' : 'code-quality');
  const platformOf = (it) => ({ 'infra-terraform': 'terraform' })[it.repo] || (/news|android|app/i.test(it.repo || '') ? 'kmp' : 'kotlin');
  const CONF = { blocker: 'high', critical: 'high', major: 'medium', minor: 'low', nit: 'low' };
  function reviewModel(it, s) {
    const ph = subPhaseStates(it, s).review;
    const base = s.review ? { passes: s.review.passes.slice() } : (() => {
      const plat = platformOf(it), refs = {};
      const passes = (s.reviews || []).map((p, i, all) => {
        const next = all[i + 1], last = i === all.length - 1;
        const findings = p.findings.map(([sev, text, where]) => {
          if (!refs[text]) refs[text] = `F-${String(Object.keys(refs).length + 1).padStart(3, '0')}`;
          const carried = next && next.findings.some((g) => g[1] === text);
          const status = last ? (s.status === 'complete' || s.status === 'completed_no_change' ? 'fixed' : 'open') : carried ? 'open' : 'fixed';
          return { ref: refs[text], sev, text, where, status, lane: `bill-${plat}-code-review-${AREA_OF(text)}`, confidence: CONF[String(sev).toLowerCase()] || 'medium' };
        });
        const lanes = ['architecture', 'code-quality', 'security', 'testing'].map((a) => `bill-${plat}-code-review-${a}`);
        findings.forEach((f) => { if (!lanes.includes(f.lane)) lanes.push(f.lane); });
        return { n: p.n, verdict: p.verdict.replace(/ /g, '_'), lanes, findings };
      });
      return { passes };
    })();
    if (ph === 'now') {
      const lanes = (base.passes[0] && base.passes[0].lanes) || ['architecture', 'code-quality', 'security', 'testing'].map((a) => `bill-${platformOf(it)}-code-review-${a}`);
      base.running = { n: base.passes.length + 1, lanes: lanes.map((name, i) => ({ name, finished: i < Math.ceil(lanes.length / 2) })) };
    }
    return base;
  }
  function genCriteria(s, n) {
    const t = s.title.replace(/\.$/, '');
    const pool = [`${t}, as the subtask spec describes, with no change to unrelated behavior.`, 'Existing callers and persisted data keep working after the change, with no migration step for the operator.',
      'Tests cover the new behavior and its failure path, and they fail on the previous implementation.', 'Every fallback the change adds records its reason, so a degraded run is visible in the logs.',
      'The change stays inside the modules the spec names; no new dependency crosses a module boundary.'];
    return Array.from({ length: n }, (_, k) => ({ ref: `AC-${String(k + 1).padStart(3, '0')}`, text: pool[k % pool.length], met: true }));
  }
  function auditModel(it, s) {
    if (s.auditResult) return s.auditResult;
    const states = subPhaseStates(it, s), ph = states.audit;
    if (ph === 'now') return { outcome: 'running', attempts: [{ n: 1, passed: false, uncovered: 1, next: 'audit_implement_fix' }] };
    const said = s.audit && s.audit.summary ? (/\bboth\b/i.test(s.audit.summary) ? 2 : /\bthree\b/i.test(s.audit.summary) ? 3 : 0) : 0;
    const n = said || 3;
    if (ph === 'fail' || ph === 'wait' || (s.audit && /fail|gap|unmet/i.test(s.audit.verdict))) {
      const criteria = genCriteria(s, n).map((c, k) => (k === 0 ? Object.assign(c, { met: false }) : c));
      return { outcome: 'failed', met: n - 1, total: n, attemptTotal: 2, summary: s.audit && s.audit.summary, criteria, attempts: [{ n: 1, passed: false, uncovered: 2, next: 'audit_implement_fix' }, { n: 2, passed: false, uncovered: 1 }] };
    }
    if (s.audit || ph === 'done') {
      const criteria = genCriteria(s, n);
      const tries = 1 + (s.id % 2);
      if (tries > 1) criteria[criteria.length - 1].fixedIn = 2;
      return { outcome: 'passed', met: n, total: n, attemptTotal: tries, summary: s.audit && s.audit.summary, criteria,
        attempts: tries > 1 ? [{ n: 1, passed: false, uncovered: 1, next: 'audit_implement_fix' }, { n: 2, passed: true }] : [{ n: 1, passed: true }] };
    }
    const reviewStarted = (s.reviews || []).length || states.review;
    return !reviewStarted && ['pending', 'in_progress', 'blocked'].includes(s.status) ? { outcome: 'upcoming' } : null;
  }
  /* Audit and Review drawn with the Verify result parts: Label for the verdict, ExpandableText for the summary, a Box of
   * CriterionRows with a Met or Missing Badge (the hint says which attempt fixed it), CheckLines for earlier attempts, and
   * FindingRows with the ref, severity Badge, state Badge, and the location Chip, lane and confidence when opened. */
  const sevTone = (sev) => ({ blocker: 'failed', critical: 'failed', major: 'blocked', minor: 'neutral' })[String(sev).toLowerCase()] || 'faint';
  const FIND_STATE = { open: ['Open', 'var(--st-blocked)'], fixed: ['Fixed', 'var(--st-done)'], accepted: ['Accepted', 'var(--st-active)'], declined: ['Declined', 'var(--fg-faint)'], false_positive: ['False positive', 'var(--fg-faint)'], refuted: ['Refuted', 'var(--fg-faint)'] };
  const laneChip = (l) => { const n = l.replace(/^bill-/, ''), i = n.indexOf('-code-review-'); return i < 0 ? l : `${n.slice(0, i)} · ${n.slice(i + 13)}`; };
  /* The goal's shared preplan digest (PLAYBOOK preplan_digest): history and decision findings, reusable patterns, selected
   * add-ons and the validation strategy. One Fold each, folded to a one-line note; a click opens it in place. Every subtask
   * shows the same digest, because preplan runs once per goal. */
  function preplanDigest(it) {
    if (it.preplan) return it.preplan;
    const plat = platformOf(it);
    return {
      findings: [['agent/history.md', `${it.title.split(' ').slice(0, 4).join(' ')}: earlier attempt`, 'A previous change in this area was reverted after its tests passed locally and failed in CI.'],
        ['agent/decisions.md', 'Fallbacks emit a record', 'Every degraded path writes a diagnostic; silent fallbacks are rejected in review.']],
      patterns: [['Reuse the existing repository seam instead of a new client', `${it.repo}/core`], ['Keep the change behind the current module boundary', `${it.repo}`]],
      addons: [`bill-${plat}-code-review-architecture`, `bill-${plat}-code-review-testing`],
      validation: 'Run the module checks for every touched module, then the contract tests once at the end.',
    };
  }
  function preplanBody(st, it) {
    const d = preplanDigest(it), key = (k) => `${it.id}|preplan|${k}`, open = (k) => st.auditMet.has(key(k));
    const fold = (k, label, note, body) => S.Fold({ label, note, act: 'audit-met', arg: key(k), open: open(k), inset: true, body });
    return `<div class="l-col g-1">${S.VerifyDoc([
      fold('hist', 'History and decisions', `${d.findings.length} ${d.findings.length === 1 ? 'heading' : 'headings'} selected`, d.findings.map(([file, heading, why]) => S.PeekOutput({ icon: 'history', html: `<b>${esc(heading)}</b> · ${esc(why)}`, end: file.split('/').pop(), tip: `${file} · ${heading}`, act: 'open-file', arg: `${it.id}/${file}` })).join('')),
      fold('pat', 'Reusable patterns', `${d.patterns.length} found`, S.KeyValues(d.patterns.map(([what, where]) => [where, what]))),
      fold('add', 'Add-ons', d.addons.length ? d.addons.map(laneChip).join(', ') : 'none', d.addons.length ? `<div class="l-row g-1 l-wrap">${d.addons.map((a) => S.Label({ text: laneChip(a), dot: false, tip: a })).join('')}</div>` : '<span class="t-muted">No add-on applies.</span>'),
      fold('val', 'Validation strategy', d.validation.length > 60 ? d.validation.slice(0, 57) + '…' : d.validation, S.Note({ text: d.validation })),
      /* The goal-level spec files preplan read (spec.md, decomposition-manifest.yaml, traceability.md); subtask specs open
       * from their plan cards. */
      ...(() => { const subSpecs = new Set((it.subtasks || []).map((x) => x.spec)); const files = bundleOrder((it.specBundle || []).filter((f) => !subSpecs.has(fileName(f)) && !/^spec_subtask_/.test(fileName(f))));
        return files.length ? [fold('spec', 'Goal spec', files.map(fileName).join(', '), files.map((f) => S.PeekOutput({ icon: f.endsWith('.yaml') ? 'file' : 'fileText', html: `<span class="mono">${esc(fileName(f))}</span>`, tip: `Open ${f}`, act: 'open-file', arg: `${it.id}/${f}` })).join(''))] : []; })(),
    ])}<span class="t-faint t-xs">Shared by every subtask in this goal.</span></div>`;
  }
  /* A subtask's plan, from its subtask_plan record: the sub-spec it governs, a one-line summary, then three Folds: the
   * ordered steps with the criteria each covers, criteria coverage, and the record itself (manifest order, hashes, and the
   * repair the runtime applied when the payload failed its contract). */
  const hash = (seed) => Array.from({ length: 64 }, (_, k) => '0123456789abcdef'[(seed * 31 + k * 17 + (k * k) % 7) % 16]).join('');
  function planRecord(it, s) {
    if (s.plan) return s.plan;
    const crit = ((auditModel(it, s) || {}).criteria || genCriteria(s, 3)).map((c) => c.ref);
    const t = s.title.replace(/\.$/, '').toLowerCase();
    return {
      summary: `${crit.length} criteria in ${crit.length + 2} steps: ${t}, then tests that fail on the old behavior.`,
      steps: [[`Read the current code path and the spec for ${t}`, []], ...crit.map((ref, k) => [k === 0 ? `Make the core change: ${t}` : `Cover ${ref} where the spec places it`, [ref]]), ['Add tests for each criterion, including the failure path', crit]],
      repaired: s.id % 3 === 0 ? { format: 'json', operation: 'closed an unterminated array' } : null,
    };
  }
  function planBody(st, it, s, summary) {
    const p = planRecord(it, s), key = (k) => `${it.id}|${s.id}|plan|${k}`, open = (k) => st.auditMet.has(key(k));
    const crit = [...new Set(p.steps.flatMap(([, refs]) => refs))];
    const by = (ref) => p.steps.map(([, refs], i) => (refs.includes(ref) ? i + 1 : 0)).filter(Boolean);
    const covered = crit.filter((r) => by(r).length).length;
    const fold = (k, label, note, body) => S.Fold({ label, note, act: 'audit-met', arg: key(k), open: open(k), inset: true, body });
    const facts = [['Sub-spec', `${it.id}/${s.spec}`, true], ['Manifest order', String(s.id - 1), true], ['Spec hash', hash(s.id * 7).slice(0, 12) + '…', true], ['Payload hash', hash(s.id * 11).slice(0, 12) + '…', true]];
    if (p.repaired) facts.push(['Repaired', `${p.repaired.format} · ${p.repaired.operation}`]);
    return `<div class="l-col g-2">${S.PeekOutput({ icon: 'fileText', html: `<span class="mono">${esc(s.spec)}</span>`, end: `${crit.length} criteria`, tip: `Open ${s.spec}`, act: 'open-file', arg: `${it.id}/${s.spec}` })}
      ${S.Note({ text: summary || p.summary })}${S.VerifyDoc([
      /* Steps use the audit's CriterionRow: the step number, its text, one Badge for coverage (the ref, "2 criteria", or
       * "Setup" for a step that covers none); open, the criteria it covers. */
      fold('steps', 'Steps', `${p.steps.length} in order`, S.Box(p.steps.map(([text, refs], i) => {
        const k = key(`step-${i + 1}`);
        return S.CriterionRow({ n: String(i + 1), html: esc(text), badge: { text: refs.length === 1 ? refs[0] : refs.length ? `${refs.length} criteria` : 'Setup', tone: refs.length ? 'accent' : 'faint' }, act: 'audit-met', arg: k, open: st.auditMet.has(k),
          body: S.FieldText({ label: 'Covers', html: refs.length ? esc(refs.join(', ')) : 'No criterion; this step prepares the others.' }) });
      }))),
      fold('cov', 'Criteria coverage', `${covered} of ${crit.length} covered`, S.CheckLines(crit.map((r) => ({ ok: by(r).length > 0, text: `${r} · ${by(r).length ? `steps ${by(r).join(', ')}` : 'no step covers it'}` })))),
      fold('rec', 'Plan record', p.repaired ? `order ${s.id - 1} · repaired` : `order ${s.id - 1}`, S.KeyValues(facts)),
    ])}</div>`;
  }
  /* Audit, laid out like Review: the verdict and summary, then one block per attempt, oldest first. Each block has its line
   * ("Attempt 2 · Gaps found · 2 of 3 criteria uncovered → audit_implement_fix") and the criteria as that attempt graded
   * them, in the audit's CriterionRows. A criterion counts as met from the attempt that fixed it (`fixedIn`); one without
   * `fixedIn` was met from the first attempt, unless it is still missing. A running audit ends with "Attempt N · Auditing". */
  function auditBody(st, it, s, a) {
    if (a.outcome === 'upcoming') return S.Note({ text: 'Runs after implementation', small: true });
    const atts = a.attempts || [], run = a.outcome === 'running', crit = a.criteria || [];
    const counts = a.total != null ? ` · ${a.met} of ${a.total} criteria met` : '';
    const verdict = { passed: ['Passed' + counts, 'var(--st-done)'], failed: ['Failed' + counts, 'var(--st-failed)'], running: [`Attempt ${atts.length + 1} · Auditing`, 'var(--st-active)'] }[a.outcome] || [a.verdict || 'Unknown', 'var(--fg-faint)'];
    const metAt = (c, n) => (c.fixedIn ? n >= c.fixedIn : c.met);
    const parts = [S.Label({ text: verdict[0], color: verdict[1] })];
    if (!run && a.summary) parts.push(S.ExpandableText({ text: a.summary, cls: 'sb-insp-note' }));
    const blocks = atts.map((x) => {
      const graded = crit.map((c) => ({ c, met: metAt(c, x.n) })).sort((p, q) => (p.met === q.met ? 0 : p.met ? 1 : -1));
      const gaps = crit.length ? graded.filter((g) => !g.met).length : x.uncovered;
      const result = x.unavailable ? 'Historical result unavailable' : (x.passed || (crit.length && !gaps)) ? 'Passed' : `Gaps found · ${crit.length ? `${gaps} of ${crit.length}` : gaps} ${gaps === 1 && !crit.length ? 'criterion' : 'criteria'} uncovered`;
      const line = S.InspectorLine({ lead: `Attempt ${x.n}`, parts: [result, x.next ? { html: `→ <span class="mono">${esc(x.next)}</span>` } : null] });
      const rows = graded.length && !x.unavailable ? S.VerifyDoc([S.Box(graded.map(({ c, met }) => {
        const key = `${it.id}|${s.id}|${x.n}|${c.ref}`;
        return S.CriterionRow({ n: c.ref, html: esc(c.text), badge: met ? { text: 'Met', tone: 'done' } : { text: 'Missing', tone: 'failed' }, hint: c.fixedIn === x.n ? 'Fixed in this attempt' : '', act: 'audit-met', arg: key, open: st.auditMet.has(key),
          body: S.FieldText({ label: met ? 'Covered by' : "What's missing", html: esc(met ? `the change in subtask ${pad(s.id)}${c.fixedIn ? `, from attempt ${c.fixedIn}` : ''}.` : x.next ? `Not covered yet; ${x.next} runs next to address it.` : 'No change in this subtask covers it.') }) });
      }))]) : '';
      return `<div class="l-col g-2">${line}${rows}</div>`;
    });
    if (run) blocks.push(`<div class="l-col g-2">${S.Label({ text: `Attempt ${atts.length + 1} · Auditing`, color: 'var(--st-active)' })}</div>`);
    if (!atts.length && crit.length) blocks.push(S.VerifyDoc([S.Box(crit.map((c) => S.CriterionRow({ n: c.ref, html: esc(c.text), badge: c.met ? { text: 'Met', tone: 'done' } : { text: 'Missing', tone: 'failed' } })))]));
    return `<div class="l-col g-2">${parts.join('')}<div class="l-col g-4">${blocks.join('')}</div></div>`;
  }
  const auditEnd = (a) => (a.outcome === 'running' ? `attempt ${(a.attempts || []).length + 1} running` : a.attemptTotal ? plural(a.attemptTotal, 'attempt', 'attempts') : '');
  /* Review findings use the audit's CriterionRow: the ref, the severity Badge and text, the status as the row Badge; open,
   * the file and the lane with its confidence. */
  const FIND_TONE = { open: 'blocked', fixed: 'done', accepted: 'active', declined: 'faint', false_positive: 'faint', refuted: 'faint' };
  function reviewBody(st, it, s, rv) {
    const tally = (list) => { const by = {}; list.forEach((f) => { by[f.status] = (by[f.status] || 0) + 1; }); return [list.length ? plural(list.length, 'finding', 'findings') : 'no findings', ...['fixed', 'open', 'accepted', 'declined', 'false_positive', 'refuted'].filter((k) => by[k]).map((k) => `${by[k]} ${FIND_STATE[k][0].toLowerCase()}`)].join(' · '); };
    const lanes = (list, findings) => `<div class="l-row g-1 l-wrap">${list.map((l) => { const n = findings.filter((f) => f.lane === l).length; return S.Label({ text: n ? `${laneChip(l)} · ${n}` : laneChip(l), color: n ? 'var(--st-blocked)' : 'var(--st-done)', tip: n ? `${l} · ${plural(n, 'finding', 'findings')}` : `${l} · clean` }); }).join('')}</div>`;
    const blocks = rv.passes.map((p) => `<div class="l-col g-2">${S.InspectorLine({ lead: `Pass ${p.n}`, parts: [(p.verdict[0].toUpperCase() + p.verdict.slice(1)).replace(/_/g, ' '), tally(p.findings)] })}${rv.running ? '' : (p.lanes || []).length ? lanes(p.lanes, p.findings) : ''}${!rv.running && p.findings.length ? S.VerifyDoc([S.Box(p.findings.map((f) => {
      const key = `${it.id}|${s.id}|${p.n}|${f.ref}`;
      return S.FindingRow({ ref: f.ref, severity: f.sev, title: f.text, state: FIND_STATE[f.status][0], stateTone: FIND_TONE[f.status], act: 'audit-met', arg: key, open: st.auditMet.has(key),
        body: S.FieldText({ label: 'Location', chips: f.where ? [S.Chip({ mono: true,  label: f.where.split('/').pop(), act: 'open-file', arg: `${it.id}/${f.where}`, tip: f.where })] : [] }) + S.FieldText({ label: 'Lane', html: esc([f.lane ? laneChip(f.lane) : '', f.confidence ? `${f.confidence} confidence` : ''].filter(Boolean).join(' · ')) }) });
    }))]) : ''}</div>`);
    if (rv.running) {
      const r = rv.running, n = r.lanes.filter((l) => l.finished).length;
      blocks.push(`<div class="l-col g-2">${S.Label({ text: `Pass ${r.n} · Reviewing · ${n} of ${r.lanes.length} lanes done`, color: 'var(--st-active)' })}<div class="l-row g-1 l-wrap">${r.lanes.map((l) => S.Label({ text: laneChip(l.name), color: l.finished ? 'var(--st-done)' : 'var(--fg-faint)', tip: `${l.name} · ${l.finished ? 'finished' : 'not finished'}` })).join('')}</div></div>`);
    }
    return `<div class="l-col g-4">${blocks.join('') || S.Note({ text: 'No review yet', small: true })}</div>`;
  }
  const reviewEnd = (rv) => (rv.running ? `pass ${rv.running.n} running` : plural(rv.passes.length, 'pass', 'passes'));
  function subtaskInspector(st, it, s) {
    const neededBy = it.subtasks.filter((x) => x.deps.some((d) => d.id === s.id)).map((x) => ({ id: x.id, optional: x.deps.find((d) => d.id === s.id).optional }));
    /* Review and audit live only in their phase cards: nothing repeats them under the pills, open or folded. */
    const ph = subPhaseSection(st, it, s);
    const reviewHtml = '', auditHtml = '';
    return S.SubtaskInspector({
      phases: ph.html, reviewHtml, auditHtml,
      title: s.title, blocked: s.blocked, commit: s.commit,
      spec: s.spec, specAct: 'open-file', specArg: specArg(it, s),
      deps: s.deps.map((d) => subChip(it, d)), neededBy: neededBy.map((d) => subChip(it, d)),
      validation: s.validation, reviews: s.reviews, audit: s.audit,
    });
  }
  /* Goal QA while it runs: `it.qaLive` names the stage, the preview state and the screenshots. Above
   * the results on the QA tab; after QA ends the stage list folds to one row and the verdict heads the block. */
  function qaLiveBlock(st, it) {
    const q = it.qaLive, stages = D.QA_STAGES;
    const atI = stages.findIndex((s) => s.id === q.at);
    const stateOf = (s, i) => (q.states && q.states[s.id]) || (q.done || i < atI ? 'done' : i === atI ? 'now' : 'pending');
    const p = q.preview, cur = stages[atI];
    const videoNow = ['live', 'reconnect', 'waiting'].includes(p.kind);
    const rows = stages.map((s, i) => {
      const k = stateOf(s, i);
      return { name: s.name, state: k, time: (q.times || {})[s.id] || (k === 'now' || k === 'warn' ? 'now' : ''), line: q.lines && q.lines[s.id] ? qaInline(q.lines[s.id]) : '',
        tag: s.video ? 'video' : s.headless ? 'headless' : null, tagOn: !!s.video && (k === 'now' || k === 'warn') && videoNow };
    });
    let preview;
    if (p.kind === 'later') {
      const reach = stages.find((s) => s.id === p.reachAt), n = stages.indexOf(reach) - atI;
      preview = { kind: 'later', title: `Video starts at ${reach.name}`, text: p.why || `QA is on ${cur.name}. ${n === 1 ? 'One stage' : `${n} stages`} to go before the app opens.` };
    } else if (p.kind === 'waiting') preview = { kind: 'waiting', title: p.text, text: p.sub };
    else if (p.kind === 'none') preview = { kind: 'none', title: 'No video for this run', text: p.text };
    else preview = Object.assign({}, p);
    preview.label = p.kind === 'live' ? `Live preview of the app under QA, ${p.caption}` : `QA preview: ${preview.title || preview.text || (p.kind === 'ended' ? `last frame at ${p.at}` : '')}`;
    const v = q.done && it.goalQa ? qaView(it.goalQa, { fixLoop: true }) : null;
    const warn = cur && stateOf(cur, atI) === 'warn';
    const word = v ? v.word : cur.name, tone = v ? v.tone : warn ? 'blocked' : 'active';
    return S.QaLive({
      word, tone, meta: q.done ? `${q.elapsed} total` : `stage ${atI + 1} of ${stages.length} · ${q.elapsed}`,
      sub: `${q.round ? `Round ${q.round} · ` : ''}Invocation <span class="mono">qa-7c1e</span>`,
      targets: [{ label: 'desktop · :desktop:app', tone }],
      rounds: q.rounds, preview, hint: q.hint,
      callout: q.callout ? S.Callout({ tone: 'warn', body: qaInline(q.callout.text), actions: [S.Chip(Object.assign({ act: q.callout.chip.mono ? 'open-file' : 'toast', arg: q.callout.chip.mono ? `qa-7c1e/${q.callout.chip.label}` : q.callout.chip.label }, q.callout.chip))] }) : '',
      shots: q.shots ? { files: q.shots.slice(-3).reverse(), total: q.shots.length } : null,
      stages: q.done ? { summary: `All ${stages.length} stages ran`, time: q.elapsed } : { stages: rows },
    });
  }
  /* ---------- Phase tabs and PhasePeek cards (design/phase-peek) ----------
   * The details pane lists a subtask's phases, or Goal finish's, as tabs. A phase that ran and has a card opens it; the
   * card shows what the phase is doing now, what it produced and its activity. goal_assessment and qa cards hold the full
   * assessment and QA results (they replace the app's Assessment / QA switch). Selection per item and list:
   * st.peekSel["<id>|<subtask id or finish>"]. Default: the running or stopped phase, else the last phase that ran. */
  function finishStates(it) {
    if (it.finish && it.finish.states) return phaseDisplayStates(it, it.finish.states);
    const out = {};
    D.GOAL_FINISH.forEach((id) => { out[id] = it.finishDone ? 'done' : ''; });
    if (!it.finishDone) {
      if (it.assessment && it.assessment.state === 'RUNNING') out.goal_assessment = 'now';
      else if (it.qaLive) { out.goal_assessment = 'done'; out.qa = it.qaLive.done ? 'done' : 'now'; }
    }
    return out;
  }
  /* The Goal finish row and pane title: running, waiting, done or pending. */
  function finishState(it) {
    const v = Object.values(finishStates(it));
    if (v.includes('wait') && it.lifecycle === 'paused') return 'paused';
    if (v.includes('skipped') && it.lifecycle === 'canceled') return 'canceled';
    return v.includes('now') ? 'active' : v.includes('wait') ? 'blocked' : v.includes('fail') ? 'failed' : it.finishDone ? 'done' : 'pending';
  }
  const QA_CARD_TONE = { failed: 'fail', blocked: 'wait', done: 'done', faint: 'skipped' };
  /* A recorded card, or one built from the goal's assessment and QA records (no agent or timing then). */
  function finishCard(it, id) {
    const c = it.finish && it.finish.cards && it.finish.cards[id];
    if (c) return c;
    if (id === 'goal_assessment' && (it.assessment || it.catalogTab === 'assessment')) {
      const a = it.assessment;
      return { title: 'Goal assessment', word: a ? (a.state === 'COMPLETED' ? 'Completed' : 'Running') : 'No result', round: a ? `round ${a.round}` : null, madeTitle: 'Findings', madeEnd: { sort: true }, made: [{ kind: 'assessment' }] };
    }
    if (id === 'qa' && (it.goalQa || it.qaLive || it.catalogTab === 'qa')) {
      if (it.qaLive && !it.qaLive.done) return { title: 'QA', round: `round ${it.qaLive.round || 1}`, nowTitle: 'Live', now: [{ kind: 'qaLive' }], madeTitle: 'Results', made: [{ kind: 'qaWait' }] };
      const v = it.goalQa ? qaView(it.goalQa, { fixLoop: true }) : null;
      return { title: 'QA', word: v ? v.word : 'No result', tone: v ? QA_CARD_TONE[v.tone] : 'skipped', madeTitle: 'Run', made: it.qaLive ? [{ kind: 'qaLive' }] : [], results: [{ kind: 'qaResults' }] };
    }
    const state = finishStates(it)[id];
    if (!state) return null;
    /* Goal finish phases without a recorded card: the same parts as a subtask card, from what the goal records. */
    const count = it.finish && it.finish.counts && it.finish.counts[id];
    const live = ['now', 'wait', 'fail'].includes(state), idx = D.GOAL_FINISH.indexOf(id);
    const slot = (it.total || 1) * 60 + D.GOAL_FINISH.slice(0, idx).reduce((n, p) => n + Math.round((PHASE_DUR[p] || 120) / 60), 0);
    const secs = PHASE_DUR[id] || 120, start = phaseClock(it, slot), end = phaseClock(it, slot + Math.round(secs / 60));
    const f = { title: phaseTitle(id), word: PEEK_WORD_OF[state], round: count || null, agent: it.agent, model: it.model ? `${it.model}${it.effort ? ' · ' + it.effort : ''}` : '',
      when: live ? `since ${start}` : start, dur: live ? null : fmtSecs(secs), made: [], activity: [[start, 'Started']] };
    if (live) { f.nowTitle = state === 'now' ? 'Now' : 'Waiting on'; f.now = [{ kind: 'signal', live: state === 'now', icon: state === 'now' ? null : 'warn', wrap: true, text: it.summary || `${phaseTitle(id)} is ${PEEK_WORD_OF[state].toLowerCase()}` }]; }
    if (id === 'pr') {
      const n = 100 + ((it.total || 3) * 37) % 800;
      f.madeTitle = 'Pull request'; f.made = [{ kind: 'out', items: [{ icon: 'external', html: `#${n} ${esc(it.title)}`, toast: `Opened pull request #${n}`, external: true }] }, { kind: 'facts', items: [['Branch', it.branch ? `<span class="mono">${esc(it.branch)}</span>` : 'none'], ['Base', '<span class="mono">main</span>']] }];
      f.open = { label: 'Open pull request', toast: `Opened pull request #${n}`, external: true };
    } else if (id === 'monitor' || id === 'monitor_fix') {
      f.madeTitle = 'Checks'; f.madeEnd = state === 'done' ? 'all passed' : '';
      f.made = [{ kind: 'checks', rows: [['build', state === 'done' ? 'ok' : 'run', '4m 12s'], ['test', state === 'done' ? 'ok' : 'run', '6m 40s'], ['lint', 'ok', '1m 05s']] }];
    } else if (id === 'validate') {
      f.madeTitle = 'Validation'; f.made = [{ kind: 'facts', items: [['Verdict', state === 'done' ? 'passed' : PEEK_WORD_OF[state].toLowerCase()], ['Gate runs', '1']] }, { kind: 'cmd', text: './gradlew check --continue' }];
    } else {
      f.madeTitle = 'Record'; f.made = [{ kind: 'text', html: `${state === 'done' ? 'Finished' : PEEK_WORD_OF[state]}${count ? ` on ${esc(count)}` : ''}.` }];
    }
    f.activity.push(live ? [phaseClock(it, slot + 4), PEEK_WORD_OF[state], state] : [end, state === 'done' ? 'Finished' : PEEK_WORD_OF[state], state === 'done' ? 'done' : state]);
    return f;
  }
  function phaseDisplayStates(it, states) {
    return Object.fromEntries(Object.entries(states).map(([phase, value]) => [phase, value === 'now' && (it.lifecycle !== 'active' || !it.live) ? it.lifecycle === 'failed' ? 'fail' : it.lifecycle === 'canceled' ? 'skipped' : 'wait' : value]));
  }
  function subDisplayState(it, sub) {
    if (sub.status === 'completed_no_change') return 'no_change';
    const value = window.DesignFlow.subtaskState(it, sub);
    return ({ running: 'active', complete: 'done', waiting: 'pending', stopped: 'canceled' })[value] || value;
  }
  function subPhaseStates(it, s) {
    if (s.phases && s.phases.states) return phaseDisplayStates(it, s.phases.states);
    /* A recorded ledger (phaseLog) decides: a phase that ran is done, an unfinished last entry is running. */
    if (s.phaseLog && s.phaseLog.length) {
      const out = {};
      D.PHASE_ORDER.forEach((id) => { const runs = s.phaseLog.filter((p) => p.p === id); if (runs.length) out[id] = runs[runs.length - 1].e ? 'done' : 'now'; });
      return phaseDisplayStates(it, out);
    }
    const out = {};
    if (s.status === 'complete' || s.status === 'completed_no_change') D.PHASE_ORDER.forEach((id) => { out[id] = 'done'; });
    else if (!isFinished(it) && s.id === it.current && it.pipeline && !it.planning) pipelineSteps(it).forEach((p) => { out[p.label] = p.state; });
    return phaseDisplayStates(it, out);
  }
  function subPhaseCounts(it, s) {
    if (s.phases && s.phases.counts) return s.phases.counts;
    return !isFinished(it) && s.id === it.current && it.pipeline && it.pipeline.now ? { [it.pipeline.now]: it.exec } : {};
  }
  function peekTabs(st, it, key, order, states, counts, cardOf, label) {
    const can = (id) => !!states[id] && !!cardOf(id);
    const live = order.find((id) => ['now', 'fail', 'wait'].includes(states[id]) && can(id));
    const ran = order.filter((id) => states[id] === 'done' && can(id));
    /* Pills expand: a click opens that phase's card under the row, a click on the open pill folds it ('' = all folded).
     * Untouched, only a running, blocked or failed phase starts open; finished work starts folded. */
    const pick = st.peekSel[`${it.id}|${key}`];
    const sel = pick === '' ? null : pick && can(pick) ? pick : live || null;
    const tabs = S.PhasePipeline(order.map((id) => ({ label: id, state: states[id] || '', count: counts[id] || null, act: can(id) ? 'peek-pill' : null, arg: `${it.id}|${key}|${id}`, selected: id === sel, tip: can(id) ? `${id === sel ? 'Hide' : 'Show'} ${id}` : null })), { tabs: true, label, expand: true });
    return { sel, tabs, card: sel ? peekCard(st, it, sel, cardOf(sel), states[sel]) : '', none: ran.length || live ? 'Select a phase to see what it did.' : 'No phase has run yet.' };
  }
  function peekBlock(st, it, b) {
    switch (b.kind) {
      case 'signal': return S.PeekSignal({ live: b.live, icon: b.icon, wrap: b.wrap, html: esc(b.text), ago: b.ago });
      case 'edit': return S.PeekSignal({ icon: 'pencil', html: `${esc(b.agent)} edited <span class="mono" title="${esc(b.path)}">${esc(b.path.split('/').pop())}</span>`, keep: S.Counts({ ins: b.ins, del: b.del }), ago: b.ago });
      case 'cmd': return S.PeekCmd(b.text);
      case 'cells': return S.PeekCells(b.cells);
      case 'text': return S.Note({ html: b.html });
      case 'stats': return S.PeekStats(b.items);
      case 'out': return b.items.map((o) => S.PeekOutput({ icon: o.icon, html: o.html, end: o.end, tip: o.tip, external: o.external, act: o.act || (o.toast ? 'toast' : null), arg: o.act ? o.arg : o.toast })).join('');
      case 'finds': return S.VerifyDoc([S.Box(b.rows.map(([severity, title, state], i) => S.FindingRow({ ref: `F-${String(i + 1).padStart(3, '0')}`, severity, title, state: state ? state[0].toUpperCase() + state.slice(1) : null })))]);
      case 'checks': return b.rows.map(([name, state, time, cmd]) => S.PeekCheck({ name, state, time, cmd })).join('');
      case 'facts': return S.PeekFacts(b.items);
      case 'html': return b.html;
      case 'assessment': {
        const a = it.assessment;
        if (!a) return S.Box(S.Note({ text: 'No assessment result is available for this run.', pad: true }));
        const rows = assessOrder(a.findings, st.assessOrder).map((f) => { const k = `${it.id}|assess|${f.ref}`; return S.FindingRow({ ref: f.ref, severity: f.severity, title: f.scenario, state: f.state === 'FIXED' ? 'Fixed' : f.state === 'REFUTED' ? 'Refuted' : 'Open', act: 'audit-met', arg: k, open: st.auditMet.has(k),
          body: S.FieldText({ label: 'Location', chips: [S.Chip({ label: `${f.path.split('/').pop()}:${f.line}`, tip: `${f.path}:${f.line}`, act: 'open-file', arg: f.path })] }) + S.FieldText({ label: 'Verification', html: esc(f.verification.toLowerCase()) }) }); });
        const facts = [a.exit ? ['Exit', esc(a.exit)] : null, a.fixCommit ? ['Fix commit', `<span class="mono">${esc(a.fixCommit)}</span>`] : null].filter(Boolean);
        return S.VerifyDoc([S.Box(rows)]) + (facts.length ? S.PeekFacts(facts) : '');
      }
      case 'monCycles': case 'ciBoard': case 'repair': case 'publish': return monPeekBlock(st, it, b);
      case 'qaLive': return qaLiveBlock(st, it);
      case 'qaRun': return `<div class="l-col g-3">${b.rounds ? S.QaRounds({ rounds: b.rounds }) : ''}${S.QaPreview({ kind: 'ended', at: b.at, label: `QA preview, last frame at ${b.at}` })}${S.QaShots({ files: b.shots, total: b.total })}${S.QaStageList({ summary: `All ${D.QA_STAGES.length} stages ran`, time: b.time })}</div>`;
      case 'qaResults': return it.goalQa ? qaResults(st, it.goalQa, { fixLoop: true, fixing: b.fixing, running: it.lifecycle === 'active' && !it.goalQa.outcome }).body : S.Box(S.Note({ text: 'No QA result is available for this run.', pad: true }));
      case 'qaWait': return S.Note({ text: 'Results appear here when QA writes its report.', dashed: true });
      case 'qaFinding': {
        const f = b.finding, key = `peek:${it.id}:${f.id}:${b.state}`;
        const dir = f.evidencePaths.map((p) => S.Chip({ icon: 'qaImage', label: qaFile(p), mono: true, tip: p, act: 'open-file', arg: p }));
        return S.Box([S.QaItem({ glyph: 'qaBug', tone: b.tone, ref: f.id, title: f.title, state: b.state, line: qaInline(capFirst(f.observed)), act: 'qa-toggle', arg: key, open: st.qaOpen.has(key),
          fields: [['Expected', qaInline(f.expected)], ['Steps', qaInline(f.steps)]], chips: [...dir, S.Chip({ icon: 'qaCopy', label: 'Copy steps', act: 'copy', arg: f.steps })] })]);
      }
      default: return '';
    }
  }
  function peekCard(st, it, id, c, state) {
    if (['now', 'wait', 'skipped', 'fail'].includes(state) && it.lifecycle !== 'active' && c.now) c = { ...c, word: LIFE[it.lifecycle][0], nowTitle: 'Last activity' };
    const blocks = (list) => (list || []).map((b) => peekBlock(st, it, b.kind === 'signal' && !it.live ? { ...b, live: false } : b)).join('');
    const sortable = c.madeEnd && c.madeEnd.sort;
    const end = sortable ? (it.assessment && it.assessment.findings.length > 1 ? S.Segmented({ act: 'assess-order', value: st.assessOrder || 'default', items: [{ id: 'default', label: 'Default' }, { id: 'priority', label: 'Priority' }, { id: 'status', label: 'Status' }] }) : '') : c.madeEnd ? esc(c.madeEnd) : '';
    const parts = [
      c.now && ['now', 'wait'].includes(state) ? S.PeekPart({ title: c.nowTitle || (state === 'wait' ? 'Waiting on' : 'Now'), live: state === 'now', body: blocks(c.now) }) : '',
      c.made && c.made.length ? S.PeekPart({ title: c.madeTitle || 'Produced', end, control: !!(sortable && end), body: blocks(c.made) }) : '',
      c.results ? S.PeekPart({ title: c.resultsTitle || 'Results', end: c.resultsEnd ? esc(c.resultsEnd) : '', body: blocks(c.results) }) : '',
    ];
    const ag = c.agent ? agentOf(c.agent) : null;
    return S.PhasePeek({ id, state, tone: c.tone, title: c.title, word: c.word, why: c.why,
      agent: ag ? S.Avatar({ name: c.agent, initials: ag.initials, color: ag.color, size: 'sm' }) : '', model: c.model || c.agent || '',
      round: c.round, when: c.when, dur: c.dur, parts, activity: c.activity,
      foot: c.open ? { label: c.open.label, act: c.open.act || 'toast', arg: c.open.arg || c.open.toast || c.open.label, external: c.open.external } : null });
  }
  /* A phase that ran but has no recorded card still opens, with the same parts as the app's SubtaskPhaseCard: header
   * (agent, model, round, start, duration), why it is blocked, Now or Waiting on while it runs, what it produced, the
   * last activity and the footer link. Values come from what the subtask records; times and counts the sample lacks are
   * derived from the goal's start so every card reads like a real one. */
  /* Phase titles as the app writes them (SubtaskPhases.kt phaseTitle). */
  const PHASE_TITLE = { goal_assessment: 'Goal assessment', qa: 'QA', qa_fix: 'QA fix', monitor_fix: 'Monitor fix', write_history: 'Write history', commit_push: 'Commit and push', pr: 'Pull request' };
  const phaseTitle = (id) => PHASE_TITLE[id] || id[0].toUpperCase() + id.slice(1);
  const PEEK_WORD_OF = { done: 'Done', now: 'Running', wait: 'Blocked', fail: 'Failed', skipped: 'Skipped' };
  const PHASE_DUR = { preplan: 140, plan: 410, implement: 1130, simplify: 290, audit: 230, review: 380, build: 190, validate: 520, write_history: 70, commit_push: 40,
    goal_assessment: 600, qa: 900, qa_fix: 700, pr: 60, monitor: 840, monitor_fix: 420 };
  const fmtSecs = (n) => (n >= 60 ? `${Math.floor(n / 60)}m ${String(n % 60).padStart(2, '0')}s` : `${n}s`);
  function phaseClock(it, slot) {
    const m = /(\d{1,2}):(\d{2})/.exec(it.started || '') || [0, 9, 0];
    const t = (+m[1] * 60 + +m[2] + slot) % 1440;
    return `${String(Math.floor(t / 60)).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`;
  }
  /* The ledger entries of one phase family (audit and its fixes, review and verify_findings, validate and its fixes). */
  const LOOP_OF = { audit_plan_fix: 'audit', audit_implement_fix: 'audit', verify_findings: 'review', review_fix: 'review', present_findings: 'review', validate_fix: 'validate', monitor_fix: 'monitor', qa_fix: 'qa' };
  const logRuns = (s, id) => (s.phaseLog || []).filter((p) => p.p === id || LOOP_OF[p.p] === id);
  const isoClock = (t) => new Date(t).toISOString().slice(11, 16);
  const runMs = (p) => (p.e ? new Date(p.e) - new Date(p.s) : 0);
  function fallbackSubCard(st, it, s, id, state, count) {
    const c0 = fallbackSubCardBase(st, it, s, id, state, count);
    const runs = logRuns(s, id);
    if (!c0 || !runs.length) return c0;
    const last = runs[runs.length - 1], total = runs.reduce((n, p) => n + runMs(p), 0) / 1000 | 0;
    const own = runs.filter((p) => p.p === id);
    const c = Object.assign({}, c0, { when: last.e ? isoClock(runs[0].s) : `since ${isoClock(last.s)}`, dur: last.e ? fmtSecs(total) : null, agent: last.a, model: [last.m, last.f].filter(Boolean).join(' · '),
      round: runs.length > 1 ? `${own.length} ${own.length === 1 ? 'run' : 'runs'}${runs.length > own.length ? ` · ${runs.length - own.length} fix` : ''}` : count || null,
      activity: runs.slice(-5).map((p) => [isoClock(p.e || p.s), `${esc(p.p)}${p.v && p.v !== 'null' ? ` <b>${esc(p.v)}</b>` : ''}`, p.e ? 'done' : 'now']) });
    if (last.t && (c.madeTitle === 'Record' || id === 'implement' || id === 'simplify')) c.made = [{ kind: 'text', html: esc(last.t) }].concat((c.made || []).filter((b) => b.kind !== 'text'));
    return c;
  }
  function fallbackSubCardBase(st, it, s, id, state, count) {
    if (!state) return null;
    const order = D.PHASE_ORDER, idx = order.indexOf(id), live = ['now', 'wait', 'fail'].includes(state);
    const slot = (s.id - 1) * 60 + order.slice(0, idx).reduce((n, p) => n + Math.round((PHASE_DUR[p] || 120) / 60), 0);
    const secs = PHASE_DUR[id] || 120, start = phaseClock(it, slot), end = phaseClock(it, slot + Math.round(secs / 60));
    const seed = s.id * 7 + idx * 3;
    const c = { title: phaseTitle(id), word: PEEK_WORD_OF[state], round: count || null, agent: it.agent, model: it.model ? `${it.model}${it.effort ? ' · ' + it.effort : ''}` : '',
      when: live ? `since ${start}` : start, dur: live ? null : fmtSecs(secs), made: [], activity: [[start, 'Started']] };
    const l = it.latest || {};
    if (live) {
      if (state === 'wait' || state === 'fail') c.why = `<b>${state === 'fail' ? 'Failed' : 'Blocked'}:</b> ${esc(s.blocked || it.summary || 'waiting for the operator')}`;
      c.nowTitle = state === 'now' ? 'Now' : 'Waiting on';
      c.now = [l.activity ? { kind: 'signal', live: state === 'now', text: l.activity.label, ago: `${Math.round(l.activity.ago / 60)}m ago` } : { kind: 'signal', icon: 'warn', wrap: true, text: s.blocked || 'Waiting for the operator' }];
      if (l.edit) c.now.push({ kind: 'edit', agent: it.agent || 'agent', path: l.edit.path, ins: l.edit.ins, del: l.edit.del, ago: `${Math.round(l.edit.ago / 60)}m ago` });
    }
    const spec = s.spec ? [{ kind: 'out', items: [{ icon: 'fileText', html: `<span class="mono">${esc(s.spec)}</span>`, tip: s.spec, toast: `Opened ${s.spec}` }] }] : [];
    switch (id) {
      case 'preplan':
        c.madeTitle = 'Digest'; c.made = [{ kind: 'html', html: preplanBody(st, it) }]; break;
      case 'plan':
        c.madeTitle = 'Plan'; c.made = [{ kind: 'html', html: planBody(st, it, s) }]; if (id === 'plan' && s.spec) c.open = { label: 'Open spec file', toast: `Opened ${s.spec}` }; break;
      case 'implement': case 'simplify': {
        const e = live && l.edit ? l.edit : { ins: 12 + (seed * 13) % 140, del: 3 + (seed * 5) % 60 };
        const files = 1 + seed % 6;
        c.madeTitle = id === 'simplify' ? 'Simplified' : 'Produced';
        c.made = id === 'simplify'
          ? [{ kind: 'text', html: esc(s.simplified || 'Folded two helpers that did the same lookup into one and dropped a parameter no caller passed. Behavior is unchanged.') }, { kind: 'stats', items: [[String(Math.max(1, files - 2)), 'files'], [`+${Math.round(e.ins / 4)}`, 'added', 'ok'], [`−${e.del}`, 'removed', 'bad']] }]
          : [{ kind: 'stats', items: [[String(files), 'files'], [`+${e.ins}`, 'added', 'ok'], [`−${e.del}`, 'removed', 'bad']] }];
        break;
      }
      case 'audit': if (auditModel(it, s)) { const a = auditModel(it, s); c.madeTitle = 'Criteria'; c.madeEnd = auditEnd(a); c.made = [{ kind: 'html', html: auditBody(st, it, s, a) }]; break; }
      // falls through
      case 'audit-generic': {
        const said = s.audit && s.audit.summary ? (/\bboth\b/i.test(s.audit.summary) ? 2 : +((/(\d+)\s+(?:acceptance\s+)?criteri/i.exec(s.audit.summary) || [])[1] || 0)) : 0;
        const n = said || 3 + seed % 3, met = s.audit && !/fail|unmet|not/i.test(s.audit.verdict) ? n : n - 1;
        c.madeTitle = 'Criteria'; c.madeEnd = `${met} of ${n}`;
        c.made = [{ kind: 'checks', rows: Array.from({ length: n }, (_, k) => [`AC-${k + 1} · ${k < met ? 'met by the change' : 'not covered yet'}`, k < met ? 'ok' : 'bad']) }];
        break;
      }
      case 'review': {
        const rv = reviewModel(it, s);
        if (rv.passes.length || rv.running) { c.madeTitle = 'Findings'; c.madeEnd = reviewEnd(rv); c.made = [{ kind: 'html', html: reviewBody(st, it, s, rv) }]; rv.passes.forEach((p, k) => c.activity.push([phaseClock(it, slot + 2 + k * 2), `Pass ${p.n} <b>${esc(p.verdict.replace(/_/g, ' '))}</b>`, k === rv.passes.length - 1 && live ? 'wait' : null])); break; }
        const passes = s.reviews || [];
        c.madeTitle = 'Findings'; c.madeEnd = passes.length ? `${passes.length} ${passes.length === 1 ? 'pass' : 'passes'}` : '';
        passes.forEach((p, k) => {
          c.made.push({ kind: 'text', html: `<b>Pass ${p.n}</b> · ${esc(p.verdict)} · ${p.open} unresolved` });
          if (p.findings.length) c.made.push({ kind: 'finds', rows: p.findings.map(([sev, text]) => [sev, text, k === passes.length - 1 && live ? 'open' : k < passes.length - 1 ? null : null]) });
          c.activity.push([phaseClock(it, slot + 2 + k * 2), `Pass ${p.n} <b>${esc(p.verdict)}</b>`, k === passes.length - 1 && live ? 'wait' : null]);
        });
        if (!passes.length) c.made.push({ kind: 'text', html: 'Approved. No findings.' });
        break;
      }
      case 'build':
        c.madeTitle = 'Build'; c.madeEnd = state === 'done' ? 'passed' : '';
        c.made = [{ kind: 'cmd', text: './gradlew assemble' }, { kind: 'stats', items: [[state === 'done' ? 'passed' : PEEK_WORD_OF[state].toLowerCase(), 'result', state === 'done' ? 'ok' : 'bad'], [fmtSecs(secs), 'took'], ['0', 'warnings']] }];
        break;
      case 'validate': {
        const v = s.validation || { passed: state === 'done', checks: 4 };
        const names = [['Unit tests', ':test'], ['Lint', 'lint'], ['Format', 'format --check'], ['Contract tests', ':contracts:test'], ['Static analysis', 'detekt'], ['Integration tests', ':it:test'],
          ['API compatibility', 'apiCheck'], ['Dependency guard', 'dependencyGuard'], ['Schema parity', ':contracts:parity'], ['Migration tests', ':runtime:migrationTest'], ['License headers', 'spotlessCheck'], ['Docs links', 'docs:links'],
          ['Snapshot tests', ':ui:verifyPaparazzi'], ['Module graph', 'moduleGraphAssert'], ['Binary size', 'sizeCheck'], ['Security scan', 'dependencyCheckAnalyze']];
        /* The first six checks show; "Show N more checks" opens the whole list in place and "Show fewer" folds it. */
        const allKey = `${it.id}|${s.id}|validate-all`, all = st.auditMet.has(allKey), shown = all ? v.checks : Math.min(v.checks, 6);
        const rows = Array.from({ length: shown }, (_, k) => [k < names.length ? names[k][0] : `Check ${k + 1}`, !v.passed && k === v.checks - 1 ? 'bad' : 'ok', fmtSecs(20 + ((seed + k) * 37) % 150), k < names.length ? names[k][1] : `check${k + 1}`]);
        c.madeTitle = 'Validation'; c.madeEnd = v.passed ? 'passed' : 'failed';
        c.made = [{ kind: 'facts', items: [['Verdict', v.passed ? 'passed' : 'failed'], ['Checks', String(v.checks)], ['Gate runs', String(count && /\d+/.test(count) ? count.match(/\d+/)[0] : 1)]] }, { kind: 'checks', rows }];
        if (v.checks > 6) c.made.push({ kind: 'html', html: S.LinkButton({ label: all ? 'Show fewer' : `Show ${v.checks - 6} more checks`, icon: all ? 'chevronUp' : 'chevronDown', act: 'audit-met', arg: allKey }) });
        c.results = [{ kind: 'facts', items: [['Duration', fmtSecs(secs)], ['Cache', 'warm'], ['Work units', String(12 + seed % 30)], ['Exit code', v.passed ? '0' : '1']] }, { kind: 'cmd', text: './gradlew check --continue' }];
        c.resultsTitle = 'Gate 1'; c.resultsEnd = v.passed ? 'passed' : 'failed';
        break;
      }
      case 'write_history':
        c.madeTitle = 'History';
        c.made = [{ kind: 'stats', items: [['yes', 'written', 'ok'], [String(seed % 3), 'decisions'], [String(1 + seed % 2), 'paths']] }, { kind: 'out', items: [{ icon: 'history', html: '<span class="mono">agent/history.md</span>', end: '1 entry', toast: 'Opened agent/history.md' }] }];
        c.decisions = seed % 3;
        break;
      case 'commit_push':
        c.madeTitle = 'Commit';
        c.made = [{ kind: 'facts', items: [['Commit', s.commit ? `<span class="mono">${esc(String(s.commit).slice(0, 8))}</span>` : 'not recorded'], ['Branch', it.branch ? `<span class="mono">${esc(it.branch)}</span>` : 'none']] }];
        if (it.branch) c.open = { label: 'Open branch', toast: `Opened ${it.branch}`, external: true };
        break;
      default: break;
    }
    if (!c.made.length) { c.madeTitle = 'Record'; c.made.push({ kind: 'text', html: `${state === 'done' ? 'Finished' : PEEK_WORD_OF[state]}${count ? ` on ${esc(count)}` : ''}.` }); }
    if (!live) c.activity.push([end, state === 'done' ? 'Finished' : PEEK_WORD_OF[state], state === 'done' ? 'done' : state]);
    else c.activity.push([phaseClock(it, slot + 6), PEEK_WORD_OF[state], state]);
    c.activity = c.activity.slice(-5);
    return c;
  }
  function subPhaseSection(st, it, s) {
    const states = subPhaseStates(it, s), cards = (s.phases && s.phases.cards) || {}, counts = subPhaseCounts(it, s);
    /* Every card, recorded or generated: implement opens this subtask's changes instead of listing files, and review and
     * audit show the full findings and criteria lists. */
    const cardFor = (id) => {
      let c = cards[id] || fallbackSubCard(st, it, s, id, states[id], counts[id]);
      if (!c) return null;
      if (id === 'implement') c = Object.assign({}, c, { made: (c.made || []).filter((b) => b.kind !== 'out'), open: { label: 'Open changes', act: 'changes-subtask', arg: `${it.id}|${s.id}` } });
      /* write_history opens both files it writes: agent/history.md and agent/decisions.md. */
      if (id === 'write_history') {
        const n = c.decisions != null ? c.decisions : 1;
        const file = (path, icon, end) => ({ icon, html: `<span class="mono">${path}</span>`, end, tip: `Open ${path}`, act: 'open-file', arg: `${it.id}/${path}` });
        c = Object.assign({}, c, { made: (c.made || []).filter((b) => b.kind !== 'out').concat({ kind: 'out', items: [file('agent/history.md', 'history', '1 entry'), file('agent/decisions.md', 'fileText', n ? `${n} ${n === 1 ? 'decision' : 'decisions'}` : 'unchanged')] }) });
      }
      if (cards[id] && id === 'plan') { const text = (c.made || []).find((b) => b.kind === 'text'); c = Object.assign({}, c, { madeTitle: 'Plan', made: [{ kind: 'html', html: planBody(st, it, s, text && text.html.replace(/<[^>]+>/g, '')) }] }); }
      if (cards[id] && id === 'preplan') c = Object.assign({}, c, { madeTitle: 'Digest', made: [{ kind: 'html', html: preplanBody(st, it) }] });
      if (cards[id] && id === 'review') { const rv = reviewModel(it, s); if (rv.passes.length || rv.running) c = Object.assign({}, c, { madeTitle: 'Findings', madeEnd: reviewEnd(rv), made: [{ kind: 'html', html: reviewBody(st, it, s, rv) }] }); }
      if (cards[id] && id === 'audit') { const a = auditModel(it, s); if (a && a.outcome !== 'upcoming') c = Object.assign({}, c, { madeTitle: 'Criteria', madeEnd: auditEnd(a), made: [{ kind: 'html', html: auditBody(st, it, s, a) }] }); }
      return c;
    };
    const p = peekTabs(st, it, String(s.id), D.PHASE_ORDER, states, counts, cardFor, `Phases of subtask ${pad(s.id)}`);
    const vals = D.PHASE_ORDER.map((id) => states[id]);
    const ran = vals.filter((v) => v === 'done').length;
    const note = (s.phases && s.phases.note) || (vals.some(Boolean) && ran < vals.length ? `${ran} of ${vals.length}` : '');
    return { sel: p.sel, html: S.PhaseBlock({ title: 'Phases', note, tabs: p.tabs, card: p.card, none: p.none }) };
  }
  function finishResults(st, it) {
    const p = peekTabs(st, it, 'finish', D.GOAL_FINISH, finishStates(it), (it.finish && it.finish.counts) || {}, (id) => finishCard(it, id), 'Goal finish phases');
    return S.PaneSection({ body: S.PhaseBlock({ tabs: p.tabs, card: p.card, none: p.none }) });
  }
  /* The Goal finish row's end: the phase that is running or stopped, then how many of its phases ran. */
  function finishMeta(it) {
    const fin = finishStates(it), ids = D.GOAL_FINISH;
    const at = ids.find((id) => ['now', 'wait', 'fail'].includes(fin[id]));
    const ran = ids.filter((id) => fin[id] === 'done').length;
    const tone = at && { now: 'active', wait: 'blocked', fail: 'failed' }[fin[at]];
    return [at ? S.Label({ text: at, color: `var(--st-${tone})`, mono: true, dot: false }) : '', at || ran ? S.Label({ text: `${ran}/${ids.length}`, dot: false }) : ''];
  }
  /* The Subtasks index. Every row is one line; a click selects it and opens the details panel. A row reads as selected
   * only while the panel shows it. */
  function subtaskSection(st, it) {
    const sel = paneShown(st) ? selectedSub(st, it) : null;
    const finished = isFinished(it);
    const rows = it.subtasks.map((s) => {
      const key = String(s.id);
      const current = !finished && s.id === it.current;
      const meta = [s.commit ? S.ShortId({ value: s.commit, label: 'commit' }) : '', current && it.live ? subClock(it) : '', s.status === 'completed_no_change' ? S.Label({ text: 'no change', dot: false }) : ''];
      return S.SubtaskRow({ num: pad(s.id), state: subDisplayState(it, s), frac: 0.5, title: s.title, current, selected: key === sel, meta, act: 'sub-select', arg: `${it.id}|${key}` });
    });
    rows.push(S.SubtaskRow({ num: '··', state: finishState(it), title: 'Goal finish', selected: sel === 'finish', meta: finishMeta(it), act: 'sub-select', arg: `${it.id}|finish` }));
    return secBlock(st, 'subtasks', { title: 'Subtasks', count: `${it.done}/${it.total}`, end: [S.ProgressRing(it.done, it.total, { text: false })], body: S.SubtaskList({ label: `Subtasks of ${it.id}`, rows }) }, it.subtasks.length > 10);
  }
  /* The panel for a detail view. A goal's panel is about the selected subtask or Goal finish: the header names it with its
   * state and steps through the index; the body is its inspector, then Execution, Runs and fixes and the goal's spec files.
   * Other runs get "Details" with Execution and Runs and fixes. */
  function detailsPanel(st, it) {
    const goal = it.kind === 'goal', subs = hasSubtasks(it);
    const sel = subs ? selectedSub(st, it) : goal ? 'finish' : null;
    const s = sel && sel !== 'finish' ? it.subtasks.find((x) => String(x.id) === sel) : null;
    const keys = subKeys(it), i = keys.indexOf(sel);
    const step = (d, icon, label, kbd) => S.IconButton({ icon, label, kbd, size: 'sm', act: 'sub-move', arg: d, cls: (d < 0 ? i <= 0 : i >= keys.length - 1) ? 'is-off' : '' });
    const lead = s ? subtaskInspector(st, it, s) : sel === 'finish' ? finishResults(st, it) : '';
    return S.DetailsPanel({
      title: s ? `Subtask ${pad(s.id)}` : sel === 'finish' ? 'Goal finish' : 'Details', label: 'Details',
      status: s ? { state: subDisplayState(it, s), frac: 0.5 } : sel === 'finish' ? { state: finishState(it) } : null,
      actions: subs ? [S.Label({ text: `${i + 1} / ${keys.length}`, dot: false }), step(-1, 'chevronUp', 'Previous subtask', '↑'), step(1, 'chevronDown', 'Next subtask', '↓')] : [],
      width: paneWidth(st, it), min: PANE_MIN, closeAct: 'toggle-props',
      body: (lead ? S.PaneSection({ body: lead }) : '') + executionPane(it) + runsPane(st, it),
    });
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
  /* ---------- Activity (feature/detail ActivitySection), composed from design system parts ----------
   * Groups come from the item: one per subtask with its phase ledger (`phaseLog`, or the details panel's phase cards when
   * no ledger was recorded) and its blocks, then Goal finish, then the run's own events. Parts: MetricStrip (phase time,
   * blocks, subtasks, models), a tall SegmentBar timeline whose segments open their group, MiniRule day labels and idle
   * gaps, a Fold per group (StatusIcon, number and name, time, duration and agents), EventLines for phases, nested Folds
   * for fix loops, ExpandableText for summaries and block reasons, and EventLines with icons for the run's events. */
  const LONG_GAP = 600000;
  const durTxt = (m) => { if (m < 1000) return '<1s'; const sec = Math.round(m / 1000); if (sec < 60) return `${sec}s`; const n = Math.round(sec / 60); if (n < 60) return `${n}m`; return `${Math.floor(n / 60)}h ${String(n % 60).padStart(2, '0')}m`; };
  const dayTxt = (t) => new Date(t).toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
  const MONTHS = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, May: 4, Jun: 5, Jul: 6, Aug: 7, Sep: 8, Oct: 9, Nov: 10, Dec: 11 };
  function clockOn(it, hhmm) {
    const m = /(\d{1,2}) ([A-Z][a-z]{2})/.exec(it.started || ''), d = new Date(D.T0);
    if (m) { d.setUTCMonth(MONTHS[m[2]]); d.setUTCDate(+m[1]); }
    const t = /(\d{1,2}):(\d{2})/.exec(hhmm || '');
    if (t) d.setUTCHours(+t[1], +t[2], 0, 0);
    return d.getTime();
  }
  const SEC = (v) => [...String(v || '').matchAll(/(\d+)\s*(h|m|s)/g)].reduce((n, [, x, u]) => n + +x * ({ h: 3600000, m: 60000, s: 1000 })[u], 0);
  /* One subtask's ledger: recorded, or rebuilt from the same phase cards the details panel shows. */
  function ledgerOf(st, it, s) {
    if (s.phaseLog && s.phaseLog.length) return s.phaseLog.map((p) => ({ p: p.p, s: new Date(p.s).getTime(), e: p.e ? new Date(p.e).getTime() : null, a: p.a, m: p.m, f: p.f, v: p.v && p.v !== 'null' ? p.v : null, t: p.t }));
    const states = subPhaseStates(it, s), counts = subPhaseCounts(it, s), cards = (s.phases && s.phases.cards) || {};
    /* Audit attempts and review passes expand into their runs and fixes, so the ledger matches the panel's records. */
    return D.PHASE_ORDER.filter((id) => states[id]).flatMap((id) => {
      const c = cards[id] || fallbackSubCard(st, it, s, id, states[id], counts[id]) || {};
      const start = clockOn(it, c.when), open = /^since /.test(c.when || '') || ['now', 'wait', 'fail'].includes(states[id]);
      const wait = ['wait', 'fail'].includes(states[id]) ? states[id] : null;
      const [m, f] = String(c.model || '').split(' · ');
      const text = (c.made || []).find((b) => b.kind === 'text');
      const span = SEC(c.dur) || (open ? 6 * 60000 : 0);
      const base = { a: c.agent, m, f };
      let runs = null;
      if (id === 'audit') { const a = auditModel(it, s); if (a && (a.attempts || []).length > 1) runs = a.attempts.flatMap((x) => [{ p: 'audit', v: x.passed ? 'passed' : `${x.uncovered} uncovered` }, x.next ? { p: x.next } : null].filter(Boolean)); }
      if (id === 'review') { const r = reviewModel(it, s); if (r.passes.length > 1) runs = r.passes.flatMap((x, i) => [{ p: 'review', v: x.verdict.replace(/_/g, ' ') }, i < r.passes.length - 1 ? { p: 'review_fix' } : null].filter(Boolean)); }
      if (!runs) return [{ ...base, p: id, s: start, e: open ? null : start + span, v: wait ? null : c.word && !['Done', 'Running'].includes(c.word) ? c.word.toLowerCase() : null, t: text ? text.html.replace(/<[^>]+>/g, '') : null, wait }];
      const step = Math.max(10000, Math.floor(span / runs.length));
      return runs.map((r, i) => ({ ...base, ...r, s: start + i * step, e: open && i === runs.length - 1 ? null : start + (i + 1) * step, wait: i === runs.length - 1 ? wait : null }));
    });
  }
  function activityGroups(st, it) {
    const groups = (it.subtasks || []).map((s) => {
      const log = ledgerOf(st, it, s);
      const blocks = (s.blocks && s.blocks.length ? s.blocks.map((b) => ({ ...b, at: b.at ? new Date(b.at).getTime() : null })) : s.blocked ? [{ why: s.blocked, phase: Object.entries(subPhaseStates(it, s)).find(([, v]) => ['wait', 'fail'].includes(v))?.[0] || it.step, at: null }] : []);
      return { id: `sub-${s.id}`, n: pad(s.id), name: s.title, state: subDisplayState(it, s), log, blocks, s: log[0] ? log[0].s : null, e: log.length && log[log.length - 1].e ? log[log.length - 1].e : null };
    });
    if (hasSubtasks(it)) {
      const fs = finishStates(it), log = D.GOAL_FINISH.filter((id) => fs[id]).map((id) => {
        const c = finishCard(it, id) || {}, start = clockOn(it, c.when), live = fs[id] === 'now';
        return { p: id, s: start, e: live ? null : start + SEC(c.dur), a: c.agent, m: String(c.model || '').split(' · ')[0], v: c.word && c.word !== 'Done' ? c.word.toLowerCase() : null, t: null };
      });
      if (log.length) groups.push({ id: 'finish', n: '··', name: 'Goal finish', state: finishState(it), log, blocks: [], s: log[0].s, e: log[log.length - 1].e });
    }
    return groups;
  }
  const agentMark = (a) => (a && D.AGENTS[a] ? S.Avatar({ name: a, initials: D.AGENTS[a].initials, color: D.AGENTS[a].color, size: 'sm' }) : '');
  const modelTxt = (p) => [String(p.m || '').replace(/^claude-/, ''), p.f].filter(Boolean).join(' · ');
  /* A ledger entry as one EventLines row: time, dot, phase, duration, verdict, the agent and model, then the summary. */
  function ledgerRow(p) {
    const live = !p.e;
    return [isoClock(p.e || p.s), `<b>${esc(p.p)}</b>${p.wait ? ` · ${p.wait === 'fail' ? 'failed' : 'blocked'}` : live ? ' · running' : ` · ${durTxt(p.e - p.s)}`}${p.v ? ` · ${esc(p.v)}` : ''}${p.a ? ` · ${agentMark(p.a)} ${esc(modelTxt(p))}` : ''}${p.t ? S.ExpandableText({ text: p.t, cls: 'sb-insp-note' }) : ''}`,
      p.wait === 'fail' ? 'var(--st-failed)' : p.wait ? 'var(--st-blocked)' : live ? 'var(--st-active)' : 'var(--st-done)'];
  }
  function groupBodyHtml(st, it, g) {
    const seq = [...g.log.map((p) => ({ p })), ...g.blocks.map((b) => ({ b }))].sort((x, y) => ((x.p ? x.p.s : x.b.at) ?? Infinity) - ((y.p ? y.p.s : y.b.at) ?? Infinity));
    const out = []; let rows = [], loop = null, prevEnd = null;
    const flushRows = () => { if (rows.length) { out.push(S.EventLines(rows)); rows = []; } };
    const flushLoop = () => {
      if (!loop) return;
      if (loop.items.length === 1) rows.push(ledgerRow(loop.items[0]));
      else {
        flushRows();
        const counts = {}; loop.items.forEach((p) => { counts[p.p] = (counts[p.p] || 0) + 1; });
        const key = `${it.id}|act|${g.id}|${loop.items[0].s}`, total = loop.items.reduce((n, p) => n + (p.e ? p.e - p.s : 0), 0);
        out.push(S.Fold({ label: `${loop.fam} loop`, note: Object.entries(counts).map(([k, n]) => `${k} ×${n}`).join(', '), end: `${isoClock(loop.items[loop.items.length - 1].e || loop.items[0].s)} · ${durTxt(total)}`, act: 'audit-met', arg: key, open: st.auditMet.has(key), inset: true, body: S.EventLines(loop.items.map(ledgerRow)) }));
      }
      loop = null;
    };
    seq.forEach((x) => {
      if (x.b) {
        flushLoop();
        const what = [x.b.phase, x.b.n ? `attempt ${x.b.n}` : null, x.b.disp === 'needs_user_action' ? 'needs your decision' : null].filter(Boolean).join(' · ');
        rows.push([x.b.at ? isoClock(x.b.at) : '', `<b>Blocked</b>${what ? ` · ${esc(what)}` : ''}${x.b.why ? S.ExpandableText({ text: x.b.why, cls: 'sb-insp-note' }) : ''}`, 'var(--st-blocked)']);
        if (x.b.at) prevEnd = x.b.at;
        return;
      }
      const p = x.p, fam = LOOP_OF[p.p] || p.p;
      if (prevEnd && p.s - prevEnd > LONG_GAP) { flushLoop(); rows.push(['', `idle ${durTxt(p.s - prevEnd)}`, 'var(--fg-faint)']); }
      if (loop && loop.fam === fam && loop.items.every((q) => q.e)) loop.items.push(p); else { flushLoop(); loop = { fam, items: [p] }; }
      prevEnd = p.e || prevEnd;
    });
    flushLoop(); flushRows();
    return out.join('') || S.EventLines([['', g.state === 'pending' ? 'Not started.' : 'No phases recorded.', 'var(--fg-faint)']]);
  }
  function activitySection(st, it) {
    const feed = activityEntries(it);
    if (it.kind !== 'goal') return feed.length ? secBlock(st, 'activity', { title: 'Activity', count: feed.length, body: S.EventLines(feed) }) : '';
    const groups = activityGroups(st, it), filter = (st.actFilter || {})[it.id] || 'all';
    const nBlocks = groups.reduce((n, g) => n + g.blocks.length, 0);
    const openOf = (g) => { const k = `${it.id}|${g.id}`; return st.actOpen && k in st.actOpen ? st.actOpen[k] : filter === 'blocks' || ['blocked', 'active', 'failed', 'paused'].includes(g.state); };
    const all = groups.flatMap((g) => g.log), timed = all.filter((p) => p.e);
    const active = timed.reduce((n, p) => n + (p.e - p.s), 0);
    const spans = groups.filter((g) => g.s).map((g) => ({ g, s: g.s, e: g.e || g.log.reduce((n, p) => Math.max(n, p.e || p.s), g.s) }));
    let idle = 0; spans.slice(1).forEach((x, i) => { const gap = x.s - spans[i].e; if (gap > LONG_GAP) idle += gap; });
    const models = {}; timed.forEach((p) => { const k = String(p.m || '').replace(/^claude-/, ''); if (k) models[k] = (models[k] || 0) + (p.e - p.s); });
    const topModel = Object.entries(models).sort((x, y) => y[1] - x[1])[0];
    const fixes = all.filter((p) => /fix/.test(p.p)).length;
    const metrics = S.MetricStrip([
      { label: 'Phase time', value: timed.length ? durTxt(active) : 'Not recorded', note: idle ? `${durTxt(idle)} idle` : 'no long gaps' },
      { label: 'Blocks', value: String(nBlocks), note: nBlocks ? 'stopped for help' : 'none' },
      { label: 'Subtasks', value: `${it.done || 0}`, unit: `/ ${it.total || 0}`, note: `${all.length} phases · ${fixes} ${fixes === 1 ? 'fix' : 'fixes'}` },
      { label: 'Models', value: esc(topModel ? topModel[0] : it.model || '—'), note: topModel && active ? `${Math.round(topModel[1] / active * 100)}% of phase time` : 'only model' },
    ]);
    const segs = []; let prev = null;
    spans.forEach(({ g, s, e }) => {
      if (prev && s - prev > LONG_GAP) segs.push({ tone: 'pending', n: Math.min(s - prev, 3600000), tip: `idle ${durTxt(s - prev)}` });
      const tone = g.state === 'active' ? 'active' : g.blocks.length || g.state === 'blocked' ? 'blocked' : g.state === 'failed' ? 'failed' : 'done';
      segs.push({ tone, n: Math.max(e - s, 60000), tip: `${g.n} · ${g.name} · ${durTxt(e - s)}`, act: 'act-group', arg: `${it.id}|${g.id}|open` });
      prev = e;
    });
    const range = spans.length ? `${dayTxt(spans[0].s)} ${isoClock(spans[0].s)} → ${it.live && it.lifecycle === 'active' ? 'now' : `${dayTxt(prev)} ${isoClock(prev)}`}` : '';
    const shown = groups.filter((g) => filter === 'all' || g.blocks.length || g.state === 'blocked');
    const body = []; let lastDay = null, lastEnd = null;
    shown.forEach((g) => {
      if (g.s) { const d = dayTxt(g.s); if (d !== lastDay) { body.push(S.MiniRule(d)); lastDay = d; } }
      if (filter === 'all' && lastEnd && g.s && g.s - lastEnd > LONG_GAP) body.push(S.MiniRule(`idle ${durTxt(g.s - lastEnd)}`));
      const agents = [...new Set(g.log.map((p) => p.a).filter(Boolean))];
      const len = g.log.reduce((n, p) => n + ((p.e || p.s) - p.s), 0);
      const tag = g.state === 'active' ? 'running' : g.blocks.length ? `${g.blocks.length} ${g.blocks.length === 1 ? 'block' : 'blocks'}` : g.state === 'blocked' ? 'blocked' : '';
      body.push(S.Fold({ lead: S.StatusIcon(g.state, 0.5), label: `${g.n}  ${g.name}`, strong: true, note: tag, end: [g.s ? isoClock(g.s) : '', len ? durTxt(len) : '', agents.map(agentMark).join('')].filter(Boolean).join(' · '),
        act: 'act-group', arg: `${it.id}|${g.id}`, open: openOf(g), inset: true, body: groupBodyHtml(st, it, g) }));
      lastEnd = g.e || lastEnd;
    });
    if (!shown.length) body.push(S.Box(S.BoxNote({ text: 'No blocks in this run.' })));
    if (feed.length && filter === 'all') {
      const k = `${it.id}|run`;
      body.push(S.Fold({ lead: S.StatusIcon(iconState(it), frac(it)), label: 'Goal activity', strong: true, note: `${feed.length} events`, act: 'act-group', arg: `${it.id}|run`, open: st.actOpen && k in st.actOpen ? st.actOpen[k] : true, inset: true, body: S.EventLines(feed) }));
    }
    const anyClosed = groups.some((g) => !openOf(g));
    return secBlock(st, 'activity', { title: 'Activity', count: `${all.length} phases`, end: [
      S.LinkButton({ label: anyClosed ? 'Expand all' : 'Collapse all', act: 'act-all', arg: `${it.id}|${anyClosed ? 1 : 0}` }),
      S.Segmented({ act: 'act-filter', value: filter, items: [{ id: 'all', arg: `${it.id}|all`, label: 'All' }, { id: 'blocks', arg: `${it.id}|blocks`, label: `Blocks ${nBlocks}` }] }),
    ], body: `<div class="l-col g-3">${metrics}${segs.length ? `<div class="l-col g-1">${S.SegmentBar({ segs, tall: true, label: 'Run timeline' })}${range ? S.MiniRule(range) : ''}</div>` : ''}<div class="l-col g-1">${body.join('')}</div></div>` });
  }


  /* ---------- QA results (QA results design; desktop feature/detail QaResultsView, QaResultsSection, QaUnblock) ---------- */
  /* The verdict word and the sentence come from counts, never from the agent's prose. Open findings, things QA left behind,
   * untested areas and Unblock entries need the reader; fixed findings, passed checks, run details and the full report fold.
   * `q` is a GoalQaResult or a standalone run's QA facts (data.js QA); `o`: { fixLoop (goal QA), running }. */
  const QA_SKIP = {
    capability_absent: ['the QA capability it needs is missing', 'capability missing'], unsupported_target: ['the target is not supported', 'target not supported'],
    disabled_policy: ['QA is turned off for this run', 'QA turned off'], isolation_unavailable: ['an isolated QA workspace was not available', 'isolation unavailable'],
    unauthorized_target: ['the target is not authorized', 'target not authorized'], offline_target: ['the target is offline', 'target offline'],
    device_contended: ['another run holds every device', 'device in use'], production_only: ['the only target is production', 'production only'],
    docker_unavailable: ['Docker is not available', 'Docker missing'], environment_unavailable: ['an isolated environment could not be set up', 'environment missing'],
    isolation_rejected: ['the environment failed the isolation checks', 'isolation rejected'], setup_budget_exceeded: ['environment setup ran past its time limit', 'setup timed out'],
    browser_unavailable: ['no browser driver is available', 'browser driver missing'],
  };
  const humanized = (t) => String(t).replace(/_/g, ' ');
  const capFirst = (t) => (t ? t[0].toUpperCase() + t.slice(1) : t);
  const counted = (n, noun) => `${n} ${n === 1 ? noun : noun.endsWith('s') ? noun : noun.endsWith('y') ? noun.slice(0, -1) + 'ies' : noun + 's'}`;
  const joinedList = (xs) => (xs.length < 2 ? xs.join('') : xs.slice(0, -1).join(', ') + ' and ' + xs[xs.length - 1]);
  const skipPhrase = (t) => (QA_SKIP[t] || [humanized(t)])[0];
  const shortReason = (t) => (QA_SKIP[t] || [0, humanized(t)])[1];
  const qaFile = (p) => String(p).replace(/[/\\]+$/, '').split(/[/\\]/).pop();
  /* Paths under a home or temp directory read as their file name; the evidence chips keep the full path. */
  const shortenPaths = (text) => String(text || '').replace(/(?:\/home\/|\/Users\/|\/tmp\/|~\/)[^\s`"'<>(),;]*/g, (m) => { const p = m.replace(/[.:!?]+$/, ''); return qaFile(p) + m.slice(p.length); });
  const qaInline = (text) => esc(shortenPaths(text)).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\n/g, '<br>');
  /* The report's "## Unblock" section: "#### capability" entries with Target, Tried, Evidence and Remedy lines and a fenced
   * snippet for .skill-bill/phases/qa.md. */
  function qaUnblock(report) {
    const lines = String(report || '').split('\n');
    const start = lines.findIndex((l) => /^#{1,2}\s+unblock\s*$/i.test(l.trim()));
    if (start < 0) return [];
    const out = [];
    let cur = null, fence = null;
    for (const line of lines.slice(start + 1)) {
      if (fence) { if (/^\s*```/.test(line)) { if (Array.isArray(fence)) cur.snippet = fence.join('\n'); fence = null; } else if (Array.isArray(fence)) fence.push(line); continue; }
      if (/^\s*```/.test(line)) { fence = cur && cur.snippet == null ? [] : true; continue; }
      const h = line.match(/^####\s+(.*)$/);
      if (h) { cur = { capability: h[1].trim(), snippet: null }; out.push(cur); continue; }
      if (/^#{1,3}\s/.test(line)) break;
      const f = cur && line.match(/^\s*-\s*(target|tried|evidence|remedy)\s*:\s*(.*)$/i);
      if (f) cur[f[1].toLowerCase()] = f[2].trim() || null;
    }
    return out;
  }
  const firstParagraph = (text) => String(text || '').trim().split(/\n\s*\n/).map((b) => b.trim()).find((b) => b && !/^(verdict|reason):/i.test(b) && !/^[#*`-]/.test(b)) || null;
  function qaView(q, o) {
    o = o || {};
    const d = q.details || {};
    const dir = d.invocationDirectory || q.dir || null;
    const findings = q.findings || [];
    const open = findings.filter((f) => !f.fixed), fixed = findings.filter((f) => f.fixed);
    const asserts = d.assertions || [];
    const passed = asserts.filter((a) => a.outcome === 'passed');
    const coverage = d.coverage || [];
    const retained = d.retainedResources || [];
    const edits = (d.temporaryEdits || []).filter((e) => e.notRestored);
    const outOfScope = (c) => c.verdict === 'skipped' && c.reason === 'empty_scope';
    const gapRow = (a) => ({ title: a.title, detail: a.detail, evidence: a.evidencePaths || [] });
    /* A failed check shows only when no finding covers it; partial coverage stands in when no check was tagged untested. */
    const notExercised = asserts.filter((a) => a.outcome === 'not_exercised').map(gapRow);
    const notTested = notExercised.concat(
      notExercised.length ? [] : coverage.filter((c) => c.verdict === 'incomplete' || (c.verdict === 'skipped' && !outOfScope(c))).map((c) => ({ title: `${capFirst(humanized(c.kind))}, ${c.reason ? shortReason(c.reason) : humanized(c.verdict)}`, detail: '', evidence: [] })),
      open.length ? [] : asserts.filter((a) => a.outcome === 'failed').map(gapRow));
    const tagged = asserts.length > 0;
    const n = { open: open.length, fixed: fixed.length, untested: notTested.length, passed: passed.length, left: retained.length + edits.length };
    const outcome = q.outcome;
    const unsettled = !outcome || outcome === 'attempted';
    const unfinished = unsettled || outcome === 'incomplete';
    const report = (q.reportText || '').trim();
    const skipToken = d.skipReason || (report.match(/^reason:[ \t]*([a-z_]+)/im) || [])[1] || null;
    const word = o.fixing && n.open ? 'Fixing' : o.running && unsettled ? 'Running' : n.open || outcome === 'findings' ? 'Needs your review' : outcome === 'skipped' ? 'Skipped'
      : unfinished && !report ? 'Did not finish' : n.untested || unfinished ? 'Incomplete' : n.left ? 'Passed, cleanup needed' : n.fixed ? 'Passed after fixes' : 'Passed';
    const tone = { Running: 'faint', Fixing: 'blocked', 'Needs your review': 'failed', 'Did not finish': 'failed', Skipped: skipToken === 'empty_scope' ? 'faint' : 'blocked', Incomplete: 'blocked', 'Passed, cleanup needed': 'blocked' }[word] || (tagged ? 'done' : 'faint');
    const kinds = {};
    retained.forEach((r) => { kinds[r.kind] = (kinds[r.kind] || 0) + 1; });
    const leftList = joinedList([...Object.entries(kinds).map(([k, c]) => (k === 'process' ? (c === 1 ? '1 process running' : `${c} processes running`) : counted(c, humanized(k)))), ...(edits.length ? [counted(edits.length, 'temporary edit')] : [])]);
    const checks = tagged && n.passed ? `${counted(n.passed, 'check')} passed.` : null;
    const things = counted(n.untested, 'thing');
    const passedLine = !tagged ? 'QA passed. This run has no tagged checks.' : n.passed === 1 ? 'The 1 check passed.' : `All ${n.passed} checks passed.`;
    const body = {
      Running: () => ['QA is still running.', n.open ? `It has ${counted(n.open, 'open problem')} so far.` : null],
      Fixing: () => [`QA found ${counted(n.open, 'problem')}. qa_fix is working on ${n.open === 1 ? 'it' : 'them'}, then QA runs again.`, checks],
      'Needs your review': () => (n.open ? [`${o.fixLoop ? 'QA could not fix' : 'QA found'} ${counted(n.open, 'problem')}${n.untested ? ` and could not test ${things}` : ''}.`, checks] : ['QA reported problems but listed none of them. Open the full report.']),
      Skipped: () => [skipToken === 'empty_scope' ? 'QA skipped this change. No acceptance criterion describes behavior it can exercise.' : skipToken ? `QA skipped this change because ${skipPhrase(skipToken)}.` : 'QA skipped this change and did not say what was missing.'],
      'Did not finish': () => ['QA stopped before it wrote a report, so this change has no QA result.'],
      Incomplete: () => (n.untested && n.fixed ? [`QA fixed ${counted(n.fixed, 'problem')}, but could not test ${things}.`, checks] : n.untested ? [`QA found no problems, but could not test ${things}.`, checks] : ['QA did not finish testing this change.', checks]),
      'Passed, cleanup needed': () => [tagged ? passedLine : `QA passed, but left ${leftList}.`],
      'Passed after fixes': () => [`QA found ${counted(n.fixed, 'problem')} and ${n.fixed === 1 ? 'fixed it' : 'fixed them'}${q.fixAttempts ? ` after ${counted(q.fixAttempts, 'attempt')}` : ''}.`, tagged ? checks : 'This run has no tagged checks.'],
      Passed: () => [passedLine],
    }[word]();
    if (n.left && !(word === 'Passed, cleanup needed' && !tagged)) body.push(`QA left ${leftList}.`);
    const inScope = coverage.filter((c) => !outOfScope(c)), outScope = coverage.filter(outOfScope);
    /* [kind, word] pairs: "Desktop" "incomplete, capability missing", then the out-of-scope kinds as one pair. */
    const targets = coverage.length ? [
      ...inScope.map((c) => [capFirst(humanized(c.kind)), c.verdict === 'passed' || c.verdict === 'findings_open' ? 'tested' : humanized(c.verdict) + (c.reason ? ', ' + shortReason(c.reason) : '')]),
      ...(outScope.length ? [[capFirst(outScope.map((c) => humanized(c.kind)).join(', ')), 'not in scope']] : []),
    ] : null;
    const noChecks = !tagged && outcome !== 'skipped';
    const leftRow = (r) => (r.kind === 'process' ? { title: 'Process still running', detail: `${r.name ? r.name + ', ' : ''}pid ${r.identity}. Release reported it as retained.`, copyLabel: 'Copy pid', copy: r.identity }
      : r.kind === 'directory' || r.kind === 'worktree' ? { title: `${capFirst(r.kind)} left behind`, detail: qaFile(r.identity), copyLabel: 'Copy path', copy: r.identity }
      : r.kind === 'device_lease' ? { title: 'Device lease still held', detail: r.identity, copyLabel: 'Copy name', copy: r.identity }
      : { title: `${capFirst(humanized(r.kind))} left behind`, detail: r.identity, copyLabel: 'Copy name', copy: r.identity });
    return {
      runKey: q.runKey, word, tone, sentence: body.filter(Boolean).join(' '), dir,
      open: open.map((f) => ({ id: f.id, title: f.title || f.id, state: o.fixLoop ? 'Not fixed' : 'Open', observed: f.observed ? capFirst(f.observed) : null, expected: f.expected || null, steps: f.steps || null, evidence: f.evidencePaths || [] })),
      left: [...retained.map(leftRow), ...edits.map((e) => ({ title: 'Temporary edit', detail: [e.path ? '`' + e.path + '`' : null, e.detail].filter(Boolean).join(': '), copyLabel: 'Copy path', copy: e.path }))],
      notTested,
      fixed: fixed.length ? fixed.map((f) => ({ title: `${f.id} ${f.title}`, name: f.title, detail: capFirst(f.observed || ''), evidence: f.evidencePaths || [] })) : null,
      passed: passed.length ? passed.map(gapRow) : null,
      runDetails: [q.failureKind ? capFirst(humanized(q.failureKind)) : null, q.reason].filter(Boolean).join('\n') || null,
      report: report ? { text: q.reportText, preview: q.reportPreview || (noChecks ? 'No tagged checks in this run' : 'Isolation, cleanup and the agent’s notes'), first: noChecks ? firstParagraph(report) : null } : null,
      /* An older run has no stored report path; its report.md sits in the run directory. */
      reportPath: d.reportPath || (dir ? dir.replace(/\/?$/, '/') + 'report.md' : null), targets,
      unblock: outcome === 'incomplete' || outcome === 'skipped' || notTested.length ? qaUnblock(q.reportText) : [],
    };
  }
  /* Rows open per run (st.qaOpen keys "qa:<runKey>:<part>"). Evidence opens against the run's invocation directory, so a run
   * without one draws no evidence chips. Returns the verdict for the section's end slot and the body. */
  /* `namesOnly` draws evidence chips that name files and open nothing; `select(findingId)` adds a checkbox, a Chat or Goal
   * switch and a shared-code hint per open finding, and `selectHead` a Select all row. A Verify run's QA step uses all
   * three. */
  function qaResults(st, q, o) {
    o = o || {};
    const v = qaView(q, o);
    const base = `qa:${q.runKey}`;
    const toggle = (k) => ({ act: 'qa-toggle', arg: `${base}:${k}`, open: st.qaOpen.has(`${base}:${k}`) });
    const evidence = (paths) => (v.dir ? paths.map((p) => S.Chip({ icon: /\.(png|jpe?g|webp)$/i.test(p) ? 'qaImage' : 'qaFile', label: qaFile(p), mono: true, tip: p, act: 'open-file', arg: p }))
      : o.namesOnly ? paths.map((p) => S.Chip({ icon: /\.(png|jpe?g|webp|mp4)$/i.test(p) ? 'qaImage' : 'qaFile', label: qaFile(p), mono: true, tip: `${p}. The QA run directory is not stored, so this file does not open.` })) : []);
    const copy = (label, text) => S.Chip({ icon: 'qaCopy', label, act: 'copy', arg: text });
    const needs = v.open.map((f) => S.QaItem(Object.assign({
      glyph: 'qaBug', tone: o.fixing ? 'blocked' : 'failed', ref: f.id, title: shortenPaths(f.title), state: o.fixing ? 'Fixing' : f.state, line: f.observed && qaInline(f.observed),
      fields: [['Expected', f.expected && qaInline(f.expected)], ['Steps', f.steps && qaInline(f.steps)]],
      chips: [...evidence(f.evidence), f.steps ? copy('Copy steps', f.steps.replace(/`/g, '')) : ''],
    }, f.expected || f.steps || ((v.dir || o.namesOnly) && f.evidence.length) ? toggle('f:' + f.id) : {}, o.select ? o.select(f.id) : {})));
    const left = v.left.map((l) => S.QaItem({ glyph: 'qaLeft', tone: 'blocked', title: l.title, line: l.detail && qaInline(l.detail), chips: l.copy ? [copy(l.copyLabel, l.copy)] : [] }));
    const gaps = v.notTested.map((g, i) => S.QaItem(Object.assign({ glyph: 'gap', tone: 'blocked', title: shortenPaths(g.title), line: g.detail && qaInline(g.detail), chips: evidence(g.evidence) }, v.dir && g.evidence.length ? toggle('g:' + i) : {})));
    const unblock = v.unblock.map((u) => S.QaItem({
      glyph: 'gap', tone: 'blocked', title: u.capability, line: u.target && esc(`Target: ${u.target}`),
      fields: [['Remedy', u.remedy && qaInline(u.remedy)], ['Tried', u.tried && qaInline(u.tried)]],
      chips: [...evidence((u.evidence || '').split(',').map((x) => x.trim()).filter(Boolean)), u.snippet != null ? copy('Copy for qa.md', u.snippet) : ''],
    }));
    const listFold = (key, label, rows) => S.QaFold(Object.assign({ icon: 'qaCheck', tone: 'done', label: `${label} · ${rows.length}`, preview: rows.map((r) => shortenPaths(r.name || r.title)).join(', '),
      rows: rows.map((r) => ({ title: shortenPaths(r.title), html: r.detail ? qaInline(r.detail) : '', chips: evidence(r.evidence) })) }, toggle(key)));
    const folds = [
      v.fixed && listFold('fixed', 'Fixed by QA', v.fixed),
      v.passed && listFold('passed', 'Passed', v.passed),
      v.runDetails && S.QaFold(Object.assign({ icon: 'qaDoc', label: 'Run details', body: qaInline(v.runDetails) }, toggle('details'))),
      v.report && S.QaFold(Object.assign({ icon: 'qaDoc', label: 'Full report', preview: v.report.preview, before: v.report.first && qaInline(v.report.first),
        body: S.ReportText(v.report.text), chips: v.reportPath ? [S.Chip({ icon: 'qaOpen', label: 'Open report.md', act: 'open-file', arg: v.reportPath })] : [] }, toggle('report'))),
    ];
    return {
      verdict: S.QaVerdict({ tone: v.tone, word: v.word }),
      body: S.QaResults({ sentence: v.sentence, targets: v.targets, folds, groups: [
        { label: o.fixing ? 'Sent to qa_fix' : 'Needs you', items: needs, head: o.selectHead || null }, { label: 'Left behind', items: left }, { label: 'Not tested', items: gaps },
        { label: 'Unblock', items: unblock, foot: S.Chip({ icon: 'qaOpen', label: 'Open qa.md', act: 'open-file', arg: '.skill-bill/phases/qa.md', tip: 'Opens .skill-bill/phases/qa.md. The app never writes it.' }) },
      ] }),
    };
  }

  /* ---------- Validation run (validation run design, Redesign; desktop feature/detail ValidationDocument,
   * ValidationOutcomeCard, ValidationChecksSection, ValidationReport, ValidationReportParts, ValidationBranchRunsSection) ---------- */
  /* A standalone validation phase renders its own document, in the mock's order: the header, the outcome card (it replaces
   * the Step and Phase elapsed tiles: the step is always validate, so the tile and the one-stage pipeline said nothing),
   * the flag strip, Checks (when the agent can report them), the report in parts, the other validation runs on the
   * branch and Activity. Sections without data are not drawn. */
  const isValidationRun = (it) => it.kind === 'phase' && it.phase === 'validation' && !!it.valRun;
  /* Durations as the validation design prints them: rounded to the second, "<1s" under a second, "1h 4m" past an hour. */
  const valDur = (ms) => {
    if (ms == null) return '—';
    if (ms < 1000) return '<1s';
    const t = Math.round(ms / 1000), h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), r = t % 60;
    return h ? `${h}h ${m}m` : m ? `${m}m ${String(r).padStart(2, '0')}s` : `${r}s`;
  };
  const durS = (ms) => S.fmtDur(ms, true);
  const md = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
  const plainMd = (s) => String(s || '').replace(/\*\*|`/g, '');
  /* The report splits at a line that opens with a bold lead-in ("**Checks run.**") or a Warnings, Risk or Notes label. Its
   * first sentence heads the card and the rest of that paragraph follows; leftover paragraphs become a Details part. Parts
   * whose title mentions warnings, risk, skipped coverage, concurrent writers, blocked or later phases are flagged; parts
   * about checks or results start open unless the agent reported checks. */
  const FLAG_TITLE = /warn|risk|couldn.t run|skipped|later phases|concurrent|blocked/i;
  const OPEN_TITLE = /check|result/i;
  function splitSentence(p) { const m = p.match(/^(.+?[.!?])\s+(?=[A-Z`"*])/); return m ? [m[1], p.slice(m[0].length)] : [p, '']; }
  function shapeReport(text) {
    const parts = [];
    let cur = { title: null, lines: [] };
    String(text).trim().split('\n').forEach((line) => {
      const m = line.match(/^\*\*(.+?)\*\*\s*(.*)$/) || line.match(/^(Warnings?|Risk|Notes?):\s+(.*)$/);
      if (m) { parts.push(cur); cur = { title: m[1].replace(/[.:]\s*$/, ''), lines: m[2] ? [m[2]] : [] }; } else cur.lines.push(line);
    });
    parts.push(cur);
    const lead = parts.shift();
    const paras = lead.lines.join('\n').trim().split(/\n\s*\n/);
    const [headline, rest] = splitSentence(paras.shift().replace(/\n/g, ' '));
    if (paras.length) parts.unshift({ title: 'Details', lines: paras.join('\n\n').split('\n') });
    return {
      headline, rest,
      parts: parts.filter((p) => p.lines.join('').trim()).map((p) => ({
        title: plainMd(p.title), html: md(p.title), text: p.lines.join('\n'), flag: FLAG_TITLE.test(p.title), open: OPEN_TITLE.test(p.title),
        items: p.lines.filter((l) => /^(\d+\.|[-*])\s+/.test(l)).length,
        preview: plainMd(p.lines.find((l) => l.trim()) || '').replace(/^\s*(\d+\.|[-*])\s+/, ''),
      })),
    };
  }
  const reportOf = (it) => (it.valRun.result ? shapeReport(it.valRun.result) : null);
  const finalOf = (c) => (c.attempts.length ? c.attempts[c.attempts.length - 1].outcome : 'pending');
  const retriesOf = (c) => c.attempts.filter((x) => x.outcome === 'failed').length;
  const checksShown = (it) => it.valRun.liveChecks && it.valRun.checks.length > 0;
  const checkExpandable = (c) => { const last = c.attempts[c.attempts.length - 1]; return c.attempts.length > 1 || !!(last && last.note && !['blocked', 'skipped'].includes(finalOf(c))); };
  /* Open by default: report parts about checks or results (none once checks are reported); a retried check while it runs. */
  const valDefaultParts = (it) => { const r = reportOf(it); return new Set(r && !checksShown(it) ? r.parts.map((p, i) => (p.open ? i : -1)).filter((i) => i >= 0) : []); };
  const valDefaultChecks = (it) => new Set(it.lifecycle === 'active' && it.valRun.liveChecks ? it.valRun.checks.filter((c) => retriesOf(c)).map((c) => c.id) : []);
  const valParts = (st, it) => st.valParts[it.id] || valDefaultParts(it);
  const valChecks = (st, it) => st.valChecks[it.id] || valDefaultChecks(it);
  const CHECK_TALLY = [['passed', 'var(--st-done)'], ['running', 'var(--st-active)'], ['blocked', 'var(--st-blocked)'], ['failed', 'var(--st-failed)'], ['skipped', 'var(--fg-faint)'], ['pending', 'var(--fg-faint)']];
  const CHECK_SEG = { passed: 'done', failed: 'blocked', blocked: 'blocked', running: 'active', skipped: 'skipped', pending: 'pending' };
  /* Every failed attempt across the checks: "2 failed attempts fixed" in the tally, "2 retried" in the Checks cell. */
  const retriesAll = (checks) => checks.reduce((t, c) => t + retriesOf(c), 0);
  function checkTally(checks) {
    const n = {};
    checks.forEach((c) => { const f = finalOf(c); n[f] = (n[f] || 0) + 1; });
    const fixed = retriesAll(checks);
    return [
      S.CheckTally({ items: CHECK_TALLY.filter(([k]) => n[k]).map(([k, color]) => ({ n: n[k], word: k, color })), note: fixed ? `${fixed} failed ${fixed === 1 ? 'attempt' : 'attempts'} fixed` : null }),
      S.SegmentBar({ equal: true, segs: checks.map((c) => ({ n: 1, tone: CHECK_SEG[finalOf(c)] })), label: `${checks.length} checks` }),
    ];
  }
  /* Card tone follows lifecycle_state until the agent reported checks: then the runtime's outcome (Passed, Blocked, Failed)
   * colors it. A finished run without checks stays neutral, because its report alone can say "blocked". The header pill
   * says Active where the card says Running. */
  const VAL_STATE = { active: ['Running', 'active', 'play', 'Active'], blocked: ['Blocked', 'blocked', 'pause'], paused: ['Paused', 'blocked', 'pause'], terminal: ['Finished', 'done', 'flag'], canceled: ['Canceled', 'faint', 'x'], failed: ['Failed', 'failed', 'x'], idle: ['Pending', 'faint', 'pause'] };
  const VAL_OUTCOME = { passed: ['Passed', 'done', 'check'], blocked: ['Blocked', 'blocked', 'warn'], failed: ['Failed', 'failed', 'x'] };
  const valStatus = (it) => (it.valRun.liveChecks && it.valRun.outcome ? VAL_OUTCOME[it.valRun.outcome] : (VAL_STATE[it.lifecycle] || VAL_STATE.terminal));
  function validationCard(it, shaped) {
    const v = it.valRun;
    const active = it.lifecycle === 'active';
    const checks = checksShown(it) ? v.checks : null;
    const [word, tone, icon] = valStatus(it);
    const activeClock = active ? S.Clock({ ms: it.goalMs, live: it.live, t0: D.T0, secs: true }) : esc(valDur(it.goalMs));
    const o = { tone, icon, live: active, aria: 'Validation outcome',
      label: `<b>${word}</b>${v.liveChecks && v.outcome === 'blocked' ? '<span>· needs an operator</span>' : ''}${it.lifecycle === 'terminal' && shaped ? '<span>· agent\'s summary below</span>' : ''}` };
    if (active) { o.label = '<b>● LIVE</b>'; if (!v.outcome) o.tone = 'done'; }
    const running = active && checks ? checks.find((c) => finalOf(c) === 'running') : null;
    const wall = v.wallMs ? { value: esc(valDur(v.wallMs)), note: 'wall clock' } : { value: activeClock, note: 'active' };
    if (running) {
      o.head = `Running <b>${esc(running.name)}</b>`;
      o.cmd = { dir: running.dir, cmd: running.cmd, clock: S.Clock({ ms: running.attempts[0].ms, live: it.live, t0: D.T0, secs: true }) };
      o.when = { value: activeClock, note: 'active' };
    } else if (active) {
      o.head = esc(v.activity || 'Starting validate');
      if (!checks) {
        o.text = 'The report appears here when the agent finishes.';
        if (v.signal) o.signal = `Last agent signal: ${v.signal.label}, ${v.signal.ago}`;
      }
      o.when = { value: activeClock, note: 'active' };
    } else if (it.lifecycle === 'canceled') {
      o.head = esc(v.cancelText || 'Canceled by you.');
      o.when = wall;
    } else if (!shaped) {
      o.head = esc(it.reason || it.summary || '');
      o.when = wall;
    } else {
      o.head = md(shaped.headline);
      o.text = shaped.rest ? md(shaped.rest) : '';
      o.when = wall;
    }
    if (checks) o.extra = checkTally(checks);
    const runs = v.branchRuns || [];
    const done = checks ? checks.filter((c) => !['pending', 'running'].includes(finalOf(c))).length : 0;
    const retried = checks ? retriesAll(checks) : 0;
    o.cells = [
      { key: 'Active', value: activeClock, sub: active ? 'ticking' : 'agent working time' },
      { key: 'Started', value: esc(it.started), sub: esc(`${v.day || ''}${it.finishedAt ? ' → ' + it.finishedAt : ''}`) },
      checks ? { key: 'Checks', value: `${done} / ${checks.length}`, sub: retried ? `${retried} retried` : 'no retries' } : { key: 'Agent', value: esc(it.agent || '—'), sub: it.agent ? `--agent ${esc(it.agent)}` : '' },
      { key: 'Run', value: `#${v.runSeq}`, sub: runs.length > 1 ? `${runs.findIndex((r) => r.seq === v.runSeq) + 1} of ${runs.length} on branch` : 'only run on branch' },
    ];
    return S.RunOutcome(o);
  }
  function checkRow(it, c, open) {
    const f = finalOf(c), last = c.attempts[c.attempts.length - 1];
    const can = checkExpandable(c);
    const total = c.attempts.reduce((t, x) => t + x.ms, 0);
    return S.CheckRow({
      name: c.name, dir: c.dir, cmd: c.cmd, state: f, reason: ['blocked', 'skipped'].includes(f) && last.note ? md(last.note) : null,
      attempt: retriesOf(c) ? c.attempts.length : null,
      time: f === 'pending' || f === 'skipped' ? '' : f === 'running' ? S.Clock({ ms: total, live: it.live, t0: D.T0, secs: true }) : esc(valDur(total)),
      open: can && open.has(c.id), act: can ? 'val-check' : null, arg: `${it.id}|${c.id}`,
      attempts: c.attempts.map((x, n) => ({ n: n + 1, at: x.at, outcome: x.outcome, exit: x.exit, time: valDur(x.ms), note: x.note ? md(x.note) : null })),
    });
  }
  /* The header: the status pill, "validation" in mono, the phase pill and, while it runs, the fresh pill; "Validation on"
   * and the branch in code; the repository and the agent; the run controls for the state. */
  function validationActions(st, it) {
    const ro = it.refreshFailed ? {} : (it.runOptions || {});
    const v = it.valRun;
    const cancel = S.Button({ label: 'Cancel', icon: 'x', variant: 'danger', act: 'cancel', arg: it.id, disabled: !ro.canCancel });
    const again = S.Button({ label: 'Run again', act: 'val-again', arg: it.id, disabled: it.refreshFailed });
    if (it.lifecycle === 'active') return [cancel];
    if (it.lifecycle === 'blocked' || it.lifecycle === 'paused') return [S.Button({ label: (it.runOptions || {}).launchLabel || 'Resume', variant: 'primary', act: 'launch', arg: it.id, disabled: !ro.canLaunch }), cancel];
    if (it.lifecycle === 'terminal') return [again, v.result ? S.Button({ label: 'Copy report', icon: 'copy', act: 'copy', arg: v.result }) : ''];
    return [again];
  }
  function validationHeader(st, it) {
    const r = repoOf(it.repo);
    const [word, tone, , pill] = valStatus(it);
    const live = it.lifecycle === 'active';
    return S.VerifyHeader({ status: { word: it.valRun.liveChecks && it.valRun.outcome ? word : LIFE[it.lifecycle][0], tone }, key: 'validation', kind: 'phase',
      pills: [it.stalled ? { word: 'Stalled', tone: 'blocked' } : null, ...(live ? [it.refreshFailed ? { word: 'stale', tone: 'faint' } : { word: 'fresh', tone: 'active' }] : [])].filter(Boolean),
      html: `Validation on <code>${esc(it.branch || 'no branch')}</code>`,
      meta: [{ icon: 'folder', text: r.path, mono: true, act: 'toast', arg: `Opened ${r.path}` }, it.agent ? { icon: 'bot', text: it.agent } : null],
      actions: validationActions(st, it),
      after: it.refreshFailed ? [S.Hint({ tone: 'warn', text: 'Refresh the detail before using run controls.' })] : [] });
  }
  const RUN_WORD = { active: ['Running', 'var(--st-active)'], blocked: ['Paused', 'var(--st-blocked)'], paused: ['Paused', 'var(--st-blocked)'], terminal: ['Finished', 'var(--fg-2)'], canceled: ['Canceled', 'var(--fg-faint)'], failed: ['Failed', 'var(--st-failed)'] };
  function validationDoc(st, it) {
    const v = it.valRun;
    const shaped = reportOf(it);
    const out = [validationHeader(st, it), validationCard(it, shaped)];
    const flagged = shaped ? shaped.parts.map((p, i) => [p, i]).filter(([p]) => p.flag) : [];
    if (flagged.length) out.push(S.FlagStrip({ title: `The report flags ${flagged.length === 1 ? 'one thing' : flagged.length + ' things'} to read`, jumps: flagged.map(([p, i]) => ({ label: p.title, act: 'val-jump', arg: `${it.id}|${i}` })) }));
    if (v.liveChecks && (v.checks.length || it.lifecycle === 'active')) {
      const open = valChecks(st, it);
      out.push(S.VerifySection({ id: 'sec-val-checks', title: 'Checks', count: v.checks.length || null, end: [S.Tag({ text: 'reported by the agent', color: 'var(--st-planning)' })],
        body: S.Box(v.checks.length ? v.checks.map((c) => checkRow(it, c, open)) : S.BoxNote({ text: 'Waiting for the agent to list its checks.' })) }));
    }
    if (shaped && shaped.parts.length) {
      const open = valParts(st, it);
      const all = shaped.parts.every((_, i) => open.has(i));
      out.push(S.VerifySection({ id: 'sec-val-report', title: 'Report', count: `${shaped.parts.length} ${shaped.parts.length === 1 ? 'part' : 'parts'}`,
        end: [S.LinkButton({ label: all ? 'Collapse all' : 'Expand all', act: 'val-all', arg: it.id })],
        body: S.Box(shaped.parts.map((p, i) => S.ReportPart({ id: `val-part-${i}`, html: p.html, flag: p.flag, preview: p.preview, count: p.items ? `${p.items} ${p.items === 1 ? 'item' : 'items'}` : '', open: open.has(i), act: 'val-part', arg: `${it.id}|${i}`, text: p.text }))) }));
    }
    const runs = v.branchRuns || [];
    if (runs.length > 1) {
      out.push(S.VerifySection({ id: 'sec-val-runs', title: 'Validation runs on this branch', count: runs.length, body: S.Box(runs.slice().reverse().map((r) => {
        const cur = r.seq === v.runSeq, [w, color] = RUN_WORD[r.lifecycle] || RUN_WORD.terminal;
        return S.BranchRun({ seq: r.seq, line: r.line, current: cur, agent: r.agent, started: r.started, time: valDur(r.ms), word: w, color, act: r.item && !cur ? 'open' : null, arg: r.item });
      })) }));
    }
    const all = activityEntries(it);
    const showAll = st.feedAll.has(it.id), hidden = showAll ? 0 : Math.max(0, all.length - FEED_MAX);
    out.push(S.VerifySection({ id: 'sec-val-activity', title: 'Activity', count: all.length, body: all.length ? S.EventLines(all.slice(hidden), { hidden, act: 'feed-all', arg: it.id }) : S.Box(S.BoxNote({ text: 'No activity recorded for this run.' })) }));
    return S.ValidationDoc(out);
  }

  /* ---------- Monitor run (design/monitor-run; desktop MonitorDocument, MonitorCyclesGrid, MonitorCiBoard,
   * MonitorRepairPanel, MonitorConflictPanel, MonitorPublishReceipt) ---------- */
  /* A standalone monitor phase renders its own document, in the mock's order: the header (status pill, "monitor", the phase
   * and fresh pills, "#482" and the PR title, repository, branch → base, Cancel and Open PR), the outcome card (one
   * sentence for now; Pull request, Head, Checks and the Repair cap pips), Cycles (one row per CI round: CI, Repair,
   * Publish), the panel of the selected cell and Activity. The panel opens on the live step, else the step the run ended
   * on. The Goal finish monitor and monitor_fix cards reuse the grid and the panels. */
  const isMonitorRun = (it) => it.kind === 'phase' && it.phase === 'monitor' && !!it.monRun;
  const MON_ORDER = ['pass', 'fail', 'pending', 'queued', 'skipping', 'cancel'];
  const MON_SEG = { pass: 'done', fail: 'failed', pending: 'active', queued: 'pending', skipping: 'skipped', cancel: 'skipped' };
  const MON_EV = { neutral: 'var(--fg-faint)', failed: 'var(--st-failed)', active: 'var(--st-active)', done: 'var(--st-done)', blocked: 'var(--st-blocked)', merged: 'var(--data-5)' };
  const monCount = (checks, b) => checks.filter((c) => c.bucket === b).length;
  const monSorted = (checks) => checks.slice().sort((a, b) => MON_ORDER.indexOf(a.bucket) - MON_ORDER.indexOf(b.bucket));
  /* Open state is a toggle over a default: a fixing check's log starts open, the passed group starts folded unless every
   * check passed. st.monOpen and st.monFold hold the keys the operator flipped. */
  const monFlip = (set, key, dflt) => (set.has(key) ? !dflt : dflt);
  /* A step that was live when the run stopped (paused, failed, canceled) no longer breathes. */
  const monState = (live, step) => (step.state === 'now' && !live ? 'stopped' : step.state);
  const monAgent = (name) => agentOf(name) || { initials: '?', color: 'var(--fg-faint)' };
  function monCell(step, cyc, run, o) {
    const last = run.cycles[run.cycles.length - 1] === cyc;
    if (!step) {
      if (o.kind === 'fix') return { empty: last ? (cyc.ci && ['done', 'merged'].includes(cyc.ci.state) ? 'Not needed' : run.capped ? 'Cap reached' : 'Starts on a failure') : '—' };
      return { empty: cyc.fix && last ? (cyc.fix.outcome === 'unresolved' ? 'Nothing to publish' : 'After the fix') : '—' };
    }
    const state = monState(o.live, step);
    let word, sub, bar = null;
    if (step.kind === 'ci') {
      const n = step.checks.length, p = monCount(step.checks, 'pass'), f = monCount(step.checks, 'fail');
      word = state === 'now' ? (n ? (o.compact ? `${p}/${n} passed` : `${p} of ${n} passed`) : 'Waiting for checks') : state === 'stopped' ? `${p} of ${n} passed` : state === 'done' ? `All ${n} passed` : state === 'conflict' ? 'Merge conflict' : state === 'merged' ? 'PR merged' : `${f} failed`;
      const lead = step.checks.find((c) => c.bucket === 'fail');
      sub = `<span class="mono">${esc(step.head)}</span> · ${lead && state === 'fail' ? esc(lead.name) : esc(step.dur)}`;
      bar = monSorted(step.checks).map((c) => (state !== 'now' && c.bucket === 'pending' ? 'queued' : c.bucket));
    } else if (step.kind === 'conflict') {
      const done = step.files.filter((f) => f.state === 'resolved').length;
      word = state === 'now' ? `${done} of ${step.files.length} resolved` : `Merged ${step.base}`;
      sub = `${esc(step.agent)} · ${state === 'now' ? 'merging ' + esc(step.base) : step.files.length + ' conflicts'} · ${esc(step.dur)}`;
    } else if (step.kind === 'fix') {
      word = state === 'now' ? 'Fixing' : step.outcome === 'unresolved' ? 'Unresolved' : step.prOnly ? 'Edited PR' : state === 'stopped' ? 'Stopped' : 'Fixed';
      const fc = step.files.length;
      sub = `${esc(step.agent)} · ${step.prOnly ? 'no files' : fc ? `${fc} ${fc === 1 ? 'file' : 'files'}` : 'no change'} · ${esc(step.dur)}`;
    } else if (step.kind === 'pub') {
      const c = step.steps.find((x) => x.commit).commit;
      word = state === 'now' ? 'Pushing' : step.merge ? 'Pushed merge' : 'Pushed';
      sub = `<span class="mono">${esc(c.sha)}</span> · ${state === 'now' ? esc(step.dur) : c.parents.length > 1 ? '2 parents' : esc(step.dur)}`;
    } else {
      word = 'PR edited'; sub = `${esc(step.field)} · no push`;
    }
    return { word, sub, bar, tone: state, live: state === 'now', sel: o.sel, act: o.act, arg: o.arg };
  }
  function monitorCycles(it, run, o) {
    const live = o.live, sel = o.sel;
    return S.MonitorCycles({ compact: o.compact, label: `Cycles of the monitor run on #${run.pr.num}`,
      rows: run.cycles.map((c) => ({ n: c.n, at: c.at, cells: ['ci', 'fix', 'pub'].map((kind) => {
        const key = `c${c.n}.${kind}`;
        return monCell(c[kind], c, run, { kind, live, compact: o.compact, sel: sel === key, act: o.act ? o.act : 'mon-cell', arg: o.arg ? o.arg(kind) : `${it.id}|${key}` });
      }) })),
      foot: o.compact ? null : [['loop', `${run.cycles.length} ${run.cycles.length === 1 ? 'cycle' : 'cycles'}`], ['upload', `${run.cycles.filter((c) => c.pub).length} published`], ['clock', 'polls every 30s']] });
  }
  function monCheck(st, it, c, scope, o) {
    const key = `${it.id}|${scope}:${c.name}`;
    const open = !!c.log && monFlip(st.monOpen, key, !!o.openLogs);
    const state = o.frozen && c.bucket === 'pending' ? 'stale' : c.bucket;
    return S.MonitorCheckRow({ name: c.name, job: c.job, state, time: c.time, why: c.why ? md(c.why) : null, rerun: c.rerun,
      ext: { act: 'toast', arg: `Opened the ${c.name} job on GitHub` }, act: c.log ? 'mon-log' : null, arg: key, open,
      log: c.log ? S.LogTail({ lines: c.log, run: c.run, actions: [S.LinkButton({ label: 'Copy', act: 'copy', arg: c.log.map((l) => l[1]).join('\n') }), S.LinkButton({ label: 'Full log', icon: 'external', act: 'toast', arg: 'Opened the job log on GitHub' })] }) : '' });
  }
  function monFoldGroup(st, it, scope, label, names, rows, dflt) {
    const key = `${it.id}|${scope}:fold`;
    const open = monFlip(st.monFold, key, dflt);
    return S.MonitorGroup({ label, count: names.length, fold: { act: 'mon-fold', arg: key, open } }) + (open ? rows() : S.FoldLine({ text: names.join(', '), act: 'mon-fold', arg: key }));
  }
  /* The CI board: the head and poll line, one bar segment per check and the tally, then Failed, Running (Unfinished once the
   * step settled), Waiting, Skipped and Cancelled, then Passed folded to one line, and what happens next. `bare` drops the
   * box head for a PhasePeek card, whose part title says the same. */
  function monCiBoard(st, it, step, scope, o) {
    const live = o.live, frozen = monState(live, step) !== 'now';
    const n = step.checks.length;
    const tally = [['pass', 'passed', 'var(--st-done)'], ['fail', 'failed', 'var(--st-failed)'], ['pending', frozen ? 'unfinished' : 'running', frozen ? 'var(--fg-muted)' : 'var(--st-active)'], ['queued', 'queued', 'var(--fg-faint)']]
      .filter(([b]) => monCount(step.checks, b)).map(([b, word, color]) => ({ n: monCount(step.checks, b), word, color }));
    const sum = S.MonitorSummary([
      S.MonitorLine({ icon: 'commit', html: `Head ${S.Chip({ mono: true, label: step.head })} <span class="t-faint">${esc(step.headNote)}</span>`, end: step.next && !frozen ? `${S.Icon('refresh', 'xs')}<span>read ${esc(step.polled)} · next ${esc(step.next)}</span>` : `<span>${esc(step.polled)}</span>` }),
      n ? S.SegmentBar({ equal: true, segs: monSorted(step.checks).map((c) => ({ n: 1, tone: frozen && c.bucket === 'pending' ? 'pending' : MON_SEG[c.bucket] })), label: `${n} checks` }) : '',
      n ? S.CheckTally({ items: tally }) : '',
      step.mergeable === 'conflicting' ? S.MonitorLine({ icon: 'merge', color: 'var(--st-blocked)', html: 'GitHub reports the PR as <b>conflicting</b> with main' }) : '',
      step.state === 'merged' ? S.MonitorLine({ icon: 'merge', color: 'var(--data-5)', html: `PR merged into main at ${esc(step.mergedAt || '')}` }) : '',
    ]);
    let rows = '';
    if (!n && step.registering) {
      rows = S.MonitorCheckRow({ name: `No checks reported on ${step.head} yet`, job: `Empty read ${step.registering.reads} of 2 · the second can confirm at ${step.registering.until} at the earliest`, state: 'queued', time: step.dur, plain: true });
    } else {
      [['fail', 'Failed'], ['pending', frozen ? 'Unfinished' : 'Running'], ['queued', 'Waiting'], ['skipping', 'Skipped'], ['cancel', 'Cancelled']].forEach(([b, label]) => {
        const list = step.checks.filter((c) => c.bucket === b);
        if (list.length) rows += S.MonitorGroup({ label, count: list.length }) + list.map((c) => monCheck(st, it, c, scope, { frozen })).join('');
      });
      const passed = step.checks.filter((c) => c.bucket === 'pass');
      if (passed.length) rows += monFoldGroup(st, it, scope, 'Passed', passed.map((c) => c.name), () => passed.map((c) => monCheck(st, it, c, scope, { frozen })).join(''), passed.length === n);
    }
    const word = { now: 'Watching', stopped: 'Stopped', done: 'Passed', fail: 'Failed', conflict: 'Conflicting', merged: 'Merged' }[monState(live, step)];
    const next = step.next_ ? S.NextLine({ html: md(step.next_) }) : '';
    if (o.bare) return S.Box([sum, rows, next]);
    return S.MonitorBox({ icon: 'eye', title: `CI on ${step.head}`, sub: `${word} · ${esc(step.since)} · ${esc(step.dur)}`, end: [S.LinkButton({ label: 'Checks on GitHub', icon: 'external', act: 'toast', arg: `Opened github.com/${it.monRun ? it.monRun.pr.slug : 'skill-bill/skill-bill'}/pull/${it.monRun ? it.monRun.pr.num : 482}/checks` })], body: [sum, rows, next] });
  }
  function monGates(gates) {
    return gates.map(([name, b, time, cmd]) => S.MonitorCheckRow({ name, job: cmd, state: b === 'pending' ? 'pending' : b, time: b === 'queued' ? 'after merge' : time, mono: true })).join('');
  }
  function monAgentLine(step, live) {
    const a = monAgent(step.agent);
    const html = step.summary ? md(step.summary) : live && step.signal ? `${S.LiveText(step.signal)} <span class="t-faint">· ${esc(step.ago)}</span>` : esc(step.agent);
    return S.RepairAgent({ initials: a.initials, color: a.color, html, sub: `${step.agent} · ${step.model}`, action: live ? S.Button({ label: 'Watch agent', act: 'toast', arg: 'Opened the agent session' }) : '' });
  }
  /* A repair: the agent and what it is doing (or its explanation), the failing check with its log, the files it changed, the
   * local gates and what happens next. A repair that only edited the PR says so instead of listing files. */
  function monFixPanel(st, it, step, scope, o) {
    const live = monState(o.live, step) === 'now';
    const ins = step.files.reduce((t, f) => t + (f.ins || 0), 0), del = step.files.reduce((t, f) => t + (f.del || 0), 0);
    const outcome = step.outcome ? S.MonBadge({ text: `Repair outcome: ${step.outcome}`, tone: step.outcome === 'fixed' ? 'ok' : 'warn' }) : '';
    const when = [live ? 'running' : step.state === 'blocked' ? 'stopped' : monState(o.live, step) === 'stopped' ? 'stopped' : 'finished', step.since, step.dur].filter(Boolean).map(esc).join(' · ');
    const fixing = S.MonitorGroup({ label: 'Fixing' }) + step.fixing.map((c) => monCheck(st, it, c, `${scope}:fx`, { openLogs: true })).join('');
    const box1 = S.MonitorBox({ icon: 'wrench', title: `Repair ${step.repair}`, sub: when, end: [outcome], body: [monAgentLine(step, live), fixing] });
    const fileRows = step.prOnly ? S.BoxNote({ text: 'No repository file changed. The repair edited the PR title.' })
      : step.files.length ? step.files.map((f) => S.ChangedFileRow(f)).join('')
      : S.BoxNote({ text: step.outcome === 'unresolved' ? 'No file changed. The agent found no repository fix for this failure.' : 'No file changed yet.' });
    const box2 = S.MonitorBox({ icon: 'pencil', title: step.prOnly ? 'Pull request' : 'Files changed',
      sub: step.prOnly ? 'edited with gh pr edit' : step.files.length ? `${step.files.length} files · ${S.Counts({ ins: ins, del: del })} · from the edit journal` : 'none',
      end: [step.files.length ? S.LinkButton({ label: 'Open diff', act: 'toast', arg: 'Opened the repair diff' }) : '', step.worktree ? S.LinkButton({ label: 'Worktree', act: 'toast', arg: `Opened ${step.worktree}` }) : ''], body: fileRows });
    const box3 = step.gates.length ? S.MonitorBox({ icon: 'check', title: 'Local checks', sub: 'build and validation gates in the worktree', end: live ? [] : ['<span>CI decides; these only allow the push</span>'],
      body: [monGates(step.gates), step.next_ && live ? S.NextLine({ html: md(step.next_) }) : ''] }) : '';
    return box1 + box2 + box3;
  }
  /* A merge repair: the base and its tip, resolved over conflicted files, the agent, each conflicted file with its hunks and
   * both sides, the files git merged cleanly (folded), then the local gates. */
  function monConflictPanel(st, it, step, scope, o) {
    const live = monState(o.live, step) === 'now';
    const done = step.files.filter((f) => f.state === 'resolved').length;
    const sum = S.MonitorSummary([
      S.MonitorLine({ icon: 'branch', html: `Merging ${S.Chip({ mono: true, label: `${step.base}@${step.baseSha}` })} into <span class="mono">${esc(it.branch || '')}</span>`, end: `${step.ahead} commits on ${esc(step.base)}` }),
      S.ResolveBar({ done, total: step.files.length }),
      S.CheckTally({ items: [{ n: done, word: 'resolved', color: 'var(--st-done)' }, step.files.length - done ? { n: step.files.length - done, word: 'conflicted', color: 'var(--st-blocked)' } : null, { n: step.auto.length, word: 'merged cleanly', color: 'var(--fg-faint)' }].filter(Boolean) }),
    ]);
    const a = monAgent(step.agent);
    const files = step.files.map((f) => S.ConflictFileRow({ path: f.path, state: f.state, live: live && f.live, hunks: f.hunks, binary: f.binary, liveText: `${step.agent} is editing this file`,
      sides: f.ours ? S.SideBySide({ items: [['THIS PR', md(f.ours)], [step.base.toUpperCase(), md(f.theirs)]] }) : '' })).join('');
    const auto = monFoldGroup(st, it, `${scope}:auto`, 'Merged cleanly', step.auto.map((p) => p.split('/').pop()), () => step.auto.map((p) => S.ChangedFileRow({ path: p, stat: false, icon: 'file' })).join(''), false);
    const box1 = S.MonitorBox({ icon: 'merge', title: `Repair ${step.repair} · merge ${step.base}`, sub: [live ? 'running' : 'finished', step.since, step.dur].map(esc).join(' · '),
      end: [step.outcome ? S.MonBadge({ text: 'Repair outcome: fixed', tone: 'ok' }) : ''],
      body: [sum, S.RepairAgent({ initials: a.initials, color: a.color, html: step.summary ? md(step.summary) : live ? `${S.LiveText(step.signal)} <span class="t-faint">· ${esc(step.ago)}</span>` : esc(step.agent), sub: `${step.agent} · ${step.model}`, action: live ? S.Button({ label: 'Watch agent', act: 'toast', arg: 'Opened the agent session' }) : '' }),
        S.MonitorGroup({ label: 'Conflicted files' }), files, auto] });
    const box2 = S.MonitorBox({ icon: 'check', title: 'Local checks', sub: 'run once every conflict is resolved', body: [monGates(step.gates), step.next_ && live ? S.NextLine({ html: md(step.next_) }) : ''] });
    return box1 + box2;
  }
  /* commit_push: staged, the commit (sha, message, one or two parents), the guarded push, the acknowledged head; steps that
   * have not happened wait dashed. The commit's files follow. A PR-only repair shows the field before and after, the
   * unchanged head and the grace window. */
  function monPublishPanel(st, it, step, scope, o) {
    const live = monState(o.live, step) === 'now';
    if (step.kind === 'prEdit') {
      return S.MonitorBox({ icon: 'tag', title: `Publish repair ${step.repair} · PR edit`, sub: ['done', step.since, step.dur].map(esc).join(' · '), end: ['<span>no commit, no push</span>'],
        body: S.PublishSteps({ steps: [
          { state: 'done', title: `Edited the PR ${step.field}`, when: step.since, html: S.SideBySide({ flush: true, items: [['BEFORE', esc(step.from)], ['AFTER', esc(step.to)]] }) },
          { state: 'done', title: 'Head unchanged', html: `PR head stays ${S.Chip({ mono: true, label: step.head })}. Nothing to push.` },
          { state: o.live ? 'now' : 'done', title: 'Grace window', when: `until ${step.grace}`, html: `Failures count again once a check runs after the edit, or at ${esc(step.grace)}.` },
        ] }) });
    }
    const steps = step.steps.map((x) => Object.assign({}, x, { state: x.state === 'now' && !o.live ? 'wait' : x.state, html: x.html ? md(x.html) : null,
      commit: x.commit ? Object.assign({}, x.commit, { act: 'copy', arg: x.commit.sha }) : null }));
    const box1 = S.MonitorBox({ icon: 'upload', title: `Publish repair ${step.repair}`, sub: [live ? 'running' : 'done', step.since, step.dur].filter(Boolean).map(esc).join(' · '), end: ['<span>the runtime commits and pushes, not the agent</span>'], body: S.PublishSteps({ steps }) });
    const box2 = step.files.length ? S.MonitorBox({ icon: 'file', title: 'In this commit', sub: `${step.files.length} files`, end: [S.LinkButton({ label: 'Commit on GitHub', icon: 'external', act: 'toast', arg: 'Opened the commit on GitHub' })], body: step.files.map((f) => S.ChangedFileRow(f)).join('') }) : '';
    return box1 + box2;
  }
  function monStepPanel(st, it, step, scope, o) {
    if (step.kind === 'ci') return monCiBoard(st, it, step, scope, o);
    if (step.kind === 'fix') return monFixPanel(st, it, step, scope, o);
    if (step.kind === 'conflict') return monConflictPanel(st, it, step, scope, o);
    return monPublishPanel(st, it, step, scope, o);
  }
  const monSelKey = (st, it) => st.monSel[it.id] || it.monRun.sel;
  function monitorHeader(st, it) {
    const r = repoOf(it.repo), run = it.monRun;
    const life = { active: ['Active', 'active'], blocked: ['Blocked', 'blocked'], terminal: ['Finished', 'neutral'] }[it.lifecycle] || [LIFE[it.lifecycle][0], 'faint'];
    const ro = it.refreshFailed ? {} : (it.runOptions || {});
    const actions = [it.lifecycle === 'active' ? S.Button({ label: 'Cancel', icon: 'x', variant: 'danger', act: 'cancel', arg: it.id, disabled: !ro.canCancel }) : '',
      S.Button({ label: 'Open PR', icon: 'pr', trail: 'external', act: 'toast', arg: `Opened github.com/${run.pr.slug}/pull/${run.pr.num}` })];
    return S.VerifyHeader({ status: { word: life[0], tone: life[1] }, key: 'monitor', kind: 'phase',
      pills: it.lifecycle === 'active' ? [it.refreshFailed ? { word: 'stale', tone: 'faint' } : { word: 'fresh', tone: 'active' }] : [],
      html: `<span class="mono">#${run.pr.num}</span>${esc(it.title)}`,
      meta: [{ icon: 'folder', text: r.path, act: 'toast', arg: `Opened ${r.path}` }, { icon: 'branch', html: `<span class="mono">${esc(it.branch)}</span> <span class="t-faint">→</span> <span class="mono">${esc(run.pr.base)}</span>` }],
      actions });
  }
  const MON_LIFE_HERO = { paused: ['Paused', 'blocked', 'pause'], failed: ['Failed', 'failed', 'x'], canceled: ['Canceled', 'faint', 'x'], idle: ['Pending', 'faint', 'pause'] };
  function monitorCard(st, it) {
    const run = it.monRun, live = it.lifecycle === 'active';
    const h = run.hero && (MON_LIFE_HERO[it.lifecycle] ? null : run.hero);
    const fallback = MON_LIFE_HERO[it.lifecycle] || ['Stopped', 'faint', 'pause'];
    const last = run.cycles[run.cycles.length - 1].ci, cs = last.checks;
    const left = [['fail', 'failed'], ['pending', monState(live, last) === 'now' ? 'running' : 'unfinished'], ['queued', 'queued']].map(([b, w]) => (monCount(cs, b) ? `${monCount(cs, b)} ${w}` : '')).filter(Boolean).join(' · ');
    const pushed = run.cycles.filter((c) => c.pub).length, full = run.cap >= 3;
    const prState = run.prState || 'open';
    const ro = it.refreshFailed ? {} : (it.runOptions || {});
    const relaunch = !h && ro.canLaunch ? S.OutcomeActions([S.Button({ label: ro.launchLabel || 'Monitor again', icon: 'refresh', variant: 'primary', act: 'launch', arg: it.id })]) : '';
    const acts = relaunch || h && h.acts ? relaunch || S.OutcomeActions(h.acts.map(([label, icon, ext, primary]) => S.Button({ label, icon, trail: ext ? 'external' : null, variant: primary ? 'primary' : null, act: primary ? 'launch' : 'toast', arg: primary ? it.id : label }))) : '';
    return S.RunOutcome({ tone: h ? h.tone : fallback[1], icon: h ? h.icon : fallback[2], live: !!(h && h.live && live), aria: 'Monitor outcome',
      label: `<b>${esc(h ? h.word : fallback[0])}</b><span>· ${run.step && it.lifecycle !== 'terminal' ? `<span class="mono">${esc(it.lifecycle === 'active' || h ? run.step : 'monitor')}</span>` : 'finished'}</span>`,
      head: md(h ? h.head : (it.reason || it.summary || '')), text: h ? md(h.text) : '', signal: h && live ? h.signal : null, extra: acts,
      when: { value: live ? S.Clock({ ms: it.goalMs, live: it.live, t0: D.T0, secs: true }) : esc(valDur(it.goalMs)), note: live ? 'active' : 'total' },
      cells: [
        { key: 'Pull request', value: `${S.Icon('pr', 'xs')}#${run.pr.num}`, sub: `${esc(prState)} → ${esc(run.pr.base)}` },
        { key: 'Head', value: `<span class="mono">${esc(run.head)}</span>`, sub: pushed ? `${pushed} ${pushed === 1 ? 'repair' : 'repairs'} published` : 'as opened' },
        { key: 'Checks', value: cs.length ? `${monCount(cs, 'pass')} / ${cs.length}` : '—', sub: !cs.length ? 'none reported yet' : left || 'all passed' },
        { key: 'Repair cap', value: `${S.CapPips({ used: run.cap, cap: 3 })}<span>${run.cap} of 3</span>`, sub: esc(run.capNote || (full ? 'stopped' : 'failed repairs')) },
      ] });
  }
  function monitorDoc(st, it) {
    const run = it.monRun, live = it.lifecycle === 'active';
    const key = monSelKey(st, it);
    const [cn, kind] = key.split('.');
    const cyc = run.cycles.find((c) => `c${c.n}` === cn);
    const step = cyc && cyc[kind];
    const out = [monitorHeader(st, it), monitorCard(st, it)];
    out.push(S.VerifySection({ id: 'sec-mon-cycles', title: 'Cycles', count: run.cycles.length, end: [esc('A failure starts a repair; a published repair starts the next cycle')], body: monitorCycles(it, run, { live, sel: key }) }));
    if (step) {
      const label = { ci: 'CI', fix: 'Repair', pub: 'Publish' }[kind], id = { ci: 'monitor', fix: 'monitor_fix', pub: 'commit_push' }[kind];
      out.push(S.VerifySection({ id: 'sec-mon-step', html: `Cycle ${cyc.n} · ${label} <span class="mono">${id}</span>`, end: monState(live, step) === 'now' ? [S.LiveText('live')] : [], body: monStepPanel(st, it, step, key, { live }) }));
    }
    out.push(S.VerifySection({ id: 'sec-mon-activity', title: 'Activity', count: run.activity.length, body: S.EventLines(run.activity.map(([t, html, tone]) => [t, html, MON_EV[tone]])) }));
    return S.MonitorDoc(out);
  }
  /* Goal finish cards: the cycles grid in compact form (a cell opens the monitor or monitor_fix tab) and the step panels
   * without the CI box head. The goal's run is it.finish.monitor ({ cycles, cap }). */
  function monPeekBlock(st, it, b) {
    const live = it.lifecycle === 'active';
    const run = { cycles: b.cycles, pr: D.MON.PR, capped: false };
    if (b.kind === 'monCycles') return monitorCycles(it, run, { live, compact: true, sel: null, act: 'peek-pill', arg: (kind) => `${it.id}|finish|${kind === 'ci' ? 'monitor' : 'monitor_fix'}` });
    if (b.kind === 'ciBoard') return monCiBoard(st, it, b.step, `finish:${b.step.head}`, { live, bare: true });
    return monStepPanel(st, it, b.step, `finish:${b.kind}:${b.step.repair || ''}`, { live });
  }

  /* ---------- Plan run (plan run design; desktop feature/detail PlanDocument, PlanStatus, PlanNowCard, PlanActivity,
   * PlanSubtasks, PlanOutput, PlanImplementation) ---------- */
  /* A standalone plan reads top down: the stage strip (the only place with stage status and time), one Now block (or the
   * blocked callout), Activity, the Spec bundle with a row per subtask spec, the Transcript and the Briefing. */
  const PLAN_STAGES = [['preplan', 'Reading the repo'], ['plan', 'Splitting the work'], ['plan_subtasks', 'Writing subtask specs']];
  const PLAN_QUIET = 120;
  const isPlanRun = (it) => it.kind === 'phase' && it.phase === 'plan' && !!it.planRun;
  const planRunning = (it) => it.lifecycle === 'active';
  /* The header badge: Planning while it runs, Blocked, or Plan ready; other lifecycles keep their own word. */
  const planBadge = (it) => (it.planRun.blocked ? S.Label({ text: 'Blocked', color: 'var(--st-blocked)', tint: true })
    : it.lifecycle === 'terminal' && it.planRun.stage === 3 ? S.Label({ text: 'Plan ready', color: 'var(--st-done)', tint: true })
    : it.lifecycle === 'active' ? S.Label({ text: 'Planning', color: 'var(--st-planning)', tint: true }) : lifeLabel(it));
  const planReady = (it) => it.lifecycle === 'terminal' && it.planRun.stage === 3;
  const pad2 = (n) => String(n).padStart(2, '0');
  const planAt = (p, off) => { const t = p.start + off; return [Math.floor(t / 3600), Math.floor(t / 60) % 60, t % 60].map(pad2).join(':'); };
  const planName = (path) => path.slice(path.lastIndexOf('/') + 1);
  const planShown = (p, e) => !p.noStream || ['phase', 'write', 'err'].includes(e.kind);
  const planLast = (p) => (p.events.length ? p.events[p.events.length - 1].at : 0);
  const planUnits = (p) => (p.states ? p.subtasks.map(([name, slug], i) => ({ id: i + 1, name, path: `${p.bundle}spec_subtask_${i + 1}_${slug}.md`, state: p.states[i] })) : null);
  /* A clock that ticks while the run is live: `sec` seconds now, counted from the page's start. */
  const planClock = (it, sec) => S.Clock({ ms: sec * 1000, live: planRunning(it), t0: D.T0, secs: true });
  function planSentence(p) {
    const u = planUnits(p);
    if (p.stage === 0) return 'Reading the repository and the intake before it splits the work.';
    if (p.stage === 1) return 'Splitting the work into subtasks. Their specs appear in the bundle once the manifest is saved.';
    const saved = u.filter((t) => t.state === 's').length;
    if (p.stage === 3) return `Plan ready. ${u.length} subtasks, all specs saved.`;
    const writing = u.filter((t) => t.state === 'w').map((t) => t.id);
    const range = writing.length > 2 ? `subtasks ${writing[0]} to ${writing[writing.length - 1]}` : writing.length === 2 ? `subtasks ${writing[0]} and ${writing[1]}` : `subtask ${writing[0]}`;
    return `Writing specs for ${range}. ${saved} of ${u.length} saved.`;
  }
  function planStrip(it) {
    const p = it.planRun;
    return S.StageStrip({ stages: PLAN_STAGES.map(([id, label], i) => {
      const done = i < p.stage, now = i === p.stage;
      const state = done ? 'done' : now && p.blocked ? 'blocked' : now && !planRunning(it) ? 'queued' : now && p.stuck ? 'quiet' : now ? 'now' : 'queued';
      const t = p.times[i];
      return { id, label, state, time: t == null ? '' : now ? planClock(it, t) : esc(S.fmtDur(t * 1000, true)), act: 'plan-stage', arg: `${it.id}|${id}`, tip: `Show ${label} in Activity` };
    }) });
  }
  function planNow(it) {
    const p = it.planRun;
    if (!planRunning(it) && !['terminal', 'blocked'].includes(it.lifecycle)) return S.PlanNow({ tone: 'plain', sentence: `${LIFE[it.lifecycle][0]}. ${it.reason || it.summary || ''}`, pulse: [{ html: `Total <b>${esc(S.fmtDur(p.elapsed * 1000, true))}</b>` }] });
    if (p.blocked) {
      const ids = p.blocked.ids;
      return S.Callout({ tone: 'warn', icon: 'warn', tinted: true, title: `Subtask ${ids.join(', ')} needs your decision`,
        body: md(`Planning stopped at subtask ${ids.join(', ')}. ${p.blocked.reason}`),
        actions: [S.Button({ label: `Retry subtask ${ids[0]}`, size: 'sm', act: 'toast', arg: `Reruns the spec writer for subtask ${ids[0]}` }), S.Button({ label: 'Open incident', size: 'sm', act: 'toast', arg: 'Opens the incident with recovery options' })] });
    }
    if (p.stage === 3) return S.PlanNow({ tone: 'plain', sentence: planSentence(p), pulse: [{ html: `Total <b>${esc(S.fmtDur(p.elapsed * 1000, true))}</b>` }, { html: `finished ${esc(p.finished || '')}` }] });
    const ev = p.events.filter((e) => planShown(p, e) && e.kind !== 'phase');
    const last = ev[ev.length - 1];
    const quiet = p.elapsed - planLast(p);
    const sub = (e) => (e.sub ? `<span class="t-faint">#${e.sub} ·</span> ` : '');
    let step, icon;
    if (p.stuck && last) { icon = 'clock'; step = `Last step ${sub(last)}${last.kind === 'note' ? esc(last.text) : `${esc(last.verb.toLowerCase())} <code>${esc(planName(last.text))}</code>`} <span class="t-faint">at ${planAt(p, last.at)}</span>`; }
    else if (p.noStream || !last) { icon = 'clock'; step = 'This agent does not stream its steps. Saved files appear as they land.'; }
    else {
      icon = { read: 'fileText', write: 'pencil', search: 'search', cmd: 'terminal', note: 'message', mcp: 'link', err: 'warn' }[last.kind] || 'bot';
      const what = last.kind === 'note' ? esc(last.text) : `${esc(last.verb)} <code>${esc(last.kind === 'write' || last.kind === 'read' ? planName(last.text) : last.text)}</code>`;
      step = sub(last) + what;
    }
    const beat = p.stuck
      ? { beat: true, quiet: true, html: `No output for ${planClock(it, quiet)}` }
      : { beat: true, html: `Last output ${planClock(it, quiet)} ago` };
    return S.PlanNow({ tone: p.stuck ? 'quiet' : 'live', sentence: planSentence(p), icon, step, pulse: [beat, { html: `Elapsed <b>${planClock(it, p.elapsed)}</b>` }, { html: esc(p.agents[Math.min(p.stage, 2)]) }] });
  }
  const PLAN_FILTER = { files: ['read', 'write', 'search'], cmds: ['cmd', 'mcp'], notes: ['note'] };
  function planSummary(list) {
    const c = (k) => list.filter((e) => e.kind === k).reduce((n, e) => n + 1 + (e.kids ? e.kids.length : 0), 0);
    return [c('read') && `${c('read')} read`, c('search') && `${c('search')} searched`, c('write') && `${c('write')} written`, (c('cmd') + c('mcp')) && `${c('cmd') + c('mcp')} commands`].filter(Boolean).join(' · ');
  }
  function planActivity(st, it) {
    const p = it.planRun;
    const all = p.events.filter((e) => planShown(p, e));
    const filter = st.planFilter[it.id] || 'all';
    const follow = st.planFollow[it.id] !== false;
    const count = (k) => all.filter((e) => PLAN_FILTER[k].includes(e.kind)).length;
    const lastAt = planLast(p);
    const groups = PLAN_STAGES.slice(0, Math.min(p.stage, 2) + 1).map(([id, label], i) => {
      const list = all.filter((e) => e.stage === i);
      const shut = st.planShut.has(`${it.id}|${id}`);
      const rows = list.filter((e) => filter === 'all' || PLAN_FILTER[filter].includes(e.kind)).map((e) => {
        const brief = e.kind === 'phase' && e.verb === 'Phase started' && p.briefs[i].size;
        return { time: planAt(p, e.at), kind: e.kind, unit: e.sub, verb: e.verb, text: e.text, kids: e.kids, dur: e.dur, live: planRunning(it) && e.at === lastAt,
          file: e.kind === 'write' ? { label: planName(e.text), act: 'open-file', arg: e.text } : null,
          link: brief ? { label: 'briefing', act: 'plan-brief', arg: `${it.id}|${id}` } : null };
      });
      return { id, label, anchor: `plan-anc-${it.id}-${id}`, shut, act: 'plan-group', arg: `${it.id}|${id}`, sum: p.noStream ? '' : planSummary(list), rows };
    });
    const tail = planRunning(it) && !p.stuck ? { text: 'Agent is working' } : null;
    const open = !isCollapsed(st, 'plan-activity');
    return secBlock(st, 'plan-activity', { id: 'sec-plan-activity', title: 'Activity', count: `${all.length} events`,
      end: open ? [S.FeedFilter({ act: 'plan-filter', value: filter, items: [{ id: 'all', arg: `${it.id}|all`, label: 'All' }, { id: 'files', arg: `${it.id}|files`, label: 'Files', count: count('files') }, { id: 'cmds', arg: `${it.id}|cmds`, label: 'Commands', count: count('cmds') }, { id: 'notes', arg: `${it.id}|notes`, label: 'Notes', count: count('notes') }], follow: { on: follow, act: 'plan-follow', arg: it.id } })] : null,
      body: S.PlanFeed({ id: it.id, groups, tail, follow, jump: 'plan-jump' }) });
  }
  function planBundle(st, it) {
    const p = it.planRun;
    const wrote = (name) => p.events.some((e) => e.kind === 'write' && planName(e.text) === name);
    const files = ['spec.md', 'decomposition-manifest.yaml'].map((name) => (wrote(name)
      ? { state: 'done', file: true, title: name, side: S.Icon('external', 'xs'), act: 'open-file', arg: p.bundle + name }
      : { state: 'queued', file: true, title: name, side: 'not yet' }));
    const u = planUnits(p);
    const running = planRunning(it);
    const units = u && u.map((t) => {
      const mine = p.events.filter((e) => e.sub === t.id);
      const shown = mine.filter((e) => planShown(p, e));
      const last = shown[shown.length - 1];
      if (t.state === 's') {
        const saved = mine.find((e) => e.kind === 'write');
        return { state: 'done', num: t.id, title: t.name, side: `<span>${esc(S.fmtDur((saved.at - mine[0].at) * 1000, true))}</span>${S.Icon('external', 'xs')}`, act: 'open-file', arg: t.path, tip: `Open ${planName(t.path)}` };
      }
      if (t.state === 'b') return { state: 'blocked', num: t.id, title: t.name, side: 'Blocked', line: esc(p.blocked.unit), why: true };
      if (t.state === 'w') {
        const quiet = p.elapsed - (last ? last.at : p.elapsed);
        const stuck = p.stuck && quiet > PLAN_QUIET;
        const startAt = p.events.find((e) => e.stage === 2 && e.kind === 'phase').at;
        return { state: stuck ? 'quiet' : 'now', num: t.id, title: t.name,
          side: stuck ? `<span>quiet ${planClock(it, quiet)}</span>` : `<span>Writing · ${planClock(it, p.elapsed - startAt)}</span>`,
          line: last && !p.noStream ? esc(last.kind === 'note' ? last.text : `${last.verb} ${last.text}`) : '' };
      }
      return { state: 'queued', num: t.id, title: t.name, side: running ? 'Queued' : 'Not started' };
    });
    const saved = u ? u.filter((t) => t.state === 's').length : 0;
    const count = u ? `${saved}/${u.length} subtask specs saved` : `${['spec.md', 'decomposition-manifest.yaml'].filter(wrote).length} of 2 files`;
    return secBlock(st, 'plan-bundle', { title: 'Spec bundle', count, body: S.PlanBundle({ files, units, head: `Subtask specs${p.wave ? ' · ' + p.wave : ''}`, empty: 'Subtask specs appear here when the work is split.' }) });
  }
  /* The raw transcript: the agent's stream as tool calls, or the plain output of an agent without a structured stream. */
  function planTranscriptLines(p) {
    if (p.output) return p.output.filter(([off]) => off <= p.elapsed).map(([off, text]) => [planAt(p, off), text]);
    return p.events.map((e) => {
      const pre = e.sub ? `[subtask ${e.sub}] ` : '';
      const t = planAt(p, e.at);
      if (e.kind === 'phase') return [t, `── ${PLAN_STAGES[e.stage][0]}: ${e.verb.toLowerCase()}${e.text ? ` (${e.text})` : ''}`];
      if (e.kind === 'note') return [t, pre + e.text];
      if (e.kind === 'write') return [t, `${pre}Write(${e.text})`];
      if (e.kind === 'read') return [t, `${pre}Read(${e.text})`];
      if (e.kind === 'search') return [t, `${pre}Grep(${e.text.split(' · ')[0]})`];
      if (e.kind === 'cmd') return [t, `${pre}Bash(${e.text})`];
      if (e.kind === 'mcp') return [t, `${pre}mcp__skill-bill__${e.text.split(' · ')[0]}`];
      return [t, `${pre}${e.verb}: ${e.text}`];
    });
  }
  function planTranscript(st, it) {
    const lines = planTranscriptLines(it.planRun);
    const open = !isCollapsed(st, 'plan-transcript');
    const copy = lines.map(([t, l]) => `${t}  ${l}`).join('\n');
    return secBlock(st, 'plan-transcript', { title: 'Transcript', count: `${lines.length} lines`,
      end: open ? [S.FeedFilter({ tools: [{ icon: 'copy', label: 'Copy', act: 'copy', arg: copy }] })] : null,
      body: S.Transcript({ lines, size: st.planTxLong.has(it.id) ? 'long' : 'short', act: 'plan-tx', arg: it.id, scroll: `plan-tx-${it.id}` }) });
  }
  /* A saved briefing is one stage's launch document: run invariants (layer 1), upstream projections (layer 2), derived
   * context (layer 3). Run setup shows once because it is the same in every briefing; the preplan digest reads as a document
   * folded by its own headings; the raw briefings stay one click away. All of it comes from stages[].briefing_text. */
  function planRawBriefing(p, stage) {
    const r = p.run;
    const lines = ['# Feature-task-runtime phase briefing', `phase: ${stage}`, '', '## Run invariants (layer 1, unconditional)', `spec_reference: ${p.bundle}spec.md`, `feature_size: ${r.size}`, 'ceremony_scaling:', ...r.ceremony.map((c) => `  ${c.replace(' ', ': ').replace(/ /g, '_')}`), 'acceptance_criteria:', ...r.acs.map((a, i) => `  AC-00${i + 1}. ${a}`), 'mandates_and_overrides:', ...(r.mandates.length ? r.mandates.map((m) => `  - ${m}`) : ['  (none)']), ''];
    if (stage !== 'preplan' && p.digest.length) {
      lines.push('## Upstream projections (layer 2, declared and validated)', '### from: preplan', 'value:', '```', `PREPLAN DIGEST ${p.bundle.split('/')[1]}.`);
      p.digest.forEach((d) => { lines.push('', d.head.toUpperCase(), ...d.items.map((x) => '- ' + x.replace(/`/g, ''))); });
      lines.push('```', '### run_invariant_spec_reference …', '(repeats layer 1)', '');
    }
    lines.push('## Derived context (layer 3, declared)', '(none)');
    return lines;
  }
  const planInline = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>');
  function planTeaser(it) {
    const p = it.planRun;
    const reached = Math.min(p.stage, 2);
    /* Bars share one scale: the largest saved briefing, and at least 20 KB, so an early preplan bar stays short. */
    const max = Math.max(20, ...p.briefs.map((b) => b.size || 0));
    const setup = p.briefs[0].size || 0;
    const flow = p.briefs.map((b, i) => {
      if (i > reached || !b.size) return { label: b.stage, none: i <= reached ? 'not saved' : '—' };
      return { label: b.stage, size: `${b.size.toFixed(1)} KB`, setup: setup / max, digest: Math.max(0, b.size - setup) / max };
    });
    const tag = (t) => p.digest.find((d) => d.tag === t);
    const facts = p.stage < 1 || !p.digest.length
      ? [{ icon: 'clock', html: 'Preplan is reading the repo. Its digest lands here and becomes most of what the plan stage reads.' }]
      : [tag('code') && { icon: 'fileText', tone: 'code', key: 'Verified by reading:', html: planInline(tag('code').items[0]) }, tag('risk') && { icon: 'warn', tone: 'risk', key: 'Risk:', html: planInline(tag('risk').items[0]) }, tag('unread') && { icon: 'search', key: 'Not read:', html: planInline(tag('unread').preview) }].filter(Boolean);
    const pills = [p.run.size, `${p.run.acs.length} criteria`, ...p.run.ceremony, p.run.mandates.length ? counted(p.run.mandates.length, 'mandate') : 'no mandates'].map((text, i) => ({ text, strong: i < 2 }));
    return S.BriefingTeaser({ flow, legend: [{ text: 'run setup', cls: 'setup' }, ...(p.stage >= 1 ? [{ text: 'preplan digest', cls: 'dig' }] : [])], facts, pills, act: 'sec-collapse', arg: 'plan-briefing|1', cta: 'Read what each stage was told' });
  }
  function planBriefing(st, it) {
    const p = it.planRun;
    const reached = Math.min(p.stage, 2);
    const fold = (k) => st.planOpen.has(`${it.id}:${k}`);
    const r = p.run;
    const setup = S.FactList({ rows: [
      { label: 'Spec', file: { label: 'spec.md', act: 'open-file', arg: p.bundle + 'spec.md' } },
      { label: 'Size', html: esc(r.size) },
      { label: 'Ceremony', html: r.ceremony.map(esc).join(' · ') },
      { label: 'Mandates', html: r.mandates.length ? r.mandates.map(esc).join(', ') : 'none', faint: !r.mandates.length },
      { label: 'Criteria', list: r.acs.map((a, i) => [`AC-00${i + 1}`, a]) },
    ] });
    const digest = p.stage < 1 || !p.digest.length
      ? S.BriefBody('The preplan digest appears here when preplan finishes. It is what the plan stage reads first.')
      : p.digest.map((d, i) => S.BriefFold({ label: d.head, preview: d.preview, open: fold('digest-' + i), act: 'plan-fold', arg: `${it.id}:digest-${i}`, body: S.BriefBody(d.items.map(planInline)) })).join('');
    const raws = p.briefs.map((b, i) => {
      if (i > reached || !b.size) return S.BriefFold({ off: true, mono: true, label: b.stage, preview: i <= reached ? b.layers : 'not started' });
      return S.BriefFold({ id: `plan-brief-${it.id}-${b.stage}`, mono: true, label: b.stage, preview: b.layers, meta: `${b.size.toFixed(1)} KB`, open: fold('raw-' + b.stage), act: 'plan-fold', arg: `${it.id}:raw-${b.stage}`,
        body: S.Transcript({ size: 'raw', lines: planRawBriefing(p, b.stage).map((l) => [null, l]), note: 'Exactly what the agent received', copy: planRawBriefing(p, b.stage).join('\n') }) });
    }).join('');
    const count = `${p.briefs.filter((b, i) => b.size && i <= reached).length} saved`;
    return secBlock(st, 'plan-briefing', { id: 'sec-plan-briefing', title: 'Briefing', count, peek: planTeaser(it),
      body: S.Box([S.BoxHead('Run setup · same in every stage'), setup, S.BoxHead('Handed to plan · preplan digest'), digest, S.BoxHead('Raw briefings'), raws]) }, true);
  }
  function planParts(st, it) {
    return [planStrip(it), planNow(it), planActivity(st, it), planBundle(st, it), planTranscript(st, it), planBriefing(st, it)];
  }

  /* ---------- Verify run (Verify run design; desktop feature/detail VerifyDocument, VerifyHeader, VerifyOutcomeCard,
   * VerifyCriteriaSection, VerifyStepsTimeline, VerifyReceiptBody, VerifyFixSelection, VerifyFixControls) ---------- */
  /* A Verify run reads top down: what the change does, the outcome card (verdict and four counts that jump to their
   * evidence), the acceptance criteria it grades, the steps with their receipts, Activity, and a selection bar for fixes. */
  const VSTEP = { collect_inputs: 'Collect inputs', extract_criteria: 'Extract criteria', gather_diff: 'Gather diff', feature_flag_audit: 'Feature flag audit', code_review: 'Code review', unit_test_value_check: 'Unit test value', completeness_audit: 'Completeness audit', qa: 'QA', verdict: 'Verdict' };
  const V_STATUS = { pending: ['Pending', 'faint'], running: ['Running', 'active'], parked: ['Awaiting you', 'blocked'], superseded: ['Superseded', 'faint'], stopped: ['Stopped', 'failed'], finished: ['Finished', 'done'] };
  const V_TONE = { 'REQUEST CHANGES': 'failed', 'APPROVE WITH FIXES': 'blocked', APPROVE: 'done' };
  const V_GRADE = { PASS: ['PASS', 'done', 'checkCircle'], PARTIAL: ['PARTIAL', 'blocked', 'alert'], FAIL: ['FAIL', 'failed', 'x'] };
  const V_SEV = { Blocker: 'failed', Major: 'failed', Minor: 'blocked', Nit: 'faint' };
  const T_TONE = { Valuable: 'done', Weak: 'blocked', Harmful: 'failed' };
  const shortRev = (s) => String(s || '?').slice(0, 7);
  const vRange = (r) => `${shortRev(r.base)}..${shortRev(r.head)}`;
  const fmtStamp = (iso) => (iso ? new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'UTC' }) : '');
  /* A run's length to the nearest second: "4m 53s", or hours and minutes past an hour ("14h 1m"). */
  const vDur = (a, b) => {
    if (!a || !b) return '';
    const sec = Math.round((new Date(b) - new Date(a)) / 1000);
    return sec >= 3600 ? `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m` : `${Math.floor(sec / 60)}m ${String(sec % 60).padStart(2, '0')}s`;
  };
  function verifyState(r) {
    if (r.supersededBy) return 'superseded';
    if (r.status === 'running') return 'running';
    if (r.status === 'pending') return r.current === 'extract_criteria' ? 'parked' : 'pending';
    if (r.status === 'abandoned' || r.status === 'failed') return 'stopped';
    return 'finished';
  }
  /* Intake as the header shows it: a link's host and issue key, a spec file's path, or "Free-text intake". */
  function intakeLabel(intake) {
    if (!intake) return 'Free-text intake';
    if (intake.startsWith('spec:')) return intake.slice(5);
    const m = intake.match(/^https?:\/\/(?:www\.)?([^/?#]+)([^?#]*)/i);
    if (!m) return 'Free-text intake';
    const key = (m[2].match(/\/([A-Z0-9]+-\d+)(?=$|[/_-])/) || [])[1];
    return [m[1], key].filter(Boolean).join(' · ');
  }
  const freeText = (intake) => !!intake && !/^(spec:|https?:\/\/)/i.test(intake);
  const auditCounts = (r) => {
    const c = { PASS: 0, PARTIAL: 0, FAIL: 0, UNGRADED: 0 };
    r.criteria.forEach((cr) => { const g = r.audit && r.audit.grades.find((g) => g.n === cr.n); c[g && g.status || 'UNGRADED']++; });
    return c;
  };
  /* Anything with a gap can be selected for fixing: review findings, Partial and Missing criteria, test rows that are not
   * Keep, open QA findings. A Missing criterion defaults to a goal (new work needs a plan, review and QA); the rest to chat. */
  function fixItems(r) {
    const out = [];
    (r.review ? r.review.findings : []).forEach((f) => out.push({ key: 'f:' + f.ref, label: f.ref, kind: 'finding', route: 'chat', locs: f.locations }));
    (r.audit ? r.audit.grades.slice().sort((a, b) => a.n - b.n) : []).filter((g) => g.status !== 'PASS').forEach((g) => out.push({ key: 'c:' + g.n, label: '#' + g.n, kind: 'criterion', route: g.status === 'FAIL' ? 'goal' : 'chat', locs: g.locations, grade: g }));
    ((r.unitTestValue || {}).rows || []).forEach((t, i) => { if (!/^keep\.?$/i.test(t.action.trim())) out.push({ key: 't:' + i, label: t.area, kind: 'test', route: 'chat', locs: t.locations }); });
    ((r.qa || {}).findings || []).forEach((f) => out.push({ key: 'qa:' + f.id, label: f.id, kind: 'qa', route: 'chat', locs: f.locations }));
    return out;
  }
  function verifyCtx(st, it) {
    const r = it.verifyRun, state = verifyState(r);
    const stop = state === 'superseded' ? r.steps.find((s) => s.id === 'extract_criteria')
      : state === 'stopped' ? r.steps.find((s) => s.status === 'running' || s.status === 'failed' || (s.status === 'pending' && s.attempts > 0)) : null;
    const marks = st.vMarks[it.id] || {};
    const fix = fixItems(r);
    /* Selection happens on a finished or stopped run only; a marked criterion leaves it. */
    const raw = st.vSel[it.id] || {};
    const sel = {};
    Object.keys(raw).forEach((k) => { if (!marks[k] && fix.some((x) => x.key === k)) sel[k] = raw[k]; });
    return { r, state, stop, fix, marks, sel, pick: state === 'finished' || state === 'stopped' };
  }
  /* "Same code as": another fixable item names the same file and line. Items marked Not in this repo or Spec question are
   * out of fixes, so they never count as the other item. */
  function sharedHint(ctx, key) {
    if (ctx.marks[key]) return null;
    const me = ctx.fix.find((x) => x.key === key);
    if (!me) return null;
    for (const loc of me.locs) {
      const others = ctx.fix.filter((x) => x.key !== key && !ctx.marks[x.key] && x.locs.includes(loc));
      if (others.length) return `Same code as ${esc(others.map((o) => o.label).join(', '))} · <code>${esc(loc)}</code>`;
    }
    return null;
  }
  /* The checkbox, the Chat or Goal switch once selected, and the shared-code hint for one fixable item. */
  function selCtl(it, ctx, key) {
    const x = ctx.fix.find((y) => y.key === key);
    if (!ctx.pick || !x) return {};
    const on = key in ctx.sel;
    return {
      lead: ctx.marks[key] ? S.Checkbox({ disabled: true, marked: true, label: `${x.label} is marked`, tip: 'Marked, so it is left out of fixes' }) : S.Checkbox({ on, act: 'vf-sel', arg: `${it.id}|${key}`, label: `Select ${x.label}` }),
      end: on ? S.RouteSwitch({ act: 'vf-route', value: `${it.id}|${key}|${ctx.sel[key]}`, items: [{ id: `${it.id}|${key}|chat`, label: 'Chat', tip: 'Fix in agent chat on the verified branch' }, { id: `${it.id}|${key}|goal`, label: 'Goal', tip: 'Start a goal based on the verified branch' }] }) : '',
      hint: sharedHint(ctx, key),
    };
  }
  function selAll(it, ctx, kind, o) {
    if (!ctx.pick) return '';
    const keys = ctx.fix.filter((x) => x.kind === kind && !ctx.marks[x.key]).map((x) => x.key);
    if (!keys.length) return '';
    const n = keys.filter((k) => k in ctx.sel).length;
    return S.SelectAll(Object.assign({ state: n === 0 ? 'off' : n === keys.length ? 'on' : 'mixed', count: keys.length, act: 'vf-all', arg: `${it.id}|${kind}` }, o || {}));
  }
  /* Copy report: the verdict, the summary and the verdict step's findings. */
  function verifyReportText(r) {
    const v = r.verdict || {};
    return [v.verdict, v.summary, ...(v.findings || [])].filter(Boolean).join('\n\n');
  }
  const newerRunId = (wf) => { const hit = D.items.find((x) => x.verifyRun && x.verifyRun.workflowId === wf) || D.history.find((h) => h.item && h.item.verifyRun && h.item.verifyRun.workflowId === wf); return hit ? hit.id : null; };
  function verifyActions(it, ctx) {
    const r = ctx.r, b = [];
    const changes = r.changed ? S.Button({ label: 'Open changes', count: r.changed.count, icon: 'diff', act: 'toast', arg: `Opened the Changes window on ${vRange(r)}` }) : '';
    if (ctx.state === 'running') b.push(S.Button({ label: 'Stop', icon: 'stop', act: 'verify-stop', arg: it.id, tip: 'Stops the run. Finished steps keep their results' }), changes);
    else if (ctx.state === 'parked') b.push(S.Button({ label: 'Confirm criteria and run', icon: 'check', variant: 'primary', act: 'verify-confirm', arg: it.id, disabled: !r.criteria.length, tip: r.criteria.length ? null : 'No numbered criteria to audit' }), S.Button({ label: 'Adjust intake', icon: 'pencil', act: 'verify-adjust', arg: it.id }), S.Button({ label: 'Discard', icon: 'x', act: 'verify-discard', arg: it.id }));
    else if (ctx.state === 'superseded') b.push(S.Button({ label: 'Run again', icon: 'play', act: 'verify-again', arg: it.id }));
    else if (ctx.state === 'stopped') b.push(S.Button({ label: `Resume from ${VSTEP[ctx.stop.id] || 'last step'}`, icon: 'rerun', variant: 'primary', act: 'verify-resume', arg: it.id }), S.Button({ label: 'Run again', icon: 'play', act: 'verify-again', arg: it.id }), changes);
    else if (ctx.state === 'finished') b.push(S.Button({ label: 'Copy report', icon: 'copy', act: 'copy', arg: verifyReportText(r) }), changes, S.Button({ label: 'Verify again', icon: 'rerun', act: 'verify-again', arg: it.id, tip: 'Starts a new Verify run on the same target and criteria, after fixes land' }));
    else b.push(S.Button({ label: 'Cancel', icon: 'x', variant: 'danger', act: 'cancel', arg: it.id, disabled: !(it.runOptions || {}).canCancel }));
    return b;
  }
  /* The head: status, key and the Verify pill; the title; repository, branch, compared range and intake; the run controls;
   * then the superseded banner and, for free-text intake, the Intake fold. */
  function verifyHeader(st, it, ctx) {
    const r = ctx.r;
    const url = /^https?:\/\//i.test(r.intake || ''), spec = (r.intake || '').startsWith('spec:');
    const [word, tone] = V_STATUS[ctx.state];
    const after = [];
    if (ctx.state === 'superseded') {
      const newer = newerRunId(r.supersededBy);
      after.push(S.Banner({ title: 'A newer verify run replaced this one.', body: 'Starting verify again for the same repository abandoned this run before you confirmed its criteria.', actions: newer ? [S.Button({ label: 'Open newer run', act: 'open', arg: newer })] : null }));
    }
    if (freeText(r.intake) && ctx.state !== 'parked') after.push(S.Fold({ label: 'Intake', open: st.vOpen.has(`${it.id}:intake`), act: 'v-toggle', arg: `${it.id}:intake`, body: S.Quote({ text: r.intake }) }));
    return S.VerifyHeader({ status: { word, tone }, key: r.issueKey || 'No issue key', title: `Verify ${r.issueKey || 'run'}`,
      meta: [
        { icon: 'folder', text: (r.repoRoot || '').split('/').pop() || 'Unknown repository', mono: true, tip: r.repoRoot, act: 'toast', arg: `Opened ${r.repoRoot}` },
        r.target ? { icon: 'branch', text: r.target, mono: true, act: 'toast', arg: `Opened ${repoOf(it.repo).web}/tree/${r.target}` } : null,
        { icon: 'commit', text: vRange(r), mono: true, tip: 'Compared range' },
        url ? { icon: 'link', text: intakeLabel(r.intake), link: true, act: 'toast', arg: `Opened ${r.intake}` } : { icon: 'file', text: spec ? r.intake.slice(5) : 'Free-text intake' },
      ],
      actions: verifyActions(it, ctx), after });
  }
  function verifySummary(ctx, it) {
    const r = ctx.r;
    if (ctx.state === 'finished') return r.verdict && r.verdict.summary ? S.WorkSummary({ label: 'What this work does', html: md(r.verdict.summary.replace(/\s+/g, ' ').trim()), link: S.TextLink({ label: 'Full summary in Verdict step', act: 'v-jump', arg: `${it.id}|verdict` }) }) : '';
    if (!['running', 'stopped', 'parked'].includes(ctx.state) || !r.criteria.length) return '';
    const first = r.criteria[0];
    return S.WorkSummary({ label: 'What this work is meant to do', note: 'from the extracted criteria', html: md(first.text), oneLine: true, tip: first.text,
      foot: `${r.criteria.length > 1 ? `${r.criteria.length - 1} more criteria below. ` : ''}A summary of the change comes with the verdict${ctx.state === 'parked' ? ', after you confirm' : ''}.` });
  }
  function verifyCells(it, ctx) {
    const r = ctx.r, c = auditCounts(r);
    const count = (list, key) => list.reduce((m, x) => { m[x[key]] = (m[x[key]] || 0) + 1; return m; }, {});
    const sev = count(r.review ? r.review.findings : [], 'severity');
    const tv = r.unitTestValue, tc = count((tv && tv.rows) || [], 'verdict');
    const reviewStep = r.steps.find((s) => s.id === 'code_review') || {};
    const q = r.qa, tag = (t) => (q ? q.details.assertions.filter((a) => a.outcome === t).length : 0);
    const f = r.flagAudit;
    const fg = (t) => `<small class="t-fg">${esc(t)}</small>`;
    const cells = [
      { key: 'Criteria', jump: 'criteria', value: r.audit ? `${c.PASS}<small> / ${r.criteria.length} met</small>` : `${r.criteria.length}<small> extracted</small>`,
        sub: r.audit ? ([c.PARTIAL && `${c.PARTIAL} partial`, c.FAIL && `${c.FAIL} missing`, c.UNGRADED && `${c.UNGRADED} ungraded`].filter(Boolean).join(' · ') || (r.criteria.length ? 'all met' : 'no criteria')) : 'not audited',
        bar: { segs: r.audit ? [{ n: c.PASS, tone: 'done' }, { n: c.PARTIAL, tone: 'blocked' }, { n: c.FAIL, tone: 'failed' }, { n: c.UNGRADED, tone: 'faint' }] : [] } },
      { key: 'Review', jump: 'code_review', value: r.review ? `${r.review.findings.length}<small> findings</small>` : '<small>—</small>',
        sub: r.review ? (['Nit', 'Minor', 'Major', 'Blocker'].filter((k) => sev[k]).map((k) => `${sev[k]} ${k.toLowerCase()}`).join(' · ') || 'clean') : reviewStep.status === 'running' ? '<span class="t-done">running</span>' : 'not run',
        bar: { segs: !r.review ? [] : r.review.findings.length ? [{ n: sev.Nit || 0, tone: 'faint' }, { n: sev.Minor || 0, tone: 'blocked' }, { n: (sev.Major || 0) + (sev.Blocker || 0), tone: 'failed' }] : [{ n: 1, tone: 'done' }] } },
      { key: 'Tests', jump: 'unit_test_value_check', value: tv ? (tv.verdict === 'skipped' ? '<small>skipped</small>' : fg(tv.overall)) : '<small>—</small>',
        sub: tv && tv.rows ? ['Valuable', 'Weak', 'Harmful'].filter((k) => tc[k]).map((k) => `${tc[k]} ${k.toLowerCase()}`).join(' · ') : tv ? 'no tests changed' : 'not run',
        bar: { segs: tv && tv.rows ? [{ n: tc.Valuable || 0, tone: 'done' }, { n: tc.Weak || 0, tone: 'blocked' }, { n: tc.Harmful || 0, tone: 'failed' }] : [] } },
      r.qaSelection === 'on'
        ? { key: 'QA', jump: 'qa', value: q ? (q.findings.length ? `${q.findings.length}<small> open</small>` : fg('passed')) : fg('pending'), sub: q ? `${tag('passed')} of ${q.details.assertions.length} checks passed` : 'not run',
          bar: { segs: q ? [{ n: tag('passed'), tone: 'done' }, { n: tag('not_exercised'), tone: 'blocked' }, { n: tag('failed'), tone: 'failed' }] : [] } }
        : { key: 'Flag audit', jump: 'feature_flag_audit', value: f ? fg(f.verdict) : '<small>—</small>', sub: f && f.flagName ? esc(f.flagName) : f ? 'no flag required' : 'not run',
          bar: { segs: f && f.checks ? [{ n: f.checks.filter((x) => x.passed).length, tone: 'done' }, { n: f.checks.filter((x) => !x.passed).length, tone: 'failed' }] : [] } },
    ];
    return cells.map((x) => Object.assign(x, { act: 'v-jump', arg: `${it.id}|${x.jump}`, tip: `Go to ${x.key === 'Criteria' ? 'the criteria' : x.key}` }));
  }
  function verifyOutcome(it, ctx) {
    const r = ctx.r, n = r.steps.length, at = (s) => r.steps.indexOf(s) + 1;
    if (ctx.state === 'pending') return S.RunOutcome({ aria: 'Verify outcome', tone: 'faint', icon: 'clock', verdict: 'Waiting to start', text: 'Verify has not started collecting inputs.', cells: verifyCells(it, ctx) });
    if (ctx.state === 'running') {
      const s = r.steps.find((x) => x.status === 'running') || r.steps.find((x) => x.id === r.current) || r.steps[0];
      return S.RunOutcome({ aria: 'Verify outcome', tone: 'active', icon: 'play', label: `Step ${at(s)} of ${n}`, verdict: VSTEP[s.id], when: { value: clock(it), note: 'running' }, cells: verifyCells(it, ctx),
        text: s.id === 'code_review' && r.changed ? `Specialist reviewers are reading ${r.changed.count} changed files. Results from earlier steps are below.` : 'Results from earlier steps are below.' });
    }
    if (ctx.state === 'parked' || ctx.state === 'pending') {
      const ok = r.criteria.length > 0;
      return S.RunOutcome({ aria: 'Verify outcome', tone: 'blocked', icon: ok ? 'alert' : 'warn', label: `Step 2 of ${n} · waiting for you`, verdict: ok ? 'Confirm the criteria' : 'No criteria to audit', when: { value: esc(fmtStamp(r.startedAt) || it.started), note: 'parked' },
        text: ok ? `Verify extracted ${r.criteria.length} acceptance criteria. Check them before the review and audit start; the audit grades the code against exactly this list.` : 'Criteria extraction found no numbered acceptance criteria, so a confirmed run would audit nothing. Adjust the intake with the criteria pasted in, or point it at a spec file.' });
    }
    if (ctx.state === 'superseded' || ctx.state === 'stopped') {
      const s = ctx.stop || r.steps[0];
      const done = r.steps.filter((x) => x.status === 'completed' || x.status === 'skipped').length;
      const hours = r.startedAt && r.finishedAt ? Math.floor((new Date(r.finishedAt) - new Date(r.startedAt)) / 3600000) : 0;
      const why = r.staleReason ? `The run made no progress${hours ? ` for ${hours} ${hours === 1 ? 'hour' : 'hours'}` : ''} and was marked stale.` : r.status === 'failed' ? 'The run failed before a verdict was recorded.' : 'You stopped the run.';
      return S.RunOutcome({ aria: 'Verify outcome', tone: ctx.state === 'superseded' ? 'faint' : 'failed', icon: ctx.state === 'superseded' ? 'x' : 'pause', label: `No verdict · stopped at step ${at(s)} of ${n}`, verdict: VSTEP[s.id], plain: true,
        when: { value: esc(fmtStamp(r.finishedAt)), note: r.startedAt ? `${vDur(r.startedAt, r.finishedAt)} after start` : '' }, cells: ctx.state === 'stopped' ? verifyCells(it, ctx) : null,
        text: ctx.state === 'superseded' ? 'Abandoned while the criteria were waiting for confirmation.' : `${why} Steps 1 to ${done} finished and their results are kept below; resuming starts again at ${(VSTEP[s.id] || s.id).toLowerCase()}.` });
    }
    const vd = r.verdict || { verdict: '', findings: [] };
    const text = 'Verification finished. See Steps for findings and evidence.';
    return S.RunOutcome({ aria: 'Verify outcome', tone: V_TONE[vd.verdict] || 'faint', icon: vd.verdict === 'APPROVE' ? 'check' : vd.verdict === 'REQUEST CHANGES' ? 'x' : 'warn', label: 'Verdict', verdict: vd.verdict, text: md(text),
      note: vd.findings.some((f) => f.startsWith('Warning:')) ? 'QA had open findings, so the runtime lowered APPROVE to APPROVE WITH FIXES.' : null,
      when: { value: esc(vDur(r.startedAt, r.finishedAt)), note: fmtStamp(r.finishedAt) }, cells: verifyCells(it, ctx) });
  }
  function verifyCriteria(st, it, ctx) {
    const r = ctx.r;
    if (!r.criteria.length && !r.criteriaRaw) return '';
    if (!r.criteria.length) return S.VerifySection({ id: 'sec-criteria', title: 'Criteria', count: 0, body: S.Quote({ html: md(r.criteriaRaw) }) });
    const c = auditCounts(r), filt = st.vFilter[it.id] || 'all';
    const grid = ctx.pick && !!r.audit;
    const grade = (n) => (r.audit ? r.audit.grades.find((g) => g.n === n) : null);
    const shown = r.criteria.filter((cr) => { const g = grade(cr.n); return !r.audit || filt === 'all' || (filt === 'gaps' ? g && g.status !== 'PASS' : g && g.status === 'PASS'); });
    const rows = shown.map((cr) => {
      const g = grade(cr.n), key = 'c:' + cr.n, gap = !!g && g.status !== 'PASS', mark = ctx.marks[key];
      const badge = mark ? { text: mark === 'repo' ? 'Not in this repo' : 'Spec question', tone: 'faint' } : g ? { text: V_GRADE[g.status][0], tone: V_GRADE[g.status][1] } : { text: ctx.state === 'parked' ? 'To confirm' : r.audit ? 'No grade' : 'Not audited', tone: 'faint' };
      const marks = grid && gap ? S.MarkRow(mark
        ? { text: mark === 'repo' ? 'Left out of fixes: the work lives in another repository.' : 'Left out of fixes until the spec is settled.', actions: [S.Button({ label: 'Clear mark', size: 'sm', act: 'vf-mark', arg: `${it.id}|${key}|none` })] }
        : { text: 'Not a code gap here?', actions: [S.Button({ label: 'Not in this repo', size: 'sm', act: 'vf-mark', arg: `${it.id}|${key}|repo` }), S.Button({ label: 'Spec question', size: 'sm', act: 'vf-mark', arg: `${it.id}|${key}|spec` })] }) : '';
      const body = g ? [
        g.evidence.length ? S.FieldText({ label: 'Evidence', chips: g.evidence.filter((e) => !/\s/.test(e)).map((e) => S.Chip({ mono: true,  label: e, act: 'toast', arg: `Opened ${e} in the Changes window` })), html: g.evidence.filter((e) => /\s/.test(e)).map(S.ReportText).join('') }) : '',
        g.missing ? S.FieldText({ label: "What's missing", html: md(g.missing) }) : '',
        g.notFound ? S.FieldText({ label: 'Not found', html: md(g.notFound) }) : '', marks].join('')
        : `<div class="t-muted">${ctx.state === 'parked' ? 'The audit runs after you confirm.' : r.audit ? 'No grade was recorded for this criterion. See Completeness audit in Steps.' : 'The completeness audit has not run yet.'}</div>`;
      const sel = grid && gap ? selCtl(it, ctx, key) : {};
      return S.CriterionRow({ id: `crit-${cr.n}`, n: cr.n, html: md(cr.text), badge, body, open: st.vOpen.has(`${it.id}:c:${cr.n}`), act: 'v-toggle', arg: `${it.id}:c:${cr.n}`,
        select: grid, lead: sel.lead || '', end: sel.end || '', hint: grid && gap ? sel.hint : null });
    });
    const fold = (k, label, items) => (items.length ? S.VerifyContext({ kind: k, label, preview: items[0].replace(/\s+/g, ' ').trim(), items: items.map(S.ReportText), open: st.vOpen.has(`${it.id}:${k}`), act: 'v-toggle', arg: `${it.id}:${k}` }) : '');
    return S.VerifySection({ id: 'sec-criteria', title: 'Criteria', count: r.criteria.length,
      end: r.audit ? [S.Segmented({ act: 'v-filter', value: `${it.id}|${filt}`, items: [{ id: `${it.id}|all`, label: 'All' }, { id: `${it.id}|gaps`, label: `Gaps ${c.PARTIAL + c.FAIL}` }, { id: `${it.id}|met`, label: `Met ${c.PASS}` }] })] : null,
      sub: r.audit ? (c.UNGRADED ? 'Audit results are incomplete. See Completeness audit in Steps.' : null) : ctx.state === 'parked' ? 'Extracted from the intake. The audit grades the code against this list once you confirm.' : ctx.state === 'superseded' ? 'Extracted but never confirmed. The newer run extracted its own list.' : 'Extracted and confirmed. Grades appear after the completeness audit.',
      body: [S.Box([grid ? selAll(it, ctx, 'criterion', { boxed: true }) : '', rows.length ? rows.join('') : S.CriterionRow({ empty: 'Nothing in this filter.' })]),
        fold('non-goals', 'Non-goals', r.nonGoals, r.nonGoals.length), fold('constraints', 'Technical constraints', r.constraints, r.constraints.length), fold('rollout', 'Rollout', r.rollout ? [r.rollout] : [], null)] });
  }
  function stepNode(ctx, s) {
    const r = ctx.r;
    if (ctx.state === 'running' && s.status === 'running') return 'run';
    if (ctx.state === 'parked' && s.id === 'extract_criteria') return 'wait';
    if (ctx.state === 'stopped' && ctx.stop === s) return 'stop';
    if (ctx.state === 'superseded' && s.id === 'extract_criteria') return 'cut';
    if (s.status === 'skipped') return 'skip';
    if (s.status === 'failed') return 'bad';
    if (s.status !== 'completed') return 'pend';
    if (s.id === 'verdict' && r.verdict && r.verdict.verdict === 'REQUEST CHANGES') return 'bad';
    if (s.id === 'completeness_audit' && auditCounts(r).FAIL) return 'bad';
    if (s.id === 'completeness_audit' && !r.criteria.length) return 'pend';
    const warn = { qa: !!(r.qa && (r.qa.findings.length || r.qa.details.assertions.some((a) => a.outcome === 'not_exercised'))), code_review: !!(r.review && r.review.findings.length),
      unit_test_value_check: !!(r.unitTestValue && (r.unitTestValue.rows || []).some((x) => x.verdict !== 'Valuable')), feature_flag_audit: !!(r.flagAudit && r.flagAudit.verdict === 'fail'),
      completeness_audit: !!(r.audit && (auditCounts(r).PARTIAL || auditCounts(r).UNGRADED)), verdict: !!(r.verdict && r.verdict.verdict !== 'APPROVE') };
    return warn[s.id] ? 'warn' : 'done';
  }
  const tally = (list, key) => Object.entries(list.reduce((m, x) => { m[x[key]] = (m[x[key]] || 0) + 1; return m; }, {})).map(([k, n]) => ({ text: `${n} ${k.toLowerCase()}` }));
  const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
  /* The QA step: the same QA results as a standalone QA run, the verdict above them. QA runs inline in Verify, so its
   * directory is not stored and evidence chips only name their files; each open finding takes the fix selection. */
  function verifyQa(st, it, ctx) {
    const outcome = { findings_open: 'findings' }[ctx.r.qaVerdict] || ctx.r.qaVerdict;
    const r = qaResults(st, Object.assign({}, ctx.r.qa, { runKey: `verify:${it.id}`, outcome }), {
      namesOnly: true, select: (id) => selCtl(it, ctx, 'qa:' + id), selectHead: selAll(it, ctx, 'qa') || null });
    return S.VerifyQa(r);
  }
  /* The Components page shows the QA step of a sample run with QA-1 open and selected. */
  const verifyQaDemo = (id) => { const it = lookup(id), st = { vOpen: new Set(), qaOpen: new Set([`qa:verify:${id}:f:QA-1`]), vSel: { [id]: { 'qa:QA-1': 'chat' } }, vMarks: {}, vFilter: {} }; return verifyQa(st, it, verifyCtx(st, it)); };
  /* Each step's one-line summary and, when it has a receipt, the body it opens to. */
  function stepInfo(st, it, ctx, s) {
    const r = ctx.r, none = { sum: [{ text: 'No receipt recorded' }], body: '' };
    const jump = (label, to) => S.TextLink({ label, act: 'v-jump', arg: `${it.id}|${to}` });
    if (s.status === 'pending' && ctx.stop !== s && !(ctx.state === 'parked' && s.id === 'extract_criteria')) return { sum: [{ text: s.id === 'qa' && r.qaSelection !== 'on' ? 'Off for this run' : 'Not started' }], body: '' };
    switch (s.id) {
      case 'collect_inputs': return {
        sum: [{ text: /^https?:/i.test(r.intake || '') ? 'Linear issue' : (r.intake || '').startsWith('spec:') ? 'Spec file' : 'Free-text intake' }, { text: r.target, mono: true }, { text: `review ${r.reviewMode}` }, { text: `QA ${r.qaSelection}` }],
        body: S.KeyValues([['Intake', r.intake], ['Repository', r.repoRoot, true], ['Target', r.target, true], ['Range', vRange(r), true], ['Review mode', r.reviewMode], ['QA', r.qaSelection]]) };
      case 'extract_criteria': {
        const n = r.criteria.length;
        return { sum: n ? [{ text: `${n} criteria` }, { text: `${r.nonGoals.length} non-goals` }, { text: `${r.constraints.length} ${r.constraints.length === 1 ? 'constraint' : 'constraints'}` }, ctx.state === 'parked' ? { text: 'waiting for your confirmation', tone: 'accent' } : null] : [{ text: 'No numbered criteria found', tone: 'blocked' }],
          body: jump('Show the criteria', 'criteria') };
      }
      case 'gather_diff': {
        if (!r.changed) return none;
        return { sum: [{ text: `${r.changed.count} files` }, { text: `${r.changed.tests} test files` }, { text: vRange(r), mono: true }],
          body: S.DirBars({ rows: r.changed.dirs, max: 7 }) + `<div class="l-row">${S.Button({ label: 'Open changes', icon: 'diff', act: 'toast', arg: `Opened the Changes window on ${vRange(r)}` })}</div>` };
      }
      case 'feature_flag_audit': {
        const f = r.flagAudit;
        if (!f) return none;
        if (f.verdict === 'skipped') return { sum: [{ text: 'Skipped' }], body: f.skipReason ? S.ReportText(f.skipReason) : '' };
        const ok = f.checks.filter((x) => x.passed).length;
        return { sum: [{ text: f.verdict === 'pass' ? 'Passed' : 'Failed', tone: f.verdict === 'pass' ? 'done' : 'failed' }, { text: f.flagName, mono: true }, { text: `${ok}/${f.checks.length} checks` }],
          body: S.KeyValues([['Flag', f.flagName, true], ['Pattern', f.pattern], ['Issues', f.issues]]) + S.CheckLines(f.checks.map((x) => ({ ok: x.passed, text: x.text }))) };
      }
      case 'code_review': {
        if (s.status === 'running') return { sum: [{ text: 'Reviewing', tone: 'active' }, { text: 'specialists run in parallel' }], body: '' };
        if (!r.review) return none;
        const verdict = humanized(r.review.verdict);
        return { sum: [{ text: capFirst(verdict), tone: /changes/.test(verdict) ? 'failed' : 'blocked' }, ...tally(r.review.findings, 'severity')],
          body: `<div>${selAll(it, ctx, 'finding', { rule: true })}${S.Box(r.review.findings.map((f) => {
            const sel = selCtl(it, ctx, 'f:' + f.ref), k = `${it.id}:d:${f.ref}`;
            return S.FindingRow({ id: `find-${f.ref}`, ref: f.ref, severity: f.severity, html: md(f.description), open: st.vOpen.has(k), act: 'v-toggle', arg: k, lead: ctx.pick ? sel.lead : undefined, end: sel.end, hint: sel.hint,
              body: S.FieldText({ label: 'Location', chips: [S.Chip({ label: fileName(f.location), tip: f.location, act: 'open-file', arg: f.location })] }) + S.FieldText({ label: 'Lane', html: esc([f.specialist, `${f.confidence} confidence`].filter(Boolean).join(' · ')) }) });
          }))}</div><div class="l-row">${S.Button({ label: 'Verify findings', act: 'toast', arg: 'Verify findings checks the code in agent chat and records confirmed fixes without changing code' })}</div>` };
      }
      case 'unit_test_value_check': {
        if (ctx.stop === s) return { sum: [{ text: 'Stopped here', tone: 'blocked' }, { text: 'no receipt recorded' }], body: '' };
        const t = r.unitTestValue;
        if (!t) return none;
        if (t.verdict === 'skipped') return { sum: [{ text: 'Skipped' }], body: t.skipReason ? S.ReportText(t.skipReason) : '' };
        const plan = [['Keep', 'done', t.keep], ['Rewrite', 'blocked', t.rewrite], ['Delete', 'failed', t.delete && t.delete !== 'None.' ? t.delete : null], ['Add', 'accent', t.missing]].filter((p) => p[2]);
        return { sum: [{ text: t.overall, tone: 'strong' }, ...tally(t.rows, 'verdict')],
          body: `<div class="t-muted">${md(t.summary)}</div><div>${selAll(it, ctx, 'test', { rule: true, note: 'Keep rows are left out' })}${t.rows.map((x, i) => {
            const fixable = ctx.fix.some((y) => y.key === 't:' + i), sel = fixable ? selCtl(it, ctx, 't:' + i) : {};
            return S.TestValueRow({ badge: { text: x.verdict, tone: T_TONE[x.verdict] || 'faint' }, area: x.area, files: x.files.map((p) => S.Chip({ mono: true,  label: p, act: 'toast', arg: `Opened ${p} in the Changes window` })),
              why: md(x.why), action: md(x.action), lead: ctx.pick ? sel.lead || '' : undefined, end: sel.end, hint: sel.hint });
          }).join('')}</div>` + (plan.length ? S.MiniRule('Plan') + S.BadgeGrid(plan.map(([k, tone, v]) => ({ badge: { text: k, tone }, html: md(v) }))) : '') };
      }
      case 'completeness_audit': {
        if (!r.audit) return none;
        const c = auditCounts(r);
        return { sum: [c.PASS ? { text: `${c.PASS} met`, tone: 'done' } : null, c.PARTIAL ? { text: `${c.PARTIAL} partial`, tone: 'blocked' } : null, c.FAIL ? { text: `${c.FAIL} missing`, tone: 'failed' } : null, c.UNGRADED ? { text: `${c.UNGRADED} ungraded`, tone: 'blocked' } : null, !r.criteria.length ? { text: 'No criteria', tone: 'faint' } : null], body: (r.audit.findings || []).map(S.ReportText).join('') + jump('Show the graded criteria', 'criteria') };
      }
      case 'qa': {
        if (r.qaSelection !== 'on' || !r.qa) return { sum: [{ text: r.qaSelection !== 'on' ? 'Off for this run' : 'No report recorded' }], body: '' };
        const q = r.qa, as = q.details.assertions, passed = as.filter((a) => a.outcome === 'passed').length, gaps = as.filter((a) => a.outcome === 'not_exercised').length, open = q.findings.filter((f) => !f.fixed).length;
        return { sum: [open ? { text: `${open} open ${open === 1 ? 'finding' : 'findings'}`, tone: 'failed' } : { text: 'Passed', tone: 'done' }, { text: `${passed} of ${as.length} checks passed` }, gaps ? { text: `${gaps} not tested`, tone: 'blocked' } : null],
          body: verifyQa(st, it, ctx) };
      }
      case 'verdict': return r.verdict ? { sum: [{ text: r.verdict.verdict, tone: V_TONE[r.verdict.verdict] }], body: [r.verdict.summary, ...r.verdict.findings].filter(Boolean).map(S.ReportText).join('') } : none;
    }
    return { sum: [], body: '' };
  }
  function verifySteps(st, it, ctx) {
    const r = ctx.r;
    const steps = r.steps.map((s, i) => {
      const info = stepInfo(st, it, ctx, s);
      const key = `${it.id}:s:${s.id}`;
      return { id: `vstep-${s.id}`, index: i + 1, name: VSTEP[s.id] || s.id, node: stepNode(ctx, s), attempt: s.attempts > 1 ? s.attempts : null, sum: info.sum, body: info.body, open: !!info.body && st.vOpen.has(key), act: info.body ? 'v-toggle' : null, arg: key };
    });
    const done = r.steps.filter((s) => s.status === 'completed' || s.status === 'skipped').length;
    return S.VerifySection({ id: 'sec-steps', title: 'Steps', count: `${done} of ${r.steps.length}`, body: S.StepTimeline({ steps }) });
  }
  /* Activity stays as it is everywhere else, folded under the steps. */
  function verifyActivity(st, it) {
    const all = activityEntries(it), open = st.vOpen.has(`${it.id}:activity`);
    const showAll = st.feedAll.has(it.id), hidden = showAll ? 0 : Math.max(0, all.length - FEED_MAX);
    return S.VerifySection({ body: S.Fold({ label: 'Activity', strong: true, note: 'agent output and tool calls, as today', open, act: 'v-toggle', arg: `${it.id}:activity`, inset: true,
      body: all.length ? S.EventLines(all.slice(hidden), { hidden, act: 'feed-all', arg: it.id }) : '<div class="t-muted">No activity recorded for this run.</div>' }) });
  }
  function verifyBar(it, ctx) {
    const items = ctx.pick ? ctx.fix.filter((x) => x.key in ctx.sel) : [];
    if (!items.length) return '';
    const chat = items.filter((x) => ctx.sel[x.key] === 'chat').length, goal = items.length - chat;
    const counts = {};
    items.forEach((x) => new Set(x.locs).forEach((l) => { counts[l] = (counts[l] || 0) + 1; }));
    const top = Object.entries(counts).sort((a, b) => b[1] - a[1])[0];
    return S.SelectionBar({ label: 'Selected for fixing', title: `${items.length} selected`, sub: items.map((x) => x.label).join(', '),
      note: top && top[1] > 1 ? `${top[1]} of them touch <code>${esc(top[0])}</code>, so one agent gets them together` : null,
      actions: [chat ? S.Button({ label: `Fix ${chat} in chat`, icon: 'bot', variant: 'primary', act: 'vf-chat', arg: it.id }) : '', goal ? S.Button({ label: `Start goal with ${goal}`, icon: 'play', act: 'vf-goal', arg: it.id }) : '', S.Button({ label: 'Clear', act: 'vf-clear', arg: it.id })] });
  }
  /* The brief for Fix in chat and the goal intake for Start goal: each selected item with its evidence, the verified branch
   * as the base. */
  function verifyFixText(st, it, route) {
    const ctx = verifyCtx(st, it), r = ctx.r;
    const items = ctx.fix.filter((x) => ctx.sel[x.key] === route);
    const crit = (n) => (r.criteria.find((c) => c.n === n) || {}).text;
    const lines = items.map((x) => {
      if (x.kind === 'criterion') return `${x.label} ${plainMd(crit(x.grade.n))}${x.grade.missing ? `\n  Missing: ${plainMd(x.grade.missing)}` : ''}${x.grade.evidence.length ? `\n  Evidence: ${x.grade.evidence.join(', ')}` : ''}`;
      if (x.kind === 'finding') { const f = r.review.findings.find((y) => 'f:' + y.ref === x.key); return `${f.ref} ${f.severity} at ${f.location}: ${plainMd(f.description)}`; }
      if (x.kind === 'qa') { const f = r.qa.findings.find((y) => 'qa:' + y.id === x.key); return `${f.id} ${f.title}${f.observed ? `\n  Observed: ${f.observed}` : ''}`; }
      const t = r.unitTestValue.rows[+x.key.slice(2)];
      return `Test ${t.area}: ${plainMd(t.action)}`;
    });
    return { items, target: r.target, repo: it.repo, text: `${route === 'goal' ? 'Close the gaps' : 'Fix what'} Verify found in ${r.issueKey || 'this run'} on ${r.target}. Base the work on that branch.\n\n${lines.join('\n')}` };
  }
  /* The whole Verify document, in the mock's order: header, what the work does, the outcome card, the criteria, the steps,
   * Activity and the selection bar. */
  function verifyDoc(st, it) {
    const ctx = verifyCtx(st, it);
    return S.VerifyDoc([verifyHeader(st, it, ctx), verifySummary(ctx, it), verifyOutcome(it, ctx), verifyCriteria(st, it, ctx), verifySteps(st, it, ctx), verifyActivity(st, it), verifyBar(it, ctx)]);
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

    const location = `<div class="l-row g-1 l-wrap">${S.Button({ label: r.path, icon: 'folder', size: 'sm', variant: 'ghost', act: 'toast', arg: `Opened ${r.path}` })}${it.branch ? S.Button({ label: it.branch, icon: 'branch', size: 'sm', variant: 'ghost', act: 'toast', arg: `Opened ${r.web}/tree/${it.branch}` }) : `<span class="t-faint t-sm">no branch</span>`}</div>`;
    const metrics = S.MetricStrip([
      goal ? (it.planning ? { label: 'Goal stage', value: 'Planning' } : finished || it.current == null ? { label: 'Subtasks', value: `${it.done || 0}`, unit: `/ ${it.total || 0}`, note: 'complete' } : { label: 'Subtask', value: `${it.current}`, unit: `/ ${it.total}`, note: `${it.done} complete · ${it.total - it.done - 1} pending` }) : { label: 'Step', value: `<span class="mono t-md">${esc(it.step)}</span>`, note: it.exec ? esc(it.exec) : '&nbsp;' },
      goal ? { label: 'Phase', value: esc(it.planning ? 'plan' : it.step), note: it.planning ? `${it.planned}/${it.total} plans saved` : esc(it.exec || ' ') } : null,
      finished ? { label: 'Total run time', value: S.fmtDur(it.goalMs), note: `finished ${esc(it.finishedAt || '')}` } : { label: goal ? 'Goal active' : it.kind === 'operation' ? 'Operation elapsed' : 'Phase elapsed', value: clock(it), note: it.live ? 'ticking' : 'last known duration' },
      goal && it.subMs != null && !finished ? { label: 'Subtask active', value: subClock(it), note: it.live ? 'ticking' : 'last known duration' } : null,
    ]);

    const parts = [];
    /* Validation and Verify runs have their own documents (validation run design, Verify run design). */
    const val = isValidationRun(it), ver = !!it.verifyRun, plan = isPlanRun(it), mon = isMonitorRun(it);
    if (ver) parts.push(verifyDoc(st, it));
    else if (val) parts.push(validationDoc(st, it));
    else if (mon) parts.push(monitorDoc(st, it));
    else parts.push(S.DocHeader({
      eyebrow: [plan ? planBadge(it) : lifeLabel(it), it.stalled ? S.Label({ text: 'Stalled', color: 'var(--st-blocked)', tint: true, dot: false }) : '', S.IdTag(rowKey(it)), it.issue && !goal ? S.IdTag(it.issue) : '', S.Label({ text: kindWord(it), dot: false }), it.lifecycle !== 'terminal' ? S.Label({ text: it.fresh && !it.refreshFailed ? 'fresh' : 'stale', color: it.fresh && !it.refreshFailed ? 'var(--st-done)' : 'var(--fg-faint)' }) : '', plan && it.model ? S.Label({ text: `${it.model}${it.effort ? ` · ${it.effort} effort` : ''}`, dot: false }) : ''],
      title: it.title, sub: location, actions: [runActions(st, it)],
    }));
    /* Validation runs get no strip: their live card already names the running check command. */
    const build = !val && !ver && !mon && buildOf(st, it);
    if (build) parts.push(S.BuildStrip(build));
    if (it.pauseRequested && !val) parts.push(S.Hint({ tone: 'info', icon: 'pause', text: 'Pause recorded. The goal pauses at its next checkpoint.' }));
    if (it.reason && !val && !ver && !plan && !mon) parts.push(S.ReasonBlock({ label: 'Run reason', text: it.reason, color: LIFE[it.lifecycle][1], open: st.reasonOpen.has(it.id), act: 'reason-toggle', arg: it.id }));
    if (!val && !mon) parts.push(decisionPanel(st, it));
    if (plan) parts.push(...planParts(st, it));
    /* A standalone QA run's results come first: they are what the operator reads in a QA report. */
    if (it.qa) { const r = qaResults(st, it.qa, { running: it.lifecycle === 'active' }); parts.push(secBlock(st, 'qa-results', { id: 'sec-qa-results', cls: 'sb-sec--qa', title: 'QA results', badge: r.verdict, body: r.body })); }
    if (!val && !ver && !plan && !mon) parts.push(metrics);
    if (subs) parts.push(subtaskSection(st, it));
    if (goal && it.planning) parts.push(secBlock(st, 'subtasks', { title: 'Subtasks', count: `${it.planned}/${it.total} plans saved`, body: S.Hint({ text: 'Subtasks appear here once their plans are saved.' }) }));
    if (goal && it.pipeline && !it.planning) {
      const cur = subs && it.subtasks.find((x) => x.id === it.current);
      parts.push(secBlock(st, 'pipeline', { title: 'Phase pipeline', body: S.PhasePipeline(pipelineSteps(it).map((p) => (cur && p.state ? Object.assign(p, { act: 'peek-open', arg: `${it.id}|${cur.id}|${p.label}`, tip: `Show ${p.label} for subtask ${pad(cur.id)}` }) : p))) }));
    }
    if (!goal && !plan && !mon && (it.steps || []).length > 1) parts.push(secBlock(st, 'pipeline', { title: 'Phase pipeline', body: S.PhasePipeline(it.steps.map(([label, state]) => ({ label, state, count: state === 'now' ? it.exec : null }))) }));
    if (goal) { const ov = window.Review.overview(st, it); parts.push(secBlock(st, 'changes', { title: 'Changes', count: ov.count, end: ov.end, body: ov.body })); }
    if (!plan && !ver && !val && !mon) parts.push(activitySection(st, it));
    /* Standalone phases and operations keep their own validation, phase results and spec bundle in the document. */
    if (!goal && it.validation) parts.push(secBlock(st, 'validation', { title: 'Validation', body: S.CheckLines(it.validation.map(([n, ok, checks]) => ({ ok, text: `Subtask ${n} · ${ok ? 'passed' : 'failed'} · ${checks} checks` }))) }));
    if (!goal && it.phaseResults) {
      const pr = it.phaseResults;
      const passes = pr.passes.map((p) => `<div class="l-col g-2">${S.InspectorLine({ lead: `Review pass ${p.n}`, parts: [p.verdict, `${p.open} unresolved`] })}${p.findings.length ? S.VerifyDoc([S.Box(p.findings.map(([sev, text, where], i) => { const k = `${it.id}|pr|${p.n}|${i}`; return S.FindingRow({ ref: `F-${String(i + 1).padStart(3, '0')}`, severity: sev, title: text, state: p.open ? 'Open' : 'Fixed', act: 'audit-met', arg: k, open: st.auditMet.has(k), body: S.FieldText({ label: 'Location', chips: [S.Chip({ label: where.split('/').pop(), tip: where, act: 'open-file', arg: where })] }) }); }))]) : ''}</div>`).join('');
      parts.push(secBlock(st, 'phase-results', { title: 'Phase results', body: `<div class="l-col g-4">${passes}${pr.audit ? `<div class="l-row g-2 t-sm"><b class="t-strong">Audit</b><span class="t-muted">${esc(pr.audit.verdict)}</span>${S.ExpandableText({ text: `· ${pr.audit.summary}`, cls: 't-faint' })}</div>` : ''}</div>` }));
    }
    if (!goal && it.specBundle) parts.push(secBlock(st, 'spec-bundle', { title: 'Spec bundle', body: S.Box(bundleOrder(it.specBundle).map((f) => S.TreeRow({ level: 0, file: true, lead: S.Icon(f.endsWith('.yaml') ? 'file' : 'fileText', 'sm'), title: fileName(f), meta: [], act: 'open-file', arg: f, rowIdx: -1 }))) }));


    const header = S.ViewHeader({
      crumbs: [{ label: 'Work', act: 'nav', arg: 'work' }, sec ? { label: sec[1], act: 'section', arg: sec[0] } : { label: 'History', act: 'nav', arg: 'history' }, { label: rowKey(it) }],
      actions: [idx >= 0 ? S.Pager({ index: idx, total: liveList.length }) : '', it.refreshFailed ? S.StaleIndicator() : '', ...paneToggles(st), S.IconButton({ icon: 'panelRight', label: 'Toggle details', kbd: '⌘ I', act: 'toggle-props', on: st.propsOpen })],
    });
    const body = S.DetailSplit({ doc: parts.join(''), pane: pane ? detailsPanel(st, it) : '' });
    return workArea(st, header, body);
  }

  /* ---------- History (feature/history) ---------- */
  function history(st) {
    rows = [];
    const kind = { all: null, goals: 'goal', phases: 'phase', operations: 'operation' }[st.historyTab];
    const list = D.history.filter((h) => (!kind || h.kind === kind) && matches(h, st.filter));
    const body = list.length ? S.GroupHeader({ label: `Finished within ${st.prefs.retention}`, count: list.length }) + list.map((h) => {
      const i = rows.push('item:' + h.id) - 1;
      return S.ListRow({ id: h.kind === 'goal' ? h.id : h.label, status: S.StatusIcon(h.lifecycle === 'terminal' ? 'done' : h.lifecycle), title: h.title, sub: h.kind === 'goal' ? '' : h.kind === 'operation' ? 'operation' : 'standalone phase', meta: [`<span class="hide-sm">${repoLabel(h.repo)}</span>`, `<span class="tnum t-faint t-sm">${S.fmtDur(h.ms)}</span>`], time: esc(h.at), act: 'open', arg: h.id, focus: st.focus === i, rowIdx: i });
    }).join('') : S.EmptyState({ icon: 'history', title: st.filter ? 'No matching work' : 'No finished work', body: st.filter ? null : `Nothing finished within ${st.prefs.retention}.` });
    const header = S.ViewHeader({
      crumbs: [{ label: 'History' }],
      middle: `<div class="l-row g-3">${S.ViewTabs({ act: 'history-tab', value: st.historyTab, items: [{ id: 'all', label: 'All' }, { id: 'goals', label: 'Goals' }, { id: 'phases', label: 'Phases' }, { id: 'operations', label: 'Operations' }] })}${S.Input({ id: 'history-filter', icon: 'search', placeholder: 'Filter history', value: st.filter, input: 'work-filter', width: '200px' })}</div>`,
      actions: paneToggles(st),
    });
    return workArea(st, header, S.ScrollArea({ id: 'history', body }));
  }

  /* ---------- Specs pane (specs pane design, variant A "selection bar"; desktop feature/specs SpecsPane, SpecsTree,
   * SpecSelectionBar, SpecsMenuModel, PurgeConfirmationDialog) ---------- */
  /* One object, one action list: right-click, the row's ⋯, the bar's ⋯ and ⌘K show the same spec actions in the same order.
   * The bar promotes one primary action chosen by state. Run verdicts follow SpecBundleControlPolicy.kt. */
  const SPEC_RUN = {
    not_started: ['Not started', 'pending', 'pending'], running: ['Running', 'active', 'active'], pausing: ['Pausing', 'paused', 'active'],
    paused: ['Paused', 'paused', 'active'], blocked: ['Blocked', 'blocked', 'active'], canceled: ['Canceled', 'canceled', 'done'],
    complete: ['Complete', 'done', 'done'], unavailable: ['Unavailable', 'stale', 'pending'],
  };
  const specPath = (sp) => (sp.folder ? `${sp.folder}/${sp.key}` : sp.key);
  const specOf = (path) => D.specs.find((sp) => path != null && (path === specPath(sp) || String(path).startsWith(specPath(sp) + '/')));
  const specProject = (st) => D.projects.find((p) => p.id === st.specProject) || D.projects[0];
  const specAgent = (st, sp) => st.chosenAgent['spec:' + sp.key] || sp.agent || null;
  const specFrac = (sp) => (sp.subtasks.length ? sp.subState.filter((x) => x === 'complete').length / sp.subtasks.length : 0);
  const specDone = (sp) => sp.subState.filter((x) => x === 'complete').length;
  const specCount = (sp) => (sp.subtasks.length ? `${specDone(sp)}/${sp.subtasks.length}` : '');
  const specIcon = (sp) => S.StatusIcon(SPEC_RUN[sp.run][1], Math.max(0.12, specFrac(sp)));
  /* SpecBundleControlPolicy.specBundleControl: what the runtime allows for one bundle. */
  function specControl(sp) {
    const live = sp.run === 'running' || sp.run === 'pausing';
    const v = { run: sp.run, startLabel: 'Start', canStart: false, canPause: false, pauseRequested: false, canCancel: false, canDelete: !live, purgeVisible: !!sp.ever && sp.run !== 'unavailable', canPurge: !!sp.ever && !live, reason: sp.reason };
    if (sp.run === 'not_started') v.canStart = true;
    if (sp.run === 'running') { v.canPause = true; v.canCancel = true; }
    if (sp.run === 'pausing') { v.pauseRequested = true; v.canCancel = true; }
    if (sp.run === 'paused' || sp.run === 'blocked') { v.canStart = true; v.startLabel = 'Resume'; v.canCancel = true; }
    if (sp.run === 'canceled') { v.canStart = true; v.startLabel = 'Run again'; }
    return v;
  }
  /* The spec actions (SpecsMenuModel.specMenuActions): open, reveal, copy, favorite; then the run group; then the
   * destructive group, each confirming in a dialog. Disabled items say why. */
  function specMenuItems(st, sp) {
    const v = specControl(sp);
    const agent = specAgent(st, sp);
    const run = [
      { icon: 'external', label: 'Export work', act: 'spec-export', arg: sp.key },
      v.canStart && { icon: 'play', label: v.startLabel, kbd: 'R', key: 'r', act: 'spec-start', arg: sp.key },
      v.canPause && { icon: 'pause', label: 'Pause after subtask', kbd: 'P', key: 'p', act: 'spec-pause', arg: sp.key },
      v.pauseRequested && { icon: 'pause', label: 'Pause requested', disabled: true, sub: 'Pause recorded. The goal stops after its current subtask.' },
      v.canStart && { icon: 'bot', label: agent ? `Change agent, now ${agent}` : 'Choose agent', kbd: 'A', key: 'a', act: 'spec-agent', arg: sp.key },
    ].filter(Boolean);
    const danger = [
      v.canCancel && { icon: 'x', label: 'Cancel goal…', danger: true, act: 'spec-cancel', arg: sp.key },
      v.purgeVisible && { icon: 'rerun', label: 'Purge run history…', danger: true, act: 'spec-purge', arg: sp.key, disabled: !v.canPurge, sub: v.canPurge ? null : 'Pause or cancel the goal first' },
      { icon: 'trash', label: 'Delete spec files…', danger: true, act: 'spec-delete', arg: sp.key, disabled: !v.canDelete, sub: v.canDelete ? null : 'Pause or cancel the goal first' },
    ].filter(Boolean);
    return [
      { icon: 'fileText', label: 'Open spec', kbd: '↵', act: 'spec-open', arg: sp.key },
      { icon: 'folder', label: 'Reveal folder', act: 'spec-reveal', arg: sp.key },
      { icon: 'copy', label: 'Copy key', act: 'copy', arg: sp.key },
      { icon: 'star', label: sp.fav ? 'Remove favorite' : 'Add favorite', kbd: 'F', key: 'f', act: 'spec-fav', arg: sp.key },
      '-', ...run, '-', ...danger,
    ];
  }
  /* Panel actions (the header ⋯): Collapse all, Reveal specs folder with its path, Import work. */
  function specPanelItems(st) {
    const pr = specProject(st);
    return [
      { icon: 'chevronsUpDown', label: 'Collapse all', act: 'spec-collapse' },
      { icon: 'folder', label: 'Reveal specs folder', act: 'spec-reveal-store', sub: pr.store },
      { icon: 'file', label: 'Import work…', act: 'spec-import', disabled: !pr.checkout, sub: pr.checkout ? null : 'This project has no checkout on this machine.' },
    ];
  }
  const SPEC_FILTERS = [['all', 'All'], ['active', 'Active'], ['pending', 'Pending'], ['done', 'Done']];
  const specGroupOk = (st, sp) => st.specStatus === 'all' || SPEC_RUN[sp.run][2] === st.specStatus;
  const specTextOk = (q, sp) => !q || sp.key.toLowerCase().includes(q) || sp.title.toLowerCase().includes(q) || sp.subtasks.some((t) => t.toLowerCase().includes(q));
  let specRows = [];
  /* The tree: bundles with status, key, title and n/m (⋯ on hover and on the selected row), expanded bundles list spec.md,
   * the subtask specs with their state, traceability.md and decomposition-manifest.yaml; finished specs sit in the done
   * folder. A click on a bundle selects it and its chevron expands it; a click on a file selects and opens it. */
  function specTree(st, project) {
    specRows = [];
    const q = (st.specQuery || '').trim().toLowerCase();
    const bundles = D.specs.filter((sp) => sp.project === project.id && specGroupOk(st, sp) && specTextOk(q, sp));
    const out = [];
    const bundleRows = (sp, level) => {
      const path = specPath(sp);
      const open = st.specOpen.has(path) || !!q;
      const muted = sp.run === 'canceled';
      specRows.push(path);
      out.push(S.TreeRow({ level, path, toggle: { act: 'spec-toggle', arg: path, label: `${open ? 'Collapse' : 'Expand'} ${sp.key}` }, open, focus: st.specSel === path, muted, lead: specIcon(sp), id: sp.key, title: sp.title, tip: sp.title, ctx: 'spec:' + path, act: 'spec-select', arg: path,
        count: muted ? null : specCount(sp), trail: `${S.IconButton({ icon: 'more', label: `${sp.key} actions`, act: 'spec-menu', arg: path, size: 'sm', cls: 'hover-only' })}`, rowIdx: -1 }));
      if (!open) return;
      const child = (sub, o) => { const p = `${path}/${sub}`; specRows.push(p); out.push(S.TreeRow(Object.assign({ level: level + 1, path: p, child: true, file: true, focus: st.specSel === p, ctx: 'spec:' + p, act: 'spec-select', arg: p, rowIdx: -1, meta: [] }, o))); };
      child('spec.md', { lead: S.Icon('fileText', 'sm'), title: 'spec.md' });
      sp.subtasks.forEach((t, j) => child(`sub/${j + 1}`, { file: false, lead: S.StatusIcon(SUB_STATE[sp.subState[j]], 0.5), id: pad(j + 1), title: t }));
      child('traceability.md', { lead: S.Icon('file', 'sm'), title: 'traceability.md' });
      child('decomposition-manifest.yaml', { lead: S.Icon('file', 'sm'), title: 'decomposition-manifest.yaml' });
    };
    bundles.filter((sp) => !sp.folder).forEach((sp) => bundleRows(sp, 0));
    const done = bundles.filter((sp) => sp.folder === 'done');
    if (done.length) {
      const open = st.specOpen.has('done') || !!q;
      specRows.push('done');
      out.push(S.TreeRow({ level: 0, path: 'done', toggle: { act: 'spec-toggle', arg: 'done', label: `${open ? 'Collapse' : 'Expand'} done` }, open, focus: st.specSel === 'done', lead: S.Icon('folder', 'sm'), title: 'done', act: 'spec-select', arg: 'done', count: done.length, rowIdx: -1 }));
      if (open) done.forEach((sp) => bundleRows(sp, 1));
    }
    return out.join('');
  }
  /* The bar for the selected spec (a bundle, or a file inside one). The primary action is Start, Resume or Run again when the
   * runtime allows a launch, else Pause after subtask while it runs, else nothing. */
  function specBar(st, sp) {
    const v = specControl(sp);
    const [word, icon] = SPEC_RUN[sp.run];
    const agent = specAgent(st, sp);
    const ag = agent && D.AGENTS[agent];
    const agentPill = v.canStart
      ? S.PropPill({ id: 'spec-agent-pill', lead: ag ? S.Avatar({ name: agent, initials: agent[0].toUpperCase(), color: ag.color, solid: true }) : S.Icon('bot', 'xs'), text: agent || 'Choose agent', need: !agent, act: 'spec-agent', arg: sp.key, tip: 'Agent (A)' })
      : agent ? S.PropPill({ lead: S.Avatar({ name: agent, initials: agent[0].toUpperCase(), color: ag.color, solid: true }), text: agent, tip: 'Agent' }) : '';
    const primary = v.canStart ? S.Button({ label: v.startLabel, icon: 'play', variant: 'primary', act: 'spec-start', arg: sp.key, tip: `${v.startLabel} (R)` })
      : v.canPause ? S.Button({ label: 'Pause', icon: 'pause', act: 'spec-pause', arg: sp.key, tip: 'Pause after subtask (P)' }) : '';
    return S.SpecBar({ key: sp.key, title: sp.title, count: specCount(sp),
      star: S.IconButton({ icon: 'star', label: `${sp.fav ? 'Remove favorite' : 'Add favorite'}`, kbd: 'F', act: 'spec-fav', arg: sp.key, size: 'sm', cls: sp.fav ? 'is-fav' : '' }),
      pills: [S.PropPill({ lead: S.StatusIcon(icon, Math.max(0.12, specFrac(sp))), text: word, tip: 'Status' }), agentPill],
      actions: [S.IconButton({ icon: 'more', label: `${sp.key} actions`, act: 'spec-bar-menu', arg: specPath(sp) }), primary],
      notes: [v.reason && { text: v.reason, tone: 'error' }, v.pauseRequested && { text: 'Pause recorded. The goal stops after its current subtask.' }].filter(Boolean) });
  }
  function specsPane(st) {
    const project = specProject(st);
    const q = (st.specQuery || '').trim().toLowerCase();
    const mine = D.specs.filter((sp) => sp.project === project.id);
    const count = (f) => mine.filter((sp) => (f === 'all' || SPEC_RUN[sp.run][2] === f) && specTextOk(q, sp)).length;
    const searching = st.specSearch || !!st.specQuery;
    const filters = searching
      ? `${S.FilterBar({ id: 'spec-q', placeholder: 'Filter by key, title or file', label: 'Filter', value: st.specQuery, input: 'spec-q' })}${S.IconButton({ icon: 'x', label: 'Close filter', kbd: 'esc', act: 'spec-search-close' })}`
      : `${S.ViewTabs({ act: 'spec-status', value: st.specStatus, items: SPEC_FILTERS.map(([id, label]) => ({ id, label, count: count(id) })) })}<span class="l-grow"></span>${S.IconButton({ icon: 'search', label: 'Filter by key, title or file', kbd: '/', act: 'spec-search', on: !!st.specQuery })}`;
    let body;
    if (!project.checkout) body = S.EmptyState({ icon: 'folder', title: 'This project has no checkout on this machine.' });
    else if (project.problem) body = S.EmptyState({ icon: 'alert', title: 'Specs unavailable', body: esc(project.problem), actions: [S.Button({ label: 'Retry', act: 'spec-retry' })] });
    else if (!mine.length) { specRows = []; body = S.EmptyState({ icon: 'fileText', title: 'No specs yet', body: 'Plan a feature with <span class="mono">/skill-bill phase:plan</span> and its bundle shows up here.' }); }
    else {
      const tree = specTree(st, project);
      body = tree || S.EmptyState({ icon: 'search', title: 'No specs match', body: 'Nothing in this project matches the filter and status.', actions: [S.Button({ label: 'Clear filters', act: 'spec-clear' })] });
    }
    const sp = project.checkout && !project.problem ? specOf(st.specSel) : null;
    return S.SidePane({
      title: 'Specs',
      head: S.PickerButton({ lead: S.RepoIcon(repoOf(project.name)), label: project.name, act: 'project-menu' }),
      actions: [S.IconButton({ icon: 'more', label: 'Panel actions', act: 'spec-panel-menu', size: 'sm' }), S.IconButton({ icon: 'x', label: 'Close specs pane', kbd: 'G S', act: 'specs-toggle', size: 'sm' })],
      bar: filters, body: S.ScrollArea({ id: 'specs', zone: 'specs', tabIndex: 0, role: 'tree', label: 'Specs', body }) + (sp ? specBar(st, sp) : ''),
    });
  }

  /* ---------- Terminal pane (feature/terminal) ---------- */
  function terminal(st) {
    const tab = st.termTabs.find((t) => t.id === st.termActive);
    const body = tab && tab.log ? S.ShellPrompt({ lines: tab.log, cursor: tab.live }) : tab ? S.ShellPrompt({ dir: tab.directory, name: tab.name, branch: tab.branch }) : '';
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
      top: `<div class="l-col g-2">${S.Button({ label: 'Back to app', icon: 'arrowLeft', variant: 'ghost', act: 'settings-back', kbd: 'esc' })}</div>`,
      items: SETTINGS_PAGES.map(([sec, pages]) => S.SidebarSection({ label: sec, items: pages.map(([id, icon, label]) => S.SidebarItem({ icon, label, on: st.settingsPage === id, act: 'settings-page', arg: id })) })),
      footer: [],
    });
  }
  function settingsPage(st) {
    const page = st.settingsPage;
    const content = { general: setGeneral, notifications: setNotifications, models: setModels, compaction: setCompaction, project: setProject, telemetry: setTelemetry }[page](st);
    const label = SETTINGS_PAGES.flatMap((s) => s[1]).find((p) => p[0] === page)[2];
    return S.ViewHeader({ crumbs: [{ label: 'Settings', act: 'settings-page', arg: 'general' }, { label }], actions: [] }) + S.ScrollArea({ id: 'settings', settings: true, body: content });
  }
  function setGeneral(st) {
    return S.SettingsHeader({ title: 'General', body: 'Where the app reads Sona’s work, which Claude account runs use, and how long finished work stays listed.' }) +
      S.SettingsGroup({ title: 'Database', rows: [S.SettingRow({ stack: true, control: `<div class="l-col g-2 l-fill">${S.Field({ for: 'db-path', label: 'Database path, blank uses runtime default', control: `<div class="l-row g-2">${S.Input({ id: 'db-path', value: st.prefs.dbDraft, mono: true, placeholder: 'Runtime default', input: 'db-path' })}${S.Button({ label: 'Save settings', variant: 'primary', act: 'db-save', disabled: st.prefs.dbDraft === st.prefs.dbPath })}</div>` })}${st.prefs.dbDraft && !st.prefs.dbDraft.trim() ? S.Hint({ tone: 'error', text: 'The path is only spaces. Clear it to use the default.' }) : ''}${S.Hint({ text: `Current database: ${st.prefs.dbPath || '~/.skill-bill/review-metrics.db'}` })}</div>` })] }) +
      S.SettingsGroup({ title: 'Claude account', rows: [S.SettingRow({ label: 'Claude config directory', desc: 'Runs started here use it as CLAUDE_CONFIG_DIR.', control: S.PickerButton({ icon: 'folder', label: st.prefs.claudeDir, mono: true, act: 'claude-menu' }) })] }) +
      S.SettingsGroup({ title: 'History retention', rows: [S.SettingRow({ label: 'Keep finished work for', desc: 'How long History and the tray list finished work.', control: S.Segmented({ act: 'pref-retention', value: st.prefs.retention, items: ['6h', '24h', '7d'].map((x) => ({ id: x, label: x })) }) })] }) +
      S.SettingsGroup({ rows: [S.SettingRow({ label: 'Start at login', desc: 'Keeps the tray icon running in the background.', control: S.Toggle({ on: st.prefs.login, act: 'pref-toggle', arg: 'login', label: 'Start at login' }) })] });
  }
  function setNotifications(st) {
    return S.SettingsHeader({ title: 'Notifications', body: 'A notification fires when work changes lifecycle state. Clicking one opens that item.' }) +
      S.SettingsGroup({ title: 'Notify me when', rows: D.NOTIFICATIONS.map(([id, label]) => S.SettingRow({ label, control: S.Toggle({ on: st.prefs.notify[id], act: 'pref-notify', arg: id, label }) })) }) +
      S.SettingsGroup({ title: 'Preview', rows: [S.SettingRow({ stack: true, control: S.Toast({ system: true, app: 'Sona', when: 'now', title: 'INF-7 is blocked', body: 'Review left 2 Blocker findings on subtask 3 · infra-terraform' }) })] });
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
      <div class="l-row g-3 l-wrap l-top"><div class="l-grow">${S.Field({ for: 'ed-model', label: 'Model', control: S.Input({ id: 'ed-model', value: d.model, mono: true, input: 'ed-model', error: blank }), help: blank ? 'A model is required. To drop an override, use Reset to tier.' : 'Passed to the agent as typed.' })}</div>${S.Field({ width: '150px', for: 'ed-effort', label: 'Effort', control: S.Input({ id: 'ed-effort', value: d.effort || '', mono: true, placeholder: 'unset', input: 'ed-effort' }), help: 'Optional.' })}</div>
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
    return `${fields.map(([key, label]) => S.Field({ width: '140px', for: 'ed-' + key, label, control: S.Input({ id: 'ed-' + key, value: d[key], mono: true, input: 'ed-field', error: !!d.error }) })).join('')}${err}
      <div class="l-row g-2 l-wrap">${S.Button({ label: 'Save', variant: 'primary', act: 'ed-save', kbd: '⌘ ↵' })}${S.Button({ label: 'Cancel', variant: 'ghost', act: 'ed-cancel' })}<span class="l-grow"></span>${removeLabel ? S.Button({ label: removeLabel, variant: 'ghost', icon: 'trash', act: 'ed-remove' }) : ''}</div>`;
  }
  function setCompaction(st) {
    const c = st.compaction;
    const trigger = Math.floor(c.window / 100) * c.pct;
    const tooLow = trigger < 200000;
    return S.SettingsHeader({ title: 'Compaction', body: 'Compacts an agent’s context before it fills. Saved as <span class="mono">compaction</span> in config.json.' }) +
      S.SettingsGroup({ rows: [
        S.SettingRow({ label: 'Compact long phases', desc: 'enabled · default on', control: S.Toggle({ on: c.enabled, act: 'cmp-toggle', label: 'Compact long phases' }) }),
        S.SettingRow({ label: 'Context window', desc: 'window_tokens · default 400,000', control: S.Input({ id: 'cmp-window', value: c.window.toLocaleString('en-US'), mono: true, input: 'cmp-window', width: '140px' }) }),
        S.SettingRow({ stack: true, label: `Compact at ${c.pct}%`, desc: `trigger_pct · default 70 · compacts at ${trigger.toLocaleString('en-US')} tokens`, control: `${S.Range({ id: 'cmp-pct', min: 1, max: 100, value: c.pct, input: 'cmp-pct', label: 'Compact at percent' })}${tooLow ? S.Hint({ tone: 'error', text: 'The trigger must be at least 200,000 tokens.' }) : ''}` }),
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
      `<div class="l-row g-2">${S.RepoIcon(repoOf(project.name))}<b class="t-strong">${esc(project.name)}</b><span class="l-grow"></span>${S.Button({ label: 'Open config.yaml', icon: 'external', size: 'sm', act: 'toast', arg: 'Opened config.yaml in your editor' })}</div>` +
      S.SettingsGroup({ rows: [
        S.SettingRow({ label: 'Spec source', desc: 'spec_type · default local', control: S.Segmented({ act: 'pref-spectype', value: st.prefs.specType, items: [{ id: 'local', label: 'Local files' }, { id: 'linear', label: 'Linear' }] }) }),
        S.SettingRow({ stack: true, control: `<div class="l-col g-2 l-fill">${S.Field({ for: 'gradle', label: 'Gradle wrapper', control: S.Input({ id: 'gradle', value: gw, mono: true, placeholder: 'gradlew', input: 'gradle', error: gwBad }), help: 'validation_gate.gradle_wrapper. Repo-relative, no “.” or “..” segments. Leave empty to use the repository’s ./gradlew.' })}${gwBad ? S.Hint({ tone: 'error', text: 'Use a repo-relative path without “.” or “..” segments.' }) : ''}</div>` }),
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
        S.SettingRow({ label: 'Proxy URL', desc: 'telemetry.proxy_url · blank uses the default endpoint', control: S.Input({ id: 'tel-proxy', value: '', mono: true, placeholder: 'default', width: '220px' }) }),
        S.SettingRow({ label: 'Batch size', desc: 'telemetry.batch_size · default 50', control: S.Input({ id: 'tel-batch', value: '50', mono: true, width: '80px' }) }),
      ] }) +
      S.SettingsGroup({ title: 'Agents', end: [S.Label({ text: 'read-only', dot: false })], rows: [agent('claude', true), agent('codex', true), agent('cursor', true), agent('junie', false)] }) +
      S.SettingsGroup({ title: 'Files', rows: ['~/.config/skill-bill/config.json', '~/.skill-bill/install-selection.json'].map((f) => S.SettingRow({ labelHtml: `<span class="mono t-sm">${f}</span>`, label: f, control: S.Button({ label: 'Open', icon: 'external', size: 'sm', act: 'open-file', arg: f }) })) });
  }

  /* ---------- New task (desktop feature/chat newtask) ---------- */
  /* st.nt (main.js) holds the open chats in tab order, the selected index, saved chats, repositories with their branches,
   * and the state of the queue and picker popups. A chat: { id, session, title, agent, repo, branch, lines, stream, unread,
   * draft, queue }. Its repository and base branch are fixed once it has messages. */
  const ntChat = (st) => st.nt.chats[st.nt.active];
  const chatLabel = (c) => c.title || 'New chat';
  const chatStatus = (c) => (c.stream ? 'replying' : c.unread ? 'unread' : null);
  const chatEmpty = (c) => !c.lines.length && !c.stream;
  const chatAgo = (m) => (m < 1 ? 'now' : m < 60 ? `${m}m` : m < 1440 ? `${Math.floor(m / 60)}h` : m < 2880 ? '1d' : `${Math.floor(m / 1440)}d`);
  const agentModel = (a) => (D.MATRIX[a] ? D.MATRIX[a].reasoning.model : null);
  const agentDot = (a) => S.Dot(agentOf(a).color);
  const defaultBranch = (repo) => (D.REPO_BRANCHES[repo] || {}).defaultBranch || '';
  const baseBranch = (c) => c.branch || defaultBranch(c.repo);
  const isOpenChat = (st, session) => st.nt.chats.some((c) => c.session === session);
  const repoAndBranch = (repo, branch) => `${esc(repo)} <span class="t-faint">/</span> ${esc(branch)}`;

  /* Send, Queue while a reply runs, or Send now when the draft is empty and messages wait (NewTaskComposer). */
  function ntPrimary(c) {
    const typed = c.draft.trim();
    if (c.stream) return typed ? { label: 'Queue' } : c.queue.length ? { label: 'Send now' } : { label: 'Queue', disabled: true };
    return { label: 'Send', disabled: !typed || !c.repo };
  }
  function ntSendButton(c) {
    const p = ntPrimary(c);
    return S.Button({ label: p.label, variant: 'primary', size: 'sm', kbd: '↵', act: 'chat-send', disabled: p.disabled, id: 'composer-send' });
  }
  function ntTranscript(c) {
    const avatar = S.Avatar({ name: c.agent, initials: agentOf(c.agent).initials, color: agentOf(c.agent).color, size: 'sm' });
    const messages = c.lines.map((l) => S.ChatMessage({
      role: l.role, text: l.text, agent: c.agent, avatar,
      images: l.images && l.images.length ? l.images.map((f) => S.ChatPill({ icon: 'image', text: f })).join('') : null,
      actions: (l.actions || []).map((a, j) => S.SuggestionChip({ label: a, primary: j === 0, act: 'chat-suggested', arg: a })),
    }));
    if (c.stream) messages.push(S.ChatMessage({ role: 'agent', agent: c.agent, avatar, text: c.stream.full.slice(0, c.stream.shown), streaming: true, streamId: c.id }));
    return S.ChatTranscript({ scroll: 'chat-' + c.id, messages });
  }
  /* The empty chat's home (new-task-home variant A): hero, step line, Recent chats of the picked repository, Needs you. */
  function ntHome(st, c) {
    const sel = st.nt.inboxSel || {};
    const recent = c.repo ? st.nt.saved.filter((x) => x.newTask.repositoryRoot === c.repo) : [];
    const needs = D.items.filter((x) => x.section === 'needs').slice(0, st.nt.few ? 1 : undefined);
    const running = D.items.filter((x) => x.lifecycle === 'active').length;
    const finished = D.items.filter((x) => x.section === 'finished').length;
    const recentRows = recent.map((x, i) => {
      const open = isOpenChat(st, x.sessionId);
      const image = x.images.length > 0;
      return S.InboxRow({
        icon: image ? 'image' : 'message', tone: image ? 'image' : null, title: x.title, sub: image && x.title !== x.images[0] ? x.images[0] : null, subMono: true,
        badges: open ? [S.Label({ text: 'open', color: 'var(--accent)', tint: true, dot: false })] : [], time: chatAgo(x.updatedMin),
        act: 'saved-open', arg: x.sessionId, selected: sel.list === 'nt-recent' && sel.i === i,
        discard: open ? null : { label: `Discard ${x.title}`, act: 'saved-discard', arg: x.sessionId },
      });
    });
    const needRows = needs.map((it, i) => S.InboxRow({ need: true, icon: 'alert', tone: 'need', key: rowKey(it), title: it.title, sub: it.summary, badges: [S.Label({ text: it.kind === 'goal' ? it.step : it.phase || it.op, mono: true, dot: false })], time: S.fmtDur(it.goalMs), act: 'open', arg: it.id, selected: sel.list === 'nt-needs' && sel.i === i }));
    const examples = st.nt.homeState === 'firstRun' ? S.Section({ title: 'Try one of these', body: S.InboxList({ id: 'nt-examples', label: 'Example tasks', rows: [
      ['Add CSV export to the history screen', 'Goal run', 'Add CSV export to the history screen. Done when a user can download the rows they see.'],
      ['Review my current branch', 'Standalone review', 'Review my current branch'],
      ['APP-123', 'From an issue', 'APP-123'],
    ].map(([title, kind, text]) => S.InboxRow({ icon: 'message', title, sub: kind, act: 'chat-quick', arg: text })) }) + (st.nt.readiness ? S.Label({ text: st.nt.readiness, color: st.nt.readiness.startsWith('Ready:') ? 'var(--st-done)' : 'var(--accent)', dot: true }) : '') }) : '';
    return S.NewTaskHome({ scroll: 'nt-home-' + c.id, children: [
      S.DocHeader({ title: 'What should we ship?', sub: 'Describe the work or paste an issue. Sona plans it, builds it, audits it and opens the PR.' }),
      S.StepLine(D.NEW_TASK_STEPS),
      recent.length ? S.Section({ title: 'Recent chats', count: recent.length, end: [S.Button({ label: 'Search chats', variant: 'ghost', size: 'sm', kbd: '/resume', act: 'resume-palette' })], body: S.InboxList({ id: 'nt-recent', label: 'Recent chats', rows: recentRows }) }) : '',
      st.nt.homeState ? examples : S.Section({ title: needs.length ? 'Needs you' : 'All clear', count: needs.length || null, end: [S.Button({ label: `${running} running · ${finished} finished today`, icon: 'arrowRight', variant: 'ghost', size: 'sm', act: 'nav', arg: 'work' })], body: needs.length ? S.InboxList({ id: 'nt-needs', label: 'Needs you', rows: needRows }) : '' }),
    ] });
  }
  function ntComposer(st, c) {
    const empty = chatEmpty(c);
    const pop = st.popover || {};
    const n = c.queue.length;
    const repoPill = empty
      ? S.ChatPill({ icon: 'folder', html: c.repo ? repoAndBranch(c.repo, c.currentBranch || baseBranch(c)) : 'Choose repository', need: !c.repo, chevron: 'chevronDown', act: 'pk-toggle', expanded: pop.type === 'target', id: 'nt-target', tip: 'Change repository or base branch' })
      : S.ChatPill({ icon: 'folder', html: repoAndBranch(c.repo, c.currentBranch || baseBranch(c)), tip: `${c.repo} / ${baseBranch(c)}` });
    const model = agentModel(c.agent);
    const chg = chatChanges(c);
    const agentPill = S.ChatPill({ lead: agentDot(c.agent), text: model ? `${c.agent} · ${model}` : c.agent, chevron: 'chevronDown', act: 'nt-agent-menu', expanded: pop.type === 'nt-agent', disabled: !!c.stream, tip: c.stream ? 'Wait for the current reply to finish' : 'Change agent' });
    const warning = c.branchIntent && c.currentBranch !== defaultBranch(c.repo) && !c.branch
      ? `<div class="l-col g-2" role="status">${S.Callout({ tone: 'warn', body: `This checkout is on ${esc(c.currentBranch)}. New work normally starts from ${esc(defaultBranch(c.repo))}. Switch to ${esc(defaultBranch(c.repo))} or explicitly use ${esc(c.currentBranch)} as the base branch before starting.` })}<div class="l-row g-2 l-wrap">${S.Button({ label: `Switch to ${defaultBranch(c.repo)}`, size: 'sm', act: 'chat-default-branch', disabled: !!c.stream })}${S.Button({ label: 'Use current branch as base', size: 'sm', act: 'chat-current-base', disabled: !!c.stream })}</div></div>` : '';
    return warning + S.Composer({
      id: 'composer-' + c.id, chatId: c.id, value: c.draft, label: `Message for ${chatLabel(c)}`,
      placeholder: empty ? 'Describe the work, or paste an issue key or spec path' : `Reply to ${c.agent}`,
      chips: empty ? D.QUICK_STARTS.map((q) => S.SuggestionChip({ label: q, note: q === 'QA my branch' && qaDeviceName(st) ? `on ${qaDeviceName(st)}` : null, act: 'chat-quick', arg: q })) : [],
      hint: qaDeviceName(st) ? 'QA runs use the device picked in the sidebar.' : null,
      queue: n ? S.ChatPill({ sans: true, text: `${n} ${n === 1 ? 'message' : 'messages'} queued`, chevron: 'chevronsUpDown', act: 'q-toggle', expanded: pop.type === 'queue', id: 'nt-queue' }) : '',
      pills: [repoPill, agentPill],
      actions: [chg ? S.ChatChangesPill(Object.assign({ act: 'chg-toggle', expanded: pop.type === 'changes', id: 'nt-changes' }, chg)) : '', c.stream ? S.Button({ label: 'Stop', size: 'sm', act: 'chat-stop', tip: 'Stop the reply. Queued messages return to the draft.' }) : '', ntSendButton(c)],
    });
  }
  function newTask(st) {
    const c = ntChat(st);
    const tabs = S.ChatTabs({ count: st.nt.chats.length, tabs: st.nt.chats.map((t, i) => ({ id: t.id, label: chatLabel(t), status: chatStatus(t), on: i === st.nt.active, tip: `${chatLabel(t)} · ${t.repo || 'no repository'} · ${t.agent}` })) });
    /* No view header: the tab strip is the top of the screen, and Discard lives in each tab's right-click menu (chatTabMenu). */
    return workArea(st, '', tabs + (chatEmpty(c) ? ntHome(st, c) : ntTranscript(c)) + ntComposer(st, c));
  }
  /* A chat tab's right-click menu (NewTaskChatTabMenu). Discard shows once the chat has messages: it closes the chat and removes it
   * from Recent chats, where Close keeps it there. */
  function chatTabMenu(st, id) {
    const c = st.nt.chats.find((x) => String(x.id) === String(id));
    if (!c) return '';
    return S.Menu({ items: [
      { icon: 'x', label: 'Close', kbd: '⌘ W', act: 'chat-close', arg: c.id },
      c.lines.length ? { icon: 'trash', label: 'Discard', act: 'chat-discard', arg: c.id, danger: true } : null,
    ].filter(Boolean) });
  }

  /* Repository and base branch picker (NewTaskRepositoryPicker). Picking a repository keeps it open and moves to the branch
   * list; picking a branch closes it. `o`: { repos, chat: { repo, branch }, pk: { filter, active, branchState, addError } }. */
  const pickMatch = (text, q) => text.toLowerCase().includes((q || '').trim().toLowerCase());
  function pickerItems(o) {
    const pk = o.pk;
    const repo = o.repos.filter((r) => pickMatch(r.name, pk.filter.repo)).map((r) => ({ kind: 'repo', id: r.name })).concat([{ kind: 'add', id: '__add' }]);
    const r = o.repos.find((x) => x.name === o.chat.repo);
    const branch = [];
    if (r && pk.branchState !== 'loading') {
      const q = pk.filter.branch;
      if (pickMatch(r.defaultBranch, q) || pickMatch('default', q)) branch.push({ kind: 'default', id: '__default', name: r.defaultBranch });
      if (pk.branchState !== 'error') {
        r.local.filter(([b]) => pickMatch(b, q)).forEach(([name, age]) => branch.push({ kind: 'local', id: name, name, age }));
        r.remote.filter(([b]) => pickMatch(b, q)).forEach(([name, age]) => branch.push({ kind: 'remote', id: name, name, age }));
      }
    }
    return { repo, branch };
  }
  function targetPickerView(o) {
    const pk = o.pk, c = o.chat;
    const items = pickerItems(o);
    const add = items.repo.length - 1;
    const repoRows = items.repo.slice(0, add).map((it, n) => ({ label: it.id, checked: it.id === c.repo, hl: pk.active.repo === n, act: 'pk-pick', arg: 'repo|' + n }));
    const repoCol = {
      title: 'Repository', label: 'Repositories', id: 'pk-list-repo', pick: 'repo', focusable: o.repos.length <= 7,
      filter: o.repos.length > 7 ? S.FilterBar({ id: 'pk-filter-repo', value: pk.filter.repo, placeholder: 'Filter repositories', input: 'pk-filter', pick: 'repo' }) : '',
      rows: repoRows.length ? [S.Menu({ items: repoRows })] : [], message: repoRows.length ? null : { title: `No repository matches "${pk.filter.repo.trim()}"`, body: '' },
      problem: pk.addError ? S.Hint({ tone: 'warn', html: `${esc(pk.addError)} ${S.Button({ label: 'Choose another folder', variant: 'ghost', size: 'sm', act: 'pk-add' })}` }) : '',
      foot: S.MenuItem({ icon: 'plus', label: 'Add repository', hl: pk.active.repo === add, act: 'pk-pick', arg: 'repo|' + add }),
    };
    const r = o.repos.find((x) => x.name === c.repo);
    const branchCol = { title: 'Base branch', label: 'Branches', id: 'pk-list-branch', pick: 'branch' };
    if (!r) branchCol.message = { title: 'Choose a repository first', body: 'Branches come from the repository you pick.' };
    else {
      branchCol.filter = S.FilterBar({ id: 'pk-filter-branch', value: pk.filter.branch, placeholder: 'Filter branches', input: 'pk-filter', pick: 'branch' });
      if (pk.branchState === 'loading') branchCol.loading = true;
      else if (!items.branch.length) branchCol.message = { title: `No branch matches "${pk.filter.branch.trim()}"`, body: 'Only branches already fetched appear here.' };
      else {
        const row = (it) => ({ html: `<span class="mono">${esc(it.name)}</span>`, label: it.name, end: it.kind === 'default' ? 'default' : esc(it.age), checked: it.kind === 'default' ? !c.branch : c.branch === it.id, hl: pk.active.branch === items.branch.indexOf(it), act: 'pk-pick', arg: 'branch|' + items.branch.indexOf(it) });
        const group = (head, kind) => { const list = items.branch.filter((it) => it.kind === kind); return list.length ? [{ head }, ...list.map(row)] : []; };
        branchCol.rows = [S.Menu({ items: [...items.branch.filter((it) => it.kind === 'default').map(row), ...group('Local', 'local'), ...group('Remote', 'remote')] })];
      }
      if (pk.branchState === 'error') branchCol.problem = S.Hint({ tone: 'warn', html: `Could not read branches for ${esc(r.name)}. The default branch still works. ${S.Button({ label: 'Retry', variant: 'ghost', size: 'sm', act: 'pk-retry' })}` });
    }
    return S.TargetPicker({ repo: repoCol, branch: branchCol });
  }
  const targetPicker = (st) => targetPickerView({ repos: st.nt.repos, chat: ntChat(st), pk: st.nt.pk });

  /* The open chats behind the tab strip's count button; a filter appears past eight chats (NewTaskChatOverflowMenu). */
  function chatMenu(st) {
    const q = (st.nt.menuQ || '').trim().toLowerCase();
    const list = st.nt.chats.filter((c) => !q || [chatLabel(c), c.repo || '', c.agent].some((v) => v.toLowerCase().includes(q)));
    const filter = st.nt.chats.length > 8 ? S.FilterBar({ id: 'chat-menu-q', value: st.nt.menuQ, placeholder: `Filter ${st.nt.chats.length} open chats`, input: 'chat-menu-q' }) : '';
    return filter + S.Menu({ items: list.length ? list.map((c) => ({ lead: S.ChatStatusMark(chatStatus(c)), label: chatLabel(c), end: esc(c.repo || ''), checked: c === ntChat(st), act: 'chat-select', arg: c.id })) : [{ head: 'No open chat matches.' }] });
  }
  /* The chat's changes against its baseline with line totals, or null before it changed a file (no pill then). */
  function chatChanges(c) {
    const x = c && c.changes;
    if (!x || !x.files.length) return null;
    const files = x.files.map(([status, path, ins, del]) => ({ status, path, ins, del }));
    return Object.assign({}, x, { list: files, files: files.length, ins: files.reduce((n, f) => n + f.ins, 0), del: files.reduce((n, f) => n + f.del, 0) });
  }
  function changesPanel(st) {
    const chg = chatChanges(ntChat(st));
    return chg ? S.ChatChangesPanel(Object.assign({}, chg, { files: chg.list, act: 'chg-open' })) : '';
  }
  function queuePanel(st) {
    const c = ntChat(st), q = st.nt.q;
    return S.QueuePanel({ count: c.queue.length, clearArmed: q.clearArmed, undo: q.undo && q.undo.chat === c.id ? q.undo.text : null, rows: c.queue.map((m, i) => S.QueuedRow({ index: i, text: m.text, next: i === 0, images: m.images, action: m.action, kb: i === q.kb, act: 'q-remove', arg: m.id })) });
  }

  /* /resume (ResumePalette, variant B of resume picker design): saved chats of the composer's repository, newest first,
   * the first 30 until a filter or Show all. `o`: { repo, saved, q, active, showAll }. */
  const RESUME_LIMIT = 30;
  const resumeGroup = (m) => (m < 8 * 60 ? 'Today' : m < 32 * 60 ? 'Yesterday' : m < 7 * 1440 ? 'This week' : 'Older');
  const savedBranch = (x) => x.newTask.baseBranch || defaultBranch(x.newTask.repositoryRoot);
  function resumeListing(o) {
    const all = o.repo ? o.saved.filter((x) => x.newTask.repositoryRoot === o.repo) : [];
    const q = (o.q || '').trim().toLowerCase();
    const matches = q ? all.filter((x) => [x.title, savedBranch(x), x.agent].some((v) => v.toLowerCase().includes(q))) : all;
    return { all, shown: q || o.showAll ? matches : matches.slice(0, RESUME_LIMIT), total: matches.length };
  }
  function highlight(text, q) {
    const i = q ? text.toLowerCase().indexOf(q.toLowerCase()) : -1;
    return i < 0 ? esc(text) : `${esc(text.slice(0, i))}<mark>${esc(text.slice(i, i + q.length))}</mark>${esc(text.slice(i + q.length))}`;
  }
  function resumePaletteView(o) {
    const { all, shown, total } = resumeListing(o);
    const q = (o.q || '').trim();
    const active = Math.min(o.active || 0, Math.max(0, shown.length - 1));
    const groups = [];
    shown.forEach((x, i) => {
      const label = resumeGroup(x.updatedMin);
      if (!groups.length || groups[groups.length - 1].label !== label) groups.push({ label, rows: [] });
      groups[groups.length - 1].rows.push(S.ResumeRow({ index: i, active: i === active, titleHtml: highlight(x.title, q), images: x.images.length, cli: !x.lines.length, branch: savedBranch(x), branchHtml: highlight(savedBranch(x), q), agent: x.agent, agentHtml: highlight(x.agent, q), agentColor: agentOf(x.agent).color, when: chatAgo(x.updatedMin), tip: x.title }));
    });
    const x = shown[active];
    const preview = x ? {
      title: x.title, cli: x.lines.length ? null : x.agent,
      facts: [{ label: 'Branch', value: `<span class="mono">${esc(savedBranch(x))}</span>` }, { label: 'Agent', value: `<span>${esc(x.agent)} · ${x.updatedMin < 1 ? 'just now' : chatAgo(x.updatedMin) + ' ago'}</span>` }],
      lines: x.lines.map((l, i) => ({ who: l.role === 'operator' ? 'You' : x.agent, you: l.role === 'operator', text: l.text, images: i === 0 ? x.images : [] })).slice(-4),
    } : null;
    const empty = !all.length ? { title: 'No saved chats yet', body: 'Chats you start here show up in this list.' } : !shown.length ? { title: `No chat matches "${q}"`, body: 'The filter checks titles, branches and agents.' } : null;
    return S.ResumePalette({
      filter: S.FilterBar({ id: 'resume-q', value: o.q, placeholder: 'Filter by title, branch or agent', label: 'Filter chats', input: 'resume-q', large: true, end: o.repo ? S.ChatPill({ icon: 'folder', text: o.repo }) : '' }),
      groups, empty, preview,
      more: !q && !o.showAll && total > RESUME_LIMIT ? S.Button({ label: `Show all ${total} chats`, variant: 'ghost', size: 'sm', act: 'resume-more' }) : '',
      count: !all.length ? '' : shown.length < total ? `${shown.length} of ${total} chats` : `${total} ${total === 1 ? 'chat' : 'chats'}`,
    });
  }
  const resumePalette = (st) => resumePaletteView({ repo: ntChat(st).repo, saved: st.nt.saved, q: st.dialog.q, active: st.dialog.active, showAll: st.dialog.showAll });

  /* The Changes window and Diff comments catalog pages: the same window as in the app, inline, over the SKILL-512 run or
   * the SKILL-474 review sample with one of its four scenarios. */
  function designSurface(st) {
    const scenario = ['1', '2', '3', '4'].includes(st.surfaceScenario) ? st.surfaceScenario : '1';
    const header = S.ViewHeader({ crumbs: [{ label: 'Work', act: 'nav', arg: 'work' }, { label: st.view === 'comments' ? 'Diff comments' : 'Changes window' }],
      middle: S.Segmented({ act: 'surface-page', value: st.view === 'comments' ? `comments/${scenario}` : 'changes', items: [{ id: 'changes', label: 'Changes window' }, ...['Writing a review', 'Review added', 'After phase:review', 'After pr-review-fix'].map((label, i) => ({ id: `comments/${i + 1}`, label: `${i + 1} · ${label}` }))] }), actions: [] });
    const view = st.view === 'comments' ? { work: 'review-sample', inline: true, seed: window.Review.scenarioSeed(scenario) } : { work: 'SKILL-512', inline: true };
    return workArea(st, header, S.ScrollArea({ id: 'surface', body: window.Review.changesWindow(st, view) }));
  }

  /* ---------- page ---------- */
  function app(st) {
    const views = { work, detail, history, settings: settingsPage, newtask: newTask, changes: designSurface, comments: designSurface };
    return shell(st, (views[st.view] || work)(st));
  }

  window.Screens = {
    subPhaseStates, finishStates, buildOf, app, work, detail, history, settingsPage, specsPane, terminal, tierOf, lookup, itemBy, subKeys, selectedSub, paneShown, PANE_MIN, paneWidth, activityGroups,
    statusIcon, stepText, clock, rowKey, repoOf, agentOf, LIFE, SECTIONS, resumable, controllable, pauseEligible, recoveryOffered,
    newTask, ntChat, chatLabel, chatStatus, chatEmpty, ntSendButton, pickerItems, targetPicker, targetPickerView, chatMenu, chatTabMenu, queuePanel, chatChanges, changesPanel,
    resumeListing, resumePalette, resumePaletteView,
    qaView, qaResults, qaLiveBlock, peekCard, finishResults, devicePanel, usageData,
    isPlanRun, planStrip, planNow, planTeaser, planActivity, planBundle, planTranscript, planBriefing, PLAN_STAGES,
    specOf, specPath, specControl, specMenuItems, specPanelItems, specProject, specAgent, specBar, get specRows() { return specRows; },
    isMonitorRun, monitorDoc, monitorCard, monitorCycles, monStepPanel, monCiBoard,
    isValidationRun, shapeReport, validationCard, validationDoc, valDur, verifyOutcome, checkRow, valDefaultParts, valDefaultChecks, checkExpandable, verifyCtx, verifyState, fixItems, verifyFixText, verifyQaDemo, VSTEP, devicePanelView, deviceTrigger, deviceOptions, devicePickable, deviceList, runAppView, runAppTarget, runAppEligible,
    get rows() { return rows; },
  };
})();
