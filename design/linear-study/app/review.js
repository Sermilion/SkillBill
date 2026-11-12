/* Changes window and Diff comments: one screen module composed only from SB components. It replaces the static Changes
 * and Diff comments views. State lives in main.js `st`: st.cw[<work>] (scope, selected file, filter, range anchor, picker)
 * and st.reviews[<work>|<scope>] (threads, composer, reply, edit, delete confirmation). Data comes from
 * DesignFlow.changesFor(work); the Diff comments catalog uses the SKILL-474 sample (DATA.REVIEW_SAMPLE). */
(function () {
  'use strict';
  const S = window.SB, D = window.DATA, esc = S.esc;
  const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
  const sum = (fs) => fs.reduce((a, f) => [a[0] + (f.add || 0), a[1] + (f.del || 0)], [0, 0]);
  const counts = ([a, d]) => `+${a} −${d}`;
  const model = (work) => window.DesignFlow.changesFor(work);

  /* ---------- scope ---------- */
  /* scope: { kind: 'all' } | { kind: 'range', from, to } | { kind: 'other' } | { kind: 'wt' } */
  const scopeKey = (sc) => (sc.kind === 'range' ? `range:${sc.from}:${sc.to}` : sc.kind);
  function cwOf(st, work) {
    st.cw = st.cw || {};
    if (!st.cw[work]) st.cw[work] = { scope: { kind: 'all' }, selected: null, query: '', anchor: null, picker: false, pq: '' };
    return st.cw[work];
  }
  function filesFor(m, sc) {
    if (sc.kind === 'all') return m.all;
    if (sc.kind === 'other') return m.other;
    if (sc.kind === 'wt') return m.working || [];
    const byPath = new Map();
    for (let i = sc.from; i <= sc.to; i++) for (const f of m.subtasks[i - 1].files) {
      const e = byPath.get(f.path) || { path: f.path, status: f.status, add: 0, del: 0, parts: [] };
      e.add += f.add; e.del += f.del; e.parts.push({ n: i, patch: f.patch }); byPath.set(f.path, e);
    }
    return [...byPath.values()].sort((a, b) => a.path.localeCompare(b.path));
  }
  function scopeLabel(m, sc) {
    if (sc.kind === 'all') return 'All changes';
    if (sc.kind === 'other') return 'Outside subtasks';
    if (sc.kind === 'wt') return 'Uncommitted';
    return sc.from === sc.to ? `${sc.from} · ${m.subtasks[sc.from - 1].name}` : `Subtasks ${sc.from}–${sc.to}`;
  }
  const inScope = (sc, n) => sc.kind === 'range' && n >= sc.from && n <= sc.to;
  const running = (m) => m.subtasks.find((s) => s.state === 'running');
  const lastPickable = (m) => (running(m) || m.subtasks.filter((s) => s.state !== 'pending').at(-1) || {}).n;
  const linesOf = (s) => s.files.reduce((a, f) => a + f.add + f.del, 0);
  const moduleOf = (p) => { const m = p.match(/^(desktop\/(?:core|feature)\/[^/]+|desktop\/agent|runtime-kotlin\/runtime-infra\/[^/]+|runtime-kotlin\/[^/]+)/); return m ? m[1] : p.split('/').slice(0, -1).join('/') || '/'; };
  const visible = (m, cw) => { const fs = filesFor(m, cw.scope); return cw.query ? fs.filter((f) => f.path.toLowerCase().includes(cw.query.toLowerCase())) : fs; };
  const hunksOf = (f) => f.hunks || window.DesignFlow.diffFile(f, f.parts ? f.parts.map((p) => p.patch) : [f.patch || '']).hunks;

  /* The strip: one ChangeStrip column per subtask, height by lines changed. */
  function strip(m, cw, o) {
    const max = Math.max(1, ...m.subtasks.map(linesOf));
    return S.ChangeStrip({ tall: o.tall, every: m.subtasks.length > 24 ? 5 : 1, label: o.label, cols: m.subtasks.map((s) => {
      const [a, d] = sum(s.files);
      return { n: s.n, state: ['done', 'running', 'pending', 'blocked', 'failed'].includes(s.state) ? s.state : 'done', h: s.state === 'pending' ? 0 : Math.max(6, Math.round(Math.sqrt(linesOf(s) / max) * 100)),
        sel: o.interactive && inScope(cw.scope, s.n), act: s.state === 'pending' ? null : o.act, arg: `${o.work}|${s.n}`,
        tip: `${s.n} · ${s.name} · ${s.state === 'pending' ? 'not started' : `${s.state === 'running' ? 'running · ' : ''}${plural(s.files.length, 'file')} · ${counts([a, d])} · ${s.sha || 'no commit recorded'}`}` };
    }) });
  }

  /* ---------- the Changes overview inside a goal's detail ---------- */
  function overview(st, it) {
    const m = model(it.id), cw = cwOf(st, it.id), run = running(m);
    const [a, d] = sum(m.all);
    const runRow = run ? S.SubtaskList({ label: 'Running subtask', rows: [S.SubtaskRow({ num: String(run.n).padStart(2, '0'), state: 'active', frac: 0.5, current: true, title: run.name, meta: [S.Label({ text: `${plural(run.files.length, 'file')} · ${counts(sum(run.files))}`, dot: false })], act: 'cw-open', arg: `${it.id}|${run.n}` })] }) : '';
    return { count: `${plural(m.all.length, 'file')} · ${counts([a, d])} · ${plural(m.subtasks.length, 'subtask')}`,
      end: [S.Button({ label: 'Open changes', icon: 'external', size: 'sm', act: 'cw-open', arg: it.id, id: 'cw-open-btn' })],
      body: `<div class="l-col g-3">${m.subtasks.length ? strip(m, cw, { work: it.id, act: 'cw-open', label: 'Lines changed per subtask; a column opens that subtask' }) : ''}${runRow}</div>` };
  }

  /* ---------- review threads ---------- */
  const STATE_LABEL = { pending: ['Pending', 'var(--accent-text)'], open: ['Open', 'var(--st-planning)'], resolved: ['Resolved', 'var(--st-done)'], declined: ['Declined', 'var(--fg-muted)'] };
  const OUTCOME = { fixed: ['Fixed', 'var(--st-done)', 'check'], refuted: ['Refuted', 'var(--fg-2)', 'ban'], skipped: ['Skipped', 'var(--fg-muted)', null], failed: ['Failed to fix', 'var(--st-failed)', null] };
  const RAIL = (ts) => { const s = ts.map((t) => t.state); return s.includes('pending') ? 'var(--accent)' : s.includes('open') ? 'var(--st-planning)' : s.includes('resolved') ? 'var(--st-done)' : 'var(--st-paused)'; };
  function reviewOf(st, key, seed) {
    st.reviews = st.reviews || {};
    if (!st.reviews[key]) st.reviews[key] = { threads: [], composer: null, editing: null, confirm: null, expanded: [], replying: null, cursor: -1, seq: 0, fresh: null };
    const r = st.reviews[key];
    if (seed && r.seededFrom !== seed.id) Object.assign(r, seed.state, { seededFrom: seed.id });
    return r;
  }
  const uid = (r) => `t${++r.seq}`;
  /* Seed for the Diff comments catalog: the sample's threads for scenario n, with ids and file paths. */
  function scenarioSeed(n) {
    const R = D.REVIEW_SAMPLE, sc = R.scenarios[n] || R.scenarios[1];
    let seq = 0;
    const threads = sc.threads.map((t) => ({ ...t, id: `t${++seq}`, file: R.files[t.file].path, comments: t.comments.map((c) => ({ ...c, id: `t${++seq}` })) }));
    return { id: `scenario-${n}`, state: { threads, seq, composer: sc.composer ? { file: R.files[sc.composer[0]].path, side: sc.composer[1], num: sc.composer[2], text: '' } : null, editing: null, confirm: null, expanded: [], replying: null, cursor: -1 } };
  }
  const agentAvatar = () => S.Avatar({ name: 'Agent', initials: D.AGENTS.claude.initials, color: D.AGENTS.claude.color, size: 'sm' });
  const youAvatar = () => S.Avatar({ name: 'You', initials: 'Y', color: 'var(--fg-muted)', size: 'sm' });
  function threadHtml(r, ctx, t) {
    const closed = t.state === 'resolved' || t.state === 'declined';
    const [word, color] = STATE_LABEL[t.state];
    const stateLabel = S.Label({ text: word, color, tint: true });
    const outdated = t.outdated ? S.Label({ text: 'Outdated', dot: false }) : '';
    const tid = `${ctx}|${t.id}`;
    if (closed && !r.expanded.includes(t.id)) {
      const first = t.comments[0], last = t.comments[t.comments.length - 1];
      const out = last.outcome ? S.Label({ text: `${OUTCOME[last.outcome][0]}${last.sha ? ` · ${last.sha}` : ''}`, color: OUTCOME[last.outcome][1], dot: false }) : '';
      return S.CommentThread({ folded: true, id: `thread-${t.id}`, state: t.state, act: 'cm-open', arg: tid, lead: S.StatusIcon(t.state === 'resolved' ? 'done' : 'canceled'), chips: [stateLabel, outdated],
        snippet: `${first.author === 'user' ? 'You' : 'Agent'}: ${esc(first.body.replace(/`/g, ''))}`, end: `${t.comments.length > 1 ? plural(t.comments.length - 1, 'reply').replace('replys', 'replies') : ''}${out}` });
    }
    const comments = t.comments.map((c, i) => {
      const editing = r.editing === c.id;
      const chips = i === 0 ? [c.sev ? S.Badge({ text: c.sev, tone: c.sev === 'major' ? 'blocked' : c.sev === 'blocker' ? 'failed' : 'neutral' }) : '', c.fid ? S.IdTag(c.fid) : '', outdated, stateLabel] : [];
      const actions = [c.author === 'user' && t.state === 'pending' && !editing ? S.IconButton({ icon: 'pencil', label: 'Edit', size: 'sm', act: 'cm-edit', arg: `${tid}|${c.id}` }) : '',
        S.IconButton({ icon: 'trash', label: i === 0 ? 'Delete thread' : 'Delete reply', size: 'sm', act: 'cm-delete', arg: `${tid}|${c.id}` }),
        i === 0 && closed ? S.IconButton({ icon: 'chevronUp', label: 'Fold', size: 'sm', act: 'cm-fold', arg: tid }) : ''];
      const out = c.outcome ? S.Label({ text: `${OUTCOME[c.outcome][0]}${c.sha ? ` · ${c.sha}` : ''}`, color: OUTCOME[c.outcome][1], tint: true, icon: OUTCOME[c.outcome][2], dot: false }) : '';
      const body = editing ? S.InlineComposer({ id: 'cm-edit-text', input: 'cm-edit', value: c.body, label: 'Edit comment', focus: true, actions: [S.Button({ label: 'Cancel', variant: 'ghost', size: 'sm', act: 'cm-edit-cancel', arg: ctx }), S.Button({ label: 'Save', variant: 'primary', size: 'sm', act: 'cm-edit-save', arg: `${tid}|${c.id}` })] }) : S.ReportText(c.body);
      return { avatar: c.author === 'user' ? youAvatar() : agentAvatar(), who: c.author === 'user' ? 'You' : 'Agent', src: c.src, when: c.when, chips, actions, outcome: out, body };
    });
    let foot;
    if (r.confirm === t.id) foot = S.MarkRow({ text: `Delete this thread and ${t.comments.length > 1 ? `its ${plural(t.comments.length - 1, 'reply').replace('replys', 'replies')}` : 'its comment'}?`, actions: [S.Button({ label: 'Keep', variant: 'ghost', size: 'sm', act: 'cm-keep', arg: ctx }), S.Button({ label: 'Delete', variant: 'danger', size: 'sm', act: 'cm-confirm', arg: tid })] });
    else if (r.replying === t.id) foot = S.InlineComposer({ id: 'cm-reply-text', input: 'cm-reply', value: r.replyText || '', placeholder: 'Reply', focus: true, hint: `${S.Kbd('⌘ ↵')} to reply`, actions: [S.Button({ label: 'Cancel', variant: 'ghost', size: 'sm', act: 'cm-reply-cancel', arg: ctx }), S.Button({ label: 'Reply', variant: 'primary', size: 'sm', act: 'cm-reply-send', arg: tid })] });
    else if (t.state === 'pending') foot = S.MarkRow({ text: 'Only you can see this until you add the review.', actions: [] });
    else if (t.state === 'open') foot = [S.Button({ label: 'Reply', icon: 'message', variant: 'ghost', size: 'sm', act: 'cm-reply', arg: tid }), '<span class="l-grow"></span>', S.Button({ label: 'Resolve', icon: 'check', size: 'sm', act: 'cm-state', arg: `${tid}|resolved` }), S.Button({ label: 'Decline', icon: 'ban', variant: 'ghost', size: 'sm', act: 'cm-state', arg: `${tid}|declined` })].join('');
    else foot = [S.Button({ label: 'Reply', icon: 'message', variant: 'ghost', size: 'sm', act: 'cm-reply', arg: tid }), '<span class="l-grow"></span>', S.Button({ label: 'Reopen', icon: 'undo', variant: 'ghost', size: 'sm', act: 'cm-state', arg: `${tid}|open` })].join('');
    return S.CommentThread({ id: `thread-${t.id}`, state: t.state, flash: r.fresh === t.id, num: t.num, original: t.outdated ? t.original : null, comments, foot });
  }
  function composerHtml(r, ctx) {
    return S.InlineComposer({ id: 'cm-new-text', input: 'cm-new', value: r.composer.text, placeholder: 'Leave a comment', focus: true, hint: 'Stays pending until you add the review',
      actions: [S.Button({ label: 'Cancel', variant: 'ghost', size: 'sm', act: 'cm-cancel', arg: ctx }), S.Button({ label: 'Add to review', variant: 'primary', size: 'sm', kbd: '⌘ ↵', act: 'cm-pending', arg: ctx, disabled: !r.composer.text.trim() })] });
  }
  /* The selected file's diff as DiffView rows, threads and the composer in slots under their lines. */
  function diffRows(r, ctx, f) {
    const hunks = hunksOf(f);
    if (!hunks.length) return [{ note: 'No text diff was recorded for this file.' }];
    const rows = [];
    hunks.forEach((h) => {
      rows.push({ hunk: h.h, src: h.src });
      h.lines.forEach(([k, o, n, text]) => {
        const side = n != null ? 'new' : 'old', num = n ?? o;
        const ts = r.threads.filter((t) => t.file === f.path && t.side === side && t.num === num);
        const comp = r.composer && r.composer.file === f.path && r.composer.side === side && r.composer.num === num;
        rows.push({ k, old: o, num: n, text, rail: ts.length ? RAIL(ts) : null, targeted: comp, line: `${ctx}|${f.path}|${side}|${num}`, add: { act: 'cm-add', arg: `${ctx}|${f.path}|${side}|${num}`, label: `Comment on line ${num}` } });
        if (ts.length || comp) rows.push({ slot: ts.map((t) => threadHtml(r, ctx, t)).join('') + (comp ? composerHtml(r, ctx) : '') });
      });
    });
    return rows;
  }

  /* ---------- the window ---------- */
  /* `view`: { work, inline, seed }. `seed` loads a Diff comments scenario. */
  function changesWindow(st, view) {
    const work = view.work, m = model(work), cw = cwOf(st, work), sc = cw.scope;
    const fs = visible(m, cw), all = filesFor(m, sc);
    if (!fs.some((f) => f.path === cw.selected)) cw.selected = fs[0] ? fs[0].path : null;
    const ctx = `${work}|${scopeKey(sc)}`, r = reviewOf(st, ctx, view.seed);
    const N = m.subtasks.length, run = running(m), [a, d] = sum(fs);
    const live = r.threads.filter((t) => t.state === 'open' || t.state === 'pending');
    const pending = r.threads.filter((t) => t.state === 'pending').length;
    const closedN = r.threads.length - live.length;
    /* toolbar: scope picker, quick scopes, filter, comment counts and navigation */
    const quick = [{ id: 'all', label: `All ${m.all.length}` }, ...(run ? [{ id: 'run', label: `Running · ${run.n}` }] : []), ...(N ? [{ id: 'other', label: `Outside subtasks ${m.other.length}`, disabled: !m.other.length }] : []), { id: 'wt', label: `Uncommitted ${(m.working || []).length}`, disabled: !(m.working || []).length }];
    const quickOn = sc.kind === 'range' && run && sc.from === run.n && sc.to === run.n ? 'run' : sc.kind;
    const pickerRows = m.subtasks.filter((s) => !cw.pq || String(s.n) === cw.pq.trim() || s.name.toLowerCase().includes(cw.pq.toLowerCase()));
    const picker = cw.picker ? S.Popover({ x: cw.pickerAt ? cw.pickerAt.x : 16, y: cw.pickerAt ? cw.pickerAt.y : 120, width: 340, role: 'dialog', cls: 'is-over', body: `${S.Input({ id: 'cw-pq', input: 'cw-pq', value: cw.pq, placeholder: 'Filter subtasks by name or number', icon: 'search' })}${pickerRows.map((s) => S.MenuItem({ lead: S.StatusIcon(s.state === 'done' ? 'done' : s.state === 'running' ? 'active' : s.state === 'pending' ? 'pending' : s.state, 0.5), html: `${S.IdTag(String(s.n))} ${esc(s.name)}`, sub: s.state === 'pending' ? 'not started' : `${plural(s.files.length, 'file')} · ${counts(sum(s.files))}`, act: s.state === 'pending' ? null : 'cw-pick', arg: `${work}|${s.n}`, disabled: s.state === 'pending', checked: inScope(sc, s.n) })).join('') || S.BoxNote({ text: 'No subtask matches.' })}${S.KeyHints([['Shift', 'click for a range'], ['↑ ↓ ↵', 'pick']])}${S.LinkButton({ label: 'All subtasks', act: 'cw-scope', arg: `${work}|all` })}` }) : '';
    const toolbar = [
      N ? S.Button({ label: `Scope: ${scopeLabel(m, sc)}`, trail: 'chevronDown', size: 'sm', act: 'cw-picker', arg: work, tipKbd: 'S', id: 'cw-picker' }) : '',
      S.Segmented({ act: 'cw-scope', value: quickOn, items: quick.map((q) => ({ ...q, arg: `${work}|${q.id}` })) }),
      S.Input({ id: 'cw-q', input: 'cw-q', value: cw.query, placeholder: 'Filter files  /', icon: 'search' }),
      '<span class="l-grow"></span>',
      r.threads.length ? S.Label({ text: `${pending ? `${pending} pending · ` : ''}${live.length - pending} open${closedN ? ` · ${closedN} closed` : ''}`, color: pending ? 'var(--accent-text)' : 'var(--st-planning)' }) : '',
      r.threads.length ? S.IconButton({ icon: 'chevronUp', label: 'Previous comment', kbd: 'P', size: 'sm', act: 'cm-nav', arg: `${ctx}|-1` }) : '',
      r.threads.length ? S.IconButton({ icon: 'chevronDown', label: 'Next comment', kbd: 'N', size: 'sm', act: 'cm-nav', arg: `${ctx}|1` }) : '',
      picker,
    ];
    /* scope line */
    const sub = sc.kind === 'all' ? m.branch || 'main...HEAD' : sc.kind === 'other' ? m.branch || 'outside subtask commits' : sc.kind === 'wt' ? 'working tree' : sc.from === sc.to ? (m.subtasks[sc.from - 1].sha ? `Commit ${m.subtasks[sc.from - 1].sha}` : 'No commit recorded') : `Recorded patches · ${sc.to - sc.from + 1} of ${N} subtasks`;
    const scope = [S.Label({ text: scopeLabel(m, sc), color: 'var(--accent-text)', dot: false }), `<span class="mono">${esc(sub)}</span>`,
      ...(sc.kind === 'range' ? [S.LinkButton({ label: `← ${sc.from > 1 ? sc.from - 1 : ''}`, act: 'cw-step', arg: `${work}|-1` }), S.LinkButton({ label: `${sc.to < lastPickable(m) ? sc.to + 1 : ''} →`, act: 'cw-step', arg: `${work}|1` }), S.LinkButton({ label: 'Show all', act: 'cw-scope', arg: `${work}|all` })] : []),
      '<span class="l-grow"></span>', S.Label({ text: `${plural(fs.length, 'file')} · ${counts([a, d])}`, dot: false })];
    /* file list grouped by module */
    const groups = {};
    fs.forEach((f) => { (groups[moduleOf(f.path)] = groups[moduleOf(f.path)] || []).push(f); });
    const files = fs.length ? Object.entries(groups).map(([g, gf]) => S.GroupHeader({ label: g, count: counts(sum(gf)) }) + gf.map((f) => {
      const ts = r.threads.filter((t) => t.file === f.path), open = ts.filter((t) => t.state === 'open' || t.state === 'pending').length;
      return S.ChatChangeRow({ path: f.path, status: f.status, ins: f.add, del: f.del, noDir: true, selected: f.path === cw.selected, act: 'cw-file', arg: `${work}|${f.path}`, multi: f.parts ? f.parts.length : 0,
        badge: ts.length ? { n: open || ts.length, tone: ts.some((t) => t.state === 'pending') ? 'pending' : open ? null : 'done', tip: `${open} open` } : null });
    }).join('')).join('') : S.Box(S.BoxNote({ text: all.length ? 'No files match the filter.' : 'No changes in this scope.' }));
    /* file header and diff */
    const f = fs.find((x) => x.path === cw.selected);
    const touched = f ? m.subtasks.filter((s) => s.files.some((x) => x.path === f.path)) : [];
    const head = f ? [`<span class="mono">${esc(f.status)}</span>`, `<span class="mono">${esc(f.path)}</span>`, S.Label({ text: counts([f.add, f.del]), dot: false }), '<span class="l-grow"></span>',
      touched.length ? S.Label({ text: `Changed by ${touched.length === 1 ? 'subtask' : 'subtasks'}`, dot: false }) : '', ...touched.map((s) => S.SubtaskChip({ num: String(s.n), act: 'cw-pick', arg: `${work}|${s.n}`, tip: `${s.n} · ${s.name}`, lead: S.StatusIcon(s.state === 'done' ? 'done' : s.state === 'running' ? 'active' : 'pending', 0.5) })),
      S.Button({ label: 'Open in editor', icon: 'external', size: 'sm', act: 'toast', arg: `Opened ${f.path.split('/').pop()} in the editor`, disabled: f.status === 'D' })] : [S.Label({ text: 'Select a file', dot: false })];
    const diff = f ? S.DiffView({ rows: diffRows(r, ctx, f) }) : '';
    const bar = pending ? S.SelectionBar({ label: 'Pending review', title: `${plural(pending, 'pending comment')}`, sub: 'only you can see them', actions: [S.Button({ label: 'Discard all', variant: 'ghost', size: 'sm', act: 'cm-discard', arg: ctx }), S.Button({ label: 'Add review', variant: 'primary', size: 'sm', kbd: '⌘ ↵', act: 'cm-review', arg: ctx })] }) : '';
    const foot = S.KeyHints([...(N ? [['S', 'pick subtask'], ['[ ]', 'previous / next subtask'], ['A', 'all']] : []), ['J K', 'file'], ['/', 'filter'], ['C', 'comment on line'], ['N P', 'next / previous comment'], ['⌘ ↵', 'add review'], ['Esc', view.inline ? 'cancel' : 'close']]);
    const body = S.ChangesFrame({ toolbar, strip: N ? strip(m, cw, { work, act: 'cw-col', interactive: true, tall: true, label: 'Subtasks; click one, shift-click or drag for a range' }) : '', scope, files, head, diff, bar, foot });
    return S.WindowSheet({ id: 'changes', inline: view.inline, title: 'Changes', sub: m.goal, body,
      actions: view.inline ? [] : [S.Label({ text: 'Hover a diff line to add a review comment', dot: false }), S.Button({ label: 'Close changes', icon: 'x', act: 'changes-close', id: 'changes-close' })] });
  }

  /* ---------- actions (merged into main.js A) ---------- */
  function actions(ctx) {
    const { st, render, toast, root } = ctx;
    const setScope = (work, sc) => { const cw = cwOf(st, work); cw.scope = sc; cw.picker = false; render(); };
    const pickRange = (work, a, b) => setScope(work, { kind: 'range', from: Math.min(a, b), to: Math.max(a, b) });
    const review = (key) => st.reviews && st.reviews[key];
    const split = (arg) => { const p = arg.split('|'); return { work: p[0], scope: p[1], rest: p.slice(2) }; };
    const ctxOf = (arg) => { const p = split(arg); return { key: `${p.work}|${p.scope}`, rest: p.rest }; };
    const thread = (arg) => { const { key, rest } = ctxOf(arg); const r = review(key); return { r, t: r && r.threads.find((x) => x.id === rest[0]), rest }; };
    const scrollToThread = (id) => { const el = root.querySelector(`#thread-${CSS.escape(id)}`); if (el) el.scrollIntoView({ block: 'center', behavior: 'smooth' }); };
    const A = {
      'cw-layer-close'() { Object.values(st.cw || {}).forEach((cw) => { cw.picker = false; }); },
      'cw-open'(arg) {
        const [work, n] = arg.split('|');
        if (n) { const cw = cwOf(st, work); cw.anchor = +n; cw.scope = { kind: 'range', from: +n, to: +n }; }
        ctx.openChanges(work, null);
      },
      'cw-scope'(arg) {
        const [work, k] = arg.split('|'), m = model(work), run = running(m);
        if (k === 'run' && run) { cwOf(st, work).anchor = run.n; return pickRange(work, run.n, run.n); }
        setScope(work, { kind: k });
      },
      'cw-col'(arg, el, e) { const [work, n] = arg.split('|'), cw = cwOf(st, work); if (e && e.shiftKey && cw.anchor) return pickRange(work, cw.anchor, +n); cw.anchor = +n; pickRange(work, +n, +n); },
      'cw-pick'(arg, el, e) { A['cw-col'](arg, el, e); },
      'cw-step'(arg) {
        const [work, d] = arg.split('|'), m = model(work), cw = cwOf(st, work), last = lastPickable(m);
        if (!last) return;
        let n = cw.scope.kind === 'range' ? (+d < 0 ? cw.scope.from - 1 : cw.scope.to + 1) : (+d < 0 ? last : 1);
        n = Math.max(1, Math.min(last, n)); cw.anchor = n; pickRange(work, n, n);
      },
      'cw-picker'(work) {
        const cw = cwOf(st, work), b = root.querySelector('#cw-picker');
        if (b) { const rc = b.getBoundingClientRect(); cw.pickerAt = { x: rc.left, y: rc.bottom + 4 }; }
        cw.picker = !cw.picker; cw.pq = ''; st.focusNext = cw.picker ? 'cw-pq' : 'cw-picker'; render();
      },
      'cw-file'(arg) { const i = arg.indexOf('|'); cwOf(st, arg.slice(0, i)).selected = arg.slice(i + 1); render(); },
      'cm-add'(arg) {
        const p = arg.split('|'), key = `${p[0]}|${p[1]}`, r = review(key);
        r.composer = { file: p[2], side: p[3], num: +p[4], text: '' }; r.replying = null; st.focusNext = 'cm-new-text'; render();
      },
      'cm-cancel'(key) { review(key).composer = null; render(); },
      'cm-pending'(key) {
        const r = review(key), text = (r.composer.text || '').trim();
        if (!text) return;
        const id = uid(r);
        r.threads.push({ id, file: r.composer.file, side: r.composer.side, num: r.composer.num, state: 'pending', comments: [{ id: uid(r), author: 'user', body: text, when: 'just now' }] });
        r.composer = null; r.fresh = id; render();
      },
      'cm-review'(key) {
        const r = review(key), p = r.threads.filter((t) => t.state === 'pending');
        if (!p.length) return;
        p.forEach((t) => { t.state = 'open'; });
        render(); toast({ icon: 'checkCircle', title: `Review added · ${p.length} ${p.length === 1 ? 'comment is' : 'comments are'} open` });
      },
      'cm-discard'(key) { const r = review(key); r.threads = r.threads.filter((t) => t.state !== 'pending'); render(); },
      'cm-state'(arg) { const { r, t, rest } = thread(arg); t.state = rest[1]; r.expanded = r.expanded.filter((x) => x !== t.id); r.fresh = t.id; render(); },
      'cm-open'(arg) { const { r, t } = thread(arg); r.expanded.push(t.id); render(); },
      'cm-fold'(arg) { const { r, t } = thread(arg); r.expanded = r.expanded.filter((x) => x !== t.id); render(); },
      'cm-edit'(arg) { const { r, rest } = thread(arg); r.editing = rest[1]; st.focusNext = 'cm-edit-text'; render(); },
      'cm-edit-cancel'(key) { review(key).editing = null; render(); },
      'cm-edit-save'(arg) {
        const { r, t, rest } = thread(arg), c = t.comments.find((x) => x.id === rest[1]);
        const el = root.querySelector('#cm-edit-text');
        c.body = (el && el.value.trim()) || c.body; r.editing = null; render();
      },
      'cm-delete'(arg) {
        const { r, t, rest } = thread(arg);
        if (t.comments[0].id === rest[1]) { if (t.state === 'pending') r.threads = r.threads.filter((x) => x !== t); else r.confirm = t.id; }
        else t.comments = t.comments.filter((c) => c.id !== rest[1]);
        render();
      },
      'cm-confirm'(arg) { const { r, t } = thread(arg); r.threads = r.threads.filter((x) => x !== t); r.confirm = null; render(); },
      'cm-keep'(key) { review(key).confirm = null; render(); },
      'cm-reply'(arg) { const { r, t } = thread(arg); r.replying = t.id; r.replyText = ''; st.focusNext = 'cm-reply-text'; render(); },
      'cm-reply-cancel'(key) { review(key).replying = null; render(); },
      'cm-reply-send'(arg) {
        const { r, t } = thread(arg), text = (r.replyText || '').trim();
        if (!text) return;
        t.comments.push({ id: uid(r), author: 'user', body: text, when: 'just now' }); r.replying = null; r.replyText = ''; render();
      },
      /* n / p: the next open or pending thread in file and line order; it may switch the selected file. */
      'cm-nav'(arg) {
        const p = arg.split('|'), key = `${p[0]}|${p[1]}`, r = review(key), m = model(p[0]), cw = cwOf(st, p[0]);
        const order = [];
        filesFor(m, cw.scope).forEach((f) => hunksOf(f).forEach((h) => h.lines.forEach(([, o, n]) => {
          const side = n != null ? 'new' : 'old', num = n ?? o;
          r.threads.filter((t) => t.file === f.path && t.side === side && t.num === num && (t.state === 'open' || t.state === 'pending')).forEach((t) => order.push(t));
        })));
        if (!order.length) return;
        r.cursor = (r.cursor + +p[2] + order.length) % order.length;
        const t = order[r.cursor];
        cw.selected = t.file; r.fresh = t.id; render(); scrollToThread(t.id);
      },
    };
    return A;
  }

  /* ---------- keyboard, text and drag (called by main.js) ---------- */
  /* The open window's work and review context, for keys and inputs. */
  function activeCtx(st) {
    const work = st.changeView ? st.changeView.work : st.view === 'comments' ? 'review-sample' : st.view === 'changes' ? 'SKILL-512' : null;
    if (!work) return null;
    const cw = cwOf(st, work);
    return { work, cw, key: `${work}|${scopeKey(cw.scope)}` };
  }
  function input(st, el, render) {
    const c = activeCtx(st), k = el.dataset.input;
    if (!c) return false;
    const r = st.reviews && st.reviews[c.key];
    if (k === 'cw-q') { c.cw.query = el.value; render(); return true; }
    if (k === 'cw-pq') { c.cw.pq = el.value; render(); return true; }
    if (k === 'cm-new' && r && r.composer) { const was = !!r.composer.text.trim(); r.composer.text = el.value; if (was !== !!el.value.trim()) render(); return true; }
    if (k === 'cm-reply' && r) { r.replyText = el.value; return true; }
    if (k === 'cm-edit') return true;
    return false;
  }
  function keys(st, e, A, hover, render) {
    const c = activeCtx(st);
    if (!c) return false;
    const r = st.reviews && st.reviews[c.key];
    const typing = /INPUT|TEXTAREA/.test(e.target.tagName), mod = e.metaKey || e.ctrlKey;
    if (e.key === 'Enter' && mod) {
      e.preventDefault();
      if (e.target.dataset.input === 'cm-new') A['cm-pending'](c.key);
      else if (e.target.dataset.input === 'cm-reply' && r) A['cm-reply-send'](`${c.key}|${r.replying}`);
      else if (e.target.dataset.input === 'cm-edit' && r) { const t = r.threads.find((x) => x.comments.some((y) => y.id === r.editing)); if (t) A['cm-edit-save'](`${c.key}|${t.id}|${r.editing}`); }
      else A['cm-review'](c.key);
      return true;
    }
    if (e.key === 'Escape') {
      if (c.cw.picker) { e.preventDefault(); c.cw.picker = false; st.focusNext = 'cw-picker'; render(); return true; }
      if (r && (r.composer || r.replying || r.editing || r.confirm)) { e.preventDefault(); r.composer = null; r.replying = null; r.editing = null; r.confirm = null; render(); return true; }
      if (typing) { e.target.blur(); return true; }
      return false;
    }
    if (typing || mod) return false;
    const m = model(c.work), fs = visible(m, c.cw), i = fs.findIndex((f) => f.path === c.cw.selected);
    const go = (fn) => { e.preventDefault(); fn(); return true; };
    if (e.key === 'j' && i < fs.length - 1) return go(() => A['cw-file'](`${c.work}|${fs[i + 1].path}`));
    if (e.key === 'k' && i > 0) return go(() => A['cw-file'](`${c.work}|${fs[i - 1].path}`));
    if (e.key === ']' && m.subtasks.length) return go(() => A['cw-step'](`${c.work}|1`));
    if (e.key === '[' && m.subtasks.length) return go(() => A['cw-step'](`${c.work}|-1`));
    if (e.key === 'a' && m.subtasks.length) return go(() => A['cw-scope'](`${c.work}|all`));
    if (e.key === 's' && m.subtasks.length) return go(() => A['cw-picker'](c.work));
    if (e.key === '/') return go(() => { const q = document.getElementById('cw-q'); if (q) q.focus(); });
    if (e.key === 'n') return go(() => A['cm-nav'](`${c.key}|1`));
    if (e.key === 'p') return go(() => A['cm-nav'](`${c.key}|-1`));
    if (e.key === 'c' && hover && hover.startsWith(`${c.key}|`)) return go(() => A['cm-add'](hover));
    return false;
  }
  /* Drag across strip columns: main.js calls dragStart on mousedown over [data-strip-n] and dragOver while it moves. */
  let drag = null;
  function dragStart(st, n, e, render) {
    const c = activeCtx(st);
    if (!c || e.shiftKey) return false;
    drag = { work: c.work, from: n };
    return true;
  }
  function dragOver(st, n, render) {
    if (!drag) return;
    const cw = cwOf(st, drag.work), m = model(drag.work), to = Math.min(n, lastPickable(m) || n);
    const sc = { kind: 'range', from: Math.min(drag.from, to), to: Math.max(drag.from, to) };
    if (scopeKey(sc) === scopeKey(cw.scope)) return;
    cw.scope = sc; cw.anchor = drag.from; render();
  }
  function dragEnd() { drag = null; }

  window.Review = { changesWindow, overview, actions, keys, input, dragStart, dragOver, dragEnd, scenarioSeed, cwOf };
})();
