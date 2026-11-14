/* Sona mobile study · detail facts
 * The rest of what the desktop detail shows for each run (design/linear-study HANDOFF 9.2 and the details pane):
 * location, chips, run options, metrics, run reason, incident with evidence and report, operator decision,
 * Execution (workflow, invocation, agent, model, liveness, started, updated), Runs and fixes, and the run events feed.
 * Merged into MD.RUNS by id. Example values only. */
(function () {
  'use strict';
  const D = window.MD;

  const MORE = {
    'SKILL-512': {
      path: '~/IdeaProjects/skill-bill', fresh: true, effort: 'high',
      runOptions: { canLaunch: false, canCancel: true, canFinalize: false },
      metrics: { exec: 'attempt 1', subActive: '12m 40s' },
      workflow: 'wftr-20261114-130212-m4x8', invocation: 'inv-7c2e19a04b6d3f85e1a9c0d2-512', liveness: 'agent output 3s ago', updated: '13:58:41',
      attempts: { phaseRuns: { implement: 4, simplify: 3, audit: 3, review: 4, validate: 3, commit_push: 3 }, fixIterations: { review: 1 }, relaunches: {}, findings: 2, kills: 0 },
      executions: [{ life: 'active', start: '13:02', startAgo: '56m ago', inv: 'inv-7c2e19a0…' }],
      latest: { edit: { path: 'GoalRunner.kt', ins: 12, del: 4, ago: '40s ago' }, event: { phase: 'implement', role: 'worker', summary: 'started subtask 4', ago: '7m ago' }, activity: { label: 'running GoalRunnerExecutionRootTest', ago: '3s ago' } },
      log: [
        { n: 1, state: 'done', title: 'Record worktree owner in goal state', start: '12:58', dur: '20m', agents: ['claude'], sha: '3e7a10c2', phases: [['12:58', 'preplan', 'done', '1m 52s'], ['13:00', 'plan', 'done', '3m 05s'], ['13:03', 'implement', 'done', '8m 20s'], ['13:11', 'simplify', 'done', '2m'], ['13:13', 'audit', 'done', '2m'], ['13:15', 'review', 'done', '2m'], ['13:17', 'validate', 'done', '1m'], ['13:18', 'commit_push', 'done', '22s']] },
        { n: 2, state: 'done', title: 'Skip cleanup while a phase holds the checkout', start: '13:18', dur: '15m', agents: ['claude'], sha: '8b04d1f9', phases: [['13:18', 'preplan', 'done', '1m'], ['13:19', 'plan', 'done', '2m'], ['13:21', 'implement', 'done', '6m'], ['13:27', 'simplify', 'done', '1m'], ['13:28', 'audit', 'done', '2m'], ['13:30', 'review', 'done', '2m'], ['13:32', 'validate', 'done', '1m'], ['13:33', 'commit_push', 'done', '18s']] },
        { n: 3, state: 'done', title: 'Migrate stored goals to owner field', start: '13:33', dur: '16m', agents: ['claude'], sha: '4f1c9a27', blocks: 0, phases: [['13:33', 'preplan', 'done', '1m'], ['13:34', 'plan', 'done', '2m'], ['13:36', 'implement', 'done', '4m'], ['13:40', 'simplify', 'done', '1m'], ['13:41', 'audit', 'done', '3m'], ['13:44', 'review', 'wait', 'pass 1 · changes requested · 2 findings'], ['13:45', 'review', 'done', 'fix 1 · 2 files +18 −6'], ['13:47', 'review', 'done', 'pass 2 · approved'], ['13:47', 'validate', 'done', '2m'], ['13:49', 'commit_push', 'done', '20s']] },
        { n: 4, state: 'active', title: 'Remove worktree in Finalize only', start: '13:49', dur: '9m', agents: ['claude'], running: true, phases: [['13:49', 'preplan', 'done', '1m'], ['13:50', 'plan', 'done', '1m'], ['13:51', 'implement', 'now', 'running · 7m']] },
      ],
    },
    'INF-7': {
      path: '~/dev/infra-terraform', fresh: true, effort: 'high',
      runOptions: { canLaunch: false, canCancel: true, canFinalize: false },
      metrics: { exec: 'attempt 3', subActive: '20m' },
      reason: 'validate blocked on subtask 2: terraform plan could not acquire the state lock on staging.tfstate (lock 6c0f2a91, held by run tf-apply-8812 since 12:51).',
      workflow: 'wftr-20261114-124011-q7td', invocation: 'inv-41ad0e93c27b5f16a8d2e7c4-inf', liveness: 'idle · blocked', updated: '13:42:10',
      attempts: { phaseRuns: { implement: 2, simplify: 2, audit: 2, review: 2, validate: 4, commit_push: 1 }, fixIterations: { validate: 2 }, relaunches: {}, findings: 0, kills: 0 },
      executions: [{ life: 'blocked', start: '12:40', startAgo: '1h ago', end: '13:18', endAgo: '24m ago', result: 'phase_blocked', inv: 'inv-41ad0e93…' }],
      latest: { event: { phase: 'validate', role: 'gate', summary: 'blocked · state lock held', ago: '24m ago' }, activity: { label: 'exited after terraform plan', ago: '24m ago' } },
      incident: {
        heading: 'Needs your action', recovery: 'available', certainty: 'high',
        evidence: [
          { ref: 'ev-tfplan-3-2f81c0', summary: 'terraform plan exited 1: Error acquiring the state lock. Lock Info: ID 6c0f2a91, Path staging.tfstate, Who ci@runner-12, Created 12:51.', kind: 'command output' },
          { ref: 'ev-lock-6c0f2a91', summary: 'DynamoDB lock table row for staging.tfstate is held by run tf-apply-8812.', kind: 'external state' },
        ],
        report: 'not submitted',
      },
      log: [
        { n: 1, state: 'done', title: 'Issue new certificates in ACM', start: '12:40', dur: '18m', agents: ['codex'], sha: 'c09d2e71', phases: [['12:40', 'preplan', 'done', '2m'], ['12:42', 'plan', 'done', '2m'], ['12:44', 'implement', 'done', '8m'], ['12:52', 'simplify', 'done', '1m'], ['12:53', 'audit', 'done', '2m'], ['12:55', 'review', 'done', '2m'], ['12:57', 'validate', 'done', '1m'], ['12:58', 'commit_push', 'done', '20s']] },
        { n: 2, state: 'blocked', title: 'Swap listener certificates', start: '12:58', dur: '20m', agents: ['codex'], blocks: 1, phases: [['12:58', 'preplan', 'done', '1m'], ['12:59', 'plan', 'done', '2m'], ['13:01', 'implement', 'done', '7m'], ['13:08', 'simplify', 'done', '1m'], ['13:09', 'audit', 'done', '1m'], ['13:10', 'review', 'done', '2m'], ['13:10', 'validate', 'fail', 'attempt 1 · plan failed'], ['13:13', 'validate', 'fail', 'attempt 2 · same error'], ['13:16', 'validate', 'fail', 'attempt 3 · same error'], ['13:18', 'validate', 'wait', 'blocked · state lock held']] },
      ],
    },
    'PAY-88': {
      path: '~/dev/payments-svc', fresh: true, effort: 'high',
      runOptions: { canLaunch: true, launchLabel: 'Resume', canCancel: true, canFinalize: false },
      metrics: { exec: 'done', subActive: null },
      workflow: 'wftr-20261114-112003-c2pa', invocation: null, liveness: 'idle · paused', updated: '12:12:40',
      attempts: { phaseRuns: { implement: 3, simplify: 3, audit: 3, review: 3, validate: 3, commit_push: 3 }, fixIterations: {}, relaunches: {}, findings: 0, kills: 0 },
      executions: [{ life: 'paused', start: '11:20', startAgo: '2h ago', end: '12:12', endAgo: '1h 46m ago', result: 'operator_pause', inv: 'inv-0b7e5d21…' }],
      latest: { event: { phase: 'commit_push', role: 'worker', summary: 'subtask 3 committed', ago: '1h 47m ago' } },
    },
    'NEWS-12': {
      path: '~/dev/newsreader', fresh: true, effort: 'medium',
      runOptions: { canLaunch: true, launchLabel: 'Run again', canCancel: false, canFinalize: false },
      metrics: { exec: 'attempt 3' },
      workflow: 'wftr-20261114-091204-n8vk', invocation: 'inv-c3f19a7e02d84b65e1f0a9d3-n12', liveness: 'exited', updated: '10:52:18',
      attempts: { phaseRuns: { implement: 3, simplify: 3, audit: 3, review: 3, validate: 5, commit_push: 2 }, fixIterations: { validate: 3 }, relaunches: { validate: 1 }, findings: 1, kills: 0 },
      executions: [{ life: 'interrupted', start: '09:12', startAgo: '4h 46m ago', end: '09:58', endAgo: '4h ago', result: 'runner_interrupted', inv: 'inv-8e21b04c…' }, { life: 'failed', start: '10:01', startAgo: '3h 57m ago', end: '10:52', endAgo: '3h 6m ago', result: 'fix_loop_exhausted', inv: 'inv-c3f19a7e…' }],
      latest: { edit: { path: 'ArticleCache.kt', ins: 9, del: 3, ago: '3h 8m ago' }, event: { phase: 'validate', role: 'gate', summary: 'failed · fix loop exhausted', ago: '3h 6m ago' } },
    },
    'ATL-402': {
      path: '~/dev/atlas-web', fresh: true, effort: 'high',
      runOptions: { canLaunch: false, canCancel: true, canFinalize: false },
      metrics: { stage: 'Goal finish', exec: 'round 1' },
      workflow: 'wftr-20261114-115302-a4tl', invocation: 'inv-9a03c7e1b5d24f68a0e3c9b1-402', liveness: 'agent output 6s ago', updated: '13:58:02',
      attempts: { phaseRuns: { implement: 4, simplify: 4, audit: 4, review: 5, validate: 4, commit_push: 4, goal_assessment: 1, qa: 1 }, fixIterations: { review: 1, goal_assessment: 1 }, relaunches: {}, findings: 3, kills: 0 },
      executions: [{ life: 'active', start: '11:53', startAgo: '2h 5m ago', inv: 'inv-9a03c7e1…' }],
      latest: { edit: { path: 'useSearchParams.ts', ins: 4, del: 1, ago: '22m ago' }, event: { phase: 'qa', role: 'worker', summary: 'stage 4 · next and previous', ago: '3m ago' }, activity: { label: 'driving Playwright on a 390px viewport', ago: '6s ago' } },
    },
    'SKILL-498': {
      path: '~/IdeaProjects/skill-bill', fresh: true, effort: 'high',
      runOptions: { canLaunch: false, canCancel: false, canFinalize: true },
      metrics: {},
      workflow: 'wftr-20261114-080211-f7dd', invocation: null, liveness: 'finished', updated: '11:12:30',
      attempts: { phaseRuns: { implement: 4, review: 4, validate: 4, commit_push: 4, pr: 1, monitor: 1 }, fixIterations: {}, relaunches: {}, findings: 0, kills: 0 },
      executions: [{ life: 'completed', start: '08:02', startAgo: '5h 56m ago', end: '11:12', endAgo: '2h 46m ago', result: 'pr_opened', inv: 'inv-62e0a9f4…' }],
      latest: { event: { phase: 'monitor', role: 'worker', summary: 'CI passed on #611', ago: '2h 46m ago' } },
    },
  };
  /* A goal waiting for an operator decision after a no-change subtask, with no incident. */
  D.RUNS.splice(4, 0, {
    id: 'SKILL-505', kind: 'goal', state: 'paused', title: 'Drop the legacy status endpoint', needs: 'Decision',
    repo: 'skill-bill', branch: 'chore/SKILL-505-legacy-status', agent: 'codex', model: 'gpt-5.2', elapsed: '41m', started: '10:31', pausedAt: '11:12',
    step: 'Waiting for a decision on subtask 2 of 3', phase: 'implement', awaitsDecision: true,
    subtasks: [['done', 'Remove the endpoint and its route'], ['no_change', 'Delete the client in the IntelliJ plugin'], ['pending', 'Update the API docs']], current: 1,
    phases: { preplan: 'done', plan: 'done', implement: 'done', simplify: 'pending', audit: 'pending', review: 'pending', validate: 'pending', commit_push: 'pending' },
    path: '~/IdeaProjects/skill-bill', fresh: true, effort: 'high',
    runOptions: { canLaunch: true, launchLabel: 'Resume', canCancel: true, canFinalize: false },
    metrics: { exec: 'attempt 1' },
    workflow: 'wftr-20261114-103107-l3gc', invocation: null, liveness: 'idle · waiting for decision', updated: '11:12:05',
    attempts: { phaseRuns: { implement: 2, simplify: 1, audit: 1, review: 1, validate: 1, commit_push: 1 }, fixIterations: {}, relaunches: {}, findings: 0, kills: 0 },
    executions: [{ life: 'paused', start: '10:31', startAgo: '3h 27m ago', end: '11:12', endAgo: '2h 46m ago', result: 'awaiting_operator_decision', inv: 'inv-5d0c8e2a…' }],
    latest: { event: { phase: 'implement', role: 'worker', summary: 'finished with no code changes', ago: '2h 46m ago' } },
  });
  D.RUNS.forEach((r) => {
    const m = MORE[r.id];
    if (!m) return;
    const inc = m.incident && r.incident ? Object.assign({}, r.incident, m.incident) : m.incident || r.incident;
    Object.assign(r, m, inc ? { incident: inc } : {});
  });
})();
