(function () {
  'use strict';
  window.DesignSurfaces.register("changes", "<div class=\"tip\" id=\"tip\"></div>\n\n<section>\n  <div class=\"caption\">Changes overview</div>\n  <div class=\"win detail\">\n    <div class=\"sechead\"><h3>Changes</h3><span class=\"count\" id=\"a-count\"></span><span class=\"sp\"></span>\n      <button class=\"btn primary\" id=\"open-window\"><svg width=\"13\" height=\"13\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" aria-hidden=\"true\"><rect x=\"1.5\" y=\"3.5\" width=\"10\" height=\"10\" rx=\"1.5\"/><path d=\"M8 1.5h6.5V8M14.5 1.5 7 9\"/></svg>Open changes</button></div>\n    <div class=\"strip\" id=\"a-strip\" aria-label=\"Lines changed per subtask\"></div>\n    <div class=\"nums\" id=\"a-nums\" aria-hidden=\"true\"></div>\n    <div class=\"runrow\" id=\"a-run\"></div>\n  </div>\n  <p class=\"note\">The section no longer lists subtasks. It shows a strip with one column per subtask, height by lines changed: done columns grey, the running one amber, not-started ones dashed. Hover a column for its name and counts, click it to open the window on that subtask. The row under the strip jumps to the running subtask.</p>\n</section>\n\n<section>\n  <div class=\"caption\">Changes window</div>\n  <div class=\"win cw\" id=\"cw\">\n    <div class=\"titlebar\"><div class=\"lights\" aria-hidden=\"true\"><span></span><span></span><span></span></div><div class=\"t\" id=\"wtitle\"></div><div style=\"width:110px\"></div></div>\n    <div class=\"toolbar\">\n      <button class=\"picker\" id=\"picker\" aria-haspopup=\"listbox\" aria-expanded=\"false\"><span class=\"k\">Scope</span><span class=\"v\" id=\"picker-v\"></span><svg width=\"12\" height=\"12\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" aria-hidden=\"true\"><path d=\"m4 6 4 4 4-4\"/></svg></button>\n      <div class=\"seg\" role=\"group\" aria-label=\"Quick scopes\" id=\"seg\"></div>\n      <span class=\"sp\"></span>\n      <input class=\"search\" id=\"q\" placeholder=\"Filter files  /\" aria-label=\"Filter files\">\n    </div>\n    <div class=\"stripwrap\"><div class=\"strip\" id=\"b-strip\" aria-label=\"Subtasks, drag to select a range\"></div><div class=\"nums\" id=\"b-nums\" aria-hidden=\"true\"></div></div>\n    <div class=\"scopeinfo\" id=\"scopeinfo\"></div>\n    <div class=\"body\">\n      <div class=\"list\" id=\"list\" role=\"listbox\" aria-label=\"Changed files\"></div>\n      <div class=\"diff\"><div class=\"dhead\" id=\"dhead\"></div><div class=\"dbody\" id=\"dbody\"></div></div>\n    </div>\n    <div class=\"foot\"><span><kbd>s</kbd> pick subtask</span><span><kbd>[</kbd> <kbd>]</kbd> previous / next subtask</span><span><kbd>a</kbd> all</span><span><kbd>j</kbd> <kbd>k</kbd> file</span><span><kbd>/</kbd> filter</span><span><kbd>o</kbd> open in editor</span><span class=\"sp\"></span><span>Read-only \u00b7 refreshes every 3 s while the goal runs</span></div>\n    <div class=\"pop\" id=\"pop\" role=\"dialog\" aria-label=\"Pick subtasks\">\n      <input id=\"pq\" placeholder=\"Filter subtasks by name or number\">\n      <div class=\"plist\" id=\"plist\" role=\"listbox\"></div>\n      <div class=\"pfoot\"><span>Click to pick one</span><span><kbd>Shift</kbd>-click for a range</span><span><kbd>\u2191</kbd><kbd>\u2193</kbd> <kbd>Enter</kbd></span><span class=\"sp\"></span><span class=\"link\" id=\"pop-all\">All subtasks</span></div>\n    </div>\n  </div>\n  <p class=\"note\"><b>Scale.</b> The scope picker replaces the chips: a searchable list of every subtask with status, files and <b>+N/\u2212N</b>. The strip under the toolbar is the fast path: click a column for one subtask, shift-click or drag across columns for a range. <b>[</b> and <b>]</b> step through subtasks one at a time, which is how you review a long run. A range diffs from the first subtask's start commit to the end of the last one, so it's one git diff, not a merge of many. Selecting a file lists the subtasks that touched it; click one to narrow to it. Sample data: a synthetic 33-subtask run (20 done, 1 running, 12 not started) built from this branch's real diffs.</p>\n</section>", function (document, host, cleanup, setTimeout, saved) {
const D = host.model || (host.getAttribute('work') ? window.DesignFlow.changesFor(host.getAttribute('work')) : window.DATA.RECORDED_CHANGES);
const S = D.subtasks, N = S.length, RUN = S.find(s => s.state === 'running');
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const sum = fs => fs.reduce((a, f) => [a[0] + f.add, a[1] + f.del], [0, 0]);
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
const lines = s => s.files.reduce((a, f) => a + f.add + f.del, 0);
const maxLines = Math.max(1, ...S.map(lines));
// scope: {kind:'all'} | {kind:'range', from, to} | {kind:'other'} | {kind:'wt'}
let scope = {kind: 'all'}, selected = null, query = '', anchor = null;

function filesFor(sc) {
  if (sc.kind === 'all') return D.all;
  if (sc.kind === 'other') return D.other;
  if (sc.kind === 'wt') return D.working || [];
  const m = new Map();
  for (let i = sc.from; i <= sc.to; i++) for (const f of S[i - 1].files) {
    const e = m.get(f.path) || {path: f.path, status: f.status, add: 0, del: 0, parts: []};
    e.add += f.add; e.del += f.del; e.parts.push({n: i, patch: f.patch}); m.set(f.path, e);
  }
  return [...m.values()].sort((a, b) => a.path.localeCompare(b.path));
}
function touchedBy(path) { return S.filter(s => s.files.some(f => f.path === path)).map(s => s.n); }
function scopeLabel(sc) {
  if (sc.kind === 'all') return 'All changes';
  if (sc.kind === 'other') return 'Outside subtasks';
  if (sc.kind === 'wt') return 'Uncommitted';
  if (sc.from === sc.to) return `${sc.from} · ${S[sc.from - 1].name}`;
  return `Subtasks ${sc.from}–${sc.to}`;
}
const inScope = n => scope.kind === 'range' && n >= scope.from && n <= scope.to;

function tip(e, s) {
  const t = document.getElementById('tip');
  if (!s) { t.style.display = 'none'; return; }
  const [a, d] = sum(s.files);
  t.innerHTML = `<div class="h">${s.n} · ${esc(s.name)}</div><div class="m">${s.state === 'pending' ? 'not started' : `${s.state === 'running' ? 'running · ' : ''}${plural(s.files.length, 'file')} · <span class="add">+${a}</span> <span class="del">−${d}</span> · ${s.sha || 'no commit recorded'}`}</div>`;
  t.style.display = 'block'; t.style.left = (e.clientX + 12) + 'px'; t.style.top = (e.clientY + 14) + 'px';
}

function strip(el, nums, interactive) {
  el.innerHTML = S.map(s => {
    const h = s.state === 'pending' ? 0 : Math.max(6, Math.round(Math.sqrt(lines(s) / maxLines) * 100));
    return `<div class="col ${s.state}${interactive && inScope(s.n) ? ' sel' : ''}" data-n="${s.n}" role="button" aria-label="Subtask ${s.n}, ${esc(s.name)}"><div class="bar" style="height:${s.state === 'pending' ? 0 : h}%"></div></div>`;
  }).join('');
  const every = el.clientWidth / N >= 22 ? 1 : 5;
  nums.innerHTML = S.map(s => `<span class="${interactive && inScope(s.n) ? 'sel' : ''}">${s.n === 1 || s.n % every === 0 ? s.n : ''}</span>`).join('');
  el.querySelectorAll('.col').forEach(c => {
    const s = S[c.dataset.n - 1];
    c.onmousemove = e => tip(e, s); c.onmouseleave = e => tip(e, null);
  });
}

let drag = null;
function wireStrip() {
  const el = document.getElementById('b-strip');
  el.onmousedown = e => {
    const c = e.target.closest('.col'); if (!c || S[c.dataset.n - 1].state === 'pending') return;
    const n = +c.dataset.n;
    if (e.shiftKey && anchor) pickRange(anchor, n); else { anchor = n; drag = n; pickRange(n, n); }
    e.preventDefault();
  };
  el.onmouseover = e => {
    const c = e.target.closest('.col'); if (drag == null || !c) return;
    const n = Math.min(+c.dataset.n, RUN ? RUN.n : N); pickRange(drag, n);
  };
  const endDrag = () => { drag = null; };
  host.ownerDocument.addEventListener('mouseup', endDrag);
  cleanup.push(() => host.ownerDocument.removeEventListener('mouseup', endDrag));
}
function pickRange(a, b) { setScope({kind: 'range', from: Math.min(a, b), to: Math.max(a, b)}); }

function frameA() {
  const [a, d] = sum(D.all);
  document.getElementById('a-count').textContent = `${plural(D.all.length, 'file')} · +${a} −${d} · ${N} subtasks`;
  strip(document.getElementById('a-strip'), document.getElementById('a-nums'), false);
  document.querySelectorAll('#a-strip .col').forEach(c => c.onclick = () => { const s = S[c.dataset.n - 1]; if (s.state !== 'pending') { anchor = s.n; pickRange(s.n, s.n); show(); } });
  const [ra, rd] = sum(RUN ? RUN.files : []);
  const r = document.getElementById('a-run');
  if (!RUN) { r.innerHTML = ''; } else r.innerHTML = `<span class="dot d-running"></span><span class="n">${RUN.n}</span><span style="flex:1">${esc(RUN.name)}</span><span class="count">${plural(RUN.files.length, 'file')}</span><span class="add">+${ra}</span><span class="del">−${rd}</span>`;
  r.onclick = () => { if (!RUN) return; anchor = RUN.n; pickRange(RUN.n, RUN.n); show(); };
  document.getElementById('open-window').onclick = () => { setScope({kind: 'all'}); show(); };
}
const show = () => {
  if (host.getAttribute('embedded') === 'overview') host.dispatchEvent(new CustomEvent('design-open-changes', { bubbles: true, composed: true, detail: { work: host.getAttribute('work'), scope } }));
  else document.getElementById('cw').scrollIntoView({behavior: 'smooth'});
};

function seg() {
  const items = [
    ['all', 'All', D.all.length],
    ...(RUN ? [['run', `Running · ${RUN.n}`, RUN.files.length]] : []),
    ...(N ? [['other', 'Outside subtasks', D.other.length]] : []),
    ['wt', 'Uncommitted', (D.working || []).length]];
  document.getElementById('seg').innerHTML = items.map(([k, l, c]) => {
    const on = k === 'run' ? scope.kind === 'range' && scope.from === RUN.n && scope.to === RUN.n : scope.kind === k;
    return `<button data-k="${k}" aria-pressed="${on}" ${c ? '' : 'disabled'}>${k === 'run' ? '<span class="dot d-running"></span>' : ''}${l}<span class="num">${c}</span></button>`;
  }).join('');
  document.querySelectorAll('#seg button').forEach(b => b.onclick = () => {
    const k = b.dataset.k; if (b.disabled) return;
    if (k === 'run') { anchor = RUN.n; pickRange(RUN.n, RUN.n); } else setScope({kind: k});
  });
}

function info(fs) {
  const [a, d] = sum(fs);
  let sub = '';
  if (scope.kind === 'all') sub = D.branch || 'main...HEAD';
  else if (scope.kind === 'other') sub = D.branch || 'outside subtask commits';
  else if (scope.kind === 'range') sub = scope.from === scope.to ? (S[scope.from - 1].sha ? `Commit ${S[scope.from - 1].sha}` : 'No commit recorded') : `Recorded patches · ${scope.to - scope.from + 1} of ${N} subtasks`;
  const nav = scope.kind === 'range' ? `<span class="link" id="prev">← ${scope.from > 1 ? scope.from - 1 : ''}</span><span class="link" id="next">${scope.to < (RUN ? RUN.n : N) ? scope.to + 1 : ''} →</span><span class="link" id="clear">Show all</span>` : '';
  document.getElementById('scopeinfo').innerHTML = `<span class="lbl">${esc(scopeLabel(scope))}</span><span>${esc(sub)}</span>${nav}<span class="sp"></span><span>${plural(fs.length, 'file')}</span><span class="add">+${a}</span><span class="del">−${d}</span>`;
  if (scope.kind === 'range') {
    document.getElementById('prev').onclick = () => step(-1);
    document.getElementById('next').onclick = () => step(1);
    document.getElementById('clear').onclick = () => setScope({kind: 'all'});
  }
}
function step(dir) {
  const last = RUN ? RUN.n : S.filter(sub => sub.state !== 'pending').at(-1)?.n;
  if (!last) return;
  let n = scope.kind === 'range' ? (dir < 0 ? scope.from - 1 : scope.to + 1) : (dir < 0 ? last : 1);
  n = Math.max(1, Math.min(last, n)); anchor = n; pickRange(n, n);
}

function moduleOf(p) {
  const m = p.match(/^(desktop\/(?:core|feature)\/[^/]+|desktop\/agent|runtime-kotlin\/runtime-infra\/[^/]+|runtime-kotlin\/[^/]+)/);
  return m ? m[1] : p.split('/').slice(0, -1).join('/');
}
function visible() { const fs = filesFor(scope); return query ? fs.filter(f => f.path.toLowerCase().includes(query)) : fs; }
function list() {
  const all = filesFor(scope), fs = visible(); info(fs);
  const el = document.getElementById('list');
  if (!fs.length) { el.innerHTML = `<div class="empty">${all.length ? 'No files match the filter.' : 'No changes in this scope.'}</div>`; return; }
  const groups = {}; fs.forEach(f => (groups[moduleOf(f.path)] ??= []).push(f));
  el.innerHTML = Object.entries(groups).map(([g, gf]) => {
    const [ga, gd] = sum(gf);
    return `<div class="group"><span>${esc(g)}</span><span><span class="add">+${ga}</span> <span class="del">−${gd}</span></span></div>` + gf.map(f => {
      const multi = f.parts && f.parts.length > 1 ? `<span class="multi" title="Changed by ${f.parts.length} subtasks in this scope">×${f.parts.length}</span>` : '';
      return `<div class="row" role="option" aria-selected="${f.path === selected}" data-path="${esc(f.path)}" title="${esc(f.path)}"><span class="st st-${f.status}">${f.status}</span><span class="name">${esc(f.path.split('/').pop())}</span><span class="counts">${multi}<span class="add">+${f.add}</span><span class="del">−${f.del}</span></span></div>`;
    }).join('');
  }).join('');
  el.querySelectorAll('.row').forEach(r => r.onclick = () => select(r.dataset.path));
}

function select(path) {
  selected = path;
  const f = filesFor(scope).find(x => x.path === path);
  document.querySelectorAll('.row').forEach(r => r.setAttribute('aria-selected', r.dataset.path === path));
  const head = document.getElementById('dhead'), body = document.getElementById('dbody');
  if (!f) { head.innerHTML = '<div class="top"><span class="path" style="color:var(--muted)">Select a file</span></div>'; body.innerHTML = ''; return; }
  const dir = f.path.split('/').slice(0, -1).join('/') + '/', name = f.path.split('/').pop();
  const tb = touchedBy(f.path);
  const touched = tb.length ? `<div class="touched">Changed by subtask${tb.length === 1 ? '' : 's'} ${tb.map(n => `<span class="tchip${inScope(n) ? ' in' : ''}" data-n="${n}" title="${esc(S[n - 1].name)}">${n}</span>`).join('')}</div>` : '';
  head.innerHTML = `<div class="top"><span class="st st-${f.status}" style="font-size:11px">${f.status}</span><span class="path"><span class="dir">${esc(dir)}</span>${esc(name)}</span><span class="add">+${f.add}</span><span class="del">−${f.del}</span><button class="btn" ${f.status === 'D' ? 'disabled' : ''}>Open in editor</button></div>${touched}`;
  head.querySelectorAll('.tchip').forEach(t => t.onclick = () => { const n = +t.dataset.n; anchor = n; pickRange(n, n); });
  body.innerHTML = '';
  if (host.getAttribute('embedded') !== 'overview') {
    const review = host.ownerDocument.createElement('sb-design-surface');
    review.setAttribute('screen', 'comments'); review.setAttribute('embedded', 'diff');
    review.setAttribute('context', `${host.getAttribute('work') || 'sample'}|${scope.kind}|${scope.from || ''}|${scope.to || ''}`);
    review.model = { files: filesFor(scope).map(file => window.DesignFlow.diffFile(file, file.parts ? file.parts.map(part => part.patch) : [file.patch || ''])), selected: path };
    body.append(review);
  }
  body.scrollTop = 0;
}

function picker() {
  const q = document.getElementById('pq').value.toLowerCase().trim();
  const rows = S.filter(s => !q || String(s.n) === q || s.name.toLowerCase().includes(q));
  document.getElementById('plist').innerHTML = rows.map(s => {
    const [a, d] = sum(s.files);
    const right = s.state === 'pending' ? `<span class="f" style="grid-column:span 3">not started</span>` : `<span class="f">${plural(s.files.length, 'file')}</span><span class="add">+${a}</span><span class="del">−${d}</span>`;
    return `<div class="prow ${s.state}${inScope(s.n) ? ' sel' : ''}" data-n="${s.n}" role="option"><span class="n">${s.n}</span><span class="dot d-${s.state}"></span><span class="nm">${esc(s.name)}</span>${right}</div>`;
  }).join('') || '<div class="empty">No subtask matches.</div>';
  document.querySelectorAll('.prow').forEach(r => r.onclick = e => {
    const n = +r.dataset.n; if (S[n - 1].state === 'pending') return;
    if (e.shiftKey && anchor) pickRange(anchor, n); else { anchor = n; pickRange(n, n); closePop(); }
  });
}
function openPop() { const p = document.getElementById('pop'); p.classList.add('open'); document.getElementById('picker').setAttribute('aria-expanded', 'true'); document.getElementById('pq').value = ''; picker(); document.getElementById('pq').focus(); }
function closePop() { document.getElementById('pop').classList.remove('open'); document.getElementById('picker').setAttribute('aria-expanded', 'false'); }

function setScope(sc) {
  scope = sc;
  document.getElementById('picker-v').textContent = scopeLabel(sc);
  seg(); strip(document.getElementById('b-strip'), document.getElementById('b-nums'), true); list();
  if (document.getElementById('pop').classList.contains('open')) picker();
  const fs = visible();
  select(fs.find(f => f.path === selected) ? selected : (fs[0] && fs[0].path));
}

document.getElementById('wtitle').textContent = `Changes · ${D.goal}`;
document.getElementById('picker').onclick = e => { e.stopPropagation(); document.getElementById('pop').classList.contains('open') ? closePop() : openPop(); };
document.getElementById('pop').onclick = e => e.stopPropagation();
document.addEventListener('click', closePop);
document.getElementById('pop-all').onclick = () => { setScope({kind: 'all'}); closePop(); };
document.getElementById('pq').oninput = picker;
document.getElementById('pq').onkeydown = e => {
  const rows = [...document.querySelectorAll('.prow:not(.pending)')]; let i = rows.findIndex(r => r.classList.contains('kb'));
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); rows[i]?.classList.remove('kb'); i = Math.max(0, Math.min(rows.length - 1, i + (e.key === 'ArrowDown' ? 1 : -1))); rows[i]?.classList.add('kb'); rows[i]?.scrollIntoView({block: 'nearest'}); }
  else if (e.key === 'Enter') { const r = rows[Math.max(i, 0)]; if (r) r.click(); closePop(); }
  else if (e.key === 'Escape') closePop();
};
document.getElementById('q').oninput = e => { query = e.target.value.toLowerCase(); list(); const fs = visible(); select(fs.some(file => file.path === selected) ? selected : fs[0]?.path); };
function navigateKey(key) {
  const fs = visible(), i = fs.findIndex(f => f.path === selected);
  if (key === 'j' && i < fs.length - 1) select(fs[i + 1].path);
  else if (key === 'k' && i > 0) select(fs[i - 1].path);
  else if (key === ']') step(1);
  else if (key === '[') step(-1);
  else if (key === 'a') setScope({kind: 'all'});
  else if (key === 's') openPop();
  else if (key === '/') document.getElementById('q').focus();
  else return false;
  document.querySelector('.row[aria-selected=true]')?.scrollIntoView({block: 'nearest'});
  return true;
}
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT') { if (e.key === 'Escape') e.target.blur(); return; }
  if (navigateKey(e.key)) e.preventDefault();
});
document.addEventListener('design-select-file', e => { select(e.detail.path); e.stopPropagation(); });
document.addEventListener('design-change-key', e => { navigateKey(e.detail.key); e.stopPropagation(); });
const resize = new ResizeObserver(() => { frameA(); strip(document.getElementById('b-strip'), document.getElementById('b-nums'), true); });
resize.observe(host);
cleanup.push(() => resize.disconnect());

if (host.hasAttribute('embedded')) document.querySelector('.foot > span:last-child').textContent = 'Read-only diff · local review comments';
if (!N) {
  document.getElementById('picker').disabled = true;
  document.querySelector('.stripwrap').hidden = true;
  document.querySelectorAll('.foot > span').forEach((span, index) => { if (index < 2) span.hidden = true; });
}
wireStrip(); frameA();
if (saved) {
  query = saved.query; anchor = saved.anchor; selected = saved.selected;
  document.getElementById('q').value = query;
  setScope(host.hasAttribute('scope') ? JSON.parse(host.getAttribute('scope')) : saved.scope);
} else if (host.hasAttribute('scope')) { setScope(JSON.parse(host.getAttribute('scope'))); } else if (host.hasAttribute('embedded') || N < 9) { setScope({ kind: 'all' }); } else { anchor = 4; pickRange(4, 9); }
if (host.getAttribute('file') && filesFor(scope).some((file) => file.path === host.getAttribute('file'))) select(host.getAttribute('file'));
return () => ({ scope, selected, query, anchor });
  });
})();
