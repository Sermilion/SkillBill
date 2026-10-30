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

  /* Subtask builders (WorkSubtask + SubtaskDependency + SubtaskValidation + SubtaskPhaseResults + PlannedSubtask.specPath).
   * `id` and `spec` come from the position. Ages (`*Ago`) are seconds before now and stand in for the model's Instants. */
  const sub = (title, status, o) => Object.assign({ title, status, commit: null, blocked: null, deps: [], validation: null, reviews: [], audit: null }, o || {});
  const subs = (list) => list.map((s, i) => Object.assign({ id: i + 1, spec: `spec_subtask_${i + 1}.md` }, s));
  const dep = (id, optional) => ({ id, optional: !!optional });
  const ok = (checks) => ({ passed: true, checks });
  const bad = (checks) => ({ passed: false, checks });
  const rev = (n, verdict, open, findings) => ({ n, verdict, open, findings: findings || [] });
  /* A goal's bundleFiles in the runtime's raw order; the detail sorts them. */
  const bundle = (n) => ['traceability.md', ...Array.from({ length: n }, (_, i) => `spec_subtask_${n - i}.md`), 'spec.md', 'decomposition-manifest.yaml'];

  /* kind: goal | phase | operation. section: the runtime's display group. lifecycle: Lifecycle enum. */
  const items = [
    {
      id: 'INF-7', kind: 'goal', repo: 'infra-terraform', lifecycle: 'blocked', section: 'needs',
      title: 'Migrate state backend to a remote workspace', step: 'review', exec: 'pass 3/3',
      done: 2, total: 6, current: 3, goalMs: 7 * H + 26 * M, subMs: 48 * M, live: false, fresh: true,
      agent: 'codex', model: 'gpt-6.1-sol', effort: 'high', branch: 'feat/INF-7-remote-state-backend',
      workflow: 'wftr-20261011-062311-k2rq', invocation: null, liveness: 'idle', started: '11 Oct 06:23', updated: '17:49:54',
      summary: 'Review left 2 Blocker findings on subtask 3',
      reason: 'awaiting_operator_decision: Review left 2 Blocker findings on subtask 3 after 3 fix passes. Pass 1 flagged that workspace variables are read from terraform.tfvars, which a remote run cannot see, and that sensitive variables are written to the plan output. Passes 2 and 3 moved the reads into modules/backend/variables.tf but kept the local file as their source, so both findings stayed open. The goal will not continue until you record a decision for subtask 3.',
      awaitsDecision: true,
      runOptions: { canLaunch: true, canCancel: true, launchLabel: 'Resume', agentId: 'codex' },
      incident: {
        progress: 'DIAGNOSIS_COMPLETE', heading: 'Needs your action', certainty: 'high',
        cause: 'Review keeps rejecting how workspace variables are read; the fix passes changed the same file three times without resolving either finding.',
        nextAction: 'Read the two Blocker findings, then record a decision for subtask 3 and resume.',
        recovery: 'unavailable', unavailableReason: 'OPERATOR_DECISION',
        evidence: [
          { kind: 'review_pass', ref: 'review_pass:wftr-20261011-062311-k2rq/subtask-3/review/pass-3/finding-batch-7f2c91d0', summary: 'Pass 3 verdict: changes requested. Both Blocker findings from pass 1 are still open: workspace variables are read from a local terraform.tfvars that the remote run cannot see, and sensitive variables are written to the plan output. The fix moved the variable reads into modules/backend/variables.tf without changing where the values come from.' },
          { kind: 'worktree_edit', ref: 'worktree_edit:wftr-20261011-062311-k2rq/journal/41', summary: 'modules/backend/main.tf edited in all three fix passes' },
        ],
        report: 'Not submitted: GitHub credentials not found',
      },
      /* Every subtask state the inspector shows: done with commit and passed validation, blocked with a reason and three
       * review passes, pending with no review yet, required and optional dependencies, and the reverse "needed by" edges. */
      subtasks: subs([
        sub('Create the remote workspace and import current state', 'complete', { commit: 'c1d84a2076e36d0aa66130b65a7b76d9f793d979', validation: ok(12),
          reviews: [rev(1, 'approved', 0)], audit: { verdict: 'passed', summary: 'Both acceptance criteria map to changes.' } }),
        sub('Point every module at the remote backend', 'complete', { commit: '7f02be9fa39b4ea20c168015b7695ab6f01a4323', deps: [dep(1)], validation: ok(12),
          reviews: [rev(1, 'changes requested', 1, [['Minor', 'The backend block repeats the organization name in every module', 'modules/network/backend.tf:3']]), rev(2, 'approved', 0)], audit: { verdict: 'passed', summary: 'All three acceptance criteria map to changes.' } }),
        sub('Move workspace variables out of tfvars files', 'blocked', { blocked: 'Review left 2 Blocker findings after 3 fix passes', deps: [dep(2)],
          reviews: [
            rev(1, 'changes requested', 3, [['Blocker', 'Workspace variables are read from a local file the remote run cannot see', 'modules/backend/variables.tf:12'], ['Blocker', 'Sensitive variables are written to the plan output', 'modules/backend/main.tf:48'], ['Major', 'The variable set is attached to one workspace instead of the project', 'modules/backend/workspace.tf:21']]),
            rev(2, 'changes requested', 2, [['Blocker', 'Workspace variables are read from a local file the remote run cannot see', 'modules/backend/variables.tf:12'], ['Blocker', 'Sensitive variables are written to the plan output', 'modules/backend/main.tf:48']]),
            rev(3, 'changes requested', 2, [['Blocker', 'Workspace variables are read from a local file the remote run cannot see', 'modules/backend/variables.tf:12'], ['Blocker', 'Sensitive variables are written to the plan output', 'modules/backend/main.tf:48']]),
          ], audit: { verdict: 'passed', summary: 'All acceptance criteria for subtask 3 map to changes.' } }),
        sub('Lock provider versions across modules', 'pending', { deps: [dep(3), dep(2, true)] }),
        sub('Run plan in CI against the remote backend', 'pending', { deps: [dep(4)] }),
        sub('Delete the local state bucket', 'pending', { deps: [dep(5), dep(1)] }),
      ]),
      pipeline: { done: ['preplan', 'plan', 'implement', 'simplify', 'audit'], now: 'review' },
      /* DetailAttempts: per-phase runs, fix iterations, relaunch causes, findings in scope, supervisor kills */
      attempts: {
        phaseRuns: { implement: 6, simplify: 2, audit: 4, audit_plan_fix: 4, audit_implement_fix: 3, review: 9, verify_findings: 3, present_findings: 3, build: 2, validate: 2 },
        fixIterations: { review: 3, audit_plan_fix: 2, audit_implement_fix: 2 },
        relaunches: { runner_interrupted: 2, timeout: 1 },
        findings: 2, kills: 1,
      },
      specBundle: bundle(6),
      startedAgo: 34 * 3600,
      executions: [
        { inv: 'inv-0a12c4e8b1f37d2e9a40c6b2-311', life: 'interrupted', start: '11 Oct 06:23', startAgo: 34 * 3600, end: '11 Oct 09:02', endAgo: 31 * 3600 + 21 * 60, result: 'runner_interrupted' },
        { inv: 'inv-5be81d07a3c94f6e2d18b7a5-4c2', life: 'interrupted', start: '11 Oct 09:40', startAgo: 30 * 3600 + 43 * 60, end: '11 Oct 13:15', endAgo: 27 * 3600 + 8 * 60, result: 'runner_interrupted' },
        { inv: 'inv-9d3f62a1e0b84c7d5f29e3c1-8a7', life: 'paused', start: '11 Oct 14:02', startAgo: 26 * 3600 + 21 * 60, end: '11 Oct 17:30', endAgo: 22 * 3600 + 53 * 60, result: 'operator_pause' },
        { inv: 'inv-2c7e90b4d6a15f3e8c02b9d4-f15', life: 'blocked', start: '12 Oct 09:31', startAgo: 52 * 60, end: '12 Oct 10:11', endAgo: 12 * 60, result: 'awaiting_operator_decision' },
      ],
      latest: { activity: { label: 'presented 2 review findings', ago: 4 * 60 }, edit: { path: 'modules/backend/main.tf', ins: 6, del: 3, ago: 9 * 60 }, event: { phase: 'review', role: 'worker', summary: '2 findings unresolved', ago: 3 * 60 } },
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
      subtasks: subs([
        sub('Token source schema', 'complete', { commit: '0a9e113e409f39618486217248be76d2b39f5f05', validation: ok(4), reviews: [rev(1, 'approved', 0)] }),
        sub('Color tokens', 'complete', { commit: '91bb0c452db5e9406575bb86803ede0684a4ce62', deps: [dep(1)], validation: ok(4), reviews: [rev(1, 'approved', 0)] }),
        sub('Type tokens', 'complete', { commit: 'e4410af453bacb605fc9f18d09448f0420077112', deps: [dep(1)], validation: ok(4), reviews: [rev(1, 'approved', 0)] }),
        sub('Spacing and radius tokens', 'complete', { commit: '2f0c7d1f4060fb13fc1be2f7f95a7eeea04e3177', deps: [dep(1)], validation: ok(4), reviews: [rev(1, 'approved', 0)] }),
        sub('Web package', 'complete', { commit: 'a7c33905c0a02c6fcf710bdd041b547c548e8c5e', deps: [dep(2), dep(3), dep(4)], validation: ok(4), reviews: [rev(1, 'approved', 0)] }),
        sub('Android package', 'blocked', { blocked: 'validate failed: 3 snapshot tests differ from the generated output', deps: [dep(2), dep(3), dep(4), dep(5, true)], validation: bad(3),
          reviews: [rev(1, 'approved', 0)], audit: { verdict: 'passed', summary: 'The package covers every token group.' } }),
        sub('iOS package', 'pending', { deps: [dep(2), dep(3), dep(4)] }),
        sub('Publish workflow', 'pending', { deps: [dep(5), dep(6), dep(7)] }),
        sub('Migration notes', 'pending', { deps: [dep(8, true)] }),
      ]),
      pipeline: { done: ['preplan', 'plan', 'implement', 'simplify', 'audit', 'review', 'build'], now: 'validate', failed: true },
      attempts: { phaseRuns: { implement: 6, simplify: 6, audit: 6, review: 6, build: 6, validate: 7 }, fixIterations: { validate: 1 }, relaunches: { validation: 1 }, findings: 0, kills: 0 },
      specBundle: bundle(9),
      startedAgo: 2 * 3600 + 30 * 60,
      latest: { activity: { label: 'ran the validation gate', ago: 12 * 60 }, edit: null, event: { phase: 'validate', role: 'gate', summary: '3 tests failed', ago: 12 * 60 } },
    },
    {
      id: 'NEWS-176', kind: 'goal', repo: 'newsreader', lifecycle: 'blocked', section: 'needs', awaitsDecision: true,
      title: 'Article cache eviction by reading history', step: 'audit', exec: 'fix 2/2',
      done: 1, total: 4, current: 2, goalMs: 54 * M, subMs: 19 * M, live: false, fresh: true,
      agent: 'claude', model: 'claude-opus-5-5', effort: 'high', branch: 'feat/NEWS-176-cache-eviction',
      workflow: 'wftr-20261012-074410-r5tn', liveness: 'idle', started: '12 Oct 07:44', updated: '08:38:15',
      summary: 'Audit left 1 criterion unmet on subtask 2',
      reason: 'awaiting_operator_decision: Audit found no change for acceptance criterion 3 on subtask 2 after 2 fix passes.',
      runOptions: { canLaunch: true, canCancel: true, launchLabel: 'Resume', agentId: 'claude' },
      subtasks: subs([
        sub('Reading history store', 'complete', { commit: '8e41c070c790b3b77ed9e0eeb4ce8b8dc416a339', validation: ok(7), reviews: [rev(1, 'approved', 0)] }),
        sub('Evict by last read time', 'blocked', { blocked: 'Audit left criterion 3 unmet after 2 fix passes', deps: [dep(1)],
          audit: { verdict: 'criteria unmet', summary: 'No change covers criterion 3: evict articles unread for 30 days.' } }),
        sub('Cache size setting', 'pending', { deps: [dep(2)] }),
        sub('Eviction metrics', 'pending', { deps: [dep(2), dep(3, true)] }),
      ]),
      pipeline: { done: ['preplan', 'plan', 'implement', 'simplify'], now: 'audit' },
      attempts: { phaseRuns: { implement: 2, simplify: 2, audit: 3, audit_plan_fix: 2, audit_implement_fix: 2, review: 1, validate: 1 }, fixIterations: { audit_plan_fix: 2 }, relaunches: {}, findings: 0, kills: 0 },
      specBundle: bundle(4),
      startedAgo: 2 * 3600 + 8 * 60,
      latest: { activity: null, edit: { path: 'app/src/main/java/news/cache/CacheEvictor.kt', ins: 22, del: 8, ago: 14 * 60 }, event: { phase: 'audit', role: 'worker', summary: '1 criterion unmet', ago: 13 * 60 } },
    },
    {
      id: 'SKILL-417', kind: 'goal', repo: 'skill-bill', lifecycle: 'active', section: 'goals',
      title: 'Keep IDE status on the live goal phase after children finish', step: 'review', exec: 'pass 2',
      done: 1, total: 3, current: 2, goalMs: 1 * H + 36 * M, subMs: 12 * M + 40000, live: true, fresh: true,
      agent: 'claude', model: 'claude-opus-5-5', effort: 'high', branch: 'feat/SKILL-417-keep-ide-status-on-the-live-goal-phase',
      workflow: 'wftr-20261010-193855-mpxi', liveness: 'live', started: '10 Oct 21:38', updated: '09:48:33',
      summary: 'review pass 2 on subtask 2',
      runOptions: { canLaunch: false, canCancel: true, launchLabel: 'Resume', agentId: 'claude' },
      subtasks: subs([
        sub('Standalone status store keeps the workflow execution', 'complete', { commit: 'a41c9e2d31400fb0f88b0c0c93e893410f9e06c2', validation: ok(4),
          reviews: [rev(1, 'approved', 0)], audit: { verdict: 'passed', summary: 'Both acceptance criteria are covered by tests.' } }),
        sub('IDE status selects the live goal phase after a child finishes', 'in_progress', { deps: [dep(1)],
          reviews: [rev(1, 'changes requested', 3, [['Major', 'The status reads the child phase after the parent snapshot is replaced', 'runtime/src/main/kotlin/dev/skillbill/ide/IdeStatusService.kt:142'], ['Minor', 'The lease check runs twice per poll', 'IdeStatusService.kt:88'], ['Minor', 'A finished child keeps its phase label for one poll', 'IdeStatusPresenter.kt:31']])] }),
        sub('Both IDE plugins accept the post-child parent snapshot', 'pending', { deps: [dep(2)] }),
      ]),
      pipeline: { done: ['preplan', 'plan', 'implement', 'simplify', 'audit'], now: 'review' },
      attempts: { phaseRuns: { implement: 2, simplify: 2, audit: 2, review: 2, verify_findings: 1, build: 1, validate: 1 }, fixIterations: { review: 1 }, relaunches: { timeout: 1 }, findings: 3, kills: 0 },
      specBundle: bundle(3),
      startedAgo: 12 * 3600 + 10 * 60,
      executions: [
        { inv: 'inv-6e0b3f9c2a71d48e5b13c7f0-2d4', life: 'failed', start: '10 Oct 21:38', startAgo: 12 * 3600 + 10 * 60, end: '10 Oct 23:02', endAgo: 10 * 3600 + 46 * 60, result: 'timeout' },
        { inv: 'inv-b84d1e2f07c96a3d5e48f2b1-9c0', life: 'active', start: '11 Oct 08:12', startAgo: 1 * 3600 + 36 * 60, end: null, endAgo: null, result: null },
      ],
      latest: { activity: { label: 'verifying 3 review findings', ago: 14 }, edit: { path: 'runtime/src/main/kotlin/dev/skillbill/ide/IdeStatusService.kt', ins: 128, del: 42, ago: 2 * 60 }, event: { phase: 'review', role: 'worker', summary: 'verifying 3 findings', ago: 40 } },
    },
    {
      id: 'PAY-19', kind: 'goal', repo: 'payments-svc', lifecycle: 'active', section: 'goals', pauseRequested: true,
      title: 'Idempotent refund webhooks', step: 'implement', exec: 'attempt 1',
      done: 3, total: 7, current: 4, goalMs: 48 * M, subMs: 4 * M + 12000, live: true, fresh: true,
      agent: 'claude', model: 'claude-sonnet-5-5', effort: 'high', branch: 'feat/PAY-19-idempotent-refund-webhooks',
      workflow: 'wftr-20261012-091530-q1fe', liveness: 'live', started: '12 Oct 09:15', updated: '09:50:11',
      summary: 'Pause requested',
      runOptions: { canLaunch: false, canCancel: true, launchLabel: 'Resume', agentId: 'claude' },
      subtasks: subs([
        sub('Idempotency key table', 'complete', { commit: '3c19a0e5dbdb9647e0a0dd9af918226c4c929098', validation: ok(6), reviews: [rev(1, 'approved', 0)] }),
        sub('Webhook signature check', 'complete', { commit: 'f55e21086c67647a80c13a3bfb1c2d0789bee6bd', validation: ok(6), reviews: [rev(1, 'approved', 0)] }),
        sub('Refund event mapping', 'complete', { commit: '1b7d4e270362aadb8ce4af4060e197e5e1973cca', deps: [dep(2)], validation: ok(6), reviews: [rev(1, 'approved', 0)] }),
        sub('Deduplicate retried deliveries', 'in_progress', { deps: [dep(1), dep(3)] }),
        sub('Replay endpoint', 'pending', { deps: [dep(4)] }),
        sub('Dead-letter queue', 'pending', { deps: [dep(4), dep(5, true)] }),
        sub('Runbook', 'pending'),
      ]),
      pipeline: { done: ['preplan', 'plan'], now: 'implement' },
      attempts: { phaseRuns: { implement: 4, simplify: 3, audit: 3, review: 3, build: 3, validate: 3, commit_push: 3 }, fixIterations: {}, relaunches: {}, findings: 0, kills: 0 },
      specBundle: bundle(7),
      startedAgo: 48 * 60,
      latest: { activity: { label: 'editing the delivery dedupe query', ago: 6 }, edit: { path: 'src/main/kotlin/payments/refund/RefundWebhookHandler.kt', ins: 54, del: 9, ago: 40 }, event: { phase: 'implement', role: 'worker', summary: null, ago: 3 * 60 } },
    },
    {
      id: 'NEWS-171', kind: 'goal', repo: 'newsreader', lifecycle: 'active', section: 'goals', planning: true, goalLaunched: false,
      title: 'Offline reading queue', step: 'plan', planned: 2, total: 5,
      goalMs: 18 * M, live: true, fresh: true,
      agent: 'codex', model: 'gpt-6-astra', effort: 'medium', branch: 'feat/NEWS-171-offline-reading-queue',
      workflow: null, invocation: 'inv-4d1a5e298ae7c3509a00da95-e93', liveness: 'live', started: '12 Oct 09:50', updated: '09:51:40',
      summary: '2/5 plans saved',
      runOptions: { canLaunch: false, canCancel: true, launchLabel: 'Resume', agentId: 'codex' },
      subtasks: [], pipeline: { done: ['preplan'], now: 'plan' },
      startedAgo: 18 * 60,
      latest: { activity: { label: 'writing subtask 3 spec', ago: 31 }, edit: null, event: { phase: 'plan', role: 'worker', summary: 'saving subtask plans', ago: 50 } },
    },
    {
      id: 'ATL-402', kind: 'goal', repo: 'atlas-web', lifecycle: 'paused', section: 'goals',
      title: 'Checkout retry with saved payment methods', step: 'implement', exec: null,
      done: 5, total: 12, current: 6, goalMs: 1 * H + 4 * M, subMs: 11 * M, live: false, fresh: true,
      agent: 'cursor', model: 'composer-2.5', effort: null, branch: 'feat/ATL-402-checkout-retry',
      workflow: 'wftr-20261011-140210-c3vv', liveness: 'unknown', started: '11 Oct 14:02', updated: '16:20:07',
      summary: 'Paused: runner interrupted',
      reason: 'runner_interrupted: The runner process exited while subtask 6 was implementing. Resume to continue from subtask 6.',
      runOptions: { canLaunch: true, canCancel: true, launchLabel: 'Resume', agentId: 'cursor' },
      subtasks: subs([['Retry policy', 'complete'], ['Saved method lookup', 'complete'], ['Retry UI', 'complete'], ['Error copy', 'complete'], ['Analytics events', 'complete'], ['Expired card path', 'in_progress'], ['3DS re-challenge', 'pending'], ['E2E tests', 'pending'], ['Feature flag', 'pending'], ['Saved method migration', 'pending'], ['Retry metrics dashboard', 'pending'], ['Rollout runbook', 'pending']].map(([t, s], i) => sub(t, s, i && i < 6 ? { deps: [dep(i)] } : {}))),
      attempts: { phaseRuns: { implement: 6, simplify: 5, audit: 5, review: 5, build: 5, validate: 5 }, fixIterations: {}, relaunches: { runner_interrupted: 1 }, findings: 0, kills: 0 },
      startedAgo: 19 * 3600 + 49 * 60,
      pipeline: { done: ['preplan', 'plan'], now: 'implement' },
      /* Raw runtime order; the detail sorts it (manifest, spec.md, then names with numbers compared as numbers). */
      specBundle: ['spec_subtask_10.md', 'traceability.md', 'spec_subtask_2.md', 'spec.md', 'spec_subtask_1.md', 'spec_subtask_11.md', 'spec_subtask_3.md', 'spec_subtask_12.md', 'decomposition-manifest.yaml', 'spec_subtask_4.md', 'spec_subtask_5.md', 'spec_subtask_6.md', 'spec_subtask_7.md', 'spec_subtask_8.md', 'spec_subtask_9.md'],
      latest: { activity: null, edit: { path: 'src/checkout/retry/RetryBanner.tsx', ins: 31, del: 4, ago: 17 * 3600 + 30 * 60 }, event: { phase: 'implement', role: 'worker', summary: 'interrupted', ago: 17 * 3600 + 29 * 60 } },
    },

    /* Standalone phases: review, validation, qa, plan, pr, monitor */
    { id: 'ph-rk-review', kind: 'phase', phase: 'review', repo: 'runtime-kotlin', lifecycle: 'failed', section: 'needs', title: 'review on feat/RT-90-lease-cleanup', branch: 'feat/RT-90-lease-cleanup', step: 'review', summary: 'Review report was incomplete; available findings were printed.', reason: 'review_incomplete: 2 of 5 specialist lanes returned no result before the deadline. The 3 completed lanes printed their findings.', goalMs: 4 * M, live: false, fresh: true, model: 'claude-opus-5-5', effort: 'xhigh', agent: 'claude', invocation: 'inv-2a9140a08b33b80c77fe0204-e04', started: '09:41', updated: '09:45:10', startedAgo: 540, finishedAgo: 300, refreshFailed: true, runOptions: { canLaunch: true, canCancel: false, launchLabel: 'Run again', agentId: 'claude' }, steps: [['review', 'fail']] },
    { id: 'ph-sb-validation', kind: 'phase', phase: 'validation', repo: 'skill-bill', lifecycle: 'active', stalled: '6m', section: 'phases', title: 'validation on fix/ide-status-readonly-lease', branch: 'fix/ide-status-readonly-lease', step: 'validation', summary: 'Running ./gradlew check', goalMs: 6 * M + 12000, live: true, fresh: true, model: 'claude-sonnet-5-5', effort: 'high', agent: 'claude', invocation: 'inv-5c028fed5cc5adf53a7a6453-7d1', started: '09:42', updated: '09:44:40', startedAgo: 480, runOptions: { canLaunch: false, canCancel: true }, steps: [['validation', 'now']] },
    { id: 'ph-atl-pr', kind: 'phase', phase: 'pr', repo: 'atlas-web', lifecycle: 'active', section: 'phases', title: 'pr on feat/ATL-402-checkout-retry', branch: 'feat/ATL-402-checkout-retry', step: 'monitor_fix', exec: '1/3', summary: 'Fixing 1 failing check: lint', goalMs: 21 * M + 8000, live: true, fresh: true, model: 'claude-sonnet-5-5', effort: 'medium', agent: 'claude', invocation: 'inv-7f3c6534839588bb13e22a54-a91', started: '09:27', updated: '09:48:02', startedAgo: 1380, runOptions: { canLaunch: false, canCancel: true }, steps: [['commit_push', 'done'], ['pr', 'done'], ['monitor', 'done'], ['monitor_fix', 'now']] },
    { id: 'ph-news-plan', kind: 'phase', phase: 'plan', issue: 'NEWS-180', repo: 'newsreader', lifecycle: 'active', section: 'phases', title: 'plan NEWS-180', branch: null, step: 'plan', summary: 'Writing the spec bundle', goalMs: 9 * M, live: true, fresh: true, model: 'gpt-6.1-sol', effort: 'high', agent: 'codex', invocation: 'inv-1d4e5b886988a1005708520a-b20', started: '09:39', updated: '09:48:30', startedAgo: 660, runOptions: { canLaunch: false, canCancel: true }, steps: [['preplan', 'done'], ['plan', 'now']] },

    /* Operations: verify, update-check, release, unit-test-value-check, feature-guard, feature-guard-cleanup, pr-review-fix */
    { id: 'op-verify', kind: 'operation', op: 'verify', issue: 'SKILL-442', repo: 'skill-bill', lifecycle: 'active', section: 'operations', title: 'Verify SKILL-442', branch: 'feat/SKILL-442-cross-criterion-audit', step: 'verify', summary: 'Auditing 6 acceptance criteria', goalMs: 3 * M + 20000, live: true, fresh: true, model: 'claude-opus-5-5', effort: 'high', agent: 'claude', invocation: 'inv-8e21aeb36f6e3a2506c6e2da-f40', started: '09:46', updated: '09:49:20', startedAgo: 240, runOptions: { canLaunch: false, canCancel: true }, steps: [] },
    { id: 'op-prfix', kind: 'operation', op: 'pr-review-fix', repo: 'atlas-web', lifecycle: 'active', section: 'operations', title: 'pr-review-fix on #318', branch: 'feat/ATL-399-cart-badge', step: 'pr-review-fix', summary: 'Proposing fixes for 4 review comments', goalMs: 2 * M, live: true, fresh: true, model: 'claude-sonnet-5-5', effort: 'medium', agent: 'claude', invocation: 'inv-3b7a1dc070603fe1ae258162-0c1', started: '09:47', updated: '09:49:01', startedAgo: 180, runOptions: { canLaunch: false, canCancel: true }, steps: [] },

    /* Finished today */
    {
      id: 'SKILL-416', kind: 'goal', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '07:46',
      title: 'Standalone phase records on the work board', step: 'monitor', done: 3, total: 3, goalMs: 2 * H + 4 * M, live: false, fresh: true,
      agent: 'claude', model: 'claude-opus-5-5', effort: 'high', branch: 'feat/SKILL-416-standalone-phase-records', workflow: 'wftr-20261012-053902-hh2k', liveness: 'idle', started: '12 Oct 05:39', updated: '07:46:12',
      summary: 'Completed 3/3 subtasks', finishedAgo: 2 * 3600 + 5 * 60, runOptions: { canLaunch: false, canCancel: false },
      subtasks: subs([
        sub('Board query lists standalone phases', 'complete', { commit: '5be0d1133c7a87af226d71285529aede9885638e', validation: ok(4), reviews: [rev(1, 'approved', 0)], audit: { verdict: 'passed', summary: 'All criteria map to changes.' } }),
        sub('Phase rows in the tray', 'complete', { commit: '0c3f7e2761efcdfd86034397f8598daf8ea41df6', deps: [dep(1)], validation: ok(4), reviews: [rev(1, 'approved', 0)], audit: { verdict: 'passed', summary: 'All criteria map to changes.' } }),
        sub('History keeps phase outcomes', 'completed_no_change', { deps: [dep(1)], validation: ok(4), reviews: [rev(1, 'approved', 0)] }),
      ]),
      pipeline: { done: PHASE_ORDER.slice(), now: null }, finishDone: true,
      attempts: { phaseRuns: { implement: 3, simplify: 3, audit: 3, review: 3, build: 3, validate: 3, write_history: 1, commit_push: 3, pr: 1, monitor: 1 }, fixIterations: {}, relaunches: {}, findings: 0, kills: 0 },
      startedAgo: 4 * 3600 + 12 * 60,
      goalQa: { outcome: 'passed', fixAttempts: 1, findings: [['QA-1', 'Phase rows show a stale clock after the phase finishes', true], ['QA-2', 'History filter drops operations', true]] },
      assessment: { state: 'COMPLETED', round: 1, fixRan: true, findings: [
        { ref: 'GA-002', severity: 'Major', path: 'BoardQuery.kt', line: 88, scenario: 'A phase finished at midnight is counted in both days.', verification: 'CONFIRMED', state: 'FIXED' },
        { ref: 'GA-004', severity: 'Minor', path: 'TrayRows.kt', line: 41, scenario: 'Phase id is truncated before the branch name.', verification: 'REFUTED', state: 'REFUTED' },
      ] },
      executions: [{ inv: 'inv-0f2eb990d8f4d5aabc0bf3da-a17', life: 'completed', start: '05:39', startAgo: 4 * 3600 + 12 * 60, end: '07:46', endAgo: 2 * 3600 + 5 * 60, result: 'goal_completion' }],
      specBundle: ['spec.md', 'spec_subtask_1.md', 'spec_subtask_2.md', 'spec_subtask_3.md', 'traceability.md', 'decomposition-manifest.yaml'],
    },
    { id: 'ph-sb-validation-done', kind: 'phase', phase: 'validation', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '07:31', title: 'validation on fix/standalone-validation-discovery', branch: 'fix/standalone-validation-discovery', step: 'validation', summary: 'Completed · 4 checks passed', goalMs: 6 * M, live: false, fresh: true, agent: 'claude', model: 'claude-sonnet-5-5', effort: 'high', invocation: 'inv-77aab57ef65d29f5758c8f12-19b', started: '07:25', updated: '07:31:00', startedAgo: 8700, finishedAgo: 8340, runOptions: { canLaunch: false, canCancel: false }, steps: [['validation', 'done']] },
    { id: 'ph-atl-qa', kind: 'phase', phase: 'qa', repo: 'atlas-web', lifecycle: 'terminal', section: 'finished', finishedAt: '06:58', title: 'qa on feat/ATL-399-cart-badge', branch: 'feat/ATL-399-cart-badge', step: 'qa', summary: 'QA verdict: pass with 1 note', goalMs: 14 * M, live: false, fresh: true, agent: 'claude', model: 'claude-sonnet-5-5', effort: 'medium', invocation: 'inv-c09d08f805689da93c189464-2e6', started: '06:44', updated: '06:58:20', startedAgo: 11160, finishedAgo: 10320, runOptions: { canLaunch: false, canCancel: false }, steps: [['qa', 'done']], qaReport: 'Cart badge updates on add, remove and quantity change in Chrome and Safari.\nNote: badge animation skips frames when 20+ items are added at once.' },
    { id: 'op-update', kind: 'operation', op: 'update-check', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '06:12', title: 'update-check', branch: null, step: 'update-check', summary: 'Completed · no newer release', goalMs: 4000, live: false, fresh: true, agent: null, invocation: 'inv-11fe3acd2aaab73b37d001bc-0a2', started: '06:12', updated: '06:12:04', startedAgo: 13080, finishedAgo: 13080, runOptions: { canLaunch: false, canCancel: false }, steps: [] },
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
    ['max_parent_packet_bytes', 'Parent packet', 1048576, 524288], ['max_lane_launch_bytes', 'Lane launch', 65536, 65536], ['max_lane_evidence_bytes', 'Lane evidence', 262144, 262144],
    ['max_evidence_result_bytes', 'Evidence result', 65536, 65536], ['max_lane_result_bytes', 'Lane result', 65536, 65536], ['max_assignment_expansions', 'Assignment expansions', 3, 3],
    ['max_specialist_tool_calls', 'Specialist tool calls', 40, 40], ['max_specialist_model_turns', 'Specialist model turns', 24, 24],
  ];

  window.DATA = { T0, REPOS, AGENTS, AGENT_CHOICES, PHASE_ORDER, GOAL_FINISH, items, history, projects, specs, doneSpecs, doneSpecCount, NOTIFICATIONS, CLAUDE_DIRS, PHASE_GROUPS, MATRIX, BUDGET };
})();
