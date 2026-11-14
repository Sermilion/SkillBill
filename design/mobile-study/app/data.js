/* Sona mobile study · sample data
 * Shaped like the desktop board read (WorkBoardItem, WorkDetailItem) as seen through one SSH snapshot.
 * Example values only. Repository and agent colors match design/linear-study/app/data.js. */
(function () {
  'use strict';

  const REPOS = {
    'skill-bill': { name: 'skill-bill', color: 'var(--data-1)', branches: ['main', 'feat/SKILL-512-worktrees', 'release/0.42'] },
    'infra-terraform': { name: 'infra-terraform', color: 'var(--data-2)', branches: ['main', 'chore/INF-7-certs'] },
    'design-tokens': { name: 'design-tokens', color: 'var(--data-5)', branches: ['main', 'feat/DSN-31-figma-export'] },
    'payments-svc': { name: 'payments-svc', color: 'var(--data-3)', branches: ['main', 'feat/PAY-88-refund-idempotency', 'feat/refunds'] },
    'newsreader': { name: 'newsreader', color: 'var(--data-4)', branches: ['main', 'feat/NEWS-12-offline'] },
    'atlas-web': { name: 'atlas-web', color: 'var(--data-6)', branches: ['main', 'feat/ATL-402-pagination'] },
  };

  const AGENTS = {
    claude: { name: 'claude', initials: 'Cl', color: 'var(--data-4)' },
    codex: { name: 'codex', initials: 'Cx', color: 'var(--data-2)' },
    cursor: { name: 'cursor', initials: 'Cu', color: 'var(--data-6)' },
    junie: { name: 'junie', initials: 'Ju', color: 'var(--data-3)' },
  };
  const AGENT_CHOICES = ['claude', 'codex', 'cursor', 'junie'];

  const SUBTASK_PHASES = ['preplan', 'plan', 'implement', 'simplify', 'audit', 'review', 'validate', 'commit_push'];
  const FINISH_PHASES = ['goal_assessment', 'qa', 'qa_fix', 'validate', 'pr', 'monitor'];

  /* kind: goal | phase | operation. state: StatusIcon state. needs: why it sits in Needs you. */
  const RUNS = [
    {
      id: 'SKILL-512', kind: 'goal', state: 'active', frac: 0.55, title: 'Keep worktrees until Finalize',
      repo: 'skill-bill', branch: 'feat/SKILL-512-worktrees', agent: 'claude', model: 'opus', elapsed: '1h 12m', started: '13:02',
      step: 'Implementing subtask 4 of 7', phase: 'implement', lastOutput: '3s ago',
      build: { cmd: 'gradlew :runtime-goalrunner:test', time: '0:48', usual: '1:30' },
      changes: { files: 12, add: 418, del: 96 },
      subtasks: [
        ['done', 'Record worktree owner in goal state'], ['done', 'Skip cleanup while a phase holds the checkout'], ['done', 'Migrate stored goals to owner field'],
        ['active', 'Remove worktree in Finalize only'], ['pending', 'Report kept worktrees in goal status'], ['pending', 'Doctor check for orphaned worktrees'], ['pending', 'Update ARCHITECTURE.md'],
      ],
      current: 3,
      phases: { preplan: 'done', plan: 'done', implement: 'active', simplify: 'pending', audit: 'pending', review: 'pending', validate: 'pending', commit_push: 'pending' },
      activity: [
        ['13:58', 'implement', 'Edited GoalRunner.kt, GoalRunnerExecutionRootTest.kt'],
        ['13:51', 'implement', 'Started subtask 4 · Remove worktree in Finalize only'],
        ['13:49', 'commit_push', 'Subtask 3 committed · 4f1c9a2'],
        ['13:40', 'review', 'Review pass 1 · 2 findings fixed'],
        ['13:21', 'implement', 'Subtask 3 · Migrate stored goals to owner field'],
      ],
    },
    {
      id: 'INF-7', kind: 'goal', state: 'blocked', title: 'Rotate staging TLS certificates', needs: 'Blocked',
      repo: 'infra-terraform', branch: 'chore/INF-7-certs', agent: 'codex', model: 'gpt-5.2', elapsed: '38m', started: '12:40', since: '24m',
      step: 'Blocked in validate · subtask 2 of 3', phase: 'validate',
      changes: { files: 4, add: 61, del: 22 },
      subtasks: [['done', 'Issue new certificates in ACM'], ['blocked', 'Swap listener certificates'], ['pending', 'Remove the old certificates']],
      current: 1,
      phases: { preplan: 'done', plan: 'done', implement: 'done', simplify: 'done', audit: 'done', review: 'done', validate: 'blocked', commit_push: 'pending' },
      incident: {
        diagnosis: 'External service unavailable',
        cause: 'terraform plan could not acquire the state lock. Another run holds lock 6c0f… on staging.tfstate.',
        next: 'Release the lock or wait for the other run to finish, then recover.',
        prereqs: [['external_service_available', 'The staging state lock is released']],
        evidence: 2,
      },
      activity: [['13:18', 'validate', 'Blocked · state lock held'], ['13:16', 'validate', 'terraform plan failed (exit 1)'], ['13:09', 'implement', 'Subtask 2 · Swap listener certificates']],
    },
    {
      id: 'DSN-31', kind: 'operation', op: 'verify', state: 'paused', title: 'Token export for Figma', needs: 'Confirm criteria',
      repo: 'design-tokens', branch: 'feat/DSN-31-figma-export', agent: 'claude', model: 'sonnet', elapsed: '6m', started: '13:44',
      step: 'Waiting for you to confirm 5 criteria', phase: 'verify',
      criteria: [
        ['AC-001', 'Exports color, type and spacing tokens to a Figma variables JSON file.'],
        ['AC-002', 'Token names keep their dotted path, such as color.bg.panel.'],
        ['AC-003', 'Aliases export as Figma aliases, not resolved values.'],
        ['AC-004', 'The export command fails with a typed error when a token has no value.'],
        ['AC-005', 'README documents the export command and its output path.'],
      ],
      intake: 'DSN-31 · Linear',
    },
    {
      id: 'PAY-88', kind: 'goal', state: 'paused', title: 'Idempotent refund webhook', needs: 'Paused',
      repo: 'payments-svc', branch: 'feat/PAY-88-refund-idempotency', agent: 'claude', model: 'opus', elapsed: '52m', started: '11:20',
      step: 'Paused after subtask 3 of 5', phase: 'commit_push', pausedAt: '12:12',
      changes: { files: 9, add: 233, del: 41 },
      subtasks: [['done', 'Store webhook event ids'], ['done', 'Reject duplicate refund events'], ['done', 'Return 200 for replayed events'], ['pending', 'Backfill ids for recent events'], ['pending', 'Alert on duplicate spikes']],
      current: 3,
      phases: { preplan: 'done', plan: 'done', implement: 'done', simplify: 'done', audit: 'done', review: 'done', validate: 'done', commit_push: 'done' },
      activity: [['12:12', 'pause', 'Pause took effect after subtask 3'], ['12:11', 'commit_push', 'Subtask 3 committed · 91be0c4']],
    },
    {
      id: 'NEWS-12', kind: 'goal', state: 'failed', title: 'Offline article cache', needs: 'Failed',
      repo: 'newsreader', branch: 'feat/NEWS-12-offline', agent: 'cursor', model: 'composer-2', elapsed: '1h 40m', started: '09:12', finished: '10:52',
      step: 'Failed in validate · ./gradlew check', phase: 'validate',
      changes: { files: 17, add: 702, del: 133 },
      reason: 'validate failed 3 times: ArticleCacheTest > evictsOldest FAILED. Expected 20 entries, found 21.',
      subtasks: [['done', 'Room table for cached articles'], ['done', 'Download on Wi-Fi'], ['failed', 'Evict past 20 articles'], ['pending', 'Offline banner']],
      current: 2,
      phases: { preplan: 'done', plan: 'done', implement: 'done', simplify: 'done', audit: 'done', review: 'done', validate: 'failed', commit_push: 'pending' },
      activity: [['10:52', 'validate', 'Failed · fix loop exhausted (3 of 3)'], ['10:40', 'validate', './gradlew check failed']],
    },
    {
      id: 'ATL-402', kind: 'goal', state: 'active', frac: 0.85, title: 'Search results pagination',
      repo: 'atlas-web', branch: 'feat/ATL-402-pagination', agent: 'cursor', model: 'composer-2', elapsed: '2h 05m', started: '11:53',
      step: 'Goal finish · QA stage 4 of 7', phase: 'qa', finishing: true,
      changes: { files: 21, add: 640, del: 188 },
      subtasks: [['done', 'Cursor-based search API'], ['done', 'Page size setting'], ['done', 'Next and previous controls'], ['done', 'Keep page in the URL']],
      current: 4,
      finish: { goal_assessment: 'done', qa: 'active', qa_fix: 'pending', validate: 'pending', pr: 'pending', monitor: 'pending' },
      qa: { verdict: 'Needs fixes', tone: 'blocked', line: '1 open finding, 6 checks passed.', open: [['QA-3', 'Next stays enabled on the last page', 'Tapped Next on page 4 of 4. The button stayed enabled and loaded an empty page.']], passed: 6 },
      activity: [['13:55', 'qa', 'Stage 4 · Next and previous on mobile width'], ['13:49', 'qa', 'Finding QA-3 recorded'], ['13:30', 'goal_assessment', 'Goal assessment passed']],
    },
    { id: 'ph-review-refunds', kind: 'phase', phaseName: 'review', state: 'active', frac: 0.4, title: 'Review on feat/refunds', repo: 'payments-svc', branch: 'feat/refunds', agent: 'codex', model: 'gpt-5.2', elapsed: '9m', started: '13:49', step: 'Pass 1 · reading 14 files', phase: 'review' },
    { id: 'op-monitor-482', kind: 'phase', phaseName: 'monitor', state: 'active', frac: 0.3, title: 'Monitor #482 · Retry flaky upload', repo: 'skill-bill', branch: 'fix/upload-retry', agent: 'claude', model: 'sonnet', elapsed: '22m', started: '13:36', step: 'Watching CI · 3 of 5 checks passed', phase: 'monitor' },
    { id: 'SKILL-498', kind: 'goal', state: 'done', title: 'Floating details over terminal', repo: 'skill-bill', branch: 'feat/SKILL-498-floating', agent: 'claude', model: 'opus', elapsed: '3h 10m', started: '08:02', finished: '11:12', step: 'PR #611 opened · CI passed', phase: 'monitor', changes: { files: 8, add: 91, del: 10 }, today: true },
    { id: 'ph-validation-news', kind: 'phase', phaseName: 'validation', state: 'done', title: 'Validation on main', repo: 'newsreader', branch: 'main', agent: 'junie', elapsed: '7m', started: '10:01', finished: '10:08', step: 'Passed · 4 checks', phase: 'validation', today: true },
  ];

  const HISTORY = [
    ['done', 'SKILL-498', 'Floating details over terminal', 'skill-bill', 'claude', '11:12', 'Today'],
    ['done', 'validation', 'Validation on main', 'newsreader', 'junie', '10:08', 'Today'],
    ['failed', 'NEWS-12', 'Offline article cache', 'newsreader', 'cursor', '10:52', 'Today'],
    ['done', 'SKILL-487', 'Usage limits card', 'skill-bill', 'codex', '18:40', 'Yesterday'],
    ['canceled', 'ATL-399', 'Saved searches', 'atlas-web', 'cursor', '16:02', 'Yesterday'],
    ['done', 'pr', 'PR for fix/upload-retry', 'skill-bill', 'claude', '15:21', 'Yesterday'],
    ['done', 'PAY-80', 'Partial refunds', 'payments-svc', 'claude', 'Wed', 'This week'],
    ['no_change', 'review', 'Review on feat/ledger', 'payments-svc', 'codex', 'Tue', 'This week'],
  ];

  const CHATS = [
    {
      id: 'c1', title: 'Dark mode setting', repo: 'atlas-web', branch: 'main', agent: 'claude', status: 'replying', time: 'now', queued: 1,
      changes: null,
      messages: [
        ['user', 'Add a dark mode setting to the profile page. Follow system by default.'],
        ['agent', 'The app reads colors from `theme.ts`, and the profile page has a settings list I can extend. I would add a three-way setting: System, Light and Dark, stored in the user preferences table.'],
        ['agent', 'Two questions before I write the goal. Should the choice sync across devices, or stay local? And should the login page follow it?'],
        ['user', 'Sync it. Login page follows system only.'],
      ],
      start: { phase: 'goal', title: 'Dark mode setting', key: 'ATL-411' },
      queuedText: 'Also check the email templates',
    },
    {
      id: 'c2', title: 'Why is NEWS-12 failing', repo: 'newsreader', branch: 'feat/NEWS-12-offline', agent: 'cursor', status: 'waiting', time: '10:58',
      changes: { files: 2, add: 14, del: 3 },
      messages: [
        ['user', 'Why did NEWS-12 fail?'],
        ['agent', 'Eviction runs before the insert, so the cache holds 21 articles for a moment and the test reads it then. Moving the eviction after the insert fixes it. I changed `ArticleCache.kt` and the test passes locally.'],
      ],
      suggestions: ['Run validation', 'Open a PR'],
    },
    { id: 'c3', title: 'Release notes for 0.42', repo: 'skill-bill', branch: 'release/0.42', agent: 'codex', status: 'idle', time: 'Yesterday', messages: [['user', 'Draft release notes for 0.42 from the merged PRs.'], ['agent', 'Drafted `RELEASE_NOTES.md` with 14 entries in three groups.']] },
  ];

  const QUICK_STARTS = [['target', 'Goal'], ['eye', 'Review'], ['check', 'Validation'], ['qaBug', 'QA'], ['pr', 'PR'], ['flag', 'Verify']];

  const PLANS = {
    'skill-bill': [
      { key: 'SKILL-512', title: 'Keep worktrees until Finalize', status: 'active', n: 3, m: 7, run: 'SKILL-512' },
      { key: 'SKILL-520', title: 'Pair a phone over SSH', status: 'pending', n: 0, m: 6 },
      { key: 'SKILL-517', title: 'Doctor checks for stale locks', status: 'pending', n: 0, m: 3 },
      { key: 'SKILL-498', title: 'Floating details over terminal', status: 'done', n: 4, m: 4 },
      { key: 'SKILL-487', title: 'Usage limits card', status: 'done', n: 5, m: 5 },
    ],
  };
  const PLAN_FILES = [
    ['fileText', 'spec.md', null],
    ['file', '01-record-worktree-owner.md', 'done'], ['file', '02-skip-cleanup-while-held.md', 'done'], ['file', '03-migrate-owner-field.md', 'done'],
    ['file', '04-remove-in-finalize.md', 'active'], ['file', '05-report-kept-worktrees.md', 'pending'], ['file', '06-doctor-orphans.md', 'pending'], ['file', '07-architecture.md', 'pending'],
    ['fileText', 'traceability.md', null], ['file', 'decomposition-manifest.yaml', null],
  ];
  const SPEC_DOC = [
    ['h1', 'Keep worktrees until Finalize'],
    ['p', 'Goals remove their checkout as soon as the last subtask commits. A phase that still reads the checkout, such as QA, then fails with a missing path.'],
    ['h2', 'Outcome'],
    ['p', 'A goal keeps its worktree until Finalize. Finalize removes it unless it holds unpublished work.'],
    ['h2', 'Acceptance criteria'],
    ['li', 'AC-001 · No phase after commit_push sees a missing checkout.'],
    ['li', 'AC-002 · Finalize removes the worktree when every commit is pushed.'],
    ['li', 'AC-003 · A kept worktree shows in goal status with its reason.'],
    ['h2', 'Non-goals'],
    ['li', 'Changing where standalone phases run.'],
  ];

  const FILES = [
    ['M', 'GoalRunner.kt', 'runtime-goalrunner/…/goalrunner', 64, 21],
    ['M', 'GoalRunnerExecutionRootTest.kt', 'runtime-goalrunner/src/test/…', 88, 4],
    ['A', 'WorktreeOwner.kt', 'runtime-domain/…/goal', 42, 0],
    ['M', 'GoalStateMigration.kt', 'runtime-infra/sqlite/…', 57, 12],
    ['M', 'ARCHITECTURE.md', 'runtime-kotlin', 18, 6],
    ['D', 'EagerWorktreeCleanup.kt', 'runtime-goalrunner/…/cleanup', 0, 39],
  ];
  /* [kind, old line, new line, text]; kind: ctx | add | del | hunk */
  const DIFF = [
    ['hunk', null, null, '@@ -212,14 +212,19 @@ class GoalRunner('],
    ['ctx', 212, 212, '  private fun finishSubtask(goal: Goal, subtask: Subtask) {'],
    ['ctx', 213, 213, '    ledger.record(goal.id, subtask.id, PhaseOutcome.COMMITTED)'],
    ['del', 214, null, '    worktrees.remove(goal.checkout)'],
    ['add', null, 214, '    if (goal.hasRemainingPhases()) {'],
    ['add', null, 215, '      events.emit(WorktreeKept(goal.id, reason = "phases remain"))'],
    ['add', null, 216, '      return'],
    ['add', null, 217, '    }'],
    ['add', null, 218, '    worktrees.markForFinalize(goal.checkout, owner = goal.id)'],
    ['ctx', 215, 219, '  }'],
    ['ctx', 216, 220, ''],
    ['hunk', null, null, '@@ -301,6 +306,12 @@ class GoalRunner('],
    ['ctx', 301, 306, '  fun finalize(goal: Goal) {'],
    ['add', null, 307, '    val checkout = worktrees.owned(goal.id) ?: return'],
    ['add', null, 308, '    if (checkout.hasUnpublishedWork()) {'],
    ['add', null, 309, '      events.emit(WorktreeKept(goal.id, reason = "unpublished work"))'],
    ['add', null, 310, '      return'],
    ['add', null, 311, '    }'],
    ['add', null, 312, '    worktrees.remove(checkout)'],
    ['ctx', 302, 313, '    board.publish(goal.id)'],
  ];
  /* A published review thread, shown read-only on the line it targets. */
  const THREAD = { after: 9, author: 'You', time: 'Yesterday', text: 'Should Finalize log the kept reason too?', reply: ['claude', 'Yes. Finalize emits WorktreeKept with "unpublished work" in the second hunk.'] };

  const MACHINES = [
    { id: 'studio', name: 'studio', host: 'studio.tail3c9a.ts.net', port: 22, user: 'dev', state: 'connected', sona: '0.42.0', updated: '12s ago', fingerprint: 'SHA256:q3J8u0hV2kR7cE1mY9tLwB4nPz6sXa5dGf0oKiU2rTo', push: 'on' },
    { id: 'buildbox', name: 'buildbox', host: '192.168.1.40', port: 2222, user: 'ci', state: 'unreachable', sona: '0.41.2', updated: 'Yesterday 18:02', fingerprint: 'SHA256:Zp1x9QmL0aT3vR8yK2cN7wE5uJ4bH6sD1fG8iO3lPqA', push: 'off' },
  ];
  const PUBLIC_KEY = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHk2pQ9vL0sT7mXc4R1bYw8uNfE3jZaD5gK6oH2iVtC sona-phone';

  /* The six push types are the desktop's OS notification types. */
  const NOTIFICATIONS = [
    ['blocked', 'A goal is blocked', true], ['failed', 'A goal or phase fails', true], ['goal_completed', 'A goal completes', true],
    ['pause_took_effect', 'A pause takes effect', true], ['standalone_phase_finished', 'A standalone phase finishes', false], ['qa_incomplete', 'QA could not complete', true],
  ];

  const USAGE = [
    { provider: 'Claude', agent: 'claude', windows: [{ label: '5 hour', used: 12, resetIn: '2h 14m', resetAt: '16:30' }, { label: 'Weekly', used: 43, resetIn: '4d 6h', resetAt: 'Thu 09:00' }] },
    { provider: 'Codex', agent: 'codex', windows: [{ label: '5 hour', used: 91, resetIn: '48m', resetAt: '14:52' }, { label: 'Weekly', used: 58, resetIn: '2d 1h', resetAt: 'Mon 15:00' }] },
    { provider: 'Cursor', agent: 'cursor', message: 'No usage data' },
    { provider: 'Junie', agent: 'junie', message: 'No usage data' },
  ];

  window.MD = { REPOS, AGENTS, AGENT_CHOICES, SUBTASK_PHASES, FINISH_PHASES, RUNS, HISTORY, CHATS, QUICK_STARTS, PLANS, PLAN_FILES, SPEC_DOC, FILES, DIFF, THREAD, MACHINES, PUBLIC_KEY, NOTIFICATIONS, USAGE };
})();
