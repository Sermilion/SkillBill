/* Sona mobile study · run documents by kind
 * The desktop gives validation, Verify, monitor, plan and standalone QA runs their own detail document, and standalone
 * review runs Phase results and a Spec bundle (design/linear-study app/screens.js: validationDoc, verifyDoc, monitorDoc,
 * planParts, qaResults; HANDOFF 4.1 and 9.2). This module adds sample runs of each kind to MD.RUNS and renders their
 * documents for the phone. MSK.doc returns null for goals, which use the generic detail in app/screens.js. */
(function () {
  'use strict';
  const D = window.MD;
  const M = window.MB;
  const SB = window.SB;
  const { esc, Icon, StatusIcon } = SB;

  /* ---------- Sample runs ---------- */
  const add = (r) => D.RUNS.push(r);
  const val = D.RUNS.find((r) => r.id === 'ph-validation-news');
  Object.assign(val, {
    path: '~/dev/newsreader', updated: '10:08:12', workflow: null, invocation: 'inv-3b8e1f04c9a27d65e0b1-val', liveness: 'finished',
    valRun: {
      outcome: 'Passed', run: '#409', ofBranch: 'only run on branch', clock: '7m 02s', active: '6m 40s', started: '10:01', finishedAt: '10:08',
      checks: [
        { name: 'Unit tests', dir: 'app', cmd: './gradlew :app:testDebugUnitTest', state: 'Passed', time: '2m 10s', attempts: [['#1', '10:02', 'Passed', 'exit 0', '2m 10s', '']] },
        { name: 'Detekt', dir: '.', cmd: './gradlew detekt', state: 'Passed', time: '38s', attempts: [['#1', '10:04', 'Passed', 'exit 0', '38s', '']] },
        { name: 'ktlint', dir: '.', cmd: './gradlew ktlintCheck', state: 'Passed', time: '1m 02s', attempts: [['#1', '10:05', 'Failed', 'exit 1', '14s', '2 files misformatted'], ['#2', '10:06', 'Passed', 'exit 0', '48s', 'after ktlintFormat']] },
        { name: 'Android lint', dir: 'app', cmd: './gradlew :app:lintDebug', state: 'Passed', time: '1m 30s', attempts: [['#1', '10:06', 'Passed', 'exit 0', '1m 30s', '']] },
      ],
      headline: 'All 4 checks passed on main.',
      rest: 'ktlint failed once on two files; the agent ran ktlintFormat and the second attempt passed.',
      report: [
        { title: 'Checks run', preview: 'Unit tests, Detekt, ktlint, Android lint', items: ['Unit tests passed: 214 tests in 2m 10s.', 'Detekt passed with no new issues.', 'ktlint passed on attempt 2 after formatting ArticleCache.kt and DownloadWorker.kt.', 'Android lint passed with 3 existing warnings.'] },
        { title: 'Changes made', preview: 'Formatted 2 files', items: ['ArticleCache.kt and DownloadWorker.kt were reformatted. No behavior changed.'], flag: true },
        { title: 'Details', preview: 'Environment and timings', items: ['JDK 21, Gradle 9.3, configuration cache on.'] },
      ],
      branchRuns: [],
    },
  });
  add({
    id: 'ph-val-447', kind: 'phase', phaseName: 'validation', state: 'active', frac: 0.6, title: 'Validation on fix/ide-status-readonly-lease', repo: 'skill-bill', branch: 'fix/ide-status-readonly-lease', agent: 'codex', model: 'gpt-5.2', effort: 'high', elapsed: '9m', started: '13:49', step: 'Check 6 of 8 · Tray check and distributable', phase: 'validation',
    path: '~/IdeaProjects/skill-bill', invocation: 'inv-0e4c71b9a2d58f36c1e7-447', liveness: 'agent output 4s ago', updated: '13:58:30', runOptions: { canCancel: true },
    valRun: {
      outcome: null, run: '#413', ofBranch: '3 of 3 on branch', clock: '9m 12s', active: '8m 40s', started: '13:49',
      running: { name: 'Tray check and distributable', dir: 'desktop', cmd: './gradlew clean check createDistributable --no-build-cache', clock: '3:12' },
      tally: '5 passed · 1 running · 2 pending · 2 failed attempts fixed', bar: ['ok', 'ok', 'ok', 'ok', 'ok', 'run', '', ''], checksOf: '5 / 8', retried: '2 retried',
      checks: [
        { name: 'Runtime checks', dir: 'runtime-kotlin', cmd: './gradlew check --no-build-cache', state: 'Passed', time: '8m 00s', attempt: 'attempt 3', attempts: [['#1', '13:49', 'Failed', 'exit 1', '2m 30s', 'GoalRunnerTest timed out'], ['#2', '13:52', 'Failed', 'exit 1', '2m 10s', 'same test'], ['#3', '13:54', 'Passed', 'exit 0', '3m 20s', 'after raising the test timeout']] },
        { name: 'Contract parity', dir: 'runtime-kotlin', cmd: './gradlew :runtime-contracts:test', state: 'Passed', time: '1m 04s', attempts: [['#1', '13:57', 'Passed', 'exit 0', '1m 04s', '']] },
        { name: 'Skill validation', dir: '.', cmd: 'scripts/validate-skills.sh', state: 'Passed', time: '22s', attempts: [] },
        { name: 'Install smoke', dir: '.', cmd: './install.sh --dry-run', state: 'Passed', time: '41s', attempts: [] },
        { name: 'Desktop unit tests', dir: 'desktop', cmd: './gradlew :app:jvmTest', state: 'Passed', time: '2m 12s', attempts: [] },
        { name: 'Tray check and distributable', dir: 'desktop', cmd: './gradlew clean check createDistributable --no-build-cache', state: 'Running', time: '3m 12s', attempts: [] },
        { name: 'Screenshot tests', dir: 'desktop', cmd: './gradlew verifyRoborazzi', state: 'Pending', time: '', attempts: [] },
        { name: 'Docs links', dir: '.', cmd: 'scripts/check-links.sh', state: 'Pending', time: '', attempts: [] },
      ],
      signal: ['running gradlew createDistributable', '4s ago'],
      branchRuns: [['#411', 'Blocked on a flaky GoalRunnerTest', 'claude', '11:02', '14m', 'Canceled'], ['#412', 'Failed: Detekt found 2 issues', 'codex', '12:30', '11m', 'Failed'], ['#413', 'Running check 6 of 8', 'codex', '13:49', '9m', 'Running']],
    },
    activity: [['13:58', 'validation', 'Check 6 of 8 started'], ['13:56', 'validation', 'Runtime checks passed on attempt 3'], ['13:49', 'validation', 'Started · 8 checks from the validation receipt']],
  });

  const dsn = D.RUNS.find((r) => r.id === 'DSN-31');
  Object.assign(dsn, {
    path: '~/dev/design-tokens', issue: 'DSN-31', runOptions: { canCancel: true }, invocation: 'inv-77c1d0a9-dsn', liveness: 'parked', updated: '13:50:02',
    verify: {
      status: 'Awaiting you', range: 'a1b2c3d..e4f5a6b', intake: 'linear.app · DSN-31', mode: 'inline', qa: 'off', step: 2,
      nonGoals: ['Exporting to Sketch or Penpot.', 'Two-way sync from Figma back to tokens.'], constraints: ['Output must validate against the Figma Variables REST schema.', 'No new runtime dependencies.', 'Export runs offline.'], rollout: ['Ship behind the token-export command; no default build change.'],
      steps: [['Collect inputs', 'done', 'Linear issue · feat/DSN-31-figma-export · inline · QA off'], ['Extract criteria', 'wait', '5 criteria · 2 non-goals · 3 constraints · waiting for your confirmation']],
    },
  });
  add({
    id: 'op-verify-0ac', kind: 'operation', op: 'verify', state: 'done', title: 'Verify ATL-399', repo: 'atlas-web', branch: 'feat/ATL-399-saved-searches', agent: 'claude', model: 'opus', effort: 'high', elapsed: '18m', started: '09:20', finished: '09:38', step: 'Verdict · approve with fixes', phase: 'verdict', issue: 'ATL-399', today: true,
    path: '~/dev/atlas-web', runOptions: {}, invocation: 'inv-0ac8612f-ver', liveness: 'finished', updated: '09:38:40',
    verify: {
      status: 'Finished', range: '91c0e2a..b7d4f19', intake: 'linear.app · ATL-399', mode: 'inline', qa: 'on', verdict: 'APPROVE WITH FIXES', downgraded: true,
      summary: 'Saved searches persist per user, show in a sidebar list and reopen with their filters. One criterion is partial: renaming a saved search does not update the URL until reload.',
      criteria: [['1', 'A user can save the current search with a name.', 'PASS', ['SavedSearchDialog.tsx']], ['2', 'Saved searches list in the sidebar, newest first.', 'PASS', ['SavedSearchList.tsx']], ['3', 'Opening a saved search restores its filters and page size.', 'PASS', ['useSavedSearch.ts']], ['4', 'Renaming a saved search updates it everywhere it shows.', 'PARTIAL', ['SavedSearchList.tsx', 'useSearchParams.ts'], 'The URL keeps the old name until reload.'], ['5', 'Deleting asks for confirmation.', 'PASS', ['ConfirmDelete.tsx']], ['6', 'Saved searches sync across devices.', 'FAIL', [], 'Not found. Saves go to localStorage only.']],
      review: { findings: 3, sub: '2 minor · 1 major' }, tests: { verdict: 'valuable', sub: '3 valuable · 1 weak' }, qaCell: { value: '1 open', sub: '5 of 7 checks passed' },
      steps: [['Collect inputs', 'done', 'Linear issue · feat/ATL-399-saved-searches · inline · QA on'], ['Extract criteria', 'done', '6 criteria · 2 non-goals · 1 constraint'], ['Gather diff', 'done', '14 files · 4 test files · 91c0e2a..b7d4f19'], ['Feature flag audit', 'skip', 'Skipped · no flag required'], ['Code review', 'warn', 'Changes requested · 1 major · 2 minor'], ['Unit test value', 'done', 'Valuable · 3 valuable · 1 weak'], ['Completeness audit', 'warn', '4 met · 1 partial · 1 missing'], ['QA', 'warn', '1 open finding · 5 of 7 checks passed'], ['Verdict', 'done', 'Approve with fixes']],
      findings: [['F-001', 'major', 'Rename writes the new name but the URL keeps the old slug until reload.', 'useSearchParams.ts:44'], ['F-002', 'minor', 'Saved search names are not trimmed.', 'SavedSearchDialog.tsx:31'], ['F-003', 'minor', 'List re-sorts on every render.', 'SavedSearchList.tsx:18']],
      nonGoals: ['Sharing saved searches with other users.'], constraints: ['Keep the search API unchanged.'], rollout: [],
    },
  });

  const mon = D.RUNS.find((r) => r.id === 'op-monitor-482');
  Object.assign(mon, {
    path: '~/IdeaProjects/skill-bill', base: 'main', pr: '#482', prTitle: 'Retry flaky upload', runOptions: { canCancel: true }, invocation: 'inv-5e21c0d9-mon', liveness: 'polling CI', updated: '13:58:20',
    monitor: {
      word: 'Watching CI', head: 'Waiting on build-desktop (macos) and runtime-tests. 3 of 5 checks passed.', signal: ['gh pr checks 482', '20s ago'], clock: '22m',
      cells: { headSha: '9b2f0c4', headSub: '1 repair published', checks: '3 / 5', checksSub: '2 running', cap: '1 of 3', capSub: '2 repairs left' },
      cycles: [
        { n: 1, cells: [{ key: 'ci', word: '1 failed', sub: '4 of 5 passed', tone: 'failed', bar: ['ok', 'ok', 'ok', 'ok', 'bad'] }, { key: 'fix', word: 'Fixed', sub: 'screenshot-diff', tone: 'done' }, { key: 'pub', word: 'Pushed', sub: '9b2f0c4', tone: 'done' }] },
        { n: 2, cells: [{ key: 'ci', word: '3 of 5 passed', sub: '2 running', tone: 'active', bar: ['ok', 'ok', 'ok', 'run', 'run'] }, null, null], empty: 'Starts on a failure' },
      ],
      ci: { sha: '9b2f0c4', since: '13:44', dur: '14m', read: 'read 20s ago · next in 10s', groups: [['Running', [['build-desktop (macos)', 'build', '6m 10s'], ['runtime-tests', 'test', '9m 02s']]], ['Passed', [['lint', 'lint', '1m 12s'], ['screenshot-diff', 'test', '4m 40s'], ['build-desktop (linux)', 'build', '5m 02s']]]] },
      repair: { n: 1, outcome: 'fixed', summary: 'Updated two golden images after the toolbar icon change.', agent: 'claude · sonnet', files: [['M', 'toolbar_dark.png', 'desktop/…/goldens', 0, 0], ['M', 'toolbar_light.png', 'desktop/…/goldens', 0, 0]], gates: [['Screenshot tests', 'ok', '2m 31s']] },
      publish: { n: 1, steps: [['Committed', '9b2f0c4', 'Update toolbar goldens'], ['Pushed', 'fix/upload-retry', '']] },
      activity: [['13:36', 'Cycle 1 started', ''], ['13:41', 'screenshot-diff failed', 'fail'], ['13:44', 'monitor_fix pushed 9b2f0c4', 'done'], ['13:44', 'Cycle 2 started', 'now']],
    },
  });

  add({
    id: 'ph-plan-520', kind: 'phase', phaseName: 'plan', state: 'active', frac: 0.45, title: 'Plan SKILL-520 · Pair a phone over SSH', repo: 'skill-bill', branch: 'main', agent: 'claude', model: 'opus', effort: 'high', elapsed: '11m', started: '13:47', step: 'Writing subtask specs · 2 of 6 saved', phase: 'plan_subtasks',
    path: '~/IdeaProjects/skill-bill', issue: 'SKILL-520', runOptions: { canCancel: true }, invocation: 'inv-9c2e1d04-pln', liveness: 'agent output 2s ago', updated: '13:58:44',
    plan: {
      stages: [{ id: 'preplan', label: 'Reading the repo', state: 'done', time: '2m 40s' }, { id: 'plan', label: 'Splitting the work', state: 'done', time: '3m 12s' }, { id: 'plan_subtasks', label: 'Writing subtask specs', state: 'run', time: '5m 08s' }],
      sentence: 'Writing specs for subtasks 3 to 5. 2 of 6 saved.', stepLine: '#3 · Write spec_subtask_3_register-fcm-token.md', pulse: 'Last output 2s ago · Elapsed 11m · claude',
      files: { spec: true, manifest: true }, units: [['1', 'SSH key and host pinning', 'saved', '1m 20s'], ['2', 'Board read over SSH', 'saved', '1m 44s'], ['3', 'Register the FCM token', 'writing', '0:41', 'reading NotificationSink.kt'], ['4', 'Push relay client', 'queued'], ['5', 'Machines screen', 'queued'], ['6', 'Connection states', 'queued']],
      feed: [['13:58', 'read', 'desktop/app/…/notifications/NotificationSink.kt'], ['13:57', 'write', 'spec_subtask_2_board-read-over-ssh.md'], ['13:56', 'cmd', 'rg -n "TransitionNotifier" desktop'], ['13:55', 'read', 'design/mobile-study/HANDOFF.md'], ['13:54', 'write', 'spec_subtask_1_ssh-key-and-host-pinning.md']],
      counts: { all: 41, files: 28, commands: 9, notes: 4 },
      transcript: 'Reading NotificationSink.kt to see how TransitionNotifier hands events to sinks.\nThe push sender can be another NotificationSink registered at app start.\nSubtask 3 owns token registration over SSH: the phone writes its FCM token to ~/.skill-bill/push/devices.json on the machine.',
      briefing: { saved: 3, sizes: [['preplan', '12.4 KB'], ['plan', '18.0 KB'], ['plan_subtasks', '9.6 KB']], criteria: 6 },
    },
  });

  add({
    id: 'ph-qa-pay', kind: 'phase', phaseName: 'qa', state: 'done', title: 'QA on feat/refunds', repo: 'payments-svc', branch: 'feat/refunds', agent: 'codex', model: 'gpt-5.2', effort: 'high', elapsed: '16m', started: '12:40', finished: '12:56', step: 'Needs your review · 1 problem', phase: 'qa', today: true,
    path: '~/dev/payments-svc', runOptions: { canLaunch: true, launchLabel: 'Run again', canFinalize: true }, invocation: 'inv-4f02e8a1-qa', liveness: 'finished', updated: '12:56:03',
    qaRun: {
      verdict: 'Needs your review', tone: 'failed', sentence: 'QA found 1 problem and could not test 1 thing. 4 checks passed. QA left 1 process running.',
      open: [{ ref: 'QA-1', title: 'Partial refund over the captured amount returns 500', state: 'Open', observed: 'POST /refunds with amount 120 on a 100 capture returned 500 instead of 422.', expected: 'A 422 with error code refund_exceeds_capture.', steps: '1. Capture 100\n2. Refund 120\n3. Read the status', evidence: ['refund-500.log', 'request.json'] }],
      left: [['Process still running', 'stripe-mock, pid 48211. Release reported it as retained.', 'Copy pid']],
      notTested: [['Webhook retries, capability missing', 'No tunnel for inbound webhooks in this sandbox.']],
      passed: ['Full refund returns 200', 'Refund is idempotent by key', 'Refund on an uncaptured charge returns 409', 'Refund lists under the charge'],
      coverage: 'Backend tested · Web, Android not in scope',
      details: [['Failure kind', 'none'], ['Report', 'report.md']],
      report: 'Verdict: findings\n\nI exercised the refund endpoints against stripe-mock. One problem: an over-capture refund returns 500.\n\n## Coverage\n\n- backend: passed with findings\n- web: not in scope',
    },
  });

  const rv = D.RUNS.find((r) => r.id === 'ph-review-refunds');
  Object.assign(rv, {
    path: '~/dev/payments-svc', runOptions: { canCancel: true }, invocation: 'inv-2a90c3f1-rv', liveness: 'agent output 8s ago', updated: '13:58:10',
    phaseResults: { passes: [{ n: 1, verdict: 'running', open: 2, findings: [['F-001', 'Major', 'A refund retry can double-post when the first call times out after Stripe accepted it.', 'refund.py:88', 'Open'], ['F-002', 'Minor', 'The idempotency key is logged in plain text.', 'refund.py:41', 'Open']] }], audit: null },
    specBundle: ['decomposition-manifest.yaml', 'spec.md', 'spec_subtask_1_refund-endpoint.md', 'spec_subtask_2_retry-policy.md'],
  });

  const news = D.RUNS.find((r) => r.id === 'NEWS-12');
  news.runApp = { device: 'Pixel 8 (emulator)', off: 'Run app turns on when the goal completes or reaches monitoring.' };

  /* ---------- Shared bits ---------- */
  const tone = (w) => ({ Passed: 'done', Finished: 'done', Failed: 'failed', Blocked: 'blocked', Running: 'active', Pending: 'pending', Skipped: 'pending', Canceled: 'pending' }[w] || 'pending');
  const STATE_OF = { Passed: 'done', Failed: 'failed', Blocked: 'blocked', Running: 'active', Pending: 'pending', Skipped: 'skipped', Canceled: 'canceled' };
  const header = (r, h, o) => `<div class="mb-runhead">${M.Chips2([M.StatePill({ state: o.state || r.state, frac: r.frac, text: o.word }), `<span class="mono t-faint" style="font-size:11.5px">${esc(o.key)}</span>`, o.pill ? M.Tag({ text: o.pill }) : '', r.state === 'active' ? M.Tag({ text: 'fresh' }) : '', o.chip || ''])}<h2 class="mb-runhead-title">${o.title}</h2><div class="mb-runhead-meta">${o.meta.join('')}</div></div>`;
  const meta = (txt, icon) => `<span class="l-row g-1">${icon ? Icon(icon, 'xs') : ''}<span>${txt}</span></span>`;
  const btns = (list) => `<div class="mb-section-pad l-row g-2 l-wrap" style="margin-top:2px">${list.join('')}</div>`;
  const events = (rows) => rows.map(([t, a, b]) => M.EventLine({ ago: t, lead: `<i class="mb-dot ${b ? 'mb-dot--' + b : ''}"></i>`, html: a })).join('');

  /* ---------- Validation run ---------- */
  function validationDoc(st, r, h) {
    const v = r.valRun;
    const word = v.outcome || (r.state === 'active' ? 'Active' : SB.STATES[r.state].label);
    const acts = r.state === 'active' ? [] : [M.Button({ label: 'Run again', icon: 'rerun', size: 'sm', act: 'sheet', arg: 'resume:' + r.id }), M.Button({ label: 'Copy report', icon: 'copy', size: 'sm', act: 'copy', arg: 'report' })];
    const head = header(r, h, { state: v.outcome ? STATE_OF[v.outcome] : r.state, word, key: 'validation', pill: 'phase', title: `Validation on <code class="mono" style="font-size:17px">${esc(r.branch)}</code>`, meta: [meta(esc(r.path), 'folder'), meta(`${M.Agent(r.agent, { sm: true })} ${esc(r.agent)}`)] });
    const cells = [
      { label: 'Active', value: esc(v.active), sub: r.state === 'active' ? 'ticking' : 'agent working time' },
      { label: 'Started', value: esc(v.started), sub: v.finishedAt ? 'Today → ' + v.finishedAt : 'Today' },
      { label: 'Checks', value: esc(v.checksOf || `${v.checks.filter((c) => c.state === 'Passed').length} / ${v.checks.length}`), sub: v.retried || (v.checks.some((c) => c.attempts.length > 1) ? '1 retried' : 'no retries'), act: 'sec', arg: 'val-checks|0' },
      { label: 'Run', value: esc(v.run), sub: v.ofBranch },
    ];
    const outcome = r.state === 'active'
      ? M.Outcome({ tone: 'active', live: true, label: '', head: `Running <b>${esc(v.running.name)}</b>`, extra: [`<div class="mb-pk-cmd"><span class="t-faint">${esc(v.running.dir)} $</span> ${esc(v.running.cmd)} <span class="t-accent">${esc(v.running.clock)}</span></div>`, `<div class="mb-ex-s">${esc(v.tally)}</div>`, M.SegBar(v.bar), `<div class="mb-ex-s">Last agent signal: ${esc(v.signal[0])}, ${esc(v.signal[1])}</div>`], when: v.clock, whenNote: 'active', cells })
      : M.Outcome({ tone: tone(v.outcome), label: `<b style="color:var(--st-${tone(v.outcome)})">${esc(v.outcome)}</b> · agent's summary below`, head: esc(v.headline), text: esc(v.rest), extra: [M.SegBar(v.checks.map((c) => (c.state === 'Passed' ? 'ok' : 'bad')))], when: v.clock, whenNote: 'wall clock', cells });
    const flagged = (v.report || []).filter((p) => p.flag);
    const flags = flagged.length ? `<div class="mb-banner2" style="background:color-mix(in srgb, var(--st-blocked) 10%, transparent)"><span><strong style="color:var(--st-blocked)">The report flags ${flagged.length === 1 ? 'one thing' : flagged.length + ' things'} to read</strong></span>${flagged.map((p) => M.Button({ label: p.title, size: 'sm', act: 'sec', arg: 'rp-' + p.title + '|1' })).join('')}</div>` : '';
    const checks = h.sec(st, 'val-checks', { title: 'Checks', icon: 'check', count: v.checks.length, end: 'reported by the agent', body: v.checks.map((c, i) => M.ExRow({
      lead: StatusIcon(STATE_OF[c.state], 0.5), title: esc(c.name), sub: `<span class="mono">${esc(c.dir)} ${esc(c.cmd)}</span>`, end: `<span class="t-${tone(c.state)}">${esc(c.state)}</span><span>${c.attempt ? esc(c.attempt) + ' · ' : ''}${esc(c.time)}</span>`,
      open: !!st.sec['ck-' + r.id + i], act: 'sec', arg: 'ck-' + r.id + i + '|0',
      body: c.attempts.length ? c.attempts.map(([n, t, w, ex, d, note]) => `<div class="mb-attempt"><span class="mono">${n}</span><span>${t}</span><span class="t-${tone(w)}">${w}</span><span class="mono">${ex}</span><span>${d}</span>${note ? `<span>· ${esc(note)}</span>` : ''}</div>`) : ['<span class="t-faint">No attempts recorded yet.</span>'],
    })) });
    const report = v.report ? h.sec(st, 'val-report', { title: 'Report', icon: 'fileText', count: v.report.length + ' parts', body: v.report.map((p) => M.ExRow({ title: esc(p.title), sub: esc(p.preview), end: `<span>${p.items.length} item${p.items.length > 1 ? 's' : ''}</span>`, flag: p.flag, open: h.collapsed(st, 'rp-' + p.title, true) === false, act: 'sec', arg: 'rp-' + p.title + '|1', body: p.items.map((x) => `<p>${esc(x)}</p>`) })) }) : '';
    const branch = v.branchRuns.length > 1 ? h.sec(st, 'val-branch', { title: 'Validation runs on this branch', icon: 'branch', count: v.branchRuns.length, body: v.branchRuns.map(([n, line, ag, at, dur, w]) => M.Row({ lead: `<i class="mb-dot mb-dot--${w === 'Running' ? 'now' : w === 'Failed' ? 'fail' : ''}"></i>`, title: `<span class="mono">${n}</span> <span class="t-2" style="font-size:13px">${esc(line)}</span>${n === v.run ? M.Tag({ text: 'this run' }) : ''}`, sub: `${esc(ag)} · ${at} · ${dur}`, trail: `<span class="t-${tone(w)}">${w}</span>` })) }) : '';
    const activity = h.sec(st, 'val-activity', { title: 'Activity', icon: 'history', count: (r.activity || []).length, body: r.activity ? events(r.activity.map(([t, ph, x]) => [t, `<b>${esc(ph)}</b> ${esc(x)}`])) : '<div class="mb-pad t-faint">No activity recorded for this run.</div>' });
    return [head, acts.length ? btns(acts) : '', outcome, flags, checks, report, branch, activity].join('');
  }

  /* ---------- Verify run ---------- */
  const GRADE = { PASS: 'done', PARTIAL: 'blocked', FAIL: 'failed' };
  function verifyDoc(st, r, h) {
    const v = r.verify;
    const parked = v.status === 'Awaiting you';
    const running = v.status === 'Running';
    const statusTone = { 'Awaiting you': 'paused', Finished: 'done', Running: 'active' }[v.status];
    const head = header(r, h, { state: statusTone, word: v.status, key: r.issue || 'No issue key', pill: 'Verify', title: esc('Verify ' + (r.issue || r.title)), meta: [meta(`<span class="mono">${esc(r.repo)}</span>`, 'folder'), meta(`<span class="mono">${esc(r.branch)}</span>`, 'branch'), meta(`<span class="mono">${esc(v.range)}</span>`, 'commit'), meta(`<span class="t-accent">${esc(v.intake)}</span>`, 'link')] });
    const acts = running ? [M.Button({ label: 'Stop', icon: 'stop', size: 'sm', variant: 'danger', act: 'sheet', arg: 'cancel:' + r.id }), M.Button({ label: 'Open changes', size: 'sm', act: 'toast', arg: 'Opens the changed files.' })] : parked ? [M.Button({ label: 'Adjust in run chat', icon: 'message', size: 'sm', act: 'run-chat', arg: r.id }), M.Button({ label: 'Discard', size: 'sm', variant: 'danger', act: 'sheet', arg: 'cancel:' + r.id })]
      : [M.Button({ label: 'Copy report', icon: 'copy', size: 'sm', act: 'copy', arg: 'report' }), M.Button({ label: 'Open changes', size: 'sm', act: 'toast', arg: 'Opens the 14 changed files.' }), M.Button({ label: 'Verify again', size: 'sm', act: 'toast', arg: 'Starts Verify again with the same intake.' })];
    const crit = v.criteria || (r.criteria || []).map(([ref, text], i) => [String(i + 1), text, null, []]);
    const what = v.summary
      ? h.sec(st, 'v-what', { title: 'What this work does', icon: 'info', body: `<div class="mb-pad"><p class="mb-p" style="margin:0">${esc(v.summary)}</p><button type="button" class="mb-linkbtn" data-act="sec" data-arg="vs-9|1">Full summary in Verdict step</button></div>` })
      : h.sec(st, 'v-what', { title: 'What this work does', icon: 'info', body: `<div class="mb-pad"><div class="mb-sublabel">What this work is meant to do · from the extracted criteria</div><p class="mb-p" style="margin:6px 0">${esc(crit[0][1])}</p><div class="mb-ex-s">${crit.length - 1} more criteria below. A summary of the change comes with the verdict, after you confirm.</div></div>` });
    const chk = st.criteria[r.id] || {};
    const met = crit.filter((c) => c[2] === 'PASS').length;
    const outcome = running
      ? M.Outcome({ tone: 'active', live: true, label: 'Step 3 of 9', verdict: 'Gather diff', text: 'Results from earlier steps are below.', when: '0:12', whenNote: 'running', cells: [{ label: 'Criteria', value: `${crit.length} confirmed`, sub: 'not audited yet', act: 'sec', arg: 'v-crit|0' }, { label: 'Review', value: 'not run' }, { label: 'Tests', value: 'not run' }, { label: 'QA', value: v.qa === 'off' ? 'off' : 'pending' }] })
      : parked
      ? M.Outcome({ tone: 'neutral', label: 'Step 2 of 9 · waiting for you', verdict: 'Confirm the criteria', text: 'Verify extracted these criteria from the intake. Untick any that should not be audited, then confirm. Nothing is audited until you do.', when: '13:50', whenNote: 'parked',
        cells: [{ label: 'Criteria', value: `${crit.length} extracted`, sub: 'not audited', act: 'sec', arg: 'v-crit|0' }, { label: 'Review', value: 'not run' }, { label: 'Tests', value: 'not run' }, { label: 'QA', value: v.qa === 'off' ? 'off' : 'pending' }] })
      : M.Outcome({ tone: v.verdict === 'APPROVE' ? 'done' : 'blocked', label: 'Verdict', verdict: v.verdict, vtone: v.verdict === 'APPROVE' ? 'done' : 'blocked', text: 'Verification finished. See Steps for findings and evidence.' + (v.downgraded ? ' QA had open findings, so the runtime lowered APPROVE to APPROVE WITH FIXES.' : ''), when: r.elapsed, whenNote: r.finished,
        cells: [{ label: 'Criteria', value: `${met} / ${crit.length} met`, sub: `${crit.filter((c) => c[2] === 'PARTIAL').length} partial · ${crit.filter((c) => c[2] === 'FAIL').length} missing`, bar: crit.map((c) => GRADE[c[2]] || ''), act: 'sec', arg: 'v-crit|0' }, { label: 'Review', value: v.review.findings + ' findings', sub: v.review.sub }, { label: 'Tests', value: esc(v.tests.verdict), sub: v.tests.sub }, { label: 'QA', value: esc(v.qaCell.value), sub: v.qaCell.sub, tone: 'blocked' }] });
    const filter = st.vfilter || 'all';
    const shown = crit.filter((c) => filter === 'all' || (filter === 'gaps' ? c[2] && c[2] !== 'PASS' : c[2] === 'PASS'));
    const critBody = [
      `<div class="mb-sec-tools">${M.Segmented({ act: 'vfilter', value: filter, options: [['all', 'All'], ['gaps', 'Gaps', crit.filter((c) => c[2] && c[2] !== 'PASS').length], ['met', 'Met', met]] })}</div>`,
      `<div class="mb-pad mb-ex-s" style="padding-top:4px">${parked ? 'Extracted from the intake. Confirm them to start the audit.' : 'Extracted and confirmed. Grades come from the completeness audit.'}</div>`,
      shown.length ? shown.map(([n, text, grade, files, missing]) => M.ExRow({
        lead: parked ? M.Checkbox({ on: chk['AC-00' + n] !== false, act: 'crit', arg: r.id + ':AC-00' + n }) : `<span class="mono t-faint" style="width:14px;font-size:11px;padding-top:2px">${n}</span>`,
        title: `${parked ? `<span class="mono t-faint" style="font-size:11px">AC-00${n}</span>` : ''}${esc(text)}`, end: grade ? M.Badge({ text: grade, tone: GRADE[grade] }) : M.Badge({ text: parked ? 'To confirm' : 'Not audited' }),
        open: !!st.sec['vc-' + r.id + n], act: 'sec', arg: 'vc-' + r.id + n + '|0',
        body: grade ? [files.length ? `<div class="mb-sublabel">Evidence</div>${M.FileChips(files)}` : '', missing ? `<div class="mb-sublabel">${grade === 'FAIL' ? 'Not found' : "What's missing"}</div><p>${esc(missing)}</p>` : '', grade !== 'PASS' ? `<div class="mb-sublabel">Not a code gap here?</div><div class="l-row g-2">${M.Button({ label: 'Not in this repo', size: 'sm', act: 'toast', arg: 'Marked: not in this repo.' })}${M.Button({ label: 'Spec question', size: 'sm', act: 'toast', arg: 'Marked: spec question.' })}</div>` : ''] : ['<p class="t-faint">The audit runs after you confirm.</p>'],
      })).join('') : '<div class="mb-pad t-faint">Nothing in this filter.</div>',
    ];
    const critSec = h.sec(st, 'v-crit', { title: 'Criteria', icon: 'flag', count: crit.length, body: critBody });
    const ctx = [['Non-goals', v.nonGoals, 'exclusion', 'x'], ['Technical constraints', v.constraints, 'requirement', 'shield'], ['Rollout', v.rollout, 'Release approach', 'arrowRight']].filter((c) => c[1] && c[1].length).map(([title, items, unit, icon]) => M.ExRow({ lead: Icon(icon, 'sm'), title: esc(title), sub: esc(items[0]), end: unit === 'Release approach' ? unit : `${items.length} ${unit}${items.length > 1 ? 's' : ''}`, open: !!st.sec['vx-' + title], act: 'sec', arg: 'vx-' + title + '|0', body: items.map((x) => `<p>${esc(x)}</p>`) })).join('');
    const ctxSec = ctx ? `<section class="mb-sec">${ctx}</section>` : '';
    const ALL = ['Collect inputs', 'Extract criteria', 'Gather diff', 'Feature flag audit', 'Code review', 'Unit test value', 'Completeness audit', 'QA', 'Verdict'];
    const stepsBody = ALL.map((name, i) => {
      const s = v.steps[i];
      const state = s ? s[1] : 'pend';
      const receipt = !s ? ['<p class="t-faint">' + (name === 'QA' && v.qa === 'off' ? 'Off for this run' : 'Not started') + '</p>']
        : name === 'Collect inputs' ? [M.KV([['Intake', esc(v.intake)], ['Repository', esc(r.repo)], ['Target', `<span class="mono">${esc(r.branch)}</span>`], ['Range', `<span class="mono">${esc(v.range)}</span>`], ['Review mode', v.mode], ['QA', v.qa]])]
          : name === 'Code review' && v.findings ? v.findings.map(([ref, sev, text, loc]) => `<div class="mb-pk-f"><span class="mb-pk-f-top"><span class="mono">${ref} · ${sev}</span></span><span>${esc(text)}</span>${M.FileChips([loc])}</div>`).concat([M.Button({ label: 'Verify findings', size: 'sm', act: 'toast', arg: 'Opens the findings for verification.' })])
            : name === 'Verdict' && v.summary ? [`<p>${esc(v.summary)}</p>`] : [`<p>${esc(s[2])}</p>`];
      return M.ExRow({ lead: M.StepNode(state), title: `<span class="tnum t-faint">${i + 1}</span> ${esc(name)}`, sub: s ? esc(s[2]) : name === 'QA' && v.qa === 'off' ? 'Off for this run' : 'Not started', open: !!st.sec['vs-' + (i + 1)], act: 'sec', arg: 'vs-' + (i + 1) + '|1', body: receipt });
    }).join('');
    const steps = h.sec(st, 'v-steps', { title: 'Steps', icon: 'workflow', count: `${v.steps.filter((x) => x[1] !== 'pend').length} of 9`, body: stepsBody });
    return [head, btns(acts), what, outcome, critSec, ctxSec, steps].join('');
  }

  /* ---------- Monitor run ---------- */
  function monitorDoc(st, r, h) {
    const m = r.monitor;
    const head = header(r, h, { key: 'monitor', pill: 'phase', title: `<span class="t-faint">${esc(r.pr)}</span> ${esc(r.prTitle)}`, meta: [meta(esc(r.path), 'folder'), meta(`<span class="mono">${esc(r.branch)} → ${esc(r.base)}</span>`, 'branch')] });
    const acts = btns([M.Button({ label: 'Open PR', icon: 'external', size: 'sm', act: 'toast', arg: 'Opens ' + r.pr + ' on GitHub.' })]);
    const outcome = M.Outcome({ tone: 'active', live: true, label: `${esc(m.word)} · monitor`, head: esc(m.head), extra: [`<div class="mb-pk-sig"><i class="mb-live"></i><span>${esc(m.signal[0])}</span><em>${esc(m.signal[1])}</em></div>`], when: m.clock, whenNote: 'active',
      cells: [{ label: 'Pull request', value: esc(r.pr), sub: 'open → ' + r.base }, { label: 'Head', value: `<span class="mono">${esc(m.cells.headSha)}</span>`, sub: m.cells.headSub }, { label: 'Checks', value: m.cells.checks, sub: m.cells.checksSub, bar: ['ok', 'ok', 'ok', 'run', 'run'] }, { label: 'Repair cap', value: m.cells.cap, sub: m.cells.capSub }] });
    const pick = st.monPick || '2:ci';
    const grid = M.CycleGrid({ act: 'mon-pick', cols: ['CI · monitor', 'Repair · monitor_fix', 'Publish · commit_push'], rows: m.cycles.map((c) => ({ n: c.n, empty: c.empty, cells: c.cells.map((x) => x && Object.assign({}, x, { on: pick === c.n + ':' + x.key })) })) });
    const cycles = h.sec(st, 'm-cycles', { title: 'Cycles', icon: 'loop', count: m.cycles.length, body: [`<div class="mb-pad mb-ex-s" style="padding-bottom:4px">A failure starts a repair; a published repair starts the next cycle.</div>`, grid, `<div class="mb-pad mb-ex-s" style="padding-top:0">2 cycles · 1 published · polls every 30s</div>`] });
    const [, key] = pick.split(':');
    let panel;
    if (key === 'ci') {
      const ci = m.ci;
      panel = [`<div class="mb-pad"><div class="mb-ex-s">Watching · since ${ci.since} · ${ci.dur} · ${esc(ci.read)}</div></div>`, ci.groups.map(([g, rows]) => `<div class="mb-group-h" style="position:static;height:30px">${g}<span class="mb-group-n">${rows.length}</span></div>` + rows.map(([n, job, t]) => M.Row({ lead: StatusIcon(g === 'Passed' ? 'done' : 'active', 0.5), title: esc(n), sub: esc(job), trail: `<span class="tnum">${t}</span>` })).join('')).join(''), `<div class="mb-pad">${M.Button({ label: 'Checks on GitHub', icon: 'external', full: true, size: 'sm', act: 'toast', arg: 'Opens the PR checks on GitHub.' })}</div>`];
    } else if (key === 'fix') {
      const rp = m.repair;
      panel = [`<div class="mb-pad l-col g-2"><div class="l-row g-2">${M.Badge({ text: 'Repair outcome: ' + rp.outcome, tone: 'done' })}</div><p class="mb-p" style="margin:0">${esc(rp.summary)}</p><div class="mb-ex-s">${esc(rp.agent)}</div></div>`, `<div class="mb-group-h" style="position:static;height:30px">Files changed<span class="mb-group-n">${rp.files.length}</span></div>`, rp.files.map((f) => M.FileRow({ status: f[0], name: f[1], dir: f[2], add: f[3], del: f[4], act: 'toast', arg: 'Binary file. No text diff.' })).join(''), `<div class="mb-group-h" style="position:static;height:30px">Local checks</div>`, rp.gates.map(([n, s0, t]) => M.Row({ lead: StatusIcon('done'), title: esc(n), trail: t })).join(''), '<div class="mb-pad mb-ex-s">CI decides; these only allow the push.</div>'];
    } else {
      const p = m.publish;
      panel = [`<div class="mb-pad mb-ex-s">The runtime commits and pushes, not the agent.</div>`, p.steps.map(([w, v0, msg]) => M.Row({ lead: StatusIcon('done'), title: `${w} <span class="mono t-2" style="font-size:12px">${esc(v0)}</span>`, sub: esc(msg) })).join(''), `<div class="mb-pad">${M.Button({ label: 'Commit on GitHub', icon: 'external', full: true, size: 'sm', act: 'toast', arg: 'Opens the commit on GitHub.' })}</div>`];
    }
    const titles = { ci: 'CI · monitor', fix: 'Repair · monitor_fix', pub: 'Publish · commit_push' };
    const step = h.sec(st, 'm-step', { title: `Cycle ${pick.split(':')[0]} · ${titles[key]}`, icon: 'monitor', end: pick === '2:ci' ? '<span class="t-accent">live</span>' : '', body: panel });
    const activity = h.sec(st, 'm-activity', { title: 'Activity', icon: 'history', count: m.activity.length, body: events(m.activity.map(([t, x, tn]) => [t, esc(x), tn])) });
    return [head, acts, outcome, cycles, step, activity].join('');
  }

  /* ---------- Plan run ---------- */
  function planDoc(st, r, h) {
    const p = r.plan;
    const head = header(r, h, { state: 'planning', word: 'Planning', key: 'plan', pill: 'phase', chip: M.Tag({ text: r.model + ' · ' + r.effort + ' effort' }), title: esc(r.title), meta: [meta(esc(r.path), 'folder'), meta(`<span class="mono">${esc(r.issue)}</span>`, 'flag')] });
    const acts = btns([M.Button({ label: 'Implement', icon: 'play', size: 'sm', disabled: true })]) + '<div class="mb-section-pad mb-ex-s" style="margin-top:6px">Implement turns on when every spec is saved</div>';
    const now = M.Outcome({ tone: 'active', live: true, label: 'Now', head: esc(p.sentence), extra: [`<div class="mb-pk-sig">${Icon('pencil', 'sm')}<span class="mono">${esc(p.stepLine)}</span></div>`, `<div class="mb-ex-s">${esc(p.pulse)}</div>`] });
    const f = st.pfilter || 'all';
    const kinds = { read: 'files', write: 'files', cmd: 'commands', note: 'notes' };
    const feed = p.feed.filter((x) => f === 'all' || kinds[x[1]] === f);
    const actSec = h.sec(st, 'p-activity', { title: 'Activity', icon: 'history', count: p.counts.all + ' events', body: [`<div class="mb-sec-tools">${M.Segmented({ act: 'pfilter', value: f, options: [['all', 'All'], ['files', 'Files', p.counts.files], ['commands', 'Cmds', p.counts.commands], ['notes', 'Notes', p.counts.notes]] })}</div>`, `<div class="mb-minirule">Writing subtask specs · plan_subtasks · 14 read · 3 written · 4 commands</div>`, events(feed.map(([t, k, x]) => [t, `<b>${k === 'cmd' ? 'ran' : k === 'write' ? 'wrote' : 'read'}</b> <span class="mono">${esc(x)}</span>`, k === 'write' ? 'done' : ''])), '<div class="mb-pad mb-ex-s"><span class="mb-chat-st t-accent"><span class="mb-spin"></span>Agent is working</span></div>'] });
    const bundle = h.sec(st, 'p-bundle', { title: 'Spec bundle', icon: 'fileText', count: '2/6 subtask specs saved', body: [M.Row({ lead: Icon('fileText'), title: 'spec.md', trail: '<span class="t-done">saved</span>', chevron: true, act: 'push', arg: 'planfile:SKILL-520:spec.md' }), M.Row({ lead: Icon('file'), title: 'decomposition-manifest.yaml', trail: '<span class="t-done">saved</span>', chevron: true, act: 'push', arg: 'planfile:SKILL-520:decomposition-manifest.yaml' }), '<div class="mb-group-h" style="position:static;height:30px">Subtask specs · wave 1</div>', p.units.map(([n, t, s0, d, last]) => M.Row({ lead: StatusIcon(s0 === 'saved' ? 'done' : s0 === 'writing' ? 'active' : 'pending', 0.5), title: `<span class="tnum t-faint">${n}</span> ${esc(t)}`, sub: s0 === 'writing' ? `Writing · ${d} · ${esc(last)}` : '', trail: s0 === 'saved' ? d : s0 === 'writing' ? '' : 'Queued' })).join('')] });
    const tr = h.sec(st, 'p-transcript', { title: 'Transcript', icon: 'terminal', count: p.transcript.split('\n').length + ' lines', end: `<span data-act="copy" data-arg="transcript" class="t-accent">Copy</span>`, body: `<div class="mb-pad"><div class="mb-mono-box">${esc(p.transcript)}</div></div>` });
    const brief = h.sec(st, 'p-brief', { title: 'Briefing', icon: 'layers', count: p.briefing.saved + ' saved', body: [`<div class="mb-pad l-col g-2">${p.briefing.sizes.map(([s0, kb]) => `<div class="l-row g-2"><span class="mono t-faint" style="width:110px;font-size:11px">${s0}</span><span class="mb-usage-bar l-grow"><i style="width:${parseFloat(kb) * 4}%;background:var(--st-planning)"></i></span><span class="tnum t-faint" style="font-size:12px">${kb}</span></div>`).join('')}<div class="l-row g-2 l-wrap">${M.Tag({ text: '40 KB' })}${M.Tag({ text: p.briefing.criteria + ' criteria' })}${M.Tag({ text: 'standard ceremony' })}</div>${M.Button({ label: 'Read what each stage was told', size: 'sm', act: 'toast', arg: 'Opens the raw briefings, read-only.' })}</div>`] }, true);
    return [head, acts, M.StageStrip({ stages: p.stages }), now, actSec, bundle, tr, brief].join('');
  }

  /* ---------- Standalone QA results ---------- */
  function qaDoc(st, r, h) {
    const q = r.qaRun;
    const parts = [
      `<div class="mb-pad"><p class="mb-p" style="margin:0">${esc(q.sentence)}</p></div>`,
      `<div class="mb-group-h" style="position:static;height:30px">Needs you<span class="mb-group-n">${q.open.length}</span></div>`,
      q.open.map((f) => M.ExRow({ lead: Icon('qaBug', 'sm'), title: `<span class="mono t-faint" style="font-size:11px">${f.ref}</span> ${esc(f.title)} <span class="t-blocked" style="font-size:12px">${f.state}</span>`, sub: esc(f.observed), open: !!st.sec['qa-' + f.ref], act: 'sec', arg: 'qa-' + f.ref + '|0',
        body: [`<div class="mb-sublabel">Expected</div><p>${esc(f.expected)}</p>`, `<div class="mb-sublabel">Steps</div><div class="mb-mono-box">${esc(f.steps)}</div>`, M.FileChips(f.evidence), M.Button({ label: 'Copy steps', icon: 'copy', size: 'sm', act: 'copy', arg: f.steps })] })).join(''),
      `<div class="mb-group-h" style="position:static;height:30px">Left behind<span class="mb-group-n">${q.left.length}</span></div>`,
      q.left.map(([t, d, c]) => M.Row({ lead: Icon('warn', 'sm'), title: esc(t), sub: esc(d), trail: `<button type="button" class="mb-prop-link" data-act="copy" data-arg="48211">${c}</button>` })).join(''),
      `<div class="mb-group-h" style="position:static;height:30px">Not tested<span class="mb-group-n">${q.notTested.length}</span></div>`,
      q.notTested.map(([t, d]) => M.Row({ lead: Icon('ban', 'sm'), title: esc(t), sub: esc(d) })).join(''),
      M.ExRow({ lead: Icon('check', 'sm'), title: `Passed · ${q.passed.length}`, sub: esc(q.passed.slice(0, 2).join(', ')) + '…', open: !!st.sec['qa-passed'], act: 'sec', arg: 'qa-passed|0', body: q.passed.map((x) => `<p>${StatusIcon('done')} ${esc(x)}</p>`) }),
      M.ExRow({ lead: Icon('info', 'sm'), title: 'Run details', open: !!st.sec['qa-details'], act: 'sec', arg: 'qa-details|0', body: [M.KV(q.details.map(([k, v]) => [k, esc(v)]))] }),
      M.ExRow({ lead: Icon('fileText', 'sm'), title: 'Full report', sub: 'Isolation, cleanup and the agent\'s notes', open: !!st.sec['qa-report'], act: 'sec', arg: 'qa-report|0', body: [`<div class="mb-mono-box">${esc(q.report)}</div>`, M.FileChips(['report.md'])] }),
      `<div class="mb-pad mb-ex-s">${esc(q.coverage)}</div>`,
    ];
    return h.sec(st, 'qa-results', { title: 'QA results', icon: 'qaBug', end: `<span class="t-${q.tone}">${esc(q.verdict)}</span>`, body: parts });
  }

  /* ---------- Standalone review: Phase results and Spec bundle ---------- */
  function phaseResults(st, r, h) {
    const pr = r.phaseResults;
    const out = [];
    if (pr) out.push(h.sec(st, 'phase-results', { title: 'Phase results', icon: 'eye', body: pr.passes.map((p) => `<div class="mb-pad" style="padding-bottom:0"><div class="mb-sublabel">Review pass ${p.n} · ${esc(p.verdict)} · ${p.open} unresolved</div></div>` + p.findings.map(([ref, sev, text, loc, state]) => M.ExRow({ lead: `<span class="mono t-faint" style="font-size:10.5px;padding-top:2px">${ref}</span>`, title: esc(text), end: `<span class="${state === 'Open' ? 't-blocked' : 't-done'}">${state}</span><span>${sev}</span>`, open: !!st.sec['pr-' + ref], act: 'sec', arg: 'pr-' + ref + '|0', body: [`<div class="mb-sublabel">Location</div>${M.FileChips([loc])}`] })).join('')).join('') + (pr.audit ? `<div class="mb-pad"><b>Audit</b> ${esc(pr.audit.verdict)} · ${esc(pr.audit.summary)}</div>` : '') }));
    if (r.specBundle) out.push(h.sec(st, 'spec-bundle', { title: 'Spec bundle', icon: 'fileText', count: r.specBundle.length, body: r.specBundle.map((f) => M.Row({ lead: Icon(f.endsWith('.yaml') ? 'file' : 'fileText', 'sm'), title: `<span class="${f.startsWith('spec_') ? 'mono' : ''}" style="font-size:13px">${esc(f)}</span>`, chevron: true, act: 'push', arg: 'planfile:' + r.id + ':' + f })) }));
    return out.join('');
  }

  /* Run app (Android goals): a split button with the device; off with its reason until the goal completes or reaches monitoring. */
  function runApp(r) {
    if (!r.runApp) return '';
    return `<div class="mb-runapp"><div class="mb-runapp-row">${M.Button({ label: 'Run app · ' + r.runApp.device, icon: 'phone', disabled: !!r.runApp.off })}${M.Button({ icon: 'chevronDown', label: '', act: 'toast', arg: 'Devices attached to the machine: Pixel 8 (emulator), Pixel 7 (USB).' })}</div>${r.runApp.off ? `<div class="mb-runapp-n">${esc(r.runApp.off)}</div>` : ''}</div>`;
  }

  /* Returns the whole document for kinds with their own layout; null for the generic detail. */
  function doc(st, r, h) {
    const tail = () => h.executionSection(st, r) + h.runsSection(st, r);
    if (r.valRun) return validationDoc(st, r, h) + tail();
    if (r.verify) return verifyDoc(st, r, h) + tail();
    if (r.monitor) return monitorDoc(st, r, h) + tail();
    if (r.plan) return planDoc(st, r, h) + tail();
    if (r.qaRun) return h.detailHeader(st, r) + qaDoc(st, r, h) + h.metricsFor(r) + h.activitySection(st, r) + tail();
    if (r.phaseResults || r.specBundle) return h.detailHeader(st, r) + h.metricsFor(r) + h.activitySection(st, r) + phaseResults(st, r, h) + tail();
    return null;
  }
  window.MSK = { doc, runApp };
})();
