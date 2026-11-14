/* Sona mobile study · component library
 * Each component returns an HTML string. Screens (app/screens.js) compose these and the layout
 * primitives in mobile.css only. Icons, status shapes, avatars, repository marks and the brand mark
 * come from the desktop library (design/linear-study/ds/components.js) so both apps draw them the same. */
(function () {
  'use strict';
  const SB = window.SB;
  const { esc, cx, Icon, StatusIcon, BrandMark } = SB;
  const join = (xs) => (Array.isArray(xs) ? xs.flat(Infinity).filter((x) => x !== false && x != null && x !== '').join('') : (xs || ''));
  const act = (a, arg) => (a ? ` data-act="${esc(a)}"${arg != null ? ` data-arg="${esc(arg)}"` : ''}` : '');

  /* ---------- Device chrome ---------- */
  function StatusBar(o) {
    return `<div class="mb-statusbar${o && o.light ? ' mb-statusbar--lock' : ''}"><span class="mb-sb-time">9:41</span><span class="mb-sb-isl"></span><span class="mb-sb-ic">${Icon('wifi', 'sm')}<i class="mb-sb-batt"><b></b></i></span></div>`;
  }
  function Phone(o) {
    return `<div class="mb-phone" data-phone="${esc(o.id)}"><div class="mb-screen">${StatusBar()}<div class="mb-body">${o.body}</div>${o.over || ''}<div class="mb-home"></div></div></div>`;
  }

  /* ---------- Navigation ---------- */
  /* Large title on tab roots (collapses on scroll in the app); compact centered title on pushed screens. */
  function NavBar(o) {
    const left = o.back ? `<button type="button" class="mb-back"${act('back')}>${Icon('chevronLeft')}<span>${esc(o.back)}</span></button>` : (o.left || '<span></span>');
    const right = `<div class="mb-nav-r">${join(o.actions)}</div>`;
    if (o.large) {
      return `<header class="mb-nav mb-nav--large"><div class="mb-nav-row">${left}${right}</div><div class="mb-nav-large"><h1>${esc(o.title)}</h1>${o.sub ? `<div class="mb-nav-sub">${o.sub}</div>` : ''}</div>${o.below || ''}</header>`;
    }
    return `<header class="mb-nav"><div class="mb-nav-row">${left}<div class="mb-nav-title">${o.title ? esc(o.title) : ''}${o.sub ? `<small>${o.sub}</small>` : ''}</div>${right}</div>${o.below || ''}</header>`;
  }
  function NavButton(o) {
    return `<button type="button" class="mb-navbtn${o.on ? ' on' : ''}" aria-label="${esc(o.label)}"${act(o.act, o.arg)}>${Icon(o.icon)}${o.dot ? '<i class="mb-navbtn-dot"></i>' : ''}</button>`;
  }
  function TabBar(o) {
    return `<nav class="mb-tabbar" aria-label="Tabs">${o.tabs.map((t) => `<button type="button" class="mb-tab${t.id === o.active ? ' on' : ''}"${act('tab', t.id)} aria-label="${esc(t.label)}">
      <span class="mb-tab-ic">${Icon(t.icon)}${t.badge ? `<b class="mb-badge">${esc(t.badge)}</b>` : ''}</span><span class="mb-tab-l">${esc(t.label)}</span></button>`).join('')}</nav>`;
  }
  function Fab(o) {
    return `<button type="button" class="mb-fab" aria-label="${esc(o.label)}"${act(o.act, o.arg)}>${Icon(o.icon || 'compose')}</button>`;
  }
  function Scroll(o) {
    return `<div class="mb-scroll${o.pad ? ' mb-scroll--pad' : ''}${o.tabs ? ' mb-scroll--tabs' : ''}${o.bar ? ' mb-scroll--bar' : ''}">${o.pull ? PullHint(o.pull) : ''}${join(o.body)}</div>`;
  }
  function PullHint(text) { return `<div class="mb-pull">${Icon('refresh', 'xs')}<span>${esc(text)}</span></div>`; }

  /* ---------- Controls ---------- */
  function Button(o) {
    const cls = cx('mb-btn', 'mb-btn--' + (o.variant || 'secondary'), o.full && 'mb-btn--full', o.size === 'sm' && 'mb-btn--sm', o.disabled && 'is-disabled');
    return `<button type="button" class="${cls}"${act(o.act, o.arg)}${o.disabled ? ' disabled' : ''}>${o.icon ? Icon(o.icon) : ''}${o.label ? `<span>${esc(o.label)}</span>` : ''}${o.trail ? Icon(o.trail, 'sm') : ''}</button>`;
  }
  function Segmented(o) {
    return `<div class="mb-seg" role="tablist">${o.options.map(([v, l, n]) => `<button type="button" role="tab" aria-selected="${v === o.value}" class="${v === o.value ? 'on' : ''}"${act(o.act, v)}>${esc(l)}${n != null ? `<span class="mb-seg-n">${esc(n)}</span>` : ''}</button>`).join('')}</div>`;
  }
  function Chip(o) {
    return `<button type="button" class="${cx('mb-chip', o.on && 'on', o.static && 'mb-chip--static')}"${act(o.act, o.arg)}>${o.lead || ''}${o.icon ? Icon(o.icon, 'sm') : ''}<span>${esc(o.text)}</span>${o.trail ? Icon(o.trail, 'xs') : ''}</button>`;
  }
  function Chips(items) { return `<div class="mb-chips">${join(items)}</div>`; }
  function Toggle(o) {
    return `<button type="button" role="switch" aria-checked="${!!o.on}" class="mb-toggle${o.on ? ' on' : ''}"${act(o.act, o.arg)}${o.disabled ? ' disabled' : ''}><i></i></button>`;
  }
  function Field(o) {
    return `<label class="mb-field"><span class="mb-field-l">${esc(o.label)}</span><span class="${cx('mb-input', o.mono && 'mono', o.error && 'is-error', !o.value && 'is-empty')}">${esc(o.value || o.placeholder || '')}</span>${o.help ? `<span class="${cx('mb-field-h', o.error && 't-failed')}">${esc(o.help)}</span>` : ''}</label>`;
  }
  function Spinner() { return '<span class="mb-spin" aria-hidden="true"></span>'; }

  /* ---------- Labels ---------- */
  function Agent(id, opts) {
    const a = window.MD.AGENTS[id];
    if (!a) return '';
    return SB.Avatar({ name: a.name, initials: a.initials, color: a.color, size: opts && opts.sm ? 'sm' : undefined });
  }
  function Repo(id) {
    const r = window.MD.REPOS[id];
    return `<span class="mb-repo">${SB.RepoIcon(r)}<span>${esc(r.name)}</span></span>`;
  }
  function Branch(name) { return `<span class="mb-branch">${Icon('branch', 'xs')}<span class="mono">${esc(name)}</span></span>`; }
  function StatePill(o) {
    const tone = (SB.STATES[o.state] || SB.STATES.pending).tone;
    return `<span class="mb-pill" style="--c:var(--st-${tone})">${StatusIcon(o.state, o.frac)}<span>${esc(o.text || SB.STATES[o.state].label)}</span></span>`;
  }
  function Tag(o) { return `<span class="${cx('mb-tag', o.mono && 'mono', o.tone && 'mb-tag--' + o.tone)}">${o.icon ? Icon(o.icon, 'xs') : ''}${esc(o.text)}</span>`; }
  function Meta(items) { return `<span class="mb-meta">${join(items.filter(Boolean).map((x, i) => (i ? '<i class="mb-meta-sep">·</i>' : '') + x))}</span>`; }

  /* ---------- Lists ---------- */
  function Group(o) {
    const head = o.title ? `<div class="mb-group-h"><span>${esc(o.title)}</span>${o.count != null ? `<span class="mb-group-n">${esc(o.count)}</span>` : ''}${o.end ? `<span class="mb-group-end">${o.end}</span>` : ''}</div>` : '';
    return `<section class="${cx('mb-group', o.inset && 'mb-group--inset')}">${head}<div class="mb-group-b">${join(o.rows)}</div>${o.foot ? `<div class="mb-group-f">${o.foot}</div>` : ''}</section>`;
  }
  /* Generic row: lead icon, title, optional second line, trailing value, chevron. 44px minimum, 56 with a second line. */
  function Row(o) {
    const tag = o.act ? 'button' : 'div';
    return `<${tag}${o.act ? ' type="button"' : ''} class="${cx('mb-row', o.sub && 'mb-row--two', o.danger && 'mb-row--danger', o.on && 'on', o.dim && 'is-dim')}"${act(o.act, o.arg)}>
      ${o.lead ? `<span class="mb-row-lead">${o.lead}</span>` : ''}
      <span class="mb-row-main"><span class="mb-row-t">${o.title}</span>${o.sub ? `<span class="mb-row-s">${o.sub}</span>` : ''}</span>
      ${o.trail != null ? `<span class="mb-row-trail">${o.trail}</span>` : ''}${o.chevron ? `<span class="mb-row-chev">${Icon('chevronRight', 'sm')}</span>` : ''}</${tag}>`;
  }
  /* Board row in Linear's mobile shape: status, key, title on the first line; reason and facts below. */
  function WorkRow(o) {
    return `<button type="button" class="${cx('mb-work', o.tone && 'mb-work--' + o.tone)}"${act(o.act, o.arg)}>
      <span class="mb-work-st">${StatusIcon(o.state, o.frac, { lg: true })}</span>
      <span class="mb-work-main">
        <span class="mb-work-top"><span class="mb-work-key mono">${esc(o.key)}</span>${o.badge ? Tag({ text: o.badge, tone: o.tone }) : ''}<span class="mb-work-time">${esc(o.time || '')}</span></span>
        <span class="mb-work-title">${esc(o.title)}</span>
        ${o.line ? `<span class="mb-work-line">${o.line}</span>` : ''}
        <span class="mb-work-meta">${join(o.meta)}</span>
      </span></button>`;
  }
  /* Swipe actions as they rest when a row is dragged left: shown statically in the States gallery. */
  function SwipeRow(o) {
    return `<div class="mb-swipe"><div class="mb-swipe-row">${o.row}</div><div class="mb-swipe-acts">${o.actions.map((a) => `<button type="button" class="mb-swipe-a" style="--c:${a.color}"${act(a.act, a.arg)}>${Icon(a.icon)}<span>${esc(a.label)}</span></button>`).join('')}</div></div>`;
  }

  /* ---------- Run detail ---------- */
  function RunHeader(o) {
    return `<div class="mb-runhead"><div class="mb-runhead-top">${StatePill({ state: o.state, frac: o.frac, text: o.stateText })}<span class="mono t-faint">${esc(o.key)}</span>${o.kind ? Tag({ text: o.kind, mono: true }) : ''}</div>
      <h2 class="mb-runhead-title">${esc(o.title)}</h2>${join(o.meta) ? `<div class="mb-runhead-meta">${join(o.meta)}</div>` : ''}</div>`;
  }
  /* The one block that answers "what is it doing now". */
  function NowCard(o) {
    return `<div class="${cx('mb-now', o.tone && 'mb-now--' + o.tone)}"><div class="mb-now-step">${o.live ? '<i class="mb-live"></i>' : ''}<span>${esc(o.step)}</span></div>
      ${o.note ? `<div class="mb-now-note">${o.note}</div>` : ''}${o.strip || ''}${o.facts ? `<div class="mb-now-facts">${join(o.facts.map(([k, v]) => `<span><em>${esc(k)}</em>${v}</span>`))}</div>` : ''}</div>`;
  }
  function PhaseStrip(o) {
    return `<div class="mb-phases" role="list">${o.phases.map(([id, st]) => `<span role="listitem" class="mb-ph mb-ph--${st}">${st === 'done' ? Icon('check', 'xs') : st === 'failed' ? Icon('x', 'xs') : st === 'blocked' ? Icon('alert', 'xs') : ''}${esc(id)}</span>`).join('<i class="mb-ph-j"></i>')}</div>`;
  }
  function BuildChip(o) {
    return `<div class="mb-build">${Spinner()}<span class="mb-build-w">Building</span><code>${esc(o.cmd)}</code><span class="tnum t-faint">${esc(o.time)}</span></div>`;
  }
  function Card(o) {
    return `<section class="${cx('mb-card', o.tone && 'mb-card--' + o.tone)}">${o.title ? `<div class="mb-card-h">${o.icon ? Icon(o.icon, 'sm') : ''}<span>${esc(o.title)}</span>${o.end ? `<span class="mb-card-end">${o.end}</span>` : ''}</div>` : ''}<div class="mb-card-b">${join(o.body)}</div></section>`;
  }
  /* A detail section that collapses, like every desktop detail section from Incident down. */
  function Section(o) {
    const head = `<button type="button" class="mb-sec-h"${act(o.toggle, o.arg)} aria-expanded="${!o.collapsed}">${o.icon ? Icon(o.icon, 'sm') : ''}<span>${esc(o.title)}</span>${o.count != null ? `<span class="mb-sec-n">${o.count}</span>` : ''}${o.end ? `<span class="mb-sec-end">${o.end}</span>` : ''}<span class="mb-sec-chev">${Icon(o.collapsed ? 'chevronRight' : 'chevronDown', 'sm')}</span></button>`;
    return `<section class="${cx('mb-sec', o.tone && 'mb-sec--' + o.tone)}">${head}${o.collapsed ? '' : `<div class="mb-sec-b">${join(o.body)}</div>`}</section>`;
  }
  function Chips2(items) { return `<div class="mb-chiprow">${join(items)}</div>`; }
  function Metrics(cells) {
    return `<div class="mb-metrics">${cells.filter(Boolean).map((c) => `<div class="mb-metric"><em>${esc(c.label)}</em><b>${c.value}${c.unit ? `<small>${esc(c.unit)}</small>` : ''}</b>${c.note ? `<span>${esc(c.note)}</span>` : ''}</div>`).join('')}</div>`;
  }
  function Reason(o) {
    return `<div class="mb-reason" style="--c:${o.color}"><div class="mb-reason-h"><span>${esc(o.label)}</span><button type="button"${act('copy', o.text)}>${Icon('copy', 'xs')}Copy reason</button></div>
      <div class="${cx('mb-reason-t', !o.open && 'is-folded')}">${esc(o.text)}</div>${o.long ? `<button type="button" class="mb-more"${act(o.toggle, o.arg)}>${o.open ? 'Show less' : 'Show more'}</button>` : ''}</div>`;
  }
  function EvidenceRow(e) {
    return `<div class="mb-ev"><span class="l-row g-2"><span class="mono t-faint">${esc(e.ref.length > 9 ? e.ref.slice(0, 8) + '…' : e.ref)}</span><button type="button" class="mb-prop-link"${act('copy', e.ref)}>Copy</button></span><span class="mb-ev-s">${esc(e.summary)}</span><span class="mb-ev-k">${esc(e.kind)}</span></div>`;
  }
  /* A total split by key (Runs, Fixes, Relaunches); taps open the split. */
  function Breakdown(o) {
    const total = Object.values(o.map).reduce((a, b) => a + b, 0);
    const rows = Object.entries(o.map).filter(([, n]) => n).sort((a, b) => b[1] - a[1]);
    return `<button type="button" class="mb-bd"${act(o.act, o.arg)}><span class="mb-bd-l">${esc(o.label)}</span><span class="mb-bd-v tnum">${total}</span>${rows.length > 1 ? Icon(o.open ? 'chevronDown' : 'chevronRight', 'xs') : ''}</button>${o.open && rows.length > 1 ? `<div class="mb-bd-rows">${rows.map(([k, n]) => `<span><em>${esc(k)}</em><b class="tnum">${n}</b></span>`).join('')}</div>` : ''}`;
  }
  /* Activity timeline: one segment per subtask group, width by time; a tap opens that group. */
  function Timeline(o) {
    return `<div class="mb-tl">${o.groups.map((g) => `<button type="button" class="mb-tl-s mb-tl-s--${g.state}" style="flex:${g.weight}"${act(g.act, g.arg)} aria-label="Open group"></button>`).join('')}</div>`;
  }
  function Fold(o) {
    return `<div class="mb-fold"><button type="button" class="mb-fold-h"${act(o.act, o.arg)} aria-expanded="${!!o.open}">${Icon(o.open ? 'chevronDown' : 'chevronRight', 'xs')}${o.lead}<span class="mb-fold-m"><span class="mb-fold-t">${esc(o.title)}${o.note ? `<em>${esc(o.note)}</em>` : ''}</span><span class="mb-fold-s">${esc(o.meta)}</span></span></button>${o.open ? `<div class="mb-fold-b">${join(o.body)}</div>` : ''}</div>`;
  }
  /* ---------- Run documents (validation, Verify, monitor, plan, QA) ---------- */
  function Badge(o) { return `<span class="mb-badge2 mb-badge2--${o.tone || 'faint'}">${esc(o.text)}</span>`; }
  function SegBar(segs) { return `<div class="mb-segbar">${segs.map((t) => `<i class="is-${t}"></i>`).join('')}</div>`; }
  /* Outcome card: label line, the verdict or head, text, the time, then metric cells that jump to their section. */
  function Outcome(o) {
    const cells = (o.cells || []).filter(Boolean).map((c) => `<${c.act ? 'button type="button"' : 'div'} class="mb-oc-cell"${c.act ? act(c.act, c.arg) : ''}><em>${esc(c.label)}</em><b class="${c.tone ? 't-' + c.tone : ''}">${c.value}</b>${c.sub ? `<span>${esc(c.sub)}</span>` : ''}${c.bar ? SegBar(c.bar) : ''}</${c.act ? 'button' : 'div'}>`).join('');
    return `<section class="mb-oc mb-oc--${o.tone || 'neutral'}"><div class="mb-oc-top"><span class="mb-oc-label">${o.live ? '<i class="mb-live"></i><b>LIVE</b>' : ''}${o.label}</span>${o.when ? `<span class="mb-oc-when"><b class="tnum">${esc(o.when)}</b><em>${esc(o.whenNote || '')}</em></span>` : ''}</div>
      ${o.verdict ? `<div class="mb-oc-verdict ${o.vtone ? 't-' + o.vtone : ''}">${esc(o.verdict)}</div>` : ''}${o.head ? `<div class="mb-oc-head">${o.head}</div>` : ''}${o.text ? `<p class="mb-oc-text">${o.text}</p>` : ''}${join(o.extra)}${o.actions ? `<div class="mb-oc-acts">${join(o.actions)}</div>` : ''}${cells ? `<div class="mb-oc-cells">${cells}</div>` : ''}</section>`;
  }
  /* An expandable row: validation checks, Verify criteria and steps, report parts, QA items, monitor checks. */
  function ExRow(o) {
    return `<div class="${cx('mb-ex', o.open && 'is-open', o.flag && 'is-flag')}"><button type="button" class="mb-ex-h"${act(o.act, o.arg)} aria-expanded="${!!o.open}">${o.lead || ''}<span class="mb-ex-m"><span class="mb-ex-t">${o.title}</span>${o.sub ? `<span class="mb-ex-s">${o.sub}</span>` : ''}</span>${o.end ? `<span class="mb-ex-end">${o.end}</span>` : ''}<span class="mb-row-chev">${Icon(o.open ? 'chevronDown' : 'chevronRight', 'sm')}</span></button>${o.open ? `<div class="mb-ex-b">${join(o.body)}</div>` : ''}</div>`;
  }
  const NODE = { done: 'done', warn: 'blocked', bad: 'failed', skip: 'skipped', stop: 'canceled', wait: 'paused', run: 'active', pend: 'pending', cut: 'stale' };
  function StepNode(state) { return StatusIcon(NODE[state] || 'pending', 0.5); }
  function CycleGrid(o) {
    const head = `<div class="mb-cg-r mb-cg-h"><span>Cycle</span>${o.cols.map((c) => `<span>${esc(c)}</span>`).join('')}</div>`;
    const rows = o.rows.map((r) => `<div class="mb-cg-r"><span class="mb-cg-n tnum">${r.n}</span>${r.cells.map((c) => (c ? `<button type="button" class="${cx('mb-cg-c', 'is-' + (c.tone || 'pending'), c.on && 'on')}"${act(o.act, r.n + ':' + c.key)}><b>${esc(c.word)}</b>${c.sub ? `<em>${esc(c.sub)}</em>` : ''}${c.bar ? SegBar(c.bar) : ''}</button>` : `<span class="mb-cg-c is-empty"><em>${esc(r.empty || '—')}</em></span>`)).join('')}</div>`).join('');
    return `<div class="mb-cg">${head}${rows}</div>`;
  }
  function StageStrip(o) {
    return `<div class="mb-stages">${o.stages.map((g) => `<div class="mb-stage is-${g.state}">${StepNode(g.state)}<span class="mb-stage-m"><b>${esc(g.label)}</b><em class="mono">${esc(g.id)}</em></span><span class="mb-stage-t tnum">${esc(g.time || '')}</span></div>`).join('')}</div>`;
  }
  function FileChips(files) { return `<div class="mb-fchips">${files.map((f) => `<button type="button" class="mb-fchip"${act('toast', 'Opens ' + f + ' from the machine.')}>${Icon(/\.(png|jpg|mp4)$/.test(f) ? 'image' : 'file', 'xs')}<span class="mono">${esc(f)}</span></button>`).join('')}</div>`; }
  function EventLine(o) {
    return `<div class="mb-evl"><span class="mb-evl-t">${esc(o.ago || '')}</span><span class="mb-evl-lead">${o.lead}</span><span class="mb-evl-x">${o.html}</span></div>`;
  }
  function KV(rows) { return `<dl class="mb-kv">${rows.map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${v}</dd></div>`).join('')}</dl>`; }
  function Para(text, o) { return `<p class="${cx('mb-p', o && o.muted && 't-muted', o && o.mono && 'mono mb-p--mono', o && o.sm && 'mb-p--sm')}">${o && o.html ? text : esc(text)}</p>`; }
  /* Subtask rows and the final Goal finish row open the Subtask screen (desktop: select the row, open the details panel). */
  function SubtaskRow(o) {
    const trail = `${o.sha ? `<span class="mono mb-sub-sha">${esc(o.sha)}</span>` : ''}${o.current ? Tag({ text: o.phase, mono: true }) : ''}${o.note ? `<span class="mb-sub-note">${esc(o.note)}</span>` : ''}`;
    return `<button type="button" class="${cx('mb-sub', o.current && 'is-current', o.finish && 'mb-sub--finish')}"${act(o.act, o.arg)}>${o.finish ? Icon('flag', 'sm') : StatusIcon(o.state, 0.5)}<span class="mb-sub-n tnum">${o.finish ? '' : o.n}</span><span class="mb-sub-t">${esc(o.title)}</span>${trail}<span class="mb-row-chev">${Icon('chevronRight', 'sm')}</span></button>`;
  }
  /* Phase tabs: a phase that ran opens its card; phases that have not run stay inert. */
  function PhaseTabs(o) {
    return `<div class="mb-ptabs" role="tablist">${o.phases.map(([id, st]) => {
      const ran = st !== 'pending';
      const ic = st === 'done' ? Icon('check', 'xs') : st === 'failed' ? Icon('x', 'xs') : st === 'blocked' ? Icon('alert', 'xs') : st === 'active' ? '<i class="mb-live mb-live--sm"></i>' : '';
      return `<button type="button" role="tab" aria-selected="${id === o.value}" class="${cx('mb-ptab', 'mb-ptab--' + st, id === o.value && 'on')}"${ran ? act(o.act, o.arg + ':' + id) : ' disabled'}>${ic}<span>${esc(id)}</span></button>`;
    }).join('')}</div>`;
  }
  function Hint(o) { return `<div class="${cx('mb-hint', o.tone && 'mb-hint--' + o.tone)}">${Icon(o.icon || 'alert', 'sm')}<span>${esc(o.text)}</span></div>`; }
  const CHECK_TONE = { ok: 'done', bad: 'failed', run: 'active', wait: 'pending' };
  function PeekBlock(b) {
    if (b.kind === 'signal') return `<div class="mb-pk-sig">${b.live ? '<i class="mb-live"></i>' : Icon('list', 'sm')}<span>${esc(b.text)}</span>${b.ago ? `<em>${esc(b.ago)}</em>` : ''}</div>`;
    if (b.kind === 'edit') return `<div class="mb-pk-sig">${Icon('pencil', 'sm')}<span>Edited <span class="mono">${esc(b.path)}</span> <span class="t-done">+${b.ins}</span> <span class="t-failed">−${b.del}</span></span><em>${esc(b.ago)}</em></div>`;
    if (b.kind === 'cmd') return `<div class="mb-pk-cmd"><span class="t-faint">$</span> ${esc(b.text)}</div>`;
    if (b.kind === 'cells') return `<div class="mb-pk-cells">${b.cells.map((c) => `<i class="${c ? 'is-' + c : ''}"></i>`).join('')}</div>`;
    if (b.kind === 'text') return `<p class="mb-pk-text">${esc(b.text)}</p>`;
    if (b.kind === 'stats') return `<div class="mb-pk-stats">${b.items.map(([v, l, t]) => `<span><b class="${t === 'ok' ? 't-done' : t === 'bad' ? 't-failed' : ''}">${esc(v)}</b><em>${esc(l)}</em></span>`).join('')}</div>`;
    if (b.kind === 'out') return `<div class="mb-pk-out">${b.items.map((x) => `<${x.act ? 'button type="button"' : 'div'} class="mb-pk-o"${x.act ? act(x.act, x.arg) : ''}>${Icon(x.icon, 'sm')}<span class="${x.mono ? 'mono' : ''}">${esc(x.text)}</span>${x.end ? `<em>${esc(x.end)}</em>` : ''}</${x.act ? 'button' : 'div'}>`).join('')}</div>`;
    if (b.kind === 'checks') return `<div class="mb-pk-checks">${b.rows.map(([name, st, time, cmd]) => `<div class="mb-pk-ck">${StatusIcon(CHECK_TONE[st] || 'pending', 0.5)}<span class="mb-pk-ck-m"><span>${esc(name)}</span>${cmd ? `<span class="mono">${esc(cmd)}</span>` : ''}</span>${time ? `<em class="${st === 'bad' ? 't-failed' : ''}">${esc(time)}</em>` : ''}</div>`).join('')}</div>`;
    if (b.kind === 'finds') return `<div class="mb-pk-finds">${b.rows.map(([sev, text, st]) => `<div class="mb-pk-f"><span class="mb-pk-f-top"><span class="mono">${esc(sev)}</span><span class="${st === 'open' ? 't-blocked' : st === 'fixed' ? 't-done' : 't-faint'}">${esc(st[0].toUpperCase() + st.slice(1))}</span></span><span>${esc(text)}</span></div>`).join('')}</div>`;
    return '';
  }
  /* One phase card (desktop PhasePeek): header, why it stopped, Now, what it produced, activity, a button to the full screen. */
  function PhaseCard(c) {
    const tone = c.word === 'Blocked' ? 'blocked' : c.word === 'Failed' ? 'failed' : c.now ? 'active' : 'done';
    const word = c.word || (c.now ? 'Running' : 'Done');
    const head = `<div class="mb-pk-h"><div class="mb-pk-h1">${StatusIcon(tone === 'done' ? 'done' : tone, 0.5, { lg: true })}<strong>${esc(c.title)}</strong><span class="mono t-faint">${esc(c.id)}</span><span class="mb-pk-word" style="color:var(--st-${tone})">${esc(word)}</span></div>
      <div class="mb-pk-h2">${Agent(c.agent, { sm: true })}<span>${esc(c.agent)}</span><span class="mono">${esc(c.model)}</span>${c.round ? `<span>· ${esc(c.round)}</span>` : ''}</div>
      <div class="mb-pk-h2"><span>${esc(c.when)}</span><span>· ${esc(c.dur)}</span></div></div>`;
    const part = (title, end, blocks) => (blocks && blocks.length ? `<div class="mb-pk-part"><div class="mb-pk-ph"><span>${esc(title)}</span>${end ? `<em>${esc(end)}</em>` : ''}</div>${blocks.map(PeekBlock).join('')}</div>` : '');
    const acts = c.activity && c.activity.length ? `<div class="mb-pk-part"><div class="mb-pk-ph"><span>Activity</span></div>${c.activity.slice(-5).map(([t, x, tn]) => `<div class="mb-pk-ev"><span class="tnum">${esc(t)}</span><i class="${tn ? 'is-' + tn : ''}"></i><span>${esc(x)}</span></div>`).join('')}</div>` : '';
    return `<section class="mb-pk mb-pk--${tone}">${head}${c.why ? `<div class="mb-pk-why mb-pk-why--${tone}">${esc(c.why)}</div>` : ''}${part(c.now && c.word === 'Blocked' ? 'Waiting on' : 'Now', null, c.now)}${part(c.madeTitle || 'Produced', c.madeEnd, c.made)}${acts}${c.open ? `<div class="mb-pk-foot">${Button({ label: c.open.label, trail: 'chevronRight', full: true, act: c.open.act, arg: c.open.arg })}</div>` : ''}</section>`;
  }
  function ActivityRow(o) {
    return `<div class="mb-act"><span class="mb-act-time tnum">${esc(o.time)}</span><span class="mb-act-dot"></span><span class="mb-act-b"><span class="mono mb-act-ph">${esc(o.phase)}</span><span>${esc(o.text)}</span></span></div>`;
  }
  function ChangeSummary(o) {
    return `<button type="button" class="mb-changes"${act(o.act, o.arg)}>${Icon('diff')}<span class="mb-changes-t">Changes</span><span class="t-muted">${o.files} files</span><span class="t-done tnum">+${o.add}</span><span class="t-failed tnum">−${o.del}</span><span class="mb-row-chev">${Icon('chevronRight', 'sm')}</span></button>`;
  }
  function Checkbox(o) {
    return `<button type="button" role="checkbox" aria-checked="${!!o.on}" class="mb-check${o.on ? ' on' : ''}"${act(o.act, o.arg)}>${o.on ? Icon('check', 'xs') : ''}</button>`;
  }
  function Criterion(o) {
    return `<div class="mb-crit">${o.check != null ? Checkbox({ on: o.check, act: o.act, arg: o.arg }) : ''}<span class="mono mb-crit-ref">${esc(o.ref)}</span><span class="mb-crit-t">${esc(o.text)}</span></div>`;
  }
  function Finding(o) {
    return `<div class="mb-find"><span class="mb-find-dot"></span><span class="mb-find-b"><span class="mb-find-top"><span class="mono">${esc(o.ref)}</span><span class="t-blocked">Open</span></span><span class="mb-find-t">${esc(o.title)}</span><span class="mb-find-s">${esc(o.text)}</span></span></div>`;
  }
  /* Sticky footer with the one primary action the runtime allows, plus a More button for the rest. */
  function ActionBar(o) {
    return `<div class="mb-actionbar">${o.note ? `<div class="mb-actionbar-note">${o.note}</div>` : ''}<div class="mb-actionbar-row">${join(o.buttons)}</div></div>`;
  }

  /* ---------- Overlays ---------- */
  function Sheet(o) {
    return `<div class="mb-scrim"${act('close-sheet')}></div><div class="mb-sheet" role="dialog" aria-label="${esc(o.title || 'Sheet')}"><div class="mb-grab"></div>
      ${o.title ? `<div class="mb-sheet-h"><div><div class="mb-sheet-t">${esc(o.title)}</div>${o.sub ? `<div class="mb-sheet-s">${o.sub}</div>` : ''}</div>${o.close === false ? '' : `<button type="button" class="mb-sheet-x" aria-label="Close"${act('close-sheet')}>${Icon('x', 'sm')}</button>`}</div>` : ''}
      <div class="mb-sheet-b">${join(o.body)}</div>${o.foot ? `<div class="mb-sheet-f">${join(o.foot)}</div>` : ''}</div>`;
  }
  function SheetOption(o) {
    return `<button type="button" class="${cx('mb-opt', o.danger && 'mb-opt--danger', o.on && 'on', o.disabled && 'is-disabled')}"${o.disabled ? ' disabled' : ''}${act(o.act, o.arg)}>
      <span class="mb-opt-ic">${o.lead || Icon(o.icon)}</span><span class="mb-opt-m"><span class="mb-opt-t">${esc(o.label)}</span>${o.desc ? `<span class="mb-opt-d">${esc(o.desc)}</span>` : ''}</span>${o.on ? `<span class="mb-opt-on">${Icon('check', 'sm')}</span>` : ''}</button>`;
  }
  function Toast(o) {
    return `<div class="${cx('mb-toast', o.tone && 'mb-toast--' + o.tone)}" role="status">${o.icon ? Icon(o.icon, 'sm') : ''}<span>${esc(o.text)}</span>${o.action ? `<button type="button"${act(o.action.act, o.action.arg)}>${esc(o.action.label)}</button>` : ''}</div>`;
  }
  /* Connection state under the nav bar. Data stays on screen with the time it was read. */
  function Banner(o) {
    return `<div class="${cx('mb-banner', 'mb-banner--' + (o.tone || 'info'))}" role="status">${o.spin ? Spinner() : Icon(o.icon || 'info', 'sm')}<span class="mb-banner-t">${o.text}</span>${o.action ? `<button type="button"${act(o.action.act, o.action.arg)}>${esc(o.action.label)}</button>` : ''}</div>`;
  }
  /* Stand-in for the OS permission dialog. */
  function SystemAlert(o) {
    return `<div class="mb-scrim mb-scrim--sys"></div><div class="mb-sysalert" role="alertdialog"><div class="mb-sysalert-b"><strong>${esc(o.title)}</strong><span>${esc(o.body)}</span></div>
      <div class="mb-sysalert-a">${o.actions.map((a) => `<button type="button"${act(a.act, a.arg)}${a.bold ? ' class="b"' : ''}>${esc(a.label)}</button>`).join('')}</div></div>`;
  }

  /* ---------- Pairing ---------- */
  function Hero(o) {
    return `<div class="mb-hero"><span class="mb-hero-mark">${o.icon ? Icon(o.icon, 'lg') : BrandMark(o.size || 44)}</span><h1>${esc(o.title)}</h1>${o.body ? `<p>${esc(o.body)}</p>` : ''}</div>`;
  }
  function Code(o) {
    return `<div class="mb-code"><code>${esc(o.text)}</code>${o.copy ? `<button type="button" aria-label="Copy"${act('copy', o.copy)}>${Icon('copy', 'sm')}</button>` : ''}</div>`;
  }
  function Fingerprint(o) {
    const s = o.value.replace('SHA256:', '');
    const groups = s.match(/.{1,11}/g) || [s];
    return `<div class="${cx('mb-fp', o.tone && 'mb-fp--' + o.tone)}">${o.label ? `<span class="mb-fp-l">${esc(o.label)}</span>` : ''}<span class="mb-fp-alg mono">SHA256 · ED25519</span><span class="mb-fp-v mono">${groups.map(esc).join('<br>')}</span></div>`;
  }
  function CheckStep(o) {
    const ic = o.state === 'done' ? StatusIcon('done') : o.state === 'failed' ? StatusIcon('failed') : o.state === 'active' ? Spinner() : StatusIcon('pending');
    return `<div class="${cx('mb-step', 'is-' + o.state)}">${ic}<span class="mb-step-t">${esc(o.text)}</span>${o.note ? `<span class="mb-step-n">${o.note}</span>` : ''}</div>`;
  }
  function Steps(n, of) {
    return `<div class="mb-steps" aria-label="Step ${n} of ${of}">${Array.from({ length: of }, (_, i) => `<i class="${i < n ? 'on' : ''}"></i>`).join('')}</div>`;
  }
  function MachineCard(o) {
    const tone = { connected: 'done', connecting: 'planning', unreachable: 'pending', auth: 'failed', hostkey: 'failed', missing: 'blocked', version: 'blocked' }[o.state];
    const word = { connected: 'Connected', connecting: 'Connecting', unreachable: 'Unreachable', auth: 'Key rejected', hostkey: 'Host key changed', missing: 'Sona not found', version: 'Update Sona' }[o.state];
    return `<button type="button" class="mb-machine"${act(o.act, o.arg)}><span class="mb-machine-ic">${Icon('monitor')}<i style="--c:var(--st-${tone})"></i></span>
      <span class="mb-machine-m"><span class="mb-machine-t">${esc(o.name)}${o.current ? Tag({ text: 'Current' }) : ''}</span><span class="mb-machine-s mono">${esc(o.user)}@${esc(o.host)}${o.port !== 22 ? ':' + o.port : ''}</span>
      <span class="mb-machine-s"><span style="color:var(--st-${tone})">${word}</span> · ${esc(o.updated)}</span></span><span class="mb-row-chev">${Icon('chevronRight', 'sm')}</span></button>`;
  }

  /* ---------- Usage ---------- */
  function usageColor(used) {
    const left = Math.max(0, Math.min(100, 100 - used));
    return left >= 50 ? `color-mix(in oklab, var(--st-done) ${(left - 50) * 2}%, var(--data-1))` : `color-mix(in oklab, var(--data-1) ${left * 2}%, var(--st-failed))`;
  }
  function UsageBlock(o) {
    const head = `<div class="mb-usage-h">${Agent(o.agent)}<span>${esc(o.provider)}</span>${o.isDefault ? Tag({ text: 'Default' }) : ''}</div>`;
    if (!o.windows) return `<div class="mb-usage">${head}<div class="mb-usage-none">${esc(o.message)}</div></div>`;
    return `<div class="mb-usage">${head}${o.windows.map((w) => `<div class="mb-usage-w"><div class="mb-usage-top"><span>${esc(w.label)}</span><span class="tnum" style="color:${usageColor(w.used)}">${w.used}%</span></div>
      <div class="mb-usage-bar"><i style="width:${w.used}%;background:${usageColor(w.used)}"></i></div><div class="mb-usage-reset">Resets in ${esc(w.resetIn)} · ${esc(w.resetAt)}</div></div>`).join('')}</div>`;
  }

  /* ---------- Changes ---------- */
  function FileRow(o) {
    const tone = { A: 'done', M: 'active', D: 'failed' }[o.status];
    return `<button type="button" class="mb-file"${act(o.act, o.arg)}><span class="mb-file-st mono" style="color:var(--st-${tone})">${esc(o.status)}</span><span class="mb-file-m"><span class="mb-file-n">${esc(o.name)}</span><span class="mb-file-d mono">${esc(o.dir)}</span></span>
      <span class="mb-file-c tnum"><span class="t-done">+${o.add}</span> <span class="t-failed">−${o.del}</span></span></button>`;
  }
  /* Unified diff, read-only. Long lines scroll sideways inside the code block; the page never does. */
  function Diff(o) {
    const rows = o.lines.map(([k, a, b, t], i) => {
      const line = k === 'hunk'
        ? `<div class="mb-dl mb-dl--hunk"><span class="mb-dl-n"></span><span class="mb-dl-c">${esc(t)}</span></div>`
        : `<div class="mb-dl mb-dl--${k}"><span class="mb-dl-n tnum">${k === 'del' ? a : b}</span><span class="mb-dl-s">${k === 'add' ? '+' : k === 'del' ? '−' : ''}</span><span class="mb-dl-c">${esc(t) || ' '}</span></div>`;
      return line + (o.thread && o.thread.after === i ? Thread(o.thread) : '');
    });
    return `<div class="mb-diff"><div class="mb-diff-in">${rows.join('')}</div></div>`;
  }
  function Thread(t) {
    return `<div class="mb-thread"><div class="mb-thread-m"><span class="mb-thread-a">${esc(t.author)}</span><span class="t-faint">${esc(t.time)}</span></div><div class="mb-thread-t">${esc(t.text)}</div>
      <div class="mb-thread-m">${Agent(t.reply[0], { sm: true })}<span class="mb-thread-a">${esc(t.reply[0])}</span></div><div class="mb-thread-t">${esc(t.reply[1])}</div>
      <div class="mb-thread-ro">${Icon('eye', 'xs')}Read-only on mobile. Reply on the desktop.</div></div>`;
  }
  function Pager(o) {
    return `<div class="mb-pager"><button type="button"${act(o.act, o.prev)}${o.prev == null ? ' disabled' : ''} aria-label="Previous file">${Icon('chevronUp')}</button>${o.compact ? '' : `<span class="tnum">${o.index + 1} of ${o.total}</span>`}<button type="button"${act(o.act, o.next)}${o.next == null ? ' disabled' : ''} aria-label="Next file">${Icon('chevronDown')}</button></div>`;
  }

  /* ---------- Chats ---------- */
  function ChatRow(o) {
    const st = o.status === 'replying' ? `<span class="mb-chat-st t-accent">${Spinner()}Replying</span>` : o.status === 'waiting' ? '<span class="mb-chat-st"><i class="mb-chat-dot"></i>Your turn</span>' : '';
    return `<button type="button" class="mb-chat"${act(o.act, o.arg)}><span class="mb-chat-av">${Agent(o.agent)}</span><span class="mb-chat-m">
      <span class="mb-chat-top"><span class="mb-chat-t">${esc(o.title)}</span><span class="mb-chat-time">${esc(o.time)}</span></span>
      <span class="mb-chat-last">${esc(o.last)}</span><span class="mb-chat-meta">${o.run ? `<span class="mb-chat-run">${StatusIcon(o.run.state, o.run.frac)}<span class="mono">${esc(o.run.key)}</span></span>` : o.repo}${st}${o.changes ? `<span class="mb-chat-ch tnum">${o.changes.files} files <span class="t-done">+${o.changes.add}</span> <span class="t-failed">−${o.changes.del}</span></span>` : ''}</span></span></button>`;
  }
  const md = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>');
  function Message(o) {
    if (o.role === 'user') return `<div class="mb-msg mb-msg--user"><div class="mb-msg-b">${md(o.text)}</div></div>`;
    return `<div class="mb-msg mb-msg--agent"><div class="mb-msg-h">${Agent(o.agent, { sm: true })}<span>${esc(o.agent)}</span></div><div class="mb-msg-b">${md(o.text)}</div></div>`;
  }
  function Typing(agent) { return `<div class="mb-msg mb-msg--agent"><div class="mb-msg-h">${Agent(agent, { sm: true })}<span>${esc(agent)}</span></div><div class="mb-typing"><i></i><i></i><i></i></div></div>`; }
  /* The app's start card: the run does not start until the operator taps Start. */
  function StartCard(o) {
    return `<div class="mb-start"><div class="mb-start-h">${Icon('target', 'sm')}<span class="mono">phase:${esc(o.phase)}</span></div><div class="mb-start-t">${esc(o.title)}</div>
      <div class="mb-start-k"><span>Issue key</span><span class="mono">${esc(o.key)}</span>${o.accepted ? Tag({ text: 'Accepted', tone: 'done' }) : ''}</div>
      <div class="mb-start-a">${o.started ? `<span class="t-done mb-start-ok">${Icon('check', 'sm')}Started on studio</span>` : `${Button({ label: o.accepted ? 'Start goal' : 'Use ' + o.key, variant: 'primary', act: o.accepted ? 'start-goal' : 'accept-key', arg: o.chat })}`}</div></div>`;
  }
  /* Run chat context: which run, its state, and where fixes land. Taps open the run. */
  function RunContext(o) {
    return `<button type="button" class="mb-runctx"${act(o.act, o.arg)}>${StatusIcon(o.state, o.frac, { lg: true })}<span class="mb-runctx-m"><span class="mb-runctx-t"><span class="mono">${esc(o.key)}</span> ${esc(o.title)}</span><span class="mb-runctx-s">Fixes go to ${esc(o.path)} on <span class="mono">${esc(o.branch)}</span></span></span><span class="mb-row-chev">${Icon('chevronRight', 'sm')}</span></button>`;
  }
  /* A proposal card in a run chat (parked Verify, PR review fix): only the operator's Confirm, Adjust or Dismiss continues it. */
  function ProposalCard(o) {
    const done = o.state !== 'open' && o.state !== 'adjusting';
    const word = { confirmed: ['Confirmed', 'done'], dismissed: ['Dismissed', 'pending'], adjusting: ['Waiting for your change', 'accent'], open: ['Waiting for you', 'accent'] }[o.state];
    return `<div class="mb-start mb-prop${done ? ' is-done' : ''}"><div class="mb-start-h">${Icon('flag', 'sm')}<span>${esc(o.title)}</span><span class="mb-prop-state t-${word[1]}">${esc(word[0])}</span></div>
      ${o.revised ? `<div class="mb-ex-s">Revised after your adjustment</div>` : ''}<div class="mb-prop-list">${o.items.map(([ref, text, off]) => `<div class="mb-prop-i${off ? ' is-off' : ''}"><span class="mono">${esc(ref)}</span><span>${esc(text)}</span></div>`).join('')}</div>
      ${o.note ? `<div class="mb-ex-s">${esc(o.note)}</div>` : ''}${done ? '' : `<div class="mb-prop-a">${Button({ label: 'Confirm', variant: 'primary', act: o.act, arg: o.chat + ':confirm' })}${Button({ label: 'Adjust', act: o.act, arg: o.chat + ':adjust' })}${Button({ label: 'Dismiss', variant: 'ghost', act: o.act, arg: o.chat + ':dismiss' })}</div>`}</div>`;
  }
  /* Operator decision after a no-change subtask: the choice goes to the agent, which records it with sona goal operator-decision. */
  function DecisionCard(o) {
    const opts = [['accept_and_advance', 'Accept and advance', 'Mark the subtask done and move on.'], ['retry_fix', 'Retry with instructions', 'Run the subtask again. Write the instructions in the composer.'], ['abandon_subtask', 'Abandon subtask', 'Skip it. Later subtasks that depend on it stay pending.']];
    if (o.state !== 'open') {
      return `<div class="mb-start mb-prop is-done"><div class="mb-start-h">${Icon('flag', 'sm')}<span>Decision · subtask ${o.subtask}</span><span class="mb-prop-state t-done">Recorded</span></div><div class="mb-start-t mono" style="font-size:13px">${esc(o.state)}</div><div class="mb-ex-s">Recorded with sona goal operator-decision. Resume the goal to continue.</div><div class="mb-prop-a">${Button({ label: 'Resume goal', icon: 'play', variant: 'primary', act: 'sheet', arg: 'resume:' + o.run })}</div></div>`;
    }
    return `<div class="mb-start mb-prop"><div class="mb-start-h">${Icon('flag', 'sm')}<span>Decision · subtask ${o.subtask}</span><span class="mb-prop-state t-accent">Waiting for you</span></div>${opts.map(([id, label, desc]) => `<button type="button" class="mb-opt"${act(o.act, o.chat + ':' + id)}><span class="mb-opt-m"><span class="mb-opt-t">${esc(label)}</span><span class="mb-opt-d"><span class="mono">${id}</span> · ${esc(desc)}</span></span></button>`).join('')}</div>`;
  }
  function Suggestions(o) { return `<div class="mb-sugs">${o.items.map((s) => `<button type="button" class="mb-sug"${act(o.act, s)}>${esc(s)}</button>`).join('')}</div>`; }
  function Composer(o) {
    /* While the agent replies, Stop stays and a typed message goes to the queue instead of sending. */
    const stop = `<button type="button" class="mb-cmp-stop" aria-label="Stop"${act('chat-stop', o.chat)}>${Icon('stop', 'sm')}</button>`;
    const sendBtn = `<button type="button" class="mb-cmp-send" aria-label="${o.replying ? 'Queue' : 'Send'}"${act('chat-send', o.chat)}${o.draft ? '' : ' disabled'}>${Icon(o.replying ? 'layers' : 'arrowUp', 'sm')}</button>`;
    const send = o.replying ? (o.draft ? stop + sendBtn : stop) : sendBtn;
    return `<div class="mb-composer">${o.queued ? `<button type="button" class="mb-queued"${act('sheet', 'queue:' + o.chat)}>${Icon('layers', 'xs')}${o.queued} message${o.queued > 1 ? 's' : ''} queued${Icon('chevronUp', 'xs')}</button>` : ''}
      <div class="mb-cmp-box"><div class="${cx('mb-cmp-in', !o.draft && 'is-empty')}"${act('draft', o.chat)}>${esc(o.draft || (o.replying ? 'Queue a message' : 'Message ' + o.agent))}</div>
      <div class="mb-cmp-row">${o.locked ? `<span class="mb-target mb-target--locked" title="Run chats use the run's checkout">${Icon('branch', 'xs')}<span>${esc(o.repo)}</span><span class="mono t-faint">${esc(o.branch)}</span></span>` : `<button type="button" class="mb-target"${act('sheet', 'target:' + o.chat)}>${SB.RepoIcon(window.MD.REPOS[o.repo])}<span>${esc(o.repo)}</span><span class="mono t-faint">${esc(o.branch)}</span>${Icon('chevronDown', 'xs')}</button>`}
      <button type="button" class="mb-cmp-agent"${act('sheet', 'agent:' + o.chat)} aria-label="Agent">${Agent(o.agent, { sm: true })}</button><span class="l-grow"></span>${send}</div></div></div>`;
  }

  /* ---------- Plans ---------- */
  function PlanRow(o) {
    return `<button type="button" class="mb-plan"${act(o.act, o.arg)}>${StatusIcon(o.status === 'done' ? 'done' : o.status === 'active' ? 'active' : 'pending', o.n / o.m)}<span class="mb-plan-m"><span class="mono t-faint">${esc(o.key)}</span><span class="mb-plan-t">${esc(o.title)}</span></span>${SB.ProgressRing(o.n, o.m)}</button>`;
  }
  function Doc(blocks) {
    return `<article class="mb-doc">${blocks.map(([k, t]) => (k === 'li' ? `<div class="mb-doc-li">${esc(t)}</div>` : `<${k}>${esc(t)}</${k}>`)).join('')}</article>`;
  }

  /* ---------- Empty, push ---------- */
  function Empty(o) {
    return `<div class="mb-empty">${Icon(o.icon, 'lg')}<strong>${esc(o.title)}</strong>${o.body ? `<span>${esc(o.body)}</span>` : ''}${o.action ? Button(o.action) : ''}</div>`;
  }
  function Push(o) {
    return `<div class="mb-push"><span class="mb-push-ic">${BrandMark(18)}</span><span class="mb-push-m"><span class="mb-push-top"><span>SONA</span><span>${esc(o.time)}</span></span><strong>${esc(o.title)}</strong><span>${esc(o.body)}</span></span></div>`;
  }
  function LockScreen(o) {
    return `<div class="mb-lock"><div class="mb-lock-time"><span>Saturday 14 November</span><strong>9:41</strong></div><div class="mb-lock-list">${join(o.pushes.map(Push))}</div></div>`;
  }

  window.MB = {
    join, act, Phone, StatusBar, NavBar, NavButton, TabBar, Fab, Scroll, PullHint, Button, Segmented, Chip, Chips, Toggle, Field, Spinner,
    Agent, Repo, Branch, StatePill, Tag, Meta, Group, Row, WorkRow, SwipeRow, RunHeader, NowCard, PhaseStrip, BuildChip, Card, KV, Para,
    Section, Chips2, Metrics, Reason, EvidenceRow, Breakdown, EventLine, Timeline, Fold, Badge, SegBar, Outcome, ExRow, StepNode, CycleGrid, StageStrip, FileChips, SubtaskRow, PhaseTabs, Hint, PeekBlock, PhaseCard, ActivityRow, ChangeSummary, Checkbox, Criterion, Finding, ActionBar, Sheet, SheetOption, Toast, Banner, SystemAlert,
    Hero, Code, Fingerprint, CheckStep, Steps, MachineCard, UsageBlock, FileRow, Diff, Thread, Pager, ChatRow, Message, Typing, StartCard,
    Suggestions, RunContext, ProposalCard, DecisionCard, Composer, PlanRow, Doc, Empty, Push, LockScreen,
  };
})();
