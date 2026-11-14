/* State examples use the same detail renderers as the app prototype. They stay out of the everyday board sample. */
(function () {
  'use strict';
  const D = window.DATA, X = window.Screens;
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const states = ['active', 'paused', 'blocked', 'failed', 'terminal', 'canceled', 'idle'];
  const words = { active: 'Active', paused: 'Paused', blocked: 'Blocked', failed: 'Failed', terminal: 'Completed', canceled: 'Canceled', idle: 'Idle / pending' };
  const examples = D.stateExamples = [];
  const types = [
    { id: 'goal', label: 'Goal', kind: 'goal', seed: 'SKILL-417', complete: 'SKILL-416' },
    { id: 'plan', label: 'Plan', kind: 'phase', seed: 'ph-sb-plan', complete: 'ph-sb-plan-done', blocked: 'ph-rk-plan' },
    { id: 'review', label: 'Review', kind: 'phase', seed: 'ph-rk-review' },
    { id: 'validation', label: 'Validation', kind: 'phase', seed: 'ph-sb-validation', complete: 'ph-sb-validation-done', paused: 'ph-sb-val-limit', canceled: 'ph-sb-val-411' },
    { id: 'qa', label: 'QA', kind: 'phase', seed: 'ph-pay-qa', complete: 'ph-atl-qa' },
    { id: 'pr', label: 'PR', kind: 'phase', seed: 'ph-atl-pr' },
    { id: 'monitor', label: 'Monitor', kind: 'phase', seed: 'ph-sb-monitor', complete: 'ph-sb-mon-green', blocked: 'ph-sb-mon-cap' },
    { id: 'verify', label: 'Verify', kind: 'operation', seed: 'op-verify', complete: 'op-verify-fixes', blocked: 'op-verify-confirm', canceled: 'op-verify-stopped' },
    { id: 'pr-review-fix', label: 'PR review fix', kind: 'operation', seed: 'op-prfix', confirm: true },
    { id: 'unit-test-value-check', label: 'Unit test value check', kind: 'operation' },
    { id: 'feature-guard', label: 'Feature guard', kind: 'operation', confirm: true },
    { id: 'feature-guard-cleanup', label: 'Feature guard cleanup', kind: 'operation', confirm: true },
    { id: 'release', label: 'Release', kind: 'operation', confirm: true },
    { id: 'update-check', label: 'Update check', kind: 'operation' },
  ];
  const reason = {
    paused: 'The runner was interrupted. Its recorded results are kept. Start another invocation when ready.',
    blocked: 'Repository credentials are unavailable. Restore access before continuing.',
    failed: 'The agent exited before it produced a result. Recorded activity is kept.',
    canceled: 'Canceled by you. No work is running.',
    idle: 'No live execution is attached to this run.',
  };
  function make(type, state) {
    const source = type[state === 'terminal' ? 'complete' : state] || type.seed;
    const seed = source && X.lookup(source);
    const item = seed ? clone(seed) : {
      repo: 'skill-bill', branch: 'design/state-examples', step: type.id, agent: 'claude', model: 'claude-sonnet-5-5',
      title: `${type.label} on design/state-examples`, goalMs: 246000, started: '09:00', updated: '09:04', startedAgo: 240,
      steps: [],
    };
    const unfinalizedTerminal = state === 'terminal' && type.kind !== 'goal' && type.id !== 'verify';
    Object.assign(item, {
      id: `state-${type.id}-${state}`, kind: type.kind, lifecycle: state,
      section: state === 'canceled' || (state === 'terminal' && !unfinalizedTerminal) ? 'history' : unfinalizedTerminal ? 'needs' : ['paused', 'blocked', 'failed'].includes(state) || state === 'idle' && type.kind === 'goal' ? 'needs' : type.kind === 'goal' ? 'goals' : type.kind === 'phase' ? 'phases' : 'operations',
      live: state === 'active', fresh: true, stalled: false, pauseRequested: false, refreshFailed: false,
      planning: false, goalLaunched: true, summary: `${type.label} · ${words[state]}`, reason: reason[state] || null,
      finishedAt: ['terminal', 'canceled'].includes(state) ? '09:04' : null,
      finishedAgo: ['terminal', 'canceled'].includes(state) ? 0 : null,
      runOptions: {
        canLaunch: unfinalizedTerminal || ['paused', 'blocked', 'failed', 'idle', 'canceled'].includes(state),
        canFinalize: unfinalizedTerminal,
        canCancel: ['active', 'paused', 'blocked', 'failed'].includes(state),
        launchLabel: type.id === 'goal' || type.id === 'plan' ? 'Resume' : 'Run again',
        agentId: 'claude',
      },
    });
    if (type.kind === 'phase') item.phase = type.id;
    if (type.kind === 'operation') item.op = type.id;
    if (type.kind === 'goal') {
      if (state !== 'terminal') { delete item.goalQa; delete item.assessment; item.finishDone = false; }
      if (['blocked', 'failed'].includes(state)) {
        const current = item.subtasks.find((s) => s.id === item.current);
        if (current) { current.status = 'blocked'; current.blocked = item.reason; }
      }
    }
    if (type.id === 'plan' && !['active', 'terminal', 'blocked'].includes(state)) {
      item.planRun.blocked = null;
      item.planRun.stuck = false;
      item.planRun.states = item.planRun.states && item.planRun.states.replaceAll('w', 'q');
    }
    if (type.id === 'validation' && !type[state] && state !== 'terminal') {
      item.valRun = Object.assign(clone(D.VAL.waiting || D.VAL.running), { liveChecks: false, checks: [], outcome: null, result: null, branchRuns: [], runSeq: 1, activity: state === 'active' ? 'Reading the project checks' : item.reason });
    }
    /* A monitor run in a state the mock has no snapshot for keeps the seed's cycles; its card speaks for the lifecycle. */
    if (type.id === 'monitor' && !type[state] && !['active', 'terminal'].includes(state)) item.monRun = Object.assign(clone(item.monRun), { hero: null });
    if (state !== 'active') item.steps = (item.steps || []).map(([label, status]) => [label, state === 'terminal' ? 'done' : status === 'now' ? state === 'failed' ? 'fail' : '' : status]);
    if (type.id === 'qa' && state !== 'terminal') { delete item.qa; item.steps = [['qa', state === 'active' ? 'now' : state === 'failed' ? 'fail' : '']]; }
    if (type.id === 'review' && state !== 'terminal') delete item.phaseResults;
    if (type.id === 'verify') {
      const run = item.verifyRun;
      if (state === 'failed') { run.status = 'failed'; run.finishedAt = '2026-11-07T09:04:00Z'; run.staleReason = null; }
      if (state === 'idle') { item.section = 'unlisted'; item.reason = 'The workflow still says running, but its last update is too old for a live board section.'; }
    }
    examples.push(item);
    return item.id;
  }
  types.forEach((type) => {
    type.cells = {};
    states.forEach((state) => {
      if (type.id === 'update-check' && state !== 'terminal') {
        type.cells[state] = { unavailable: 'UpdateCheckOperation returns a completed check result. Unavailable release information is part of that result, not a paused, blocked or failed workflow.' };
      } else if (type.kind === 'operation' && type.id !== 'verify' && ['paused', 'canceled'].includes(state)) {
        type.cells[state] = { unavailable: 'OperationOutcome has Completed, Blocked, Failed and AwaitingConfirmation. These operations do not write paused or canceled workflow states.' };
      } else if (state === 'idle' && !['goal', 'verify'].includes(type.id)) {
        type.cells[state] = { unavailable: 'Idle is derived only for a goal without a live execution or an abandoned active verify. Standalone records use paused / runner_interrupted.' };
      } else if (type.id === 'verify' && state === 'paused') {
        type.cells[state] = { unavailable: 'Verify stops or waits for criteria confirmation. Its workflow does not write a paused state.' };
      } else {
        type.cells[state] = { id: make(type, state) };
      }
    });
  });
  const special = [];
  function extra(typeId, state, label, patch) {
    const type = types.find((t) => t.id === typeId);
    const item = clone(X.lookup(type.cells[state].id));
    Object.assign(item, patch || {}, { id: `state-${typeId}-${label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}` });
    if (label === 'Stalled' && typeId === 'plan') item.planRun.stuck = true;
    examples.push(item); special.push({ type: type.label, label, id: item.id });
    return item;
  }
  extra('goal', 'active', 'Planning', { planning: true, planned: 2, total: 5, step: 'plan', subtasks: [], pipeline: null });
  extra('goal', 'active', 'Pause requested', { pauseRequested: true });
  types.filter((t) => t.id !== 'update-check').forEach((type) => extra(type.id, 'active', 'Stalled', { stalled: true, live: false }));
  types.filter((t) => t.confirm).forEach((type) => extra(type.id, 'blocked', 'Awaiting confirmation', { summary: 'A proposal is ready. Confirm it in chat before changes run.', reason: 'Awaiting confirmation. Review the proposed changes in chat.', runOptions: { canLaunch: false, canCancel: false }, awaitingConfirmation: true }));
  extra('pr', 'terminal', 'PR opened', { summary: 'Pull request opened. CI is pending.', steps: [['pr', 'done']] });
  extra('review', 'terminal', 'Review clean', { phaseResults: { passes: [{ n: 1, verdict: 'approved', open: 0, findings: [] }] }, summary: 'Review approved. No findings.' });
  extra('review', 'terminal', 'Review findings', { phaseResults: { passes: [{ n: 1, verdict: 'changes requested', open: 1, findings: [['Major', 'A canceled request still writes its result into the next selection.', 'DetailReads.kt:88']] }] }, summary: 'Review finished. 1 finding remains open.' });
  const approved = extra('verify', 'terminal', 'Approved');
  approved.verifyRun.verdict = { verdict: 'APPROVE', summary: 'The change meets its acceptance criteria.', findings: [] };
  approved.verifyRun.qa = null; approved.verifyRun.qaSelection = 'off';
  approved.verifyRun.review = { verdict: 'approved', findings: [] };
  const queued = extra('verify', 'active', 'Pending', { live: false });
  queued.verifyRun.status = 'pending'; queued.verifyRun.current = 'collect_inputs'; queued.verifyRun.criteria = [];
  queued.verifyRun.steps.forEach((s) => { s.status = 'pending'; s.attempts = 0; });
  [
    ['Needs review', 'open'], ['Passed', 'clean'], ['Not tested', 'incomplete'],
    ['Cleanup needed', 'left'], ['Skipped', 'skipped'], ['Did not finish', 'unfinished'], ['Older report', 'legacy'],
  ].forEach(([label, key]) => {
    if (!D.QA[key]) throw new Error(`Missing QA state example: ${key}`);
    extra('goal', 'terminal', `Goal QA, ${label}`, { goalQa: clone(D.QA[key]), catalogTab: 'qa' });
  });
  /* Goal QA while it runs: one example per scenario. Subtasks are done and the goal sits in QA. */
  Object.keys(D.QA_LIVE).forEach((key) => {
    const q = D.QA_LIVE[key];
    const live = extra('goal', q.done ? 'terminal' : 'active', `Goal QA live, ${q.label}`, { qaLive: clone(q), catalogTab: 'qa', step: q.round ? 'qa_fix' : 'qa', summary: q.done ? 'Goal QA finished with open findings.' : `goal QA: ${D.QA_STAGES.find((s) => s.id === q.at).name.toLowerCase()}` });
    live.subtasks.forEach((s) => { if (s.status !== 'completed_no_change') { s.status = 'complete'; s.blocked = null; } });
    live.done = live.total; live.current = null; live.finishDone = !!q.done;
    live.exec = `round ${q.round || 1}`; delete live.subMs;
    live.pipeline = { done: D.PHASE_ORDER.slice(), now: null };
    if (q.done) live.goalQa = clone(D.QA.open); else delete live.goalQa;
    delete live.assessment;
  });
  const absent = extra('goal', 'terminal', 'Missing goal results', { catalogTab: 'assessment' });
  delete absent.goalQa; delete absent.assessment;
  const assessing = extra('goal', 'active', 'Assessment running', { assessment: clone(X.lookup('SKILL-416').assessment), catalogTab: 'assessment' });
  assessing.assessment.state = 'RUNNING';
  assessing.assessment.findings[0].state = 'OPEN';
  const findings = extra('goal', 'terminal', 'Assessment open findings', { catalogTab: 'assessment' });
  findings.assessment.findings[0].state = 'OPEN';
  /* Phase peek (design/phase-peek): a subtask stopped at validate, and Goal finish at five moments. `catalogPath` is the
   * part of the link after the item id: the subtask or finish, then the phase to open. */
  const P = D.PEEK;
  const stuck = extra('goal', 'blocked', 'Subtask blocked at validate', { catalogPath: '3/validate', step: 'validate', exec: 'attempt 3', current: 3, done: 2, reason: 'The IntelliJ plugin test needs network access.' });
  stuck.subtasks.forEach((x) => { x.blocked = null; });
  stuck.subtasks[1].status = 'complete';
  stuck.subtasks[2].status = 'blocked';
  stuck.subtasks[2].phases = { cards: P.sub3, counts: { validate: 'attempt 3' }, states: Object.assign(Object.fromEntries(D.PHASE_ORDER.map((id, i) => [id, i < D.PHASE_ORDER.indexOf('validate') ? 'done' : ''])), { validate: 'wait' }) };
  stuck.pipeline = { done: D.PHASE_ORDER.slice(0, D.PHASE_ORDER.indexOf('validate')), now: 'validate' };
  /* Subtask audit states beyond INF-7's passed audits: a failed audit with unmet criteria, and one still running. */
  function auditExample(label, state, auditResult) {
    const it = extra('goal', state === 'fail' ? 'blocked' : 'active', label, { catalogPath: '2/audit', step: 'audit', exec: `attempt ${auditResult.attempts.length + (state === 'now' ? 1 : 0)}`, current: 2, done: 1, live: state === 'now' });
    const sub = it.subtasks[1];
    sub.status = state === 'fail' ? 'blocked' : 'in_progress'; sub.blocked = state === 'fail' ? 'Audit found 2 criteria not covered after 3 attempts' : null; sub.reviews = []; delete sub.phases;
    sub.phases = { states: Object.assign(Object.fromEntries(D.PHASE_ORDER.map((id, i) => [id, i < D.PHASE_ORDER.indexOf('audit') ? 'done' : ''])), { audit: state === 'fail' ? 'fail' : 'now' }), counts: { audit: `attempt ${auditResult.attempts.length + (state === 'now' ? 1 : 0)}` } };
    sub.auditResult = auditResult;
    it.pipeline = { done: D.PHASE_ORDER.slice(0, D.PHASE_ORDER.indexOf('audit')), now: 'audit' };
  }
  const crit = [
    { ref: 'AC-001', text: 'IDE status reports the parent goal phase, not the finished child phase, within one poll after a child finishes.' },
    { ref: 'AC-002', text: 'The lease check runs once per poll, and a missing lease reads as idle instead of failing the request.' },
    { ref: 'AC-003', text: 'A finished child drops its phase label in the same poll that reports the parent phase.' },
    { ref: 'AC-004', text: 'Status reads stay under 50 ms at 200 tracked runs.' },
    { ref: 'AC-005', text: 'Both IDE plugins keep their existing status contract; no new field is required.' },
  ];
  auditExample('Subtask audit failed', 'fail', { outcome: 'failed', met: 3, total: 5, attemptTotal: 3,
    summary: 'Two criteria still have no covering change: the poll-level lease check and the read-time budget.',
    criteria: crit.map((c, i) => Object.assign({}, c, { met: ![1, 3].includes(i), fixedIn: i === 2 ? 2 : null })),
    attempts: [{ n: 1, passed: false, uncovered: 4, next: 'audit_plan_fix' }, { n: 2, passed: false, uncovered: 3, next: 'audit_implement_fix' }, { n: 3, passed: false, uncovered: 2 }] });
  auditExample('Subtask audit running', 'now', { outcome: 'running', attempts: [{ n: 1, passed: false, uncovered: 2, next: 'audit_implement_fix' }, { n: 2, passed: false, uncovered: 1, next: 'audit_implement_fix' }] });
  function finishExample(label, phase, states, cards, o) {
    const it = extra('goal', o.state || 'active', label, { catalogPath: `finish/${phase}`, title: 'Keep QA live preview working across display crashes', branch: 'feat/SKILL-512-qa-live-preview', step: phase, summary: o.summary, agent: 'codex', model: 'gpt-6.1-sol', effort: 'high' });
    it.subtasks.forEach((x) => { if (x.status !== 'completed_no_change') x.status = 'complete'; x.blocked = null; delete x.phases; });
    it.done = it.total; it.current = null; delete it.subMs; it.exec = null;
    it.pipeline = { done: D.PHASE_ORDER.slice(), now: null };
    const full = Object.fromEntries(D.GOAL_FINISH.map((id) => [id, '']));
    it.finish = { states: Object.assign(full, states), counts: o.counts || {}, cards };
    it.finishDone = D.GOAL_FINISH.every((id) => ['done', 'skipped'].includes(it.finish.states[id]));
    delete it.goalQa; delete it.qaLive; delete it.assessment;
    it.assessment = clone(P.assessment);
    if (o.goalQa) it.goalQa = clone(o.goalQa);
    if (o.qaLive) it.qaLive = clone(o.qaLive);
    return it;
  }
  const F = P.finish;
  finishExample('Goal finish, QA running', 'qa', { goal_assessment: 'done', qa: 'now' }, { goal_assessment: F.assess, qa: F.qaLive }, { counts: { qa: 'round 1' }, qaLive: P.qaLive, summary: 'goal QA: exercise running app' });
  finishExample('Goal finish, qa_fix running', 'qa_fix', { goal_assessment: 'done', qa: 'done', qa_fix: 'now' }, { goal_assessment: F.assess, qa: F.qaRound1, qa_fix: F.qaFixNow }, { counts: { qa: 'round 1' }, goalQa: P.qa.round1, summary: 'qa_fix: fixing QA-1' });
  finishExample('Goal finish, validate running', 'validate', { goal_assessment: 'done', qa: 'done', qa_fix: 'done', validate: 'now' }, { goal_assessment: F.assess, qa: F.qa, qa_fix: F.qaFix, validate: F.valNow }, { counts: { qa: 'round 2' }, goalQa: P.qa.fixed, summary: 'validate: compose screenshot tests' });
  finishExample('Goal finish, monitor cycle 2', 'monitor', { goal_assessment: 'done', qa: 'done', qa_fix: 'done', validate: 'done', pr: 'done', monitor: 'now', monitor_fix: 'done' }, { goal_assessment: F.assess, qa: F.qa, qa_fix: F.qaFix, validate: F.valDone, pr: F.pr, monitor: F.monNow, monitor_fix: F.monFix }, { counts: { qa: 'round 2', monitor: 'cycle 2' }, goalQa: P.qa.fixed, summary: 'monitor: waiting for CI on #482' });
  finishExample('Goal finish, done', 'monitor', { goal_assessment: 'done', qa: 'done', qa_fix: 'skipped', validate: 'done', pr: 'done', monitor: 'done', monitor_fix: 'skipped' }, { goal_assessment: F.assess, qa: F.qaClean, validate: F.valDone, pr: F.pr, monitor: F.monDone }, { state: 'terminal', goalQa: P.qa.clean, summary: 'Goal finished. CI passed.' });
  const known = [
    ['Goal', 'Completed with QA and assessment', 'SKILL-416'],
    ['Goal', 'Operator decision', 'NEWS-176'],
    ['Goal', 'Recovery prerequisites', 'DSN-31'],
    ['Plan', 'Reading the repository', 'ph-atl-plan'], ['Plan', 'Splitting without a live stream', 'ph-news-plan'],
    ['Plan', 'Writing subtask specs', 'ph-sb-plan'], ['Plan', 'Quiet agent', 'ph-pay-plan'], ['Plan', 'Blocked subtask', 'ph-rk-plan'], ['Plan', 'Plan ready', 'ph-sb-plan-done'],
    ['QA', 'Needs review', 'ph-sb-qa'], ['QA', 'Passed', 'ph-atl-qa'], ['QA', 'Not tested', 'ph-sb-qa-gaps'],
    ['QA', 'Cleanup needed', 'ph-sb-qa-left'], ['QA', 'Skipped', 'ph-sb-qa-docs'], ['QA', 'Did not finish', 'ph-pay-qa'], ['QA', 'Older report', 'ph-sb-qa-legacy'],
    ['Validation', 'Running, stored row', 'ph-sb-val-running'], ['Validation', 'Running, no checks yet', 'ph-sb-val-waiting'],
    ['Validation', 'Live checks unavailable', 'ph-rk-validation'], ['Validation', 'Live checks reported', 'ph-sb-validation'],
    ['Validation', 'Passed with checks', 'ph-sb-validation-done'], ['Validation', 'Repairs in report', 'ph-sb-val-repairs'],
    ['Validation', 'Coverage gaps', 'ph-sb-val-413'], ['Validation', 'Blocked, report only', 'ph-za-val-report'], ['Validation', 'Blocked with checks', 'ph-za-validation'],
    ['Validation', 'Provider limit', 'ph-sb-val-limit'], ['Validation', 'Canceled', 'ph-sb-val-411'],
    ['Monitor', 'CI running', 'ph-sb-mon-ci'], ['Monitor', 'Repair running', 'ph-sb-monitor'], ['Monitor', 'Publishing the repair', 'ph-sb-mon-publish'],
    ['Monitor', 'Merge conflict repair', 'ph-sb-mon-conflict'], ['Monitor', 'Waiting for checks to register', 'ph-sb-mon-wait'], ['Monitor', 'Repair edited the PR only', 'ph-sb-mon-pr-edit'],
    ['Monitor', 'CI green', 'ph-sb-mon-green'], ['Monitor', 'Repair cap reached', 'ph-sb-mon-cap'], ['Monitor', 'Repair unresolved', 'ph-sb-mon-unresolved'], ['Monitor', 'PR merged while watching', 'ph-sb-mon-merged'],
    ['Verify', 'Awaiting criteria confirmation', 'op-verify-confirm'], ['Verify', 'No numbered criteria', 'op-verify-nocrit'],
    ['Verify', 'Approved with fixes and QA', 'op-verify-fixes'], ['Verify', 'Request changes with selection', 'op-verify-085'],
    ['Verify', 'Stopped', 'op-verify-stopped'], ['Verify', 'Superseded', 'op-verify-superseded'],
  ].map(([type, label, id]) => ({ type, label, id }));
  D.STATE_CATALOG = { states, words, types, special: special.concat(known) };
  window.Library.states = function () {
    const S = window.SB, esc = S.esc, catalog = D.STATE_CATALOG;
    const link = (id, label, aria) => {
      const item = X.lookup(id);
      const tab = item.catalogPath || (item.catalogTab || id === 'SKILL-416' ? `finish/${item.catalogTab === 'assessment' ? 'goal_assessment' : 'qa'}` : null);
      return `<a data-state-link href="#detail/${encodeURIComponent(id)}${tab ? `/${tab}` : ''}" aria-label="${esc(aria || label)}">${esc(label)}</a>`;
    };
    const location = (id) => {
      const item = X.lookup(id);
      return item ? item.section === 'unlisted' ? 'Detail only' : (X.SECTIONS.find(([key]) => key === item.section) || ['', 'History'])[1] : 'Unavailable';
    };
    const matrix = catalog.types.map((type) => `<tr><th scope="row">${esc(type.label)}<small>${esc(type.kind)}</small></th>${catalog.states.map((state) => {
      const cell = type.cells[state];
      return cell.id ? `<td>${X.statusIcon(X.lookup(cell.id))} ${link(cell.id, 'View', `${type.label}, ${catalog.words[state]}`)}<small>${esc(location(cell.id))}</small></td>` : `<td><span class="t-faint" tabindex="0" title="${esc(cell.unavailable)}" aria-label="Not applicable. ${esc(cell.unavailable)}">N/A</span></td>`;
    }).join('')}</tr>`).join('');
    const variants = catalog.special.map((row) => `<tr><th scope="row">${esc(row.type)}</th><td>${X.statusIcon(X.lookup(row.id))} ${link(row.id, row.label)}</td><td>${esc(location(row.id))}</td></tr>`).join('');
    const chats = [
      /* Activity is part of each detail and reads that item's records. */
      ['Activity', 'Recorded run, 33 subtasks', 'detail/SKILL-512'], ['Activity', 'Running', 'detail/SKILL-417'], ['Activity', 'Blocked, with audit and review loops', 'detail/INF-7'], ['Activity', 'Standalone phase', 'detail/ph-rk-review'],
      ['Changes', 'Subtask scopes and ranges', 'changes'],
      ...['Writing a review', 'Review added', 'After phase review', 'After review fixes'].map((label, i) => ['Diff comments', label, `comments/${i + 1}`]),
      ['New task', 'Home', 'new-task'], ['New task', 'Two idle chats', 'new-task/state/two'],
      ['New task', 'Replying in the background', 'new-task/state/busy'], ['New task', 'Mixed repositories', 'new-task/state/mixed'],
      ['New task', 'Nine open chats', 'new-task/state/many'], ['New task', 'Twenty chats with overflow', 'new-task/state/lots'],
      ['New task', 'Confirm closing a replying chat', 'new-task/state/closing'], ['New task', 'Few recent chats', 'new-task/state/few'],
      ['New task', 'Queued messages', 'new-task/queue'],
      ['New task', 'Read-only chat on feature branch', 'new-task/state/branch-read'],
      ['New task', 'Branch choice before edits', 'new-task/state/branch-edit'],
      ['Chat changes', 'Chat with changes', 'new-task/state/changes'],
      ...['none', 'few', 'many', 'resumed'].map((id) => ['Chat changes', { none: 'No changes yet', few: 'Three files', many: 'Seventeen files', resumed: 'Resumed chat' }[id], `new-task/changes/${id}`]),
      ...['pending', 'firstRun', 'noAgent', 'noRepo', 'detectionFailed'].map((id) => ['New task', { pending: 'Board read pending', firstRun: 'First run, ready', noAgent: 'No agent detected', noRepo: 'Choose a repository', detectionFailed: 'Agent detection failed' }[id], `new-task/state/${id}`]),
      ...['default', 'longBranch', 'firstRun', 'manyRepos', 'noMatch', 'loading', 'branchError', 'badFolder'].map((id) => ['Repository picker', { default: 'Default', longBranch: 'Long branch name', firstRun: 'No repository', manyRepos: 'Many repositories', noMatch: 'No matching branch', loading: 'Reading branches', branchError: 'Branch read failed', badFolder: 'Folder is not a repository' }[id], `new-task/picker/${id}`]),
      ...['default', 'cli', 'noMatch', 'empty', 'many'].map((id) => ['Resume chat', { default: 'Saved chats', cli: 'Agent CLI session', noMatch: 'No matching chat', empty: 'No saved chats', many: 'More than 30 chats' }[id], `new-task/resume/${id}`]),
      ...Object.keys(D.QA_DEVICES.lists).map((id) => ['QA device', { mixed: 'Mixed devices', one: 'One ready device', unauth: 'Unauthorized', offline: 'Offline', avdOnly: 'Stopped emulators', none: 'No devices', noAdb: 'ADB unavailable' }[id], `qa-device/${id}`]),
      ...D.specs.map((spec) => ['Plans', `${spec.key}, ${spec.run.replaceAll('_', ' ')}`, `specs/${X.specPath(spec)}`]),
      ['Plans', 'No plans', 'specs/project/l-9c1b'], ['Plans', 'No local checkout', 'specs/project/l-51d0'], ['Plans', 'Read unavailable', 'specs/project/l-a7e2'],
    ].map(([type, label, route]) => `<tr><th scope="row">${esc(type)}</th><td><a data-state-link href="#${esc(route)}">${esc(label)}</a></td></tr>`).join('');
    return `<div class="doc-main doc-states" data-scroll="states"><div class="doc-inner">
      <div class="doc-intro"><h1>States</h1><p>Each link opens a working example in the prototype. The app's behavior and shared styling are the baseline. Added mock functionality stays in the design for later implementation.</p><p>These are sample records. They do not start runs or add work to the sample board.</p></div>
      <h2 class="doc-h2">Lifecycle by work type</h2><p class="t-muted">${catalog.types.length} types. Planning and stalled are display states over Active. The app displays Idle as Pending. The location under each link shows the board section or History.</p>
      <div class="doc-state-table"><table><caption>Work types and lifecycle states</caption><thead><tr><th scope="col">Work type</th>${catalog.states.map((state) => `<th scope="col">${esc(catalog.words[state])}</th>`).join('')}</tr></thead><tbody>${matrix}</tbody></table></div>
      <p class="t-muted">N/A means the runtime does not produce that combination. Idle applies to goals without a live execution and to Verify runs whose last update is too old. An Idle Verify has no live board section, even while its workflow still says Running. Verify uses Stopped or Awaiting you instead of Paused. Other operations have no paused or canceled outcome. Update check returns a completed result even when release information is unavailable.</p>
      <h2 class="doc-h2">Display states and result variants</h2><p class="t-muted">A completed run can still have findings, missing coverage or failing CI. These examples keep lifecycle and result separate. Verify awaiting confirmation belongs in Needs you.</p>
      <div class="doc-state-table"><table><caption>Work display states and outcomes</caption><thead><tr><th scope="col">Work type</th><th scope="col">Example</th><th scope="col">Location</th></tr></thead><tbody>${variants}</tbody></table></div>
      <h2 class="doc-h2">Chats, devices and plans</h2><div class="doc-state-table"><table><caption>Interactive screen states</caption><thead><tr><th scope="col">View</th><th scope="col">Example</th></tr></thead><tbody>${chats}</tbody></table></div>
      <h2 class="doc-h2">Source inventory</h2><p class="t-muted">WorkModels.kt and StatusKindMapping.kt define the app's lifecycle and display vocabulary. IdeStatusPolicy.kt defines lifecycle conversion and board membership. PhaseCommand.kt discovers standalone phases from SkeletonDefinition. The seven Operation implementations declare their IDs. VerifyRunState.kt defines Verify's detail states, and DetailsPane.kt owns the Goal finish switch, which this design replaces with phase tabs (design/phase-peek).</p>
      <p class="t-muted">Source paths and checks for this pass are recorded in STATE-COVERAGE.md.</p>
    </div></div>`;
  };
})();
