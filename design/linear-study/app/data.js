/* Sample data shaped like the desktop app's domain model
 * (desktop-tray core/domain/model/WorkModels.kt, SpecModels.kt) and the runtime config models
 * (runtime-domain config/model). Values are examples; every field exists in the real model. */
(function () {
  const T0 = Date.now();
  const M = 60000, H = 3600000;

  const REPOS = {
    'skill-bill': { name: 'skill-bill', path: '~/IdeaProjects/skill-bill', color: 'var(--data-1)', web: 'github.com/oila-gmbh/skill-bill' },
    'infra-terraform': { name: 'infra-terraform', path: '~/dev/infra-terraform', color: 'var(--data-2)', web: 'github.com/acme/infra-terraform' },
    'design-tokens': { name: 'design-tokens', path: '~/dev/design-tokens', color: 'var(--data-5)', web: 'github.com/acme/design-tokens' },
    'payments-svc': { name: 'payments-svc', path: '~/dev/payments-svc', color: 'var(--data-3)', web: 'github.com/acme/payments-svc' },
    'newsreader': { name: 'newsreader', path: '~/dev/newsreader', color: 'var(--data-4)', web: 'github.com/acme/newsreader' },
    'atlas-web': { name: 'atlas-web', path: '~/dev/atlas-web', color: 'var(--data-6)', web: 'github.com/acme/atlas-web' },
    'runtime-kotlin': { name: 'runtime-kotlin', path: '~/dev/runtime-kotlin', color: 'var(--data-2)', web: 'github.com/acme/runtime-kotlin' },
  };

  /* SupportedAgent: claude, codex, junie, cursor. MODEL_DIRECTIVE_CAPABLE_AGENTS: claude, codex, cursor. */
  const AGENTS = {
    claude: { name: 'claude', initials: 'Cl', color: 'var(--data-4)', modelCapable: true },
    codex: { name: 'codex', initials: 'Cx', color: 'var(--data-2)', modelCapable: true },
    cursor: { name: 'cursor', initials: 'Cu', color: 'var(--data-6)', modelCapable: true },
    junie: { name: 'junie', initials: 'Ju', color: 'var(--data-3)', modelCapable: false },
  };
  const AGENT_CHOICES = ['claude', 'codex', 'cursor', 'junie'];

  const PHASE_ORDER = ['preplan', 'plan', 'implement', 'simplify', 'audit', 'review', 'build', 'validate', 'write_history', 'commit_push'];
  const GOAL_FINISH = ['pr', 'monitor', 'monitor_fix'];

  /* kind: goal | phase | operation. section: the runtime's display group. lifecycle: Lifecycle enum. */
  const items = [
    {
      id: 'INF-7', kind: 'goal', repo: 'infra-terraform', lifecycle: 'blocked', section: 'needs',
      title: 'Migrate state backend to a remote workspace', step: 'review', exec: 'pass 3/3',
      done: 2, total: 6, current: 3, goalMs: 7 * H + 26 * M, subMs: 48 * M, live: false, fresh: true,
      agent: 'codex', model: 'gpt-6.1-sol', effort: 'high', branch: 'feat/INF-7-remote-state-backend',
      workflow: 'wftr-20261011-062311-k2rq', invocation: null, liveness: 'idle', started: '11 Oct 06:23', updated: '17:49:54',
      summary: 'Review left 2 Blocker findings on subtask 3',
      reason: 'awaiting_operator_decision: Review left 2 Blocker findings on subtask 3 after 3 fix passes. The goal will not continue until you decide.',
      runOptions: { canLaunch: true, canCancel: true, launchLabel: 'Resume', agentId: 'codex' },
      incident: {
        progress: 'DIAGNOSIS_COMPLETE', heading: 'Needs your action', certainty: 'high',
        cause: 'Review keeps rejecting how workspace variables are read; the fix passes changed the same file three times without resolving either finding.',
        nextAction: 'Read the two Blocker findings, then record a decision for subtask 3 and resume.',
        recovery: 'unavailable', unavailableReason: 'OPERATOR_DECISION',
        evidence: [{ kind: 'review_pass', ref: 'review-pass-3', summary: 'Pass 3 verdict: changes requested, 2 Blocker findings unresolved' }, { kind: 'worktree_edit', ref: 'edit-41', summary: 'modules/backend/main.tf edited in all three fix passes' }],
        report: 'Not submitted: GitHub credentials not found',
      },
      subtasks: [
        ['Create the remote workspace and import current state', 'complete', 'c1d84a2'],
        ['Point every module at the remote backend', 'complete', '7f02be9'],
        ['Move workspace variables out of tfvars files', 'blocked', null, 'Review left 2 Blocker findings after 3 fix passes'],
        ['Lock provider versions across modules', 'pending'], ['Run plan in CI against the remote backend', 'pending'], ['Delete the local state bucket', 'pending'],
      ],
      pipeline: { done: ['preplan', 'plan', 'implement', 'simplify', 'audit'], now: 'review' },
      attempts: { 'Phase runs': 'implement 1 · review 3', 'Fix iterations': 'review 3', 'Findings in scope': '2' },
      validation: [[1, true, 5], [2, true, 5]],
      phaseResults: { subtask: 3, passes: [
        { n: 1, verdict: 'changes requested', open: 3, findings: [] },
        { n: 2, verdict: 'changes requested', open: 2, findings: [] },
        { n: 3, verdict: 'changes requested', open: 2, findings: [
          ['Blocker', 'Workspace variables are read from a local file the remote run cannot see', 'modules/backend/variables.tf:12'],
          ['Blocker', 'Sensitive variables are written to the plan output', 'modules/backend/main.tf:48'],
        ] },
      ], audit: { verdict: 'passed', summary: 'All acceptance criteria for subtask 3 map to changes.' } },
      latest: { activity: 'review · present_findings', activityAgo: '4m', edit: '…/main.tf +6 −3', editAgo: '9m', event: 'review · worker · 2 findings unresolved' },
    },
    {
      id: 'DSN-31', kind: 'goal', repo: 'design-tokens', lifecycle: 'failed', section: 'needs',
      title: 'Generate platform token packages from one source', step: 'validate', exec: 'gate run 2',
      done: 5, total: 9, current: 6, goalMs: 22 * M, subMs: 6 * M, live: false, fresh: true,
      agent: 'claude', model: 'claude-sonnet-5-5', effort: 'high', branch: 'feat/DSN-31-token-packages',
      workflow: 'wftr-20261012-081104-z8aa', liveness: 'idle', started: '12 Oct 08:11', updated: '08:33:02',
      summary: 'validate failed on subtask 6',
      reason: 'validation_failed: ./gradlew check exited 1. 3 snapshot tests in android-tokens differ from the generated output.',
      runOptions: { canLaunch: true, canCancel: true, launchLabel: 'Resume', agentId: 'claude' },
      incident: {
        progress: 'NEEDS_PREREQUISITES', heading: 'Diagnosis ready', certainty: 'high',
        cause: 'The snapshot goldens in android-tokens were recorded before the new spacing scale; the generated output is correct.',
        nextAction: 'Confirm your project edits are done, then recover: the runtime re-records the goldens and re-runs validate.',
        recovery: 'available', prerequisites: [{ id: 'project_edit_completed', text: 'Commit or stash your local edits in design-tokens so the fix starts from a clean tree.' }],
        preview: { label: 'Re-record goldens and resume', effects: 'Runs validate_fix on subtask 6, then resumes the goal.', workflow: 'wftr-20261012-081104-z8aa', phase: 'validate' },
        evidence: [{ kind: 'validation_gate', ref: 'gate-run-2', summary: './gradlew check · exit 1 · 3 failed snapshot tests' }],
        report: 'Submitted · issue #212',
      },
      subtasks: [['Token source schema', 'complete', '0a9e113'], ['Color tokens', 'complete', '91bb0c4'], ['Type tokens', 'complete', 'e4410af'], ['Spacing and radius tokens', 'complete', '2f0c7d1'], ['Web package', 'complete', 'a7c3390'], ['Android package', 'blocked', null, 'validate failed'], ['iOS package', 'pending'], ['Publish workflow', 'pending'], ['Migration notes', 'pending']],
      pipeline: { done: ['preplan', 'plan', 'implement', 'simplify', 'audit', 'review', 'build'], now: 'validate', failed: true },
      attempts: { 'Phase runs': 'validate 2', 'Relaunch causes': 'validation 1' },
      validation: [[5, true, 4], [6, false, 4]],
      latest: { activity: 'validate · gate', activityAgo: '12m', edit: null, event: 'validate · gate · 3 tests failed' },
    },
    {
      id: 'SKILL-417', kind: 'goal', repo: 'skill-bill', lifecycle: 'active', section: 'goals',
      title: 'Keep IDE status on the live goal phase after children finish', step: 'review', exec: 'pass 2',
      done: 1, total: 3, current: 2, goalMs: 1 * H + 36 * M, subMs: 12 * M + 40000, live: true, fresh: true,
      agent: 'claude', model: 'claude-opus-5-5', effort: 'high', branch: 'feat/SKILL-417-keep-ide-status-on-the-live-goal-phase',
      workflow: 'wftr-20261010-193855-mpxi', liveness: 'live', started: '10 Oct 21:38', updated: '09:48:33',
      summary: 'review pass 2 on subtask 2',
      runOptions: { canLaunch: false, canCancel: true, launchLabel: 'Resume', agentId: 'claude' },
      subtasks: [['Standalone status store keeps the workflow execution', 'complete', 'a41c9e2'], ['IDE status selects the live goal phase after a child finishes', 'in_progress'], ['Both IDE plugins accept the post-child parent snapshot', 'pending']],
      dependencies: [[3, 2, false]],
      pipeline: { done: ['preplan', 'plan', 'implement', 'simplify', 'audit'], now: 'review' },
      attempts: { 'Phase runs': 'implement 1 · review 2', 'Fix iterations': 'review 1', 'Findings in scope': '3', 'Relaunch causes': 'timeout 1' },
      validation: [[1, true, 4]],
      phaseResults: { subtask: 1, passes: [{ n: 1, verdict: 'approved', open: 0, findings: [] }], audit: { verdict: 'passed', summary: 'Both acceptance criteria are covered by tests.' } },
      latest: { activity: 'verifying 3 review findings', activityAgo: '14s', edit: '…/IdeStatusService.kt +128 −42', editAgo: '2m', event: 'review · worker · verifying 3 findings' },
    },
    {
      id: 'PAY-19', kind: 'goal', repo: 'payments-svc', lifecycle: 'active', section: 'goals', pauseRequested: true,
      title: 'Idempotent refund webhooks', step: 'implement', exec: 'attempt 1',
      done: 3, total: 7, current: 4, goalMs: 48 * M, subMs: 4 * M + 12000, live: true, fresh: true,
      agent: 'claude', model: 'claude-sonnet-5-5', effort: 'high', branch: 'feat/PAY-19-idempotent-refund-webhooks',
      workflow: 'wftr-20261012-091530-q1fe', liveness: 'live', started: '12 Oct 09:15', updated: '09:50:11',
      summary: 'Pause requested',
      runOptions: { canLaunch: false, canCancel: true, launchLabel: 'Resume', agentId: 'claude' },
      subtasks: [['Idempotency key table', 'complete', '3c19a0e'], ['Webhook signature check', 'complete', 'f55e210'], ['Refund event mapping', 'complete', '1b7d4e2'], ['Deduplicate retried deliveries', 'in_progress'], ['Replay endpoint', 'pending'], ['Dead-letter queue', 'pending'], ['Runbook', 'pending']],
      pipeline: { done: ['preplan', 'plan'], now: 'implement' },
      validation: [[1, true, 6], [2, true, 6], [3, true, 6]],
      latest: { activity: 'editing the delivery dedupe query', activityAgo: '6s', edit: '…/RefundWebhookHandler.kt +54 −9', editAgo: '40s', event: 'implement · worker' },
    },
    {
      id: 'NEWS-171', kind: 'goal', repo: 'newsreader', lifecycle: 'active', section: 'goals', planning: true, goalLaunched: false,
      title: 'Offline reading queue', step: 'plan', planned: 2, total: 5, wave: '3, 4, 5',
      goalMs: 18 * M, live: true, fresh: true,
      agent: 'codex', model: 'gpt-6-astra', effort: 'medium', branch: 'feat/NEWS-171-offline-reading-queue',
      workflow: null, invocation: 'inv-4d1a…e93', liveness: 'live', started: '12 Oct 09:50', updated: '09:51:40',
      summary: '2/5 plans saved',
      runOptions: { canLaunch: false, canCancel: true, launchLabel: 'Resume', agentId: 'codex' },
      subtasks: [], pipeline: { done: ['preplan'], now: 'plan' },
      latest: { activity: 'writing subtask 3 spec', activityAgo: '31s', edit: null, event: 'plan · worker · wave 3, 4, 5' },
    },
    {
      id: 'ATL-402', kind: 'goal', repo: 'atlas-web', lifecycle: 'paused', section: 'goals',
      title: 'Checkout retry with saved payment methods', step: 'implement', exec: null,
      done: 5, total: 9, current: 6, goalMs: 1 * H + 4 * M, subMs: 11 * M, live: false, fresh: true,
      agent: 'cursor', model: 'composer-2.5', effort: null, branch: 'feat/ATL-402-checkout-retry',
      workflow: 'wftr-20261011-140210-c3vv', liveness: 'unknown', started: '11 Oct 14:02', updated: '16:20:07',
      summary: 'Paused: runner interrupted',
      reason: 'runner_interrupted: The runner process exited while subtask 6 was implementing. Resume to continue from subtask 6.',
      runOptions: { canLaunch: true, canCancel: true, launchLabel: 'Resume', agentId: 'cursor' },
      subtasks: [['Retry policy', 'complete'], ['Saved method lookup', 'complete'], ['Retry UI', 'complete'], ['Error copy', 'complete'], ['Analytics events', 'complete'], ['Expired card path', 'in_progress'], ['3DS re-challenge', 'pending'], ['E2E tests', 'pending'], ['Feature flag', 'pending']],
      pipeline: { done: ['preplan', 'plan'], now: 'implement' },
      latest: { activity: null, edit: '…/RetryBanner.tsx +31 −4', editAgo: '2h', event: 'implement · worker · interrupted' },
    },

    /* Standalone phases: review, validation, qa, plan, pr, monitor */
    { id: 'ph-rk-review', kind: 'phase', phase: 'review', repo: 'runtime-kotlin', lifecycle: 'failed', section: 'needs', title: 'review on feat/RT-90-lease-cleanup', branch: 'feat/RT-90-lease-cleanup', step: 'review', summary: 'Review report was incomplete; available findings were printed.', reason: 'review_incomplete: 2 of 5 specialist lanes returned no result before the deadline. The 3 completed lanes printed their findings.', goalMs: 4 * M, live: false, fresh: true, model: 'claude-opus-5-5', effort: 'xhigh', agent: 'claude', invocation: 'inv-2a91…e04', started: '09:41', updated: '09:45:10', runOptions: { canLaunch: true, canCancel: false, launchLabel: 'Run again', agentId: 'claude' }, steps: [['review', 'fail']] },
    { id: 'ph-sb-validation', kind: 'phase', phase: 'validation', repo: 'skill-bill', lifecycle: 'active', stalled: '6m', section: 'phases', title: 'validation on fix/ide-status-readonly-lease', branch: 'fix/ide-status-readonly-lease', step: 'validation', summary: 'Running ./gradlew check', goalMs: 6 * M + 12000, live: true, fresh: true, model: 'claude-sonnet-5-5', effort: 'high', agent: 'claude', invocation: 'inv-5c02…7d1', started: '09:42', updated: '09:44:40', runOptions: { canLaunch: false, canCancel: true }, steps: [['validation', 'now']] },
    { id: 'ph-atl-pr', kind: 'phase', phase: 'pr', repo: 'atlas-web', lifecycle: 'active', section: 'phases', title: 'pr on feat/ATL-402-checkout-retry', branch: 'feat/ATL-402-checkout-retry', step: 'monitor_fix', exec: '1/3', summary: 'Fixing 1 failing check: lint', goalMs: 21 * M + 8000, live: true, fresh: true, model: 'claude-sonnet-5-5', effort: 'medium', agent: 'claude', invocation: 'inv-7f3c…a91', started: '09:27', updated: '09:48:02', runOptions: { canLaunch: false, canCancel: true }, steps: [['commit_push', 'done'], ['pr', 'done'], ['monitor', 'done'], ['monitor_fix', 'now']] },
    { id: 'ph-news-plan', kind: 'phase', phase: 'plan', issue: 'NEWS-180', repo: 'newsreader', lifecycle: 'active', section: 'phases', title: 'plan NEWS-180', branch: null, step: 'plan', summary: 'Writing the spec bundle', goalMs: 9 * M, live: true, fresh: true, model: 'gpt-6.1-sol', effort: 'high', agent: 'codex', invocation: 'inv-1d4e…b20', started: '09:39', updated: '09:48:30', runOptions: { canLaunch: false, canCancel: true }, steps: [['preplan', 'done'], ['plan', 'now']] },

    /* Operations: verify, update-check, release, unit-test-value-check, feature-guard, feature-guard-cleanup, pr-review-fix */
    { id: 'op-verify', kind: 'operation', op: 'verify', issue: 'SKILL-442', repo: 'skill-bill', lifecycle: 'active', section: 'operations', title: 'Verify SKILL-442', branch: 'feat/SKILL-442-cross-criterion-audit', step: 'verify', summary: 'Auditing 6 acceptance criteria', goalMs: 3 * M + 20000, live: true, fresh: true, model: 'claude-opus-5-5', effort: 'high', agent: 'claude', invocation: 'inv-8e21…f40', started: '09:46', updated: '09:49:20', runOptions: { canLaunch: false, canCancel: true }, steps: [] },
    { id: 'op-prfix', kind: 'operation', op: 'pr-review-fix', repo: 'atlas-web', lifecycle: 'active', section: 'operations', title: 'pr-review-fix on #318', branch: 'feat/ATL-399-cart-badge', step: 'pr-review-fix', summary: 'Proposing fixes for 4 review comments', goalMs: 2 * M, live: true, fresh: true, model: 'claude-sonnet-5-5', effort: 'medium', agent: 'claude', invocation: 'inv-3b7a…0c1', started: '09:47', updated: '09:49:01', runOptions: { canLaunch: false, canCancel: true }, steps: [] },

    /* Finished today */
    {
      id: 'SKILL-416', kind: 'goal', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '07:46',
      title: 'Standalone phase records on the work board', step: 'monitor', done: 3, total: 3, goalMs: 2 * H + 4 * M, live: false, fresh: true,
      agent: 'claude', model: 'claude-opus-5-5', effort: 'high', branch: 'feat/SKILL-416-standalone-phase-records', workflow: 'wftr-20261012-053902-hh2k', liveness: 'idle', started: '12 Oct 05:39', updated: '07:46:12',
      summary: 'Completed 3/3 subtasks', runOptions: { canLaunch: false, canCancel: false },
      subtasks: [['Board query lists standalone phases', 'complete', '5be0d11'], ['Phase rows in the tray', 'complete', '0c3f7e2'], ['History keeps phase outcomes', 'completed_no_change']],
      pipeline: { done: PHASE_ORDER.slice(), now: null }, finishDone: true,
      validation: [[1, true, 4], [2, true, 4], [3, true, 4]],
      goalQa: { outcome: 'passed', fixAttempts: 1, findings: [['QA-1', 'Phase rows show a stale clock after the phase finishes', true], ['QA-2', 'History filter drops operations', true]] },
      assessment: { state: 'COMPLETED', round: 1, fixRan: true, findings: [
        { ref: 'GA-002', severity: 'Major', path: 'BoardQuery.kt', line: 88, scenario: 'A phase finished at midnight is counted in both days.', verification: 'CONFIRMED', state: 'FIXED' },
        { ref: 'GA-004', severity: 'Minor', path: 'TrayRows.kt', line: 41, scenario: 'Phase id is truncated before the branch name.', verification: 'REFUTED', state: 'REFUTED' },
      ] },
      executions: [['inv-0f2e…a17', 'completed', '05:39', '07:46', 'goal_completion']],
      specBundle: ['spec.md', 'traceability.md', 'decomposition-manifest.yaml', 'subtasks/01-board-query.md', 'subtasks/02-tray-rows.md', 'subtasks/03-history.md'],
    },
    { id: 'ph-sb-validation-done', kind: 'phase', phase: 'validation', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '07:31', title: 'validation on fix/standalone-validation-discovery', branch: 'fix/standalone-validation-discovery', step: 'validation', summary: 'Completed · 4 checks passed', goalMs: 6 * M, live: false, fresh: true, agent: 'claude', model: 'claude-sonnet-5-5', effort: 'high', invocation: 'inv-77aa…19b', started: '07:25', updated: '07:31:00', runOptions: { canLaunch: false, canCancel: false }, steps: [['validation', 'done']] },
    { id: 'ph-atl-qa', kind: 'phase', phase: 'qa', repo: 'atlas-web', lifecycle: 'terminal', section: 'finished', finishedAt: '06:58', title: 'qa on feat/ATL-399-cart-badge', branch: 'feat/ATL-399-cart-badge', step: 'qa', summary: 'QA verdict: pass with 1 note', goalMs: 14 * M, live: false, fresh: true, agent: 'claude', model: 'claude-sonnet-5-5', effort: 'medium', invocation: 'inv-c09d…2e6', started: '06:44', updated: '06:58:20', runOptions: { canLaunch: false, canCancel: false }, steps: [['qa', 'done']], qaReport: 'Cart badge updates on add, remove and quantity change in Chrome and Safari.\nNote: badge animation skips frames when 20+ items are added at once.' },
    { id: 'op-update', kind: 'operation', op: 'update-check', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '06:12', title: 'update-check', branch: null, step: 'update-check', summary: 'Completed · no newer release', goalMs: 4000, live: false, fresh: true, agent: null, invocation: 'inv-11fe…0a2', started: '06:12', updated: '06:12:04', runOptions: { canLaunch: false, canCancel: false }, steps: [] },
  ];

  /* History: finished within the retention window (HistoryFilter ALL | GOALS | PHASES | OPERATIONS) */
  const history = [
    { id: 'SKILL-416', kind: 'goal', repo: 'skill-bill', lifecycle: 'terminal', title: 'Standalone phase records on the work board', ms: 2 * H + 4 * M, at: '07:46' },
    { id: 'ph-sb-validation-done', kind: 'phase', label: 'validation', repo: 'skill-bill', lifecycle: 'terminal', title: 'fix/standalone-validation-discovery', ms: 6 * M, at: '07:31' },
    { id: 'ph-atl-qa', kind: 'phase', label: 'qa', repo: 'atlas-web', lifecycle: 'terminal', title: 'feat/ATL-399-cart-badge', ms: 14 * M, at: '06:58' },
    { id: 'ATL-399', kind: 'goal', repo: 'atlas-web', lifecycle: 'terminal', title: 'Cart badge counts', ms: 1 * H + 12 * M, at: '06:40' },
    { id: 'op-update', kind: 'operation', label: 'update-check', repo: 'skill-bill', lifecycle: 'terminal', title: 'No newer release', ms: 4000, at: '06:12' },
    { id: 'DSN-30', kind: 'goal', repo: 'design-tokens', lifecycle: 'failed', title: 'Token naming lint', ms: 9 * M, at: '03:48' },
    { id: 'PAY-18', kind: 'goal', repo: 'payments-svc', lifecycle: 'canceled', title: 'Refund ledger export', ms: 26 * M, at: 'Yesterday 22:10' },
    { id: 'op-release', kind: 'operation', label: 'release', repo: 'skill-bill', lifecycle: 'terminal', title: 'Release v0.9.4', ms: 7 * M, at: 'Yesterday 18:02' },
  ];

  /* Spec pane: projects and the spec store tree (bundles with decomposition-manifest.yaml) */
  const projects = [{ id: 'l-f2ea', name: 'skill-bill', root: '~/IdeaProjects/skill-bill', store: '~/.skill-bill/projects/l-f2ea…/specs' }, { id: 'l-9c1b', name: 'newsreader', root: '~/dev/newsreader', store: '~/.skill-bill/projects/l-9c1b…/specs' }];
  const specs = [
    { key: 'SKILL-442', title: 'Cross-criterion audit, test-value probes and goal assessment', status: 'in_progress', done: 2, total: 8, run: 'running', subtasks: ['Interaction scenario authoring and catalog', 'Interaction trace audit mode', 'Accepted-criterion re-check', 'Validate test probes'], subState: ['complete', 'complete', 'in_progress', 'pending'] },
    { key: 'SKILL-417', title: 'Keep IDE status on the live goal phase after children finish', status: 'in_progress', done: 1, total: 3, run: 'running', subtasks: ['Standalone status store keeps the workflow execution', 'IDE status selects the live goal phase', 'Both IDE plugins accept the post-child parent snapshot'], subState: ['complete', 'in_progress', 'pending'] },
    { key: 'SKILL-438', title: 'Retry budget for flaky validation commands', status: 'blocked', done: 1, total: 4, run: 'paused', subtasks: ['Flake classifier', 'Retry budget config', 'Validation runner retries', 'Report retries in history'], subState: ['complete', 'blocked', 'pending', 'pending'] },
    { key: 'SKILL-444', title: 'Spec browser pane in the desktop app', status: 'pending', done: 0, total: 4, run: 'not_started', subtasks: ['Spec tree projection', 'Run actions toolbar', 'Purge flow', 'Pane states'], subState: ['pending', 'pending', 'pending', 'pending'] },
    { key: 'SKILL-401', title: 'Agent profile picker', status: 'abandoned', done: 0, total: 2, run: 'canceled', subtasks: ['Profiles', 'Picker'], subState: ['skipped', 'skipped'] },
  ];
  const doneSpecs = [['SKILL-446', 'Move write history to goal finalization'], ['SKILL-443', 'Decompose terminal decoder reads a cleared terminal as absent'], ['SKILL-420', 'Backend and web QA phase'], ['SKILL-416', 'Standalone phase records on the work board']];
  const doneSpecCount = 41;

  /* Desktop settings (AppSettings) */
  const NOTIFICATIONS = [
    ['blocked', 'A goal is blocked', true], ['failed', 'A goal or phase fails', true], ['goal_completed', 'A goal completes', true],
    ['pause_took_effect', 'A pause takes effect', true], ['standalone_phase_finished', 'A standalone phase finishes', false], ['qa_incomplete', 'QA could not complete', true],
  ];
  const CLAUDE_DIRS = ['~/.claude', '~/.claude-work'];

  /* Runtime config: execution_matrix (DEFAULT_PHASE_TIERS), compaction, project store config.yaml, telemetry */
  const PHASE_GROUPS = [
    ['Planning', [['intake', 'implementation'], ['preplan', 'implementation'], ['plan', 'reasoning'], ['plan_subtasks', 'reasoning']]],
    ['Build', [['implement', 'implementation'], ['simplify', 'implementation'], ['implement_fix', 'implementation'], ['build', 'implementation']]],
    ['Audit', [['audit', 'reasoning'], ['audit_plan_fix', 'reasoning'], ['audit_implement_fix', 'implementation']]],
    ['Review', [['review', 'reasoning'], ['verify_findings', 'reasoning'], ['present_findings', 'reasoning']]],
    ['Validate and QA', [['validate', 'reasoning'], ['qa', 'implementation'], ['qa_fix', 'implementation']]],
    ['Ship', [['write_history', 'implementation'], ['commit_push', 'implementation'], ['pr', 'implementation'], ['monitor', 'implementation'], ['monitor_fix', 'implementation']]],
    ['Goal finish', [['goal_assessment', 'reasoning'], ['goal_assessment_fix', 'implementation']]],
  ];
  const MATRIX = {
    claude: { reasoning: { model: 'claude-opus-5-5', effort: 'high' }, implementation: { model: 'claude-sonnet-5-5', effort: 'high' }, phases: { review: { model: 'claude-opus-5-5', effort: 'xhigh' } } },
    codex: { reasoning: { model: 'gpt-6.1-sol', effort: 'high' }, implementation: { model: 'gpt-6-luna', effort: 'high' }, phases: {} },
    cursor: { reasoning: { model: 'cursor-grok-4.6-high', effort: null }, implementation: { model: 'composer-2.5', effort: null }, phases: {} },
  };
  /* ReviewContextBudgetPolicy defaults; one value changed in this example */
  const BUDGET = [
    ['max_parent_packet_bytes', 'Parent packet', '1 MiB', '512 KiB'], ['max_lane_launch_bytes', 'Lane launch', '64 KiB', '64 KiB'], ['max_lane_evidence_bytes', 'Lane evidence', '256 KiB', '256 KiB'],
    ['max_evidence_result_bytes', 'Evidence result', '64 KiB', '64 KiB'], ['max_lane_result_bytes', 'Lane result', '64 KiB', '64 KiB'], ['max_assignment_expansions', 'Assignment expansions', '3', '3'],
    ['max_specialist_tool_calls', 'Specialist tool calls', '40', '40'], ['max_specialist_model_turns', 'Specialist model turns', '24', '24'],
  ];

  window.DATA = { T0, REPOS, AGENTS, AGENT_CHOICES, PHASE_ORDER, GOAL_FINISH, items, history, projects, specs, doneSpecs, doneSpecCount, NOTIFICATIONS, CLAUDE_DIRS, PHASE_GROUPS, MATRIX, BUDGET };
})();
