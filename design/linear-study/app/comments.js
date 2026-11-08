(function () {
  'use strict';
  window.DesignSurfaces.register("comments", "<div class=\"mockbar\">\n  <b>Diff comments</b>\n  <span>Scenario</span>\n  <div class=\"seg\" id=\"scenarios\">\n    <button data-s=\"1\">1 \u00b7 Writing a review</button>\n    <button data-s=\"2\">2 \u00b7 Review added</button>\n    <button data-s=\"3\">3 \u00b7 After phase:review</button>\n    <button data-s=\"4\">4 \u00b7 After pr-review-fix</button>\n  </div>\n  <span class=\"note\">Everything is clickable. Hover a diff line and click + (or press c) to comment.</span>\n</div>\n\n<div class=\"stage\">\n  <div class=\"window\">\n    <div class=\"titlebar\"><div class=\"lights\"><i></i><i></i><i></i></div><div class=\"title\">Changes \u00b7 SKILL-474 \u00b7 feat/diff-cache</div><div style=\"width:56px\"></div></div>\n    <div class=\"toolbar\">\n      <div class=\"scope\"><span class=\"lbl\">SCOPE</span><span class=\"val\">main...feat/diff-cache</span><span style=\"flex:1\"></span><svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.4\" stroke-linecap=\"round\"><path d=\"m6 9 6 6 6-6\"/></svg></div>\n      <div class=\"seg2\"><span class=\"on\">All <em>4</em></span><span>Uncommitted <em>0</em></span></div>\n      <div class=\"filter\">Filter files  /</div>\n      <div class=\"spacer\"></div>\n      <div class=\"cnav\" id=\"cnav\"></div>\n    </div>\n    <div class=\"scopebar\"><span class=\"mono\">main...feat/diff-cache</span><span>\u00b7 4 files</span><span class=\"add\">+33</span><span class=\"del\">\u22129</span></div>\n    <div class=\"body\">\n      <div class=\"files\" id=\"files\"></div>\n      <div class=\"pane\">\n        <div class=\"pane-head\" id=\"panehead\"></div>\n        <div class=\"scroll\" id=\"scroll\"></div>\n        <div class=\"reviewbar\" id=\"reviewbar\"></div>\n        <div class=\"toast\" id=\"toast\"></div>\n      </div>\n    </div>\n    <div class=\"footer\">\n      <span class=\"h\"><span class=\"kc\">j</span><span class=\"kc\">k</span> file</span>\n      <span class=\"h\"><span class=\"kc\">/</span> filter</span>\n      <span class=\"h\"><span class=\"kc\">o</span> open in editor</span>\n      <span class=\"h\"><span class=\"kc\">c</span> comment on line</span>\n      <span class=\"h\"><span class=\"kc\">n</span><span class=\"kc\">p</span> next / previous comment</span>\n      <span class=\"h\"><span class=\"kc\">\u2318</span><span class=\"kc\">\u21b5</span> add review</span>\n      <span class=\"spacer\"></span>\n      <span>Comments are saved locally with feat/diff-cache \u00b7 refreshes every 3 s while the run runs</span>\n    </div>\n  </div>\n</div>", function (document, host, cleanup, setTimeout, saved) {
const I = {
  plus:'<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
  msg:'<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
  bot:'<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2M20 14h2M15 13v2M9 13v2"/></svg>',
  up:'<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="m18 15-6-6-6 6"/></svg>',
  down:'<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="m6 9 6 6 6-6"/></svg>',
  right:'<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="m9 18 6-6-6-6"/></svg>',
  pen:'<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/></svg>',
  trash:'<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>',
  check:'<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',
  ban:'<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="m5.7 5.7 12.6 12.6"/></svg>',
  undo:'<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-15-6.7L3 13"/></svg>',
  okc:'<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="m8 12 3 3 5-6"/></svg>',
  ext:'<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/></svg>',
};

/* ---------- sample diff ---------- */
const C='c', A='a', D='d';
const SAMPLE_FILES = [
  { path:'desktop/core/data/src/jvmMain/kotlin/dev/skillbill/desktop/core/data/repository/RuntimeWorkChangesRepository.kt', st:'M', ins:14, del:5, hunks:[
    { h:'@@ -41,10 +41,14 @@', src:'class RuntimeWorkChangesRepository(', lines:[
      [C,41,41,'  override fun fileDiff(baseline: ChangeBaseline, path: String): FileDiffState {'],
      [C,42,42,'    val root = baseline.worktreeRoot'],
      [D,43,null,'    val raw = git.diff(root, baseline.revision(), path)'],
      [D,44,null,'      ?: return FileDiffState.BaseUnavailable("Base commit is gone")'],
      [A,null,43,'    val revision = baseline.revision()'],
      [A,null,44,'    val raw = cache.getOrPut(revision to path) {'],
      [A,null,45,'      git.diff(root, revision, path)'],
      [A,null,46,'    } ?: return FileDiffState.BaseUnavailable("Base commit is gone")'],
      [C,45,47,'    if (raw.length > MAX_DIFF_BYTES) {'],
      [C,46,48,'      return FileDiffState.TooLarge(MAX_DIFF_BYTES)'],
      [C,47,49,'    }'],
      [D,48,null,'    return FileDiffState.Shown(UnifiedDiffParser.parse(raw))'],
      [A,null,50,'    val parsed = UnifiedDiffParser.parse(raw)'],
      [A,null,51,'    if (parsed.hunks.isEmpty()) return FileDiffState.Binary'],
      [A,null,52,'    return FileDiffState.Shown(parsed)'],
      [C,49,53,'  }'],
    ]},
    { h:'@@ -88,7 +92,12 @@', src:'class RuntimeWorkChangesRepository(', lines:[
      [C,88,92,'  private fun ChangeBaseline.revision(): String = when (this) {'],
      [C,89,93,'    is ChangeBaseline.SubtaskStart -> baseSha'],
      [C,90,94,'    is ChangeBaseline.RunStart -> baseSha'],
      [D,91,null,'    is ChangeBaseline.TargetMergeBase -> git.mergeBase(worktreeRoot, targetBranch)'],
      [A,null,95,'    is ChangeBaseline.TargetMergeBase ->'],
      [A,null,96,'      git.mergeBase(worktreeRoot, targetBranch ?: git.defaultBranch(worktreeRoot))'],
      [C,92,97,'    is ChangeBaseline.CommittedBranchRange -> "$targetBranch...$headRef"'],
      [C,93,98,'  }'],
      [A,null,99,''],
      [A,null,100,'  private companion object {'],
      [A,null,101,'    const val MAX_DIFF_BYTES = 512 * 1024'],
      [A,null,102,'  }'],
      [C,94,103,'}'],
    ]},
  ]},
  { path:'desktop/core/data/src/jvmMain/kotlin/dev/skillbill/desktop/core/data/repository/CommittedChangeCache.kt', st:'M', ins:9, del:2, hunks:[
    { h:'@@ -12,8 +12,15 @@', src:'internal class CommittedChangeCache(', lines:[
      [C,12,12,'  private val entries = LinkedHashMap<Key, ChangeSet>(16, 0.75f, true)'],
      [C,13,13,''],
      [D,14,null,'  fun get(key: Key): ChangeSet? = entries[key]'],
      [A,null,14,'  @Synchronized'],
      [A,null,15,'  fun get(key: Key): ChangeSet? = entries[key]'],
      [C,15,16,''],
      [D,16,null,'  fun put(key: Key, value: ChangeSet) { entries[key] = value }'],
      [A,null,17,'  @Synchronized'],
      [A,null,18,'  fun put(key: Key, value: ChangeSet) {'],
      [A,null,19,'    entries[key] = value'],
      [A,null,20,'    while (entries.size > capacity) {'],
      [A,null,21,'      entries.remove(entries.keys.first())'],
      [A,null,22,'    }'],
      [A,null,23,'  }'],
      [C,17,24,''],
      [C,18,25,'  data class Key(val root: String, val from: String, val to: String)'],
      [C,19,26,'}'],
    ]},
  ]},
  { path:'desktop/feature/changes/src/commonMain/kotlin/dev/skillbill/desktop/feature/changes/ChangesWindowViewModel.kt', st:'M', ins:2, del:1, hunks:[
    { h:'@@ -140,6 +140,7 @@', src:'class ChangesWindowViewModel(', lines:[
      [C,140,140,'  private fun select(path: String) {'],
      [C,141,141,'    val file = files.firstOrNull { it.path == path } ?: return'],
      [D,142,null,'    _state.update { it.copy(selectedPath = path) }'],
      [A,null,142,'    _state.update { it.copy(selectedPath = path, scrollRequest = it.scrollRequest + 1) }'],
      [A,null,143,'    ensureDiffs(listOf(path))'],
      [C,143,144,'  }'],
      [C,144,145,''],
    ]},
  ]},
  { path:'desktop/core/data/src/jvmTest/kotlin/dev/skillbill/desktop/core/data/repository/CommittedChangeCacheTest.kt', st:'A', ins:8, del:0, hunks:[
    { h:'@@ -0,0 +1,8 @@', src:'', lines:[
      [A,null,1,'class CommittedChangeCacheTest {'],
      [A,null,2,'  @Test'],
      [A,null,3,'  fun `evicts the least recently read entry`() {'],
      [A,null,4,'    val cache = CommittedChangeCache(capacity = 1)'],
      [A,null,5,'    cache.put(key("a"), changes); cache.put(key("b"), changes)'],
      [A,null,6,'    assertNull(cache.get(key("a")))'],
      [A,null,7,'  }'],
      [A,null,8,'}'],
    ]},
  ]},
];
const FILES = host.model ? host.model.files : SAMPLE_FILES;
const F = i => FILES[i].path;

/* ---------- scenarios ---------- */
const U = (body, when='just now') => ({ id:uid(), author:'user', body, when });
const AG = (body, src, when, extra={}) => ({ id:uid(), author:'agent', body, src, when, ...extra });
let _id = saved ? saved.id : 0; function uid() { return 'x' + (++_id); }

const T = {
  u1: () => ({ file:F(0), side:'new', num:44 }),
  u2: () => ({ file:F(1), side:'new', num:21 }),
  u3: () => ({ file:F(0), side:'new', num:101 }),
  a1: () => ({ file:F(1), side:'new', num:18 }),
  a2: () => ({ file:F(0), side:'new', num:96 }),
  a3: () => ({ file:F(2), side:'new', num:143 }),
};
const B = {
  u1:'If `git.diff` returns null the miss is never cached, so a gone base runs git again on every scroll.',
  u2:'`removeEldestEntry` would make this one line.',
  u3:'512 KB feels low for generated files. Can we match the CLI limit?',
  a1:'`put` now holds the lock, but `stats()` still reads `entries.size` without it. A read during eviction can see a size above `capacity`.',
  a2:'`git.defaultBranch` starts a git process on every revision lookup. With 40 files in view that is 40 processes per refresh.',
  a3:'`ensureDiffs` already runs from the scroll observer after the jump, so selecting a file reads its diff twice.',
};
function scenario(n) {
  _id = 0;
  const th = (key, state, comments, extra={}) => ({ id:uid(), ...T[key](), state, comments, ...extra });
  if (n === 1) return [
    th('u1','pending',[U(B.u1,'2m')]),
    th('u2','pending',[U(B.u2,'just now')]),
  ];
  if (n === 2) return [
    th('u1','open',[U(B.u1,'4m')]),
    th('u2','open',[U(B.u2,'3m')]),
    th('u3','open',[U(B.u3,'2m')]),
  ];
  const review = (k, sev, fid, when) => AG(B[k], 'phase:review', when, { sev, fid });
  if (n === 3) return [
    th('u1','open',[U(B.u1,'22m')]),
    th('u2','open',[U(B.u2,'21m')]),
    th('u3','open',[U(B.u3,'20m')]),
    th('a1','open',[review('a1','major','F-001','just now')]),
    th('a2','open',[review('a2','minor','F-002','just now')]),
    th('a3','open',[review('a3','minor','F-003','just now')]),
  ];
  const fix = (outcome, body, sha) => AG(body, 'pr-review-fix', 'just now', { outcome, sha });
  const rfix = (outcome, body, sha) => AG(body, 'phase:review', '6m', { outcome, sha });
  return [
    th('u1','resolved',[U(B.u1,'1h'), fix('fixed','A gone base is now cached as `BaseUnavailable`, so git runs once per path.','a41c9e2')]),
    th('u2','declined',[U(B.u2,'1h'), fix('refuted','`removeEldestEntry` needs a `LinkedHashMap` subclass and cannot read the constructor `capacity` before init. The loop stays.')]),
    th('u3','open',[U(B.u3,'1h'), fix('skipped','The CLI has no diff size limit to match. Pick a number and I will apply it.')]),
    th('a1','resolved',[review('a1','major','F-001','40m'), rfix('fixed','`stats()` takes the same lock now.','7be03d1')]),
    th('a2','open',[review('a2','minor','F-002','40m'), rfix('failed','Caching the default branch per root broke `RuntimeWorkChangesRepositoryTest` (2 failures: the test switches branches mid-run). Left unchanged.')]),
    th('a3','resolved',[review('a3','minor','F-003','40m'), rfix('fixed','Dropped the extra `ensureDiffs` call.','7be03d1')],
       { outdated:true, original:'    ensureDiffs(listOf(path))' }),
  ];
}

/* ---------- state ---------- */
let S = {};
function load(n) {
  S = { scenario:n, threads:scenario(n), selected:0, composer:null, editing:null, confirm:null, expanded:new Set(), fresh:new Set(), cursor:-1, hover:null };
  if (n === 1) S.composer = { file:F(0), side:'new', num:101, text:'' };
  document.querySelectorAll('#scenarios button').forEach(b => b.classList.toggle('on', +b.dataset.s === n));
  render(true);
  document.getElementById('scroll').scrollTop = 0;
}

const esc = s => s.replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const fmt = s => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*([^*]+)\*/g, '<em>$1</em>');
const key = (file, side, num) => file + '|' + side + '|' + num;
const counts = () => {
  const c = { pending:0, open:0, resolved:0, declined:0 };
  S.threads.forEach(t => c[t.state]++);
  return c;
};
const navOrder = () => {
  const order = [];
  FILES.forEach(f => f.hunks.forEach(h => h.lines.forEach(l => {
    const side = l[2] != null ? 'new' : 'old', num = l[2] ?? l[1];
    S.threads.filter(t => t.file === f.path && t.side === side && t.num === num && t.state !== 'resolved' && t.state !== 'declined').forEach(t => order.push(t.id));
  })));
  return order;
};

/* ---------- render ---------- */
function render(initial) {
  renderFiles(); renderNav(); renderHead(); renderDiff(); renderReviewBar();
  S.fresh.clear();
  if (S.composer) { const ta = document.querySelector('.composer textarea'); if (ta && document.activeElement !== ta) { ta.focus({ preventScroll: !initial }); ta.selectionStart = ta.value.length; } }
  if (S.editing) { const ta = document.querySelector('.editing textarea'); if (ta && document.activeElement !== ta) { ta.focus({preventScroll:true}); ta.selectionStart = ta.value.length; } }
}

function renderFiles() {
  const el = document.getElementById('files');
  let html = '', lastDir = null;
  FILES.forEach((f, i) => {
    const dir = f.path.slice(0, f.path.lastIndexOf('/') + 1), name = f.path.slice(dir.length);
    const short = dir.replace(/^desktop\//, '').replace(/src\/(jvmMain|jvmTest|commonMain)\/kotlin\/dev\/skillbill\/desktop\//, '…/');
    if (short !== lastDir) { html += `<div class="dir">${esc(short)}</div>`; lastDir = short; }
    const ts = S.threads.filter(t => t.file === f.path);
    const live = ts.filter(t => t.state === 'open' || t.state === 'pending').length;
    const pend = ts.some(t => t.state === 'pending');
    const badge = ts.length ? `<span class="cbadge ${pend ? 'pending' : live ? '' : 'done'}" title="${live} open">${I.msg}${live || ts.length}</span>` : '';
    const stc = f.st === 'A' ? 'var(--st-done)' : 'var(--accent-text)';
    html += `<div class="file ${i === S.selected ? 'sel' : ''}" data-file="${i}"><span class="st" style="color:${stc}">${f.st}</span><span class="name">${esc(name)}</span>${badge}<span class="add">+${f.ins}</span><span class="del">−${f.del}</span></div>`;
  });
  el.innerHTML = html;
}

function renderNav() {
  const c = counts(), el = document.getElementById('cnav');
  const parts = [];
  if (c.pending) parts.push(`<span class="dot" style="background:var(--accent)"></span><span class="n">${c.pending}</span> pending`);
  parts.push(`<span class="dot" style="background:var(--st-planning)"></span><span class="n">${c.open}</span> open`);
  if (c.resolved + c.declined) parts.push(`<span class="dot" style="background:var(--st-done)"></span><span class="n">${c.resolved + c.declined}</span> closed`);
  el.innerHTML = `<div class="sum">${I.msg}${parts.join('<span style="color:var(--border-strong)">·</span>')}</div>
    <div class="arrows"><button data-nav="-1" title="Previous comment (p)">${I.up}</button><button data-nav="1" title="Next comment (n)">${I.down}</button></div>`;
}

function renderHead() {
  const f = FILES[S.selected], dir = f.path.slice(0, f.path.lastIndexOf('/') + 1);
  document.getElementById('panehead').innerHTML =
    `<span class="st" style="font-family:var(--mono);font-size:11px;font-weight:600;color:var(--accent-text)">${f.st}</span>
     <span class="path"><span>${esc(dir)}</span>${esc(f.path.slice(dir.length))}</span>
     <span class="add">+${f.ins}</span><span class="del">−${f.del}</span><span class="spacer"></span>
     <button class="btn">${I.ext} Open in editor</button>`;
}

function renderDiff() {
  const el = document.getElementById('scroll');
  const st = el.scrollTop;
  let html = '';
  FILES.forEach((f, fi) => {
    if (host.model && fi !== S.selected) return;
    const dir = f.path.slice(0, f.path.lastIndexOf('/') + 1);
    html += `<div class="sep" id="file-${fi}"><span style="font-family:var(--mono);font-size:11px;font-weight:600;color:${f.st === 'A' ? 'var(--st-done)' : 'var(--accent-text)'}">${f.st}</span><span class="path"><span>${esc(dir)}</span>${esc(f.path.slice(dir.length))}</span><span class="add">+${f.ins}</span><span class="del">−${f.del}</span></div>`;
    if (!f.hunks.length) html += '<div class="empty">No text diff was recorded for this file.</div>';
    f.hunks.forEach(h => {
      html += `<div class="hunk">${esc(h.h)}<span>${esc(h.src)}</span></div>`;
      h.lines.forEach(l => {
        const [k, o, n, text] = l;
        const side = n != null ? 'new' : 'old', num = n ?? o;
        const ts = S.threads.filter(t => t.file === f.path && t.side === side && t.num === num);
        const comp = S.composer && S.composer.file === f.path && S.composer.side === side && S.composer.num === num;
        const rail = ts.length ? railOf(ts) : null;
        const prefix = k === A ? '+' : k === D ? '−' : ' ';
        html += `<div class="row k-${k === A ? 'add' : k === D ? 'del' : 'ctx'} ${rail ? 'anchored' : ''} ${comp ? 'targeted' : ''}" style="${rail ? '--rail:' + rail : ''}" data-f="${fi}" data-side="${side}" data-num="${num}">
          <span class="g">${o ?? ''}</span><span class="g">${n ?? ''}</span>
          <button class="addc" title="Comment on line ${num} (c)" data-add>${I.plus}</button>
          <span class="t">${esc(prefix + text)}</span></div>`;
        if (ts.length || comp) {
          html += `<div class="slot">${ts.map(threadHtml).join('')}${comp ? composerHtml() : ''}</div>`;
        }
      });
    });
  });
  el.innerHTML = html;
  el.style.scrollBehavior = 'auto'; el.scrollTop = st; el.style.scrollBehavior = '';
}
const railOf = ts => {
  const s = ts.map(t => t.state);
  if (s.includes('pending')) return 'var(--accent)';
  if (s.includes('open')) return 'var(--st-planning)';
  if (s.includes('resolved')) return 'var(--st-done)';
  return 'var(--st-paused)';
};

const CHIP = { pending:['pending','Pending'], open:['open','Open'], resolved:['resolved','Resolved'], declined:['declined','Declined'] };
const OUT = { fixed:['fixed', I.check, 'Fixed'], refuted:['refuted', I.ban, 'Refuted'], skipped:['skipped', '', 'Skipped'], failed:['failed', '', 'Failed to fix'] };

function threadHtml(t) {
  const closed = t.state === 'resolved' || t.state === 'declined';
  const anim = S.fresh.has(t.id) ? 'enter' : '';
  const [cc, cl] = CHIP[t.state];
  if (closed && !S.expanded.has(t.id)) {
    const first = t.comments[0], last = t.comments[t.comments.length - 1];
    const icon = t.state === 'resolved' ? `<span style="color:var(--st-done);display:grid">${I.check}</span>` : `<span style="color:var(--fg-muted);display:grid">${I.ban}</span>`;
    const reply = t.comments.length > 1 ? ` · ${t.comments.length - 1} ${t.comments.length === 2 ? 'reply' : 'replies'}` : '';
    const outc = last.outcome ? `<span class="outcome ${OUT[last.outcome][0]}" style="margin:0">${OUT[last.outcome][2]}${last.sha ? ' <code>' + last.sha + '</code>' : ''}</span>` : '';
    return `<div class="thread s-${t.state} ${anim}" id="t-${t.id}"><div class="collapsed" data-expand="${t.id}">${icon}
      <span class="chip ${cc}">${cl}</span>${t.outdated ? '<span class="chip outdated">Outdated</span>' : ''}
      <span class="snip"><b>${first.author === 'user' ? 'You' : 'Agent'}:</b> ${esc(first.body.replace(/`/g, ''))}</span>
      <span style="color:var(--fg-faint)">${reply}</span>${outc}<span class="chev">${I.right}</span></div></div>`;
  }
  let html = `<div class="thread s-${t.state} ${anim}" id="t-${t.id}">`;
  if (t.outdated) html += `<div style="padding-top:10px"></div><div class="orig"><b>${t.num}</b>${esc(t.original)}</div>`;
  t.comments.forEach((c, i) => {
    const isEdit = S.editing === c.id;
    const av = c.author === 'user' ? `<span class="av user">BG</span>` : `<span class="av agent">${I.bot}</span>`;
    const chips = i === 0 ? `<span class="right">${c.sev ? `<span class="sev ${c.sev}">${c.sev}</span><span class="fid">${c.fid}</span>` : ''}${t.outdated ? '<span class="chip outdated">Outdated</span>' : ''}<span class="chip ${cc}"><span class="d"></span>${cl}</span>${closed ? `<button class="ib" data-collapse="${t.id}" title="Collapse">${I.up}</button>` : ''}</span>` : '';
    const canEdit = c.author === 'user' && t.state === 'pending';
    const icons = `<span class="icons">${canEdit ? `<button class="ib" data-edit="${t.id}:${c.id}" title="Edit">${I.pen}</button>` : ''}<button class="ib del" data-delete="${t.id}:${c.id}" title="${i === 0 ? 'Delete thread' : 'Delete reply'}">${I.trash}</button></span>`;
    const out = c.outcome ? `<span class="outcome ${OUT[c.outcome][0]}">${OUT[c.outcome][1]}${OUT[c.outcome][2]}${c.sha ? ' · <code>' + c.sha + '</code>' : ''}</span>` : '';
    const body = isEdit
      ? `<div class="composer inline editing"><textarea data-edit-text>${esc(c.body)}</textarea><div class="cf"><span class="hint"></span><button class="btn ghost" data-edit-cancel>Cancel</button><button class="btn primary" data-edit-save="${t.id}:${c.id}">Save</button></div></div>`
      : `<div class="cbody">${out}${fmt(c.body)}</div>`;
    html += `<div class="c ${i > 0 ? 'reply' : ''} ${S.fresh.has(c.id) ? 'enter' : ''}">${av}<div class="cmain"><div class="chead"><span class="who">${c.author === 'user' ? 'You' : 'Agent'}</span>${c.src ? `<span class="src">${c.src}</span>` : ''}<span class="when">${c.when}</span>${i === 0 ? '' : '<span class="right"></span>'}${icons}${chips}</div>${body}</div></div>`;
  });
  if (S.confirm === t.id) {
    html += `<div class="confirm"><span>Delete this thread and its ${t.comments.length - 1 || 'no'} ${t.comments.length === 2 ? 'reply' : 'replies'}?</span><button class="btn ghost" data-keep>Keep</button><button class="btn danger" data-confirm-delete="${t.id}">Delete</button></div>`;
  } else if (S.replying === t.id) {
    html += `<div class="composer inline"><textarea placeholder="Reply" data-reply-text>${esc(S.replyText || '')}</textarea><div class="cf"><span class="hint"><span class="kc">⌘</span><span class="kc">↵</span> to reply</span><button class="btn ghost" data-reply-cancel>Cancel</button><button class="btn primary" data-reply-send="${t.id}">Reply</button></div></div>`;
  } else if (t.state === 'pending') {
    html += `<div class="tfoot"><span class="state-note">Only you can see this until you add the review.</span></div>`;
  } else if (t.state === 'open') {
    html += `<div class="tfoot"><div class="replybox" data-reply="${t.id}">Reply…</div><button class="btn" data-state="${t.id}:resolved"><span style="color:var(--st-done);display:grid">${I.check}</span>Resolve</button><button class="btn ghost" data-state="${t.id}:declined">${I.ban}Decline</button></div>`;
  } else {
    html += `<div class="tfoot"><div class="replybox" data-reply="${t.id}">Reply…</div><button class="btn ghost" data-state="${t.id}:open">${I.undo}Reopen</button></div>`;
  }
  return html + '</div>';
}

function composerHtml() {
  return `<div class="composer enter"><textarea placeholder="Leave a comment" data-composer>${esc(S.composer.text)}</textarea>
    <div class="cf"><span class="hint">Stays pending until you add the review</span>
    <button class="btn ghost" data-cancel>Cancel</button><button class="btn primary" data-addpending ${S.composer.text.trim() ? '' : 'disabled'}>Add to review <span class="kc">⌘↵</span></button></div></div>`;
}

function renderReviewBar() {
  const n = counts().pending, el = document.getElementById('reviewbar');
  if (n) el.innerHTML = `<span class="pulse"></span><span><span class="count">${n} pending ${n === 1 ? 'comment' : 'comments'}</span> <span class="sub">· only you can see them</span></span><span class="vr"></span>
    <button class="btn ghost" data-discard>Discard all</button><button class="btn primary" data-review>Add review <span class="kc">⌘↵</span></button>`;
  el.classList.toggle('show', n > 0);
}

let toastTimer;
function toast(text) {
  const el = document.getElementById('toast');
  el.innerHTML = `<span class="ok">${I.okc}</span>${text}`;
  el.classList.add('show'); clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2600);
}

/* ---------- actions ---------- */
function findThread(id) { return S.threads.find(t => t.id === id); }
function openComposer(fi, side, num) {
  S.composer = { file:F(fi), side, num, text:'' }; S.replying = null; render();
  const row = document.querySelector(`.row[data-f="${fi}"][data-side="${side}"][data-num="${num}"]`);
  row && row.nextElementSibling && row.nextElementSibling.scrollIntoView({ block:'nearest', behavior:'smooth' });
}
function addPending() {
  const text = S.composer.text.trim(); if (!text) return;
  const t = { id:uid(), file:S.composer.file, side:S.composer.side, num:S.composer.num, state:'pending', comments:[U(text)] };
  S.threads.push(t); S.fresh.add(t.id); S.composer = null; render();
}
function addReview() {
  const p = S.threads.filter(t => t.state === 'pending'); if (!p.length) return;
  p.forEach(t => { t.state = 'open'; });
  render(); toast(`Review added · ${p.length} ${p.length === 1 ? 'comment is' : 'comments are'} open`);
}
function setState(id, state) {
  const t = findThread(id); t.state = state;
  if (state === 'open') S.expanded.delete(id); else S.expanded.delete(id);
  S.fresh.add(id); render();
}
function jump(dir) {
  const order = navOrder(); if (!order.length) return;
  S.cursor = (S.cursor + dir + order.length) % order.length;
  const t = findThread(order[S.cursor]);
  S.selected = FILES.findIndex(f => f.path === t.file);
  if (host.model) { host.dispatchEvent(new CustomEvent('design-select-file', { bubbles: true, composed: true, detail: { path: t.file } })); return; }
  renderFiles(); renderHead();
  const el = document.getElementById('t-' + t.id);
  el.scrollIntoView({ block:'center', behavior:'smooth' });
  el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash');
}

document.getElementById('scenarios').addEventListener('click', e => { const b = e.target.closest('button'); if (b) load(+b.dataset.s); });

document.addEventListener('click', e => {
  const t = e.target.closest('[data-add],[data-cancel],[data-addpending],[data-review],[data-discard],[data-state],[data-expand],[data-collapse],[data-edit],[data-edit-cancel],[data-edit-save],[data-delete],[data-confirm-delete],[data-keep],[data-reply],[data-reply-cancel],[data-reply-send],[data-nav],.file');
  if (!t) return;
  const d = t.dataset;
  if (t.classList.contains('file')) { S.selected = +d.file; renderFiles(); renderHead(); document.getElementById('file-' + d.file).scrollIntoView({ behavior:'smooth' }); return; }
  if ('add' in d) { const r = t.closest('.row'); openComposer(+r.dataset.f, r.dataset.side, +r.dataset.num); return; }
  if ('cancel' in d) { S.composer = null; render(); return; }
  if ('addpending' in d) return addPending();
  if ('review' in d) return addReview();
  if ('discard' in d) { S.threads = S.threads.filter(x => x.state !== 'pending'); render(); return; }
  if (d.state) { const [id, st] = d.state.split(':'); return setState(id, st); }
  if (d.expand) { S.expanded.add(d.expand); S.fresh.add(d.expand); render(); return; }
  if (d.collapse) { S.expanded.delete(d.collapse); render(); return; }
  if (d.edit) { S.editing = d.edit.split(':')[1]; render(); return; }
  if ('editCancel' in d) { S.editing = null; render(); return; }
  if (d.editSave) { const [tid, cid] = d.editSave.split(':'); const c = findThread(tid).comments.find(c => c.id === cid); c.body = document.querySelector('[data-edit-text]').value.trim() || c.body; S.editing = null; render(); return; }
  if (d.delete) {
    const [tid, cid] = d.delete.split(':'); const th = findThread(tid);
    if (th.comments[0].id === cid) {
      if (th.state === 'pending') { S.threads = S.threads.filter(x => x !== th); render(); }
      else { S.confirm = tid; render(); }
    } else { th.comments = th.comments.filter(c => c.id !== cid); render(); }
    return;
  }
  if (d.confirmDelete) { const el = document.getElementById('t-' + d.confirmDelete); el.classList.add('leave'); setTimeout(() => { S.threads = S.threads.filter(x => x.id !== d.confirmDelete); S.confirm = null; render(); }, 180); return; }
  if ('keep' in d) { S.confirm = null; render(); return; }
  if (d.reply) { S.replying = d.reply; S.replyText = ''; render(); document.querySelector('[data-reply-text]').focus(); return; }
  if ('replyCancel' in d) { S.replying = null; render(); return; }
  if (d.replySend) return sendReply(d.replySend);
  if (d.nav) return jump(+d.nav);
});
function sendReply(tid) {
  const text = (document.querySelector('[data-reply-text]')?.value || '').trim(); if (!text) return;
  const c = U(text); findThread(tid).comments.push(c); S.fresh.add(c.id); S.replying = null; render();
}

document.addEventListener('input', e => {
  if (e.target.matches('[data-composer]')) {
    S.composer.text = e.target.value;
    const b = document.querySelector('[data-addpending]'); if (b) b.disabled = !S.composer.text.trim();
  }
  if (e.target.matches('textarea')) { e.target.style.height = 'auto'; e.target.style.height = e.target.scrollHeight + 'px'; }
});

document.getElementById('scroll').addEventListener('mouseover', e => {
  const r = e.target.closest('.row'); if (r) S.hover = { f:+r.dataset.f, side:r.dataset.side, num:+r.dataset.num };
});

document.addEventListener('keydown', e => {
  const inText = e.target.matches('textarea, input');
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
    e.preventDefault();
    if (e.target.matches('[data-composer]')) return addPending();
    if (e.target.matches('[data-reply-text]')) return sendReply(S.replying);
    if (e.target.matches('[data-edit-text]')) return document.querySelector('[data-edit-save]').click();
    return addReview();
  }
  if (e.key === 'Escape') {
    if (S.composer || S.replying || S.editing) e.stopImmediatePropagation();
    if (S.composer) { S.composer = null; render(); }
    else if (S.replying) { S.replying = null; render(); }
    else if (S.editing) { S.editing = null; render(); }
    return;
  }
  if (inText) return;
  if (e.key === 'c' && S.hover) { e.preventDefault(); openComposer(S.hover.f, S.hover.side, S.hover.num); }
  if (e.key === 'n') jump(1);
  if (e.key === 'p') jump(-1);
  if (!host.model && (e.key === 'j' || e.key === 'k')) { S.selected = Math.max(0, Math.min(FILES.length - 1, S.selected + (e.key === 'j' ? 1 : -1))); renderFiles(); renderHead(); document.getElementById('file-' + S.selected).scrollIntoView({ behavior:'smooth' }); }
});

if (saved) {
  S = saved.state;
  if (host.model) S.selected = Math.max(0, FILES.findIndex(file => file.path === host.model.selected));
  document.querySelectorAll('#scenarios button').forEach((button) => button.classList.toggle('on', +button.dataset.s === S.scenario));
  render(true);
} else if (host.model) {
  S = { scenario: 0, threads: [], selected: Math.max(0, FILES.findIndex(file => file.path === host.model.selected)), composer: null, editing: null, confirm: null, expanded: new Set(), fresh: new Set(), cursor: -1, hover: null };
  render(true);
} else load(Math.max(1, Math.min(4, Number(host.getAttribute('scenario')) || 1)));
return () => ({ state: S, id: _id });
  });
})();
