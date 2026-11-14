/* Sona mobile study · subtask and Goal finish sample data
 * The same records the desktop details pane reads (design/linear-study HANDOFF 4.1, Phase peek; 9.2 SubtaskInspector):
 * phase records (status, attempt, start, duration, agent, model), settlement envelopes, the edit journal,
 * agent activity stamps and the phase receipts. Example values only.
 *
 * MD.subtask(runId, index) returns { title, state, reason, props, phases: [[id, state]], cards: { phase: card } }.
 * MD.finish(runId) returns the same shape for Goal finish.
 * A card: title, agent, model, round, when, dur, word, why, now [blocks], madeTitle, madeEnd, made [blocks],
 * activity [[time, text, tone]], open { label, act, arg }.
 * Blocks: signal { live, text, ago }, edit { path, ins, del, ago }, cmd { text }, cells { cells }, text { text },
 * stats { items: [[value, label, tone]] }, out { items: [{ icon, text, end, mono }] }, checks { rows: [[name, state, time, cmd]] },
 * finds { rows: [[severity, text, state]] }. */
(function () {
  'use strict';
  const D = window.MD;
  const OPUS = 'opus · high';
  const SONNET = 'sonnet · high';
  const GPT = 'gpt-5.2 · high';

  /* Per-run facts the cards draw from. Subtask fields are indexed by subtask position. */
  const FACTS = {
    'SKILL-512': {
      agent: 'claude', specs: ['01-record-worktree-owner.md', '02-skip-cleanup-while-held.md', '03-migrate-owner-field.md', '04-remove-in-finalize.md', '05-report-kept-worktrees.md', '06-doctor-orphans.md', '07-architecture.md'],
      commits: ['3e7a10c2', '8b04d1f9', '4f1c9a27'], deps: [[], [1], [1], [2, 3], [4], [4], [4, 5]],
      times: [['12:58', '13:00', '13:03', '13:11', '13:13', '13:15', '13:17', '13:18'], ['13:18', '13:19', '13:21', '13:27', '13:28', '13:30', '13:32', '13:33'], ['13:33', '13:34', '13:36', '13:40', '13:41', '13:44', '13:47', '13:49'], ['13:49', '13:50', '13:51']],
      files: [
        [['runtime-domain/…/goal/WorktreeOwner.kt', 42, 0, true], ['runtime-domain/…/goal/GoalState.kt', 11, 2]],
        [['runtime-goalrunner/…/cleanup/WorktreeCleanup.kt', 26, 9], ['runtime-goalrunner/…/cleanup/WorktreeCleanupTest.kt', 48, 0, true]],
        [['runtime-infra/sqlite/…/GoalStateMigration.kt', 57, 12], ['runtime-infra/sqlite/…/GoalStateMigrationTest.kt', 71, 0, true], ['runtime-infra/sqlite/…/V14__worktree_owner.sql', 6, 0, true]],
        [['runtime-goalrunner/…/goalrunner/GoalRunner.kt', 64, 21], ['runtime-goalrunner/…/GoalRunnerExecutionRootTest.kt', 88, 4]],
      ],
      criteria: [
        ['Goal state records the worktree owner', 'Owner survives a restart'],
        ['Cleanup skips a checkout a phase still holds', 'A released checkout is cleaned on the next pass'],
        ['Stored goals gain an owner on read', 'The migration is repeatable'],
        ['No phase after commit_push sees a missing checkout', 'Finalize removes the worktree when every commit is pushed', 'Finalize keeps a worktree with unpublished work'],
      ],
    },
    'INF-7': {
      agent: 'codex', specs: ['01-issue-certificates.md', '02-swap-listeners.md', '03-remove-old-certificates.md'], commits: ['c09d2e71'], deps: [[], [1], [2]],
      times: [['12:40', '12:42', '12:44', '12:52', '12:53', '12:55', '12:57', '12:58'], ['12:58', '12:59', '13:01', '13:08', '13:09', '13:10', '13:12']],
      files: [[['stacks/staging/acm.tf', 38, 0], ['stacks/staging/outputs.tf', 6, 0]], [['stacks/staging/alb.tf', 14, 9], ['stacks/staging/nlb.tf', 3, 13]]],
      criteria: [['New certificates issue and validate in ACM'], ['Listeners serve the new certificates', 'No listener downtime during the swap']],
    },
    'NEWS-12': {
      agent: 'cursor', specs: ['01-room-cache-table.md', '02-download-on-wifi.md', '03-evict-past-20.md', '04-offline-banner.md'], commits: ['77a0b5e1', '2d9f3c84'], deps: [[], [1], [1], [2, 3]],
      times: [['09:12', '09:14', '09:17', '09:41', '09:43', '09:46', '09:49', '09:51'], ['09:51', '09:53', '09:56', '10:14', '10:15', '10:18', '10:21', '10:22'], ['10:22', '10:23', '10:25', '10:33', '10:34', '10:36', '10:38']],
      files: [[['app/…/cache/ArticleEntity.kt', 34, 0, true]], [['app/…/sync/DownloadWorker.kt', 81, 4, true]], [['app/…/cache/ArticleCache.kt', 29, 6], ['app/…/cache/ArticleCacheTest.kt', 44, 0, true]]],
      criteria: [['Articles persist across restarts'], ['Downloads run only on Wi-Fi'], ['The cache keeps at most 20 articles', 'The oldest article goes first']],
    },
    'PAY-88': {
      agent: 'claude', specs: ['01-store-event-ids.md', '02-reject-duplicates.md', '03-replay-returns-200.md', '04-backfill-ids.md', '05-duplicate-alert.md'], commits: ['5a1e66b0', '0f3d92aa', '91be0c4e'], deps: [[], [1], [2], [1], [2]],
      times: [['11:20', '11:21', '11:24', '11:35', '11:36', '11:38', '11:40', '11:41'], ['11:41', '11:42', '11:44', '11:53', '11:54', '11:56', '11:58', '11:59'], ['11:59', '12:00', '12:02', '12:06', '12:07', '12:09', '12:11', '12:11']],
      files: [[['src/webhooks/event_ids.py', 33, 0, true]], [['src/webhooks/refund.py', 21, 8]], [['src/webhooks/refund.py', 9, 3], ['tests/test_refund_replay.py', 40, 0, true]]],
      criteria: [['Event ids persist'], ['Duplicate refund events are rejected'], ['A replayed event returns 200 with no side effects']],
    },
    'ATL-402': {
      agent: 'cursor', specs: ['01-cursor-api.md', '02-page-size.md', '03-next-previous.md', '04-page-in-url.md'], commits: ['e81c4b02', '3f7d9a11', '6c2b0e95', 'a90d17f3'], deps: [[], [1], [1], [3]],
      times: [['11:53', '11:55', '11:58', '12:20', '12:22', '12:25', '12:28', '12:29'], ['12:29', '12:30', '12:32', '12:41', '12:42', '12:44', '12:46', '12:47'], ['12:47', '12:48', '12:51', '13:05', '13:06', '13:08', '13:10', '13:11'], ['13:11', '13:12', '13:14', '13:22', '13:23', '13:25', '13:27', '13:28']],
      files: [[['api/search/cursor.ts', 66, 12]], [['web/search/PageSize.tsx', 41, 0, true]], [['web/search/Pager.tsx', 58, 7, true], ['web/search/Pager.test.tsx', 62, 0, true]], [['web/search/useSearchParams.ts', 19, 4]]],
      criteria: [['Search pages by cursor'], ['Page size is 20, 50 or 100'], ['Next and previous move one page', 'Previous is disabled on page 1'], ['The page survives a reload']],
    },
  };

  FACTS['SKILL-505'] = {
    agent: 'codex', specs: ['01-remove-endpoint.md', '02-delete-plugin-client.md', '03-update-api-docs.md'], commits: ['d41e0c93'], deps: [[], [1], [1]],
    times: [['10:31', '10:33', '10:36', '10:48', '10:49', '10:51', '10:53', '10:54'], ['10:54', '10:56', '10:59']],
    files: [[['runtime-mcp/…/StatusEndpoint.kt', 0, 84], ['runtime-mcp/…/Routes.kt', 2, 6]], []],
    criteria: [['The endpoint returns 404', 'No route references it'], ['The plugin no longer calls the endpoint']],
  };
  const PHASE_TITLE = { preplan: 'Preplan', plan: 'Plan', implement: 'Implement', simplify: 'Simplify', audit: 'Audit', review: 'Review', validate: 'Validate', commit_push: 'Commit and push', goal_assessment: 'Goal assessment', qa: 'QA', qa_fix: 'QA fix', pr: 'Pull request', monitor: 'Monitor' };
  const base = (f) => f.split('/').pop();

  /* Subtask phase states: done subtasks ran every phase; the current one follows the run's phase map. */
  function phaseStates(run, i) {
    const s = run.subtasks[i][0];
    if (s === 'done') return D.SUBTASK_PHASES.map((p) => [p, 'done']);
    if (s === 'no_change' && run.phases) return D.SUBTASK_PHASES.map((p) => [p, run.phases[p]]);
    if (i === run.current && run.phases && run.state !== 'paused') return D.SUBTASK_PHASES.map((p) => [p, run.phases[p]]);
    return D.SUBTASK_PHASES.map((p) => [p, 'pending']);
  }

  function cardFor(run, f, i, phase, state) {
    const t = (f.times[i] || [])[D.SUBTASK_PHASES.indexOf(phase)] || '—';
    const files = f.files[i] || [];
    const crit = f.criteria[i] || [];
    const ins = files.reduce((n, x) => n + x[1], 0);
    const del = files.reduce((n, x) => n + x[2], 0);
    const agent = run.agent;
    const model = agent === 'codex' ? GPT : agent === 'cursor' ? 'composer-2' : phase === 'audit' || phase === 'preplan' || phase === 'plan' || phase === 'review' ? OPUS : SONNET;
    const c = { title: PHASE_TITLE[phase], agent, model, when: t, dur: '2m 10s', activity: [] };
    const live = state === 'active';
    if (phase === 'preplan') Object.assign(c, { dur: '1m 52s', made: [{ kind: 'text', text: 'Change stays in ' + (files[0] ? base(files[0][0]) : 'one file') + ' and its test. No migration risk outside this subtask.' }], activity: [[t, 'Started'], [t, 'Settled · done', 'done']] });
    if (phase === 'plan') Object.assign(c, { dur: '3m 05s', made: [{ kind: 'out', items: [{ icon: 'fileText', text: f.specs[i], mono: true, end: crit.length + ' criteria', act: 'spec' }] }, { kind: 'text', text: crit.join('. ') + '.' }], activity: [[t, 'Started'], [t, 'Wrote ' + f.specs[i], 'done']] });
    if (phase === 'implement') {
      Object.assign(c, { round: 'attempt 1', dur: live ? '12m 40s' : '8m 20s', madeTitle: 'Edits', madeEnd: 'from the edit journal',
        made: [{ kind: 'stats', items: [[String(files.length), 'files'], ['+' + ins, 'added', 'ok'], ['−' + del, 'removed', 'bad'], [String(files.filter((x) => x[3]).length), 'new']] }, { kind: 'out', items: files.map(([p, a, d, n]) => ({ icon: n ? 'file' : 'pencil', text: base(p) + (n ? ' · new' : ''), end: '+' + a + ' −' + d })) }],
        activity: live ? [[t, 'Started'], ['13:56', 'Edited GoalRunner.kt', 'now']] : [[t, 'Started'], [t, `Settled · ${files.length} files, +${ins} −${del}`, 'done']],
        open: { label: 'Open changes', act: 'push', arg: 'changes:' + run.id } });
      if (live) c.now = [{ kind: 'signal', live: true, text: 'Running GoalRunnerExecutionRootTest', ago: run.lastOutput || '3s ago' }, { kind: 'edit', path: 'GoalRunner.kt', ins: 12, del: 4, ago: '40s ago' }, run.build ? { kind: 'cmd', text: './' + run.build.cmd } : null].filter(Boolean);
    }
    if (phase === 'simplify') Object.assign(c, { dur: '4m 41s', made: [{ kind: 'stats', items: [[String(Math.max(1, files.length - 1)), 'files'], ['+6', 'added', 'ok'], ['−19', 'removed', 'bad'], ['−13', 'net']] }, { kind: 'text', text: 'Folded a helper into its only caller and removed a duplicate null check.' }], activity: [[t, 'Started'], [t, 'Settled · done', 'done']] });
    if (phase === 'audit') Object.assign(c, { word: 'Satisfied', madeTitle: 'Acceptance criteria', madeEnd: `${crit.length} of ${crit.length} covered`, made: [{ kind: 'checks', rows: crit.map((x) => [x, 'ok']) }], activity: [[t, 'Started'], [t, 'Verdict satisfied', 'done']] });
    if (phase === 'review') Object.assign(c, { round: 'pass 1', dur: '6m 12s', word: 'Approved', madeTitle: 'Pass 1', madeEnd: 'approved', made: i === 2 && run.id === 'SKILL-512' ? [{ kind: 'finds', rows: [['major', 'Migration reads the owner before the column exists on a fresh store', 'fixed'], ['minor', 'Test name says "cleanup" for a migration test', 'fixed']] }] : [{ kind: 'text', text: 'Approved on the first pass. No findings.' }], activity: [[t, 'Pass 1 started'], [t, 'Pass 1 approved', 'done']], open: { label: 'Open review', act: 'toast', arg: 'Opens the review run with every pass and finding.' } });
    if (phase === 'validate') {
      Object.assign(c, { dur: '3m 40s', word: 'Passed', madeTitle: 'Checks', madeEnd: '4 of 4 passed', made: [{ kind: 'checks', rows: checksFor(run, 'ok') }], activity: [[t, 'Started'], [t, 'Validation passed · 4 checks', 'done']], open: { label: 'Open validation run', act: 'toast', arg: 'Opens the validation run detail.' } });
      if (state === 'blocked') {
        Object.assign(c, { round: 'attempt 3', when: 'since 13:09', dur: '24m', word: 'Blocked',
          why: 'terraform plan cannot acquire the state lock. Another run holds lock 6c0f… on staging.tfstate. Three attempts hit the same error.',
          now: [{ kind: 'signal', text: 'Waits for you: release the lock, then Recover.' }],
          madeEnd: '2 passed · 1 failed', made: [{ kind: 'cells', cells: ['ok', 'ok', 'bad'] }, { kind: 'checks', rows: [['terraform fmt', 'ok', '2s', 'terraform fmt -check'], ['tflint', 'ok', '9s', 'tflint'], ['terraform plan', 'bad', 'exit 1', 'terraform plan']] }],
          activity: [['13:10', 'Attempt 1 · plan failed, exit 1', 'fail'], ['13:13', 'Attempt 2 · same error', 'fail'], ['13:16', 'Attempt 3 · same error', 'fail'], ['13:18', 'Blocked, waiting for you', 'wait']] });
      }
      if (state === 'failed') {
        Object.assign(c, { round: 'attempt 3', when: '10:38', dur: '14m', word: 'Failed',
          why: 'Fix loop exhausted after 3 attempts. ArticleCacheTest > evictsOldest expected 20 entries and found 21.',
          madeEnd: '3 passed · 1 failed', made: [{ kind: 'cells', cells: ['ok', 'ok', 'ok', 'bad'] }, { kind: 'checks', rows: [['Unit tests', 'bad', 'exit 1', ':app:testDebugUnitTest'], ['Detekt', 'ok', '38s', 'detekt'], ['ktlint', 'ok', '14s', 'ktlintCheck'], ['Lint', 'ok', '1m 02s', ':app:lintDebug']] }],
          activity: [['10:38', 'Attempt 1 · 1 test failed', 'fail'], ['10:44', 'Fix 1 · ArticleCache.kt', 'done'], ['10:46', 'Attempt 2 · same test failed', 'fail'], ['10:50', 'Attempt 3 · same test failed', 'fail'], ['10:52', 'Failed · fix loop exhausted', 'fail']] });
      }
    }
    if (phase === 'commit_push') Object.assign(c, { dur: '22s', madeTitle: 'Commit', made: [{ kind: 'out', items: [{ icon: 'commit', text: f.commits[i] + ' · ' + run.subtasks[i][1], end: '', mono: false }, { icon: 'branch', text: 'Pushed to ' + run.branch, end: files.length + ' paths' }] }], activity: [[t, 'Committed ' + files.length + ' paths'], [t, 'Pushed', 'done']] });
    return c;
  }
  function checksFor(run, st) {
    if (run.repo === 'infra-terraform') return [['terraform fmt', st, '2s', 'terraform fmt -check'], ['tflint', st, '9s', 'tflint'], ['terraform plan', st, '41s', 'terraform plan']];
    if (run.repo === 'atlas-web') return [['Unit tests', st, '48s', 'npm test'], ['Typecheck', st, '22s', 'tsc --noEmit'], ['ESLint', st, '15s', 'eslint .'], ['Playwright', st, '2m 10s', 'npx playwright test']];
    if (run.repo === 'payments-svc') return [['pytest', st, '1m 12s', 'pytest'], ['mypy', st, '18s', 'mypy src'], ['ruff', st, '3s', 'ruff check'], ['Contract tests', st, '44s', 'pytest tests/contract']];
    return [['Unit tests', st, '2m 51s', ':runtime:test'], ['Detekt', st, '41s', 'detektAll'], ['ktlint', st, '18s', 'ktlintCheck'], ['Contract tests', st, '1m 50s', ':contracts:test']];
  }

  function subtask(runId, i) {
    const run = D.RUNS.find((r) => r.id === runId);
    const f = FACTS[runId];
    const [state, title] = run.subtasks[i];
    const phases = phaseStates(run, i);
    const cards = {};
    phases.forEach(([p, s]) => { if (s !== 'pending') cards[p] = cardFor(run, f, i, p, s); });
    const neededBy = f.deps.map((d, j) => (d.includes(i + 1) ? j + 1 : null)).filter(Boolean);
    const validate = phases.find((p) => p[0] === 'validate')[1];
    const props = [
      ['Commit', f.commits[i] ? { sha: f.commits[i] } : null],
      ['Spec file', { file: f.specs[i] }],
      ['Depends on', f.deps[i].length ? f.deps[i].join(', ') : null],
      ['Needed by', neededBy.length ? neededBy.join(', ') : null],
      ['Validation', validate === 'done' ? 'Passed · ' + checksFor(run, 'ok').length + ' checks' : validate === 'blocked' ? 'Blocked · attempt 3' : validate === 'failed' ? 'Failed · attempt 3' : null],
    ];
    const reason = state === 'blocked' ? 'Blocked in validate: terraform plan cannot acquire the staging state lock.' : state === 'failed' ? 'Failed in validate after 3 attempts: ArticleCacheTest > evictsOldest.' : null;
    return { title, state, n: i + 1, total: run.subtasks.length, reason, props, phases, cards };
  }

  /* Goal finish for ATL-402: assessment done with a fix, QA running round 1. */
  function finish(runId) {
    const run = D.RUNS.find((r) => r.id === runId);
    const phases = D.FINISH_PHASES.map((p) => [p, run.finish ? run.finish[p] : 'pending']);
    const cards = {
      goal_assessment: { title: 'Goal assessment', agent: 'cursor', model: 'composer-2', round: 'round 1', when: '13:28', dur: '9m 30s', word: 'Completed', madeTitle: 'Findings', madeEnd: '1 refuted · 1 fixed',
        made: [{ kind: 'finds', rows: [['major', 'Page size resets to 20 after a reload · useSearchParams.ts:31', 'fixed'], ['minor', 'Pager re-renders on every keystroke in the search box · Pager.tsx:18', 'refuted']] }, { kind: 'out', items: [{ icon: 'commit', text: 'Fix commit b4e01c77', end: '' }] }],
        activity: [['13:28', 'Round 1 started'], ['13:33', '2 findings · 1 major, 1 minor', 'wait'], ['13:35', 'Refuted the re-render finding'], ['13:37', 'Fixed page size reset · b4e01c77', 'done']] },
      qa: { title: 'QA', agent: 'cursor', model: 'composer-2', round: 'round 1', when: 'since 13:38', dur: '18m', word: 'Running',
        now: [{ kind: 'signal', live: true, text: 'Stage 4 of 7 · Next and previous on a 390px viewport', ago: '6s ago' }, { kind: 'cells', cells: ['ok', 'ok', 'ok', 'run', '', '', ''] }, { kind: 'cmd', text: 'npx playwright test e2e/pager.spec.ts --project=mobile' }],
        madeTitle: 'So far', madeEnd: '1 finding · 6 passed',
        made: [{ kind: 'finds', rows: [['QA-3', 'Next stays enabled on the last page. Tapped Next on page 4 of 4; it loaded an empty page.', 'open']] }, { kind: 'out', items: [{ icon: 'image', text: 'pager-last-page.png', end: 'screenshot' }, { icon: 'image', text: 'pager-mobile.png', end: 'screenshot' }] }],
        activity: [['13:38', 'Round 1 started'], ['13:44', 'Stages 1 to 3 passed', 'done'], ['13:49', 'Finding QA-3 recorded', 'wait'], ['13:55', 'Stage 4 running', 'now']],
        open: { label: 'Open QA results', act: 'toast', arg: 'Opens the full QA results with steps and evidence.' } },
    };
    const ran = phases.filter((p) => p[1] !== 'pending').length;
    return { title: 'Goal finish', state: run.state === 'active' ? 'active' : run.state, finish: true, n: ran, total: phases.length, reason: null, props: [['Stage', 'qa · round 1'], ['Phases run', `${ran} of ${phases.length}`], ['Branch', run.branch]], phases, cards };
  }

  D.subtask = subtask;
  D.finish = finish;
  D.hasPeek = (runId) => !!FACTS[runId];
})();
