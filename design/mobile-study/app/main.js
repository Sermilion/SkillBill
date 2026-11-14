/* Sona mobile study · state, actions and the two views (Prototype, Screens)
 * Every phone on the page owns its own state, so gallery phones stay interactive. Refreshing resets everything. */
(function () {
  'use strict';
  const D = window.MD;
  const M = window.MB;
  const S = window.MS;

  /* ---------- State ---------- */
  function base(over) {
    const st = {
      tab: 'needs', stacks: { needs: [], work: [], chats: [], plans: [], settings: [] },
      onboard: null, lock: false, conn: 'connected', machine: 'studio', updated: null,
      sheet: null, toast: null, pickAgent: null,
      workSeg: 'active', chatSeg: 'new', histWin: '24h', plansSeg: 'all', scope: 'run',
      runs: {}, chats: {}, criteria: {}, recover: {}, peek: {}, sec: {}, bd: {},
      newChat: { repo: 'atlas-web', branch: 'main', agent: 'claude', draft: '' },
      defaultAgent: 'claude', pushOn: true, pushTitles: true,
      notif: Object.fromEntries(D.NOTIFICATIONS.map(([id, , on]) => [id, on])),
    };
    return deepMerge(st, over || {});
  }
  function deepMerge(a, b) {
    Object.keys(b).forEach((k) => {
      if (b[k] && typeof b[k] === 'object' && !Array.isArray(b[k]) && a[k] && typeof a[k] === 'object' && !Array.isArray(a[k])) deepMerge(a[k], b[k]);
      else a[k] = b[k];
    });
    return a;
  }
  const at = (tab, ...stack) => ({ tab, stacks: { [tab]: stack } });

  /* ---------- Phones ---------- */
  const phones = {};
  let seq = 0;
  function mount(el, state, special) {
    const id = 'p' + ++seq;
    phones[id] = { el, st: state, special, timers: [] };
    el.dataset.host = id;
    paint(id);
    return id;
  }
  function signature(st) { const s = st.stacks[st.tab]; return [st.onboard, st.lock, st.conn, st.tab, s[s.length - 1]].join('|'); }
  function paint(id) {
    const p = phones[id];
    const prevScroll = p.el.querySelector('.mb-scroll');
    const keep = prevScroll && p.sig === signature(p.st) ? prevScroll.scrollTop : 0;
    const out = p.special ? p.special(p.st) : S.render(p.st);
    if (p.special && p.st.sheet) out.over = (out.over || '') + S.render(Object.assign({}, p.st, { onboard: null, lock: false })).over;
    p.el.innerHTML = M.Phone({ id, body: out.body, over: out.over });
    p.sig = signature(p.st);
    const sc = p.el.querySelector('.mb-scroll');
    if (sc && keep) sc.scrollTop = keep;
    /* Keep the selected phase tab in view, like the desktop opening on the running or stopped phase. */
    const tabs = p.el.querySelector('.mb-ptabs');
    const on = tabs && tabs.querySelector('.mb-ptab.on');
    if (on) tabs.scrollLeft = on.offsetLeft - tabs.offsetLeft - (tabs.clientWidth - on.offsetWidth) / 2;
  }
  function later(id, ms, fn) { const p = phones[id]; p.timers.push(setTimeout(() => { fn(p.st); paint(id); }, ms)); }
  function toast(id, text, o) {
    const p = phones[id];
    p.st.toast = Object.assign({ text }, o || {});
    clearTimeout(p.toastTimer);
    p.toastTimer = setTimeout(() => { p.st.toast = null; paint(id); }, (o && o.action) ? 5000 : 2800);
  }

  /* Controls go to the machine over SSH. When it can't be reached, nothing is queued; the toast says so and offers Retry. */
  function control(id, st, label, fn, retry) {
    st.sheet = null;
    if (st.conn !== 'connected') { toast(id, `${label} didn't reach ${st.machine}. Nothing changed.`, { tone: 'failed', icon: 'wifi', action: { label: 'Retry', act: retry[0], arg: retry[1] } }); return; }
    fn();
  }
  const setRun = (st, rid, patch) => { st.runs[rid] = Object.assign(st.runs[rid] || {}, patch); };
  const setChat = (st, cid, patch) => { st.chats[cid] = Object.assign(st.chats[cid] || {}, patch); };
  const chat = (st, cid) => Object.assign({}, (st.extraChats || []).concat(D.CHATS).find((c) => c.id === cid), st.chats[cid] || {});

  const ONBOARD_BACK = { host: 'welcome', key: 'host', trust: 'key' };
  const ACTIONS = {
    tab(st, a) { if (st.tab === a) st.stacks[a] = []; st.tab = a; st.sheet = null; },
    push(st, a) { st.sheet = null; st.stacks[st.tab].push(a); },
    back(st) {
      if (st.onboard) { st.onboard = st.onboard === 'host' && st.paired ? null : ONBOARD_BACK[st.onboard] || null; return; }
      st.stacks[st.tab].pop();
    },
    sheet(st, a) { st.sheet = a; st.pickAgent = null; },
    'close-sheet'(st) { st.sheet = null; },
    toast(st, a, id) { st.sheet = null; toast(id, a); },
    copy(st, a, id) { toast(id, 'Copied', { icon: 'check', tone: 'done' }); },
    'work-seg'(st, a) { st.workSeg = a; },
    'hist-win'(st, a) { st.histWin = a; },
    'plans-seg'(st, a) { st.plansSeg = a; },
    scope(st, a) { st.scope = a; },
    'diff-go'(st, a) { const s = st.stacks[st.tab]; s[s.length - 1] = 'diff:' + a; },
    'subtask-go'(st, a) { const s = st.stacks[st.tab]; s[s.length - 1] = 'subtask:' + a; },
    sec(st, a) { const [k, d] = a.split('|'); st.sec[k] = !(k in st.sec ? st.sec[k] : d === '1'); },
    bd(st, a) { st.bd[a] = !st.bd[a]; },
    'detail-go'(st, a) { const s = st.stacks[st.tab]; s[s.length - 1] = 'detail:' + a; },
    'chat-seg'(st, a) { st.chatSeg = a; },
    /* Open the run's chat, creating it on first use. It is bound to the run's checkout and branch. */
    'run-chat'(st, rid) {
      st.sheet = null;
      const all = (st.extraChats || []).concat(D.CHATS);
      let c = all.find((x) => x.run === rid);
      if (!c) {
        const r = S.runOf(st, rid);
        c = { id: 'rc-' + rid, run: rid, kind: 'run', title: 'Chat about ' + (r.kind === 'phase' ? r.phaseName : r.id), repo: r.repo, branch: r.branch, path: r.path, agent: r.agent, status: 'idle', time: 'now', messages: [] };
        st.extraChats = [c].concat(st.extraChats || []);
      }
      st.stacks[st.tab].push('chat:' + c.id);
    },
    'open-run'(st, rid) { const s = st.stacks[st.tab]; if (s[s.length - 2] === 'detail:' + rid) s.pop(); else s.push('detail:' + rid); },
    proposal(st, a, id) {
      const [cid, what] = a.split(':');
      const c = chat(st, cid);
      if (what === 'adjust') { setChat(st, cid, { proposal: Object.assign({}, c.proposal, { state: 'adjusting' }), draft: 'Also check that each theme exports as its own mode' }); toast(id, 'Say how the proposal should change, then send.', { icon: 'message' }); return; }
      if (what === 'confirm') {
        control(id, st, 'Confirm', () => {
          ACTIONS['confirm-criteria'](st, c.run, id);
          setChat(st, cid, { proposal: Object.assign({}, c.proposal, { state: 'confirmed' }), messages: c.messages.concat([['agent', 'Confirmed. Verify is gathering the diff, then runs the audits. Results show on the run.']]) });
        }, ['proposal', a]);
        return;
      }
      control(id, st, 'Dismiss', () => {
        setRun(st, c.run, { state: 'canceled', needs: null, step: 'Proposal dismissed' });
        setChat(st, cid, { proposal: Object.assign({}, c.proposal, { state: 'dismissed' }), messages: c.messages.concat([['agent', 'Dismissed. The Verify run is canceled; start it again with the same intake when you are ready.']]) });
      }, ['proposal', a]);
    },
    decision(st, a, id) {
      const [cid, choice] = a.split(':');
      const c = chat(st, cid);
      if (choice === 'retry_fix') { setChat(st, cid, { draft: 'retry_fix: delete the dead /status fixtures in the plugin tests' }); toast(id, 'Write the instructions for the retry, then send.', { icon: 'message' }); return; }
      control(id, st, 'Decision', () => {
        setChat(st, cid, { decision: Object.assign({}, c.decision, { state: choice }), messages: c.messages.concat([['user', choice], ['agent', `Recorded ${choice} for subtask ${c.decision.subtask} with sona goal operator-decision. Resume the goal when you want it to continue.`]]) });
        setRun(st, c.run, { awaitsDecision: false, needs: 'Paused', step: 'Decision recorded · resume to continue' });
      }, ['decision', a]);
    },
    finalize(st, a, id) { control(id, st, 'Finalize', () => { setRun(st, a, { runOptions: { canFinalize: false }, liveness: 'finalized' }); toast(id, a + ' finalized. The worktree is removed on the machine.', { icon: 'check', tone: 'done' }); }, ['finalize', a]); },
    'act-filter'(st, a) { st.actFilter = a; },
    'act-all'(st, a) { const [rid, mode] = a.split(':'); (S.runOf(st, rid).log || []).forEach((g) => { st.sec['act-' + g.n] = mode !== 'open'; }); },
    'act-open'(st, a) { const [, n] = a.split(':'); st.sec['act-' + n] = false; },
    vfilter(st, a) { st.vfilter = a; },
    pfilter(st, a) { st.pfilter = a; },
    'mon-pick'(st, a) { st.monPick = a; },
    peek(st, a) { const k = a.lastIndexOf(':'); st.peek[a.slice(0, k)] = a.slice(k + 1); },
    refresh(st, a, id) {
      st.sheet = null;
      if (st.conn !== 'connected') return ACTIONS.retry(st, a, id);
      st.updated = 'just now';
      toast(id, 'Read the board from ' + st.machine, { icon: 'refresh' });
    },
    retry(st, a, id) {
      st.conn = 'connecting'; st.toast = null;
      later(id, 1400, (s) => { s.conn = 'connected'; s.updated = 'just now'; });
    },
    pause(st, rid, id) {
      control(id, st, 'Pause', () => { setRun(st, rid, { pauseRequested: true }); toast(id, `Pause requested. ${rid} stops after this subtask.`, { icon: 'pause' }); }, ['pause', rid]);
    },
    stop(st, rid, id) {
      control(id, st, 'Pause', () => {
        const r = S.runOf(st, rid);
        setRun(st, rid, { state: 'paused', needs: 'Paused', pausedAt: '14:03', step: `Paused during subtask ${r.current + 1} of ${r.subtasks.length}`, build: null });
        toast(id, `${rid} paused. Resume restarts subtask ${r.current + 1}.`, { icon: 'pause' });
      }, ['stop', rid]);
    },
    'pick-agent'(st, a) { st.pickAgent = a; },
    resume(st, rid, id) {
      const agent = st.pickAgent || S.runOf(st, rid).agent;
      control(id, st, 'Resume', () => {
        const r = S.runOf(st, rid);
        setRun(st, rid, { state: 'active', frac: 0.2, needs: null, agent, step: r.kind === 'goal' ? `Resuming subtask ${r.current + 1} of ${r.subtasks.length}` : 'Starting', lastOutput: 'just now', pauseRequested: false, finished: null });
        toast(id, `${rid} resumed on ${st.machine} with ${agent}.`, { icon: 'play', tone: 'done' });
      }, ['resume', rid]);
    },
    'recover-check'(st, rid) { st.recover[rid] = !st.recover[rid]; },
    recover(st, rid, id) {
      control(id, st, 'Recover', () => {
        const r = S.runOf(st, rid);
        setRun(st, rid, { state: 'active', frac: 0.6, needs: null, incident: null, step: 'Resuming validate · subtask 2 of 3', lastOutput: 'just now', phases: Object.assign({}, r.phases, { validate: 'active' }), subtasks: r.subtasks.map((s, i) => (i === 1 ? ['active', s[1]] : s)) });
        toast(id, `Recovered. ${rid} resumes validate.`, { icon: 'check', tone: 'done' });
      }, ['recover', rid]);
    },
    cancel(st, rid, id) {
      control(id, st, 'Cancel', () => { setRun(st, rid, { state: 'canceled', needs: null, step: 'Canceled from your phone', build: null }); toast(id, `${rid} canceled.`, { icon: 'x' }); }, ['cancel', rid]);
    },
    crit(st, a) { const [rid, ref] = a.split(':'); st.criteria[rid] = st.criteria[rid] || {}; st.criteria[rid][ref] = st.criteria[rid][ref] === false; },
    'confirm-criteria'(st, rid, id) {
      control(id, st, 'Confirm', () => {
        const chk = st.criteria[rid] || {};
        const n = D.RUNS.find((r) => r.id === rid).criteria.filter((c) => chk[c[0]] !== false).length;
        const base0 = S.runOf(st, rid);
        const kept = base0.criteria.filter((c) => chk[c[0]] !== false).map(([, t], i) => [String(i + 1), t, null, []]);
        setRun(st, rid, { state: 'active', frac: 0.15, needs: null, criteria: null, step: `Auditing ${n} criteria`, lastOutput: 'just now',
          verify: Object.assign({}, base0.verify, { status: 'Running', criteria: kept, steps: [base0.verify.steps[0], ['Extract criteria', 'done', `${n} criteria confirmed`], ['Gather diff', 'run', 'Collecting the diff']] }) });
        toast(id, `Confirmed ${n} criteria. Verify is auditing.`, { icon: 'check', tone: 'done' });
      }, ['confirm-criteria', rid]);
    },
    draft(st, cid) {
      const sample = cid === 'new' ? 'Add retry with backoff to the image uploader' : chat(st, cid).status === 'replying' ? 'Also cover the email templates' : chat(st, cid).run ? 'Is the lock still held?' : 'Run validation and open a PR';
      if (cid === 'new') st.newChat.draft = st.newChat.draft ? '' : sample;
      else setChat(st, cid, { draft: chat(st, cid).draft ? '' : sample });
    },
    'chat-send'(st, cid, id) {
      if (cid === 'new') {
        const n = st.newChat;
        if (!n.draft) return;
        control(id, st, 'Message', () => {
          const nid = 'n' + Date.now();
          st.extraChats = [{ id: nid, title: n.draft.slice(0, 34), repo: n.repo, branch: n.branch, agent: n.agent, status: 'replying', time: 'now', messages: [['user', n.draft]] }].concat(st.extraChats || []);
          st.newChat = Object.assign({}, n, { draft: '' });
          st.stacks[st.tab].pop(); st.stacks[st.tab].push('chat:' + nid);
          later(id, 2200, (s) => { const cc = chat(s, nid); setChat(s, nid, { status: 'waiting', messages: cc.messages.concat([['agent', 'I found the uploader in `upload/ImageUploader.kt`. It retries once with no delay. I can add exponential backoff with a cap of 5 attempts.']]) }); });
        }, ['chat-send', cid]);
        return;
      }
      const c = chat(st, cid);
      if (!c.draft) return;
      control(id, st, 'Message', () => {
        if (c.status === 'replying') { setChat(st, cid, { queued: (c.queued || 0) + 1, queuedText: c.draft, draft: '' }); toast(id, 'Queued. Sends after the current reply.', { icon: 'layers' }); return; }
        if (c.decision && c.decision.state === 'open' && /^retry_fix/.test(c.draft)) {
          setChat(st, cid, { draft: '', decision: Object.assign({}, c.decision, { state: 'retry_fix' }), messages: c.messages.concat([['user', c.draft], ['agent', `Recorded retry_fix for subtask ${c.decision.subtask} with your instructions. Resume the goal to run it again.`]]) });
          setRun(st, c.run, { awaitsDecision: false, needs: 'Paused', step: 'Decision recorded · resume to continue' });
          return;
        }
        const adjusting = c.proposal && c.proposal.state === 'adjusting';
        setChat(st, cid, { messages: c.messages.concat([['user', c.draft]]), draft: '', status: 'replying', suggestions: null });
        const reply = adjusting ? 'Added a criterion for theme modes. Here is the revised proposal.'
          : c.run && c.run !== 'INF-7' ? `Read ${c.run}'s records and the checkout at ${c.path || c.repo} on \`${c.branch}\`. Nothing is uncommitted there. Ask for a fix and I will make it in that checkout.`
          : c.run ? `Checked the checkout at ${c.path || c.repo} on \`${c.branch}\`. The lock is still held by tf-apply-8812; its apply is on step 41 of 52. I will tell you here when it clears.`
            : 'Validation passed: 4 checks. I opened PR #77 from `' + c.branch + '`.';
        later(id, 2000, (s) => { const cc = chat(s, cid); setChat(s, cid, Object.assign({ status: 'waiting', messages: cc.messages.concat([['agent', reply]]) }, adjusting ? { proposal: Object.assign({}, cc.proposal, { state: 'open', revised: true }) } : {})); });
      }, ['chat-send', cid]);
    },
    'chat-stop'(st, cid, id) { control(id, st, 'Stop', () => setChat(st, cid, { status: 'waiting' }), ['chat-stop', cid]); },
    suggest(st, text, id) { const cid = st.stacks[st.tab][st.stacks[st.tab].length - 1].split(':')[1]; setChat(st, cid, { draft: text }); ACTIONS['chat-send'](st, cid, id); },
    'accept-key'(st, cid) { setChat(st, cid, { accepted: true }); },
    'start-goal'(st, cid, id) {
      control(id, st, 'Start', () => { setChat(st, cid, { started: true }); toast(id, 'ATL-411 started on ' + st.machine + '.', { icon: 'play', tone: 'done', action: { label: 'Open', act: 'tab', arg: 'work' } }); }, ['start-goal', cid]);
    },
    quick(st, label) { st.newChat.draft = { Goal: 'Start a goal: ', Review: 'Review my branch', Validation: 'Run the checks', QA: 'QA this branch', PR: 'Open a PR', Verify: 'Verify ' }[label]; },
    'chat-agent'(st, a) { const [cid, ag] = a.split(':'); if (cid === 'new') st.newChat.agent = ag; else setChat(st, cid, { agent: ag }); st.sheet = null; },
    'target-repo'(st, a) { const [cid, repo] = a.split(':'); const patch = { repo, branch: D.REPOS[repo].branches[0] }; if (cid === 'new') Object.assign(st.newChat, patch); else setChat(st, cid, patch); },
    'target-branch'(st, a) { const [cid, br] = [a.slice(0, a.indexOf(':')), a.slice(a.indexOf(':') + 1)]; if (cid === 'new') st.newChat.branch = br; else setChat(st, cid, { branch: br }); st.sheet = null; },
    'queue-send'(st, cid, id) { setChat(st, cid, { queued: 0 }); st.sheet = null; toast(id, 'Sent now. The current reply stopped.', { icon: 'arrowUp' }); },
    'queue-remove'(st, cid, id) { setChat(st, cid, { queued: 0 }); st.sheet = null; toast(id, 'Removed from the queue.'); },
    'default-agent'(st, a) { st.defaultAgent = a; st.sheet = null; },
    'push-on'(st) { st.pushOn = !st.pushOn; },
    'push-titles'(st) { st.pushTitles = !st.pushTitles; },
    notif(st, a) { st.notif[a] = !st.notif[a]; },
    'make-current'(st, a) { st.machine = a; st.conn = D.MACHINES.find((m) => m.id === a).state; st.sheet = null; st.stacks.settings = []; },
    'start-plan'(st, key, id) { control(id, st, 'Start', () => toast(id, `${key} started on ${st.machine} with ${st.defaultAgent}.`, { icon: 'play', tone: 'done' }), ['start-plan', key]); },
    onboard(st, a, id) {
      st.sheet = null;
      if (st.conn === 'hostkey') st.conn = 'connected';
      st.onboard = a;
      if (a === 'check') later(id, 1800, (s) => { if (s.onboard === 'check') s.onboard = 'check-done'; });
    },
    'onboard-finish'(st, a) { st.onboard = null; st.paired = true; st.pushOn = a === 'on'; st.tab = 'needs'; },
  };

  function onClick(e) {
    const t = e.target.closest('[data-act]');
    const host = e.target.closest('[data-host]');
    if (!t || !host || t.disabled) return;
    const id = host.dataset.host;
    const p = phones[id];
    const fn = ACTIONS[t.dataset.act];
    if (!fn) return;
    e.preventDefault();
    if (p.special && !['sheet', 'close-sheet', 'toast'].includes(t.dataset.act)) { p.special = null; p.st.lock = false; }
    fn(p.st, t.dataset.arg, id);
    paint(id);
  }

  /* ---------- Gallery ---------- */
  const GALLERY = [
    { title: 'Pairing', note: 'One machine per pairing: host, phone key, host key check, compatibility check, then push permission.', phones: [
      ['Welcome', { onboard: 'welcome' }],
      ['Host and user', { onboard: 'host' }],
      ["Phone's public key", { onboard: 'key' }],
      ['Trust on first use', { onboard: 'trust' }],
      ['Checking the machine', { onboard: 'check' }],
      ['Runtime too old', { onboard: 'check-version' }],
      ['Push permission', { onboard: 'notify-ask' }],
    ] },
    { title: 'Tabs', note: 'The app reads the board over SSH on open and on pull to refresh. Needs you is derived from the board, with no read or unread state.', phones: [
      ['Needs you', {}],
      ['Work · Active', at('work')],
      ['Work · History', Object.assign(at('work'), { workSeg: 'history', histWin: '7d' })],
      ['Chats', at('chats')],
      ['Plans', at('plans')],
      ['Settings', at('settings')],
      ['Needs you · empty', { runs: { 'INF-7': { needs: null }, 'DSN-31': { needs: null }, 'PAY-88': { needs: null }, 'NEWS-12': { needs: null } } }],
    ] },
    { title: 'Run detail', note: 'Everything the desktop detail shows, in its order: chips, title, location, run actions, build strip, pause notice, run reason, incident or operator decision, metrics, subtasks with Goal finish, phase pipeline, changes, activity, then the details pane parts (Execution, Runs and fixes). Validation, Verify, monitor, plan and standalone QA runs use their own documents, as on desktop. One primary action per state sits in the bar, the rest in More. Pause after subtask, Pause now, Resume or Run again with an agent, Recover, Cancel and Confirm criteria call the same runtime controls as the desktop.', phones: [
      ['Active goal', at('work', 'detail:SKILL-512')],
      ['Active · More', Object.assign(at('work', 'detail:SKILL-512'), { sheet: 'actions:SKILL-512' })],
      ['Blocked with incident', at('needs', 'detail:INF-7')],
      ['Recover', Object.assign(at('needs', 'detail:INF-7'), { sheet: 'recover:INF-7', recover: { 'INF-7': true } })],
      ['Paused · Resume with', Object.assign(at('needs', 'detail:PAY-88'), { sheet: 'resume:PAY-88' })],
      ['Failed', at('needs', 'detail:NEWS-12')],
      ['Goal finish · QA', at('work', 'detail:ATL-402')],
      ['Verify · confirm criteria', Object.assign(at('needs', 'detail:DSN-31'), { criteria: { 'DSN-31': { 'AC-005': false } } })],
      ['Operator decision', at('needs', 'detail:SKILL-505')],
      ['Activity · Blocks filter', Object.assign(at('needs', 'detail:INF-7'), { actFilter: 'blocks', sec: { incident: true, subtasks: true, pipeline: true, changes: true } })],
      ['Execution, Runs and fixes', Object.assign(at('needs', 'detail:NEWS-12'), { sec: { activity: true, subtasks: true, pipeline: true, changes: true }, bd: { 'NEWS-12:runs': true } })],
      ['Validation · running', at('work', 'detail:ph-val-447')],
      ['Validation · passed', Object.assign(at('work', 'detail:ph-validation-news'), { sec: { 'rp-Changes made': false } })],
      ['Verify · finished', Object.assign(at('work', 'detail:op-verify-0ac'), { sec: { 'vc-op-verify-0ac4': true } })],
      ['Monitor', at('work', 'detail:op-monitor-482')],
      ['Monitor · repair', Object.assign(at('work', 'detail:op-monitor-482'), { monPick: '1:fix', sec: { 'm-cycles': true } })],
      ['Plan', at('work', 'detail:ph-plan-520')],
      ['Standalone QA', Object.assign(at('work', 'detail:ph-qa-pay'), { sec: { 'qa-QA-1': true } })],
      ['Standalone review', Object.assign(at('work', 'detail:ph-review-refunds'), { sec: { 'pr-F-001': true } })],
      ['Cancel', Object.assign(at('work', 'detail:ph-review-refunds'), { sheet: 'cancel:ph-review-refunds' })],
    ] },
    { title: 'Subtask', note: 'Tap a subtask, or the Goal finish row, for what the desktop details panel shows: Commit, Spec file, Depends on, Needed by, Validation, then a tab per phase. A phase that ran opens its card with agent, model, round, time, why it stopped, Now, what it produced, activity and a link to the full screen. Up and down page through subtasks.', phones: [
      ['Current subtask · implement running', at('work', 'detail:SKILL-512', 'subtask:SKILL-512:3')],
      ['Done subtask · review findings', Object.assign(at('work', 'detail:SKILL-512', 'subtask:SKILL-512:2'), { peek: { 'SKILL-512:2': 'review' } })],
      ['Done subtask · validate checks', Object.assign(at('work', 'detail:SKILL-512', 'subtask:SKILL-512:0'), { peek: { 'SKILL-512:0': 'validate' } })],
      ['Blocked in validate', at('needs', 'detail:INF-7', 'subtask:INF-7:1')],
      ['Failed in validate', at('needs', 'detail:NEWS-12', 'subtask:NEWS-12:2')],
      ['Not started', at('needs', 'detail:PAY-88', 'subtask:PAY-88:3')],
      ['Goal finish · QA running', at('work', 'detail:ATL-402', 'subtask:ATL-402:finish')],
      ['Goal finish · assessment', Object.assign(at('work', 'detail:ATL-402', 'subtask:ATL-402:finish'), { peek: { 'ATL-402:finish': 'goal_assessment' } })],
    ] },
    { title: 'Changes', note: 'Read-only. Scopes, files and a unified diff with published threads. Commenting stays on the desktop.', phones: [
      ['Files', at('work', 'detail:SKILL-512', 'changes:SKILL-512')],
      ['Diff with a thread', at('work', 'detail:SKILL-512', 'changes:SKILL-512', 'diff:SKILL-512:0')],
    ] },
    { title: 'Chats', note: 'Both desktop chat kinds. New task chats pick a repository and branch and start new work through start cards. Run chats belong to one run: open one from the chat button on the run, and fixes go to that run\'s checkout. A parked Verify shows its proposal card there, and an operator decision is settled there.', phones: [
      ['Agent replying, start card', at('chats', 'chat:c1')],
      ['Start card accepted', Object.assign(at('chats', 'chat:c1'), { chats: { c1: { accepted: true, status: 'waiting', queued: 0 } } })],
      ['Your turn, suggestions', at('chats', 'chat:c2')],
      ['Queue', Object.assign(at('chats', 'chat:c1'), { sheet: 'queue:c1' })],
      ['New task', at('chats', 'newchat')],
      ['Repository and branch', Object.assign(at('chats', 'newchat'), { sheet: 'target:new' })],
      ['Chat changes', Object.assign(at('chats', 'chat:c2'), { sheet: 'chat-changes:c2' })],
      ['Run chats', Object.assign(at('chats'), { chatSeg: 'runs' })],
      ['Run chat · blocked goal', at('needs', 'detail:INF-7', 'chat:rc-inf7')],
      ['Run chat · Verify proposal', at('needs', 'detail:DSN-31', 'chat:rc-dsn31')],
      ['Run chat · proposal revised', Object.assign(at('needs', 'detail:DSN-31', 'chat:rc-dsn31'), { chats: { 'rc-dsn31': { proposal: { kind: 'verify', title: 'Verify proposal · DSN-31', state: 'open', revised: true }, messages: [['agent', 'I read DSN-31 from Linear and the diff on `feat/DSN-31-figma-export`. Here is what I would audit. Nothing runs until you confirm.'], ['user', 'Also check that each theme exports as its own mode'], ['agent', 'Added a criterion for theme modes. Here is the revised proposal.']] } } })],
      ['Run chat · operator decision', at('needs', 'detail:SKILL-505', 'chat:rc-505')],
      ['Run chat · first open', Object.assign(at('work', 'detail:SKILL-512', 'chat:rc-SKILL-512'), { extraChats: [{ id: 'rc-SKILL-512', run: 'SKILL-512', kind: 'run', title: 'Chat about SKILL-512', repo: 'skill-bill', branch: 'feat/SKILL-512-worktrees', path: '~/IdeaProjects/skill-bill', agent: 'claude', status: 'idle', time: 'now', messages: [] }] })],
    ] },
    { title: 'Plans', note: 'Spec bundles from the selected project. Files are read-only; Start, Pause and Run again follow the runtime verdict.', phones: [
      ['Pending bundle', at('plans', 'plan:SKILL-520')],
      ['Active bundle', at('plans', 'plan:SKILL-512')],
      ['spec.md', at('plans', 'plan:SKILL-512', 'planfile:SKILL-512:spec.md')],
    ] },
    { title: 'Settings', note: 'Machines, usage, default agent and notifications. Desktop settings (database, Claude directories, retention, start at login, models, compaction, project, telemetry) are not on mobile.', phones: [
      ['Machine', at('settings', 'machine:studio')],
      ['Notifications', at('settings', 'notifications')],
      ['Notifications · titles hidden', Object.assign(at('settings', 'notifications'), { pushTitles: false })],
      ['Default agent', Object.assign(at('settings'), { sheet: 'default-agent' })],
    ] },
    { title: 'Connection', note: 'SSH only while the app is open. Reads keep the last snapshot on failure; controls never queue.', phones: [
      ['Connecting', { conn: 'connecting' }],
      ['Unreachable, stale data', { conn: 'unreachable' }],
      ['Control did not send', Object.assign(at('work', 'detail:SKILL-512'), { conn: 'unreachable', toast: { text: "Pause didn't reach studio. Nothing changed.", tone: 'failed', icon: 'wifi', action: { label: 'Retry', act: 'pause', arg: 'SKILL-512' } } })],
      ['Host key changed', { conn: 'hostkey' }],
      ['Key rejected', { conn: 'auth' }],
      ['Sona not installed', { conn: 'missing' }],
      ['Sona too old', { conn: 'version' }],
    ] },
    { title: 'Push', note: 'Sent by the desktop app through a small relay to Firebase. Tapping one opens the app, connects and goes to the run.', phones: [
      ['Lock screen', { lock: true }],
      ['Lock screen · titles hidden', { lock: true, pushTitles: false }],
      ['Swipe actions', {}, 'swipe'],
    ] },
  ];

  function renderGallery(root) {
    root.innerHTML = `<div class="doc-gal">${GALLERY.map((sec, si) => `<section class="doc-gal-sec" id="g-${si}"><h2>${sec.title}</h2><p>${sec.note}</p><div class="doc-gal-row">${sec.phones.map((ph, pi) => `<figure><div class="doc-gal-ph" data-g="${si}-${pi}"></div><figcaption>${ph[0]}</figcaption></figure>`).join('')}</div></section>`).join('')}</div>`;
    root.querySelectorAll('[data-g]').forEach((el) => {
      const [si, pi] = el.dataset.g.split('-').map(Number);
      const [, over, special] = GALLERY[si].phones[pi];
      const st = base(JSON.parse(JSON.stringify(over)));
      mount(el, st, special === 'swipe' ? S.swipeExample : null);
    });
  }

  /* ---------- Prototype ---------- */
  const JUMPS = [
    ['Pairing', [['Welcome', { onboard: 'welcome' }], ['Trust host key', { onboard: 'trust' }], ['Push permission', { onboard: 'notify' }]]],
    ['Needs you', [['Needs you', {}], ['Blocked goal', at('needs', 'detail:INF-7')], ['Verify criteria', at('needs', 'detail:DSN-31')], ['Paused goal', at('needs', 'detail:PAY-88')]]],
    ['Work', [['Board', at('work')], ['Active goal', at('work', 'detail:SKILL-512')], ['Subtask', at('work', 'detail:SKILL-512', 'subtask:SKILL-512:3')], ['Goal finish', at('work', 'detail:ATL-402', 'subtask:ATL-402:finish')], ['Goal QA', at('work', 'detail:ATL-402')], ['Diff', at('work', 'detail:SKILL-512', 'changes:SKILL-512', 'diff:SKILL-512:0')]]],
    ['Chats', [['Chat with start card', at('chats', 'chat:c1')], ['New task', at('chats', 'newchat')], ['Run chats', Object.assign(at('chats'), { chatSeg: 'runs' })], ['Run chat · Verify', at('needs', 'detail:DSN-31', 'chat:rc-dsn31')], ['Run chat · decision', at('needs', 'detail:SKILL-505', 'chat:rc-505')]]],
    ['Settings', [['Machine', at('settings', 'machine:studio')], ['Notifications', at('settings', 'notifications')]]],
    ['Connection', [['Unreachable', { conn: 'unreachable' }], ['Host key changed', { conn: 'hostkey' }], ['Lock screen', { lock: true }]]],
  ];
  let protoId = null;
  function renderPrototype(root) {
    root.innerHTML = `<div class="doc-proto"><div class="doc-proto-ph" id="proto"></div><aside class="doc-proto-side">
      <h1>Sona mobile</h1><p>A remote for the Sona runtime on your computer. The phone connects over SSH when you open it, reads the board, and sends the same controls the desktop sends. Goals never run on the phone.</p>
      <p class="doc-tip">Tap a composer to fill a sample draft. Controls change this prototype only.</p>
      ${JUMPS.map(([g, items]) => `<div class="doc-jump-g"><h3>${g}</h3>${items.map(([l], i) => `<button type="button" data-jump="${g}:${i}">${l}</button>`).join('')}</div>`).join('')}
    </aside></div>`;
    protoId = mount(root.querySelector('#proto'), base({}));
    root.querySelectorAll('[data-jump]').forEach((b) => b.addEventListener('click', () => {
      const [g, i] = b.dataset.jump.split(':');
      const over = JUMPS.find((x) => x[0] === g)[1][Number(i)][1];
      phones[protoId].st = base(JSON.parse(JSON.stringify(over)));
      phones[protoId].st.paired = true;
      paint(protoId);
    }));
  }

  /* ---------- Views ---------- */
  function show(view) {
    document.querySelectorAll('[data-view]').forEach((b) => b.classList.toggle('on', b.dataset.view === view));
    document.querySelectorAll('.doc-pane').forEach((p) => { p.hidden = p.id !== 'v-' + view; });
    const pane = document.getElementById('v-' + view);
    if (!pane.dataset.ready) {
      pane.dataset.ready = '1';
      if (view === 'prototype') renderPrototype(pane);
      if (view === 'screens') renderGallery(pane);
    }
  }
  document.addEventListener('click', onClick);
  document.querySelectorAll('[data-view]').forEach((b) => b.addEventListener('click', () => { location.hash = b.dataset.view; }));
  window.addEventListener('hashchange', () => show(location.hash.slice(1) || 'prototype'));
  show(['prototype', 'screens', 'scope'].includes(location.hash.slice(1)) ? location.hash.slice(1) : 'prototype');
})();
