(function () {
  'use strict';
  const D = window.DATA;
  const plain = (value) => String(value || '').replace(/<[^>]*>/g, '').replace(/&amp;/g, '&');
  const duration = (value) => [...String(value || '').matchAll(/(\d+)\s*(h|m|s)/g)].reduce((sum, [, n, unit]) => sum + Number(n) * ({ h: 3600000, m: 60000, s: 1000 })[unit], 0);
  const item = (id) => window.Screens.lookup(id);
  const lifecycleState = (it) => ({ active: it.live ? 'running' : 'waiting', terminal: 'complete', idle: 'pending' })[it.lifecycle] || it.lifecycle;
  function subtaskState(it, sub) {
    if (['in_progress', 'planning'].includes(sub.status)) return lifecycleState(it) === 'complete' ? 'stopped' : lifecycleState(it);
    return ({ complete: 'complete', completed_no_change: 'complete' })[sub.status] || sub.status;
  }
  function activityFor(id) {
    const it = item(id);
    if (!it) throw new Error(`Activity item is missing: ${id}`);
    const groupFor = (key, name, status, cards, states, sub) => {
      const phases = [], facts = [];
      Object.entries(cards).forEach(([phase, card]) => {
        const recorded = states[phase];
        if (!recorded) return;
        const current = ['now', 'wait', 'fail'].includes(recorded);
        const phaseState = current ? recorded === 'fail' ? 'failed' : recorded === 'wait' ? (['paused','canceled','failed','stopped'].includes(status) ? status : 'blocked') : status : recorded === 'skipped' ? (status === 'canceled' ? 'canceled' : 'skipped') : 'complete';
        const running = phaseState === 'running';
        const summary = (card.made || []).filter((part) => part.kind === 'text').map((part) => plain(part.html)).join(' ');
        const match = /^(?:since )?(\d\d):(\d\d)$/.exec(card.when || '');
        const [model, effort] = (card.model || '').split(' · ');
        if (!match) { facts.push({ name: phase, x: [card.round, card.word || (current ? phaseState : '')].filter(Boolean).join(' · '), a: card.agent, m: model, f: effort, t: summary, running, status: phaseState }); return; }
        const date = new Date(D.T0); date.setUTCHours(+match[1], +match[2], 0, 0);
        const elapsed = duration(card.dur);
        phases.push({ p: phase, n: Number((card.round || '').match(/\d+/)?.[0] || 1), s: date.toISOString(), e: running ? null : new Date(+date + elapsed).toISOString(), elapsed, clockLabel: card.when, state: phaseState, durationKnown: !!elapsed, a: card.agent, m: model, f: effort, t: summary });
      });
      if (sub && !phases.length && !facts.length) {
        (sub.reviews || []).forEach((review) => facts.push({ name: 'review', x: `pass ${review.n} · ${review.verdict}`, t: `${review.open || 0} open findings` }));
        if (sub.validation) facts.push({ name: 'validate', x: sub.validation.passed ? 'passed' : 'failed', status: sub.validation.passed ? 'complete' : 'failed', t: `${sub.validation.checks} checks` });
        if (sub.audit) facts.push({ name: 'audit', x: sub.audit.verdict, t: sub.audit.summary });
        if (sub.commit) facts.push({ name: 'commit_push', x: sub.commit.slice(0, 8) });
      }
      return { contextual: true, clockLabel: Object.values(cards)[0]?.when, id: key, n: sub?.id ?? null, name, status, s: phases[0]?.s, e: phases.at(-1)?.e, ph: phases, after: facts, blocks: sub?.blocked ? [{ why: sub.blocked, phase: it.step }] : [] };
    };
    const groups = (it.subtasks || []).map((sub) => groupFor(`sub-${sub.id}`, sub.title, subtaskState(it, sub), sub.phases?.cards || {}, window.Screens.subPhaseStates(it, sub), sub));
    if (it.finish?.cards) groups.push(groupFor('finish', 'Goal finish', lifecycleState(it), it.finish.cards, window.Screens.finishStates(it)));
    const latest = it.latest || {};
    const recent = [];
    (it.executions || []).forEach((execution, index) => {
      recent.push({ name: `Run ${index + 1} started`, t: execution.start });
      if (execution.end) recent.push({ name: `Run ${index + 1} ${execution.life}`, status: execution.life, t: [execution.end, execution.result].filter(Boolean).join(' · ') });
    });
    if (!it.executions?.length && it.startedAgo != null) {
      recent.push({ name: 'Run started', t: it.started });
      if (it.finishedAgo != null) recent.push({ name: it.lifecycle === 'terminal' ? 'Completed' : it.lifecycle, status: lifecycleState(it), t: it.finishedAt });
    }
    if (latest.edit) recent.push({ name: 'File edited', t: `${latest.edit.path} +${latest.edit.ins} −${latest.edit.del}` });
    if (latest.event) recent.push({ name: latest.event.phase, t: latest.event.summary });
    if (latest.activity) recent.push({ name: 'Agent activity', t: latest.activity.label, a: it.agent, m: it.model, f: it.effort, running: it.live && it.lifecycle === 'active' });
    if (it.pauseRequested) recent.push({ name: 'Pause requested', t: 'The goal pauses at its next checkpoint.', status: 'paused' });
    if (it.reason && !groups.some((group) => group.blocks.length)) recent.push({ name: 'Run reason', t: it.reason, status: lifecycleState(it) });
    (it.flowEvents || []).forEach((event) => recent.push(event));
    const blocks = it.lifecycle === 'blocked' && !groups.some((group) => group.blocks.length) ? [{ why: it.reason || it.summary, phase: it.step }] : [];
    if (recent.length || !groups.length || blocks.length) groups.push({ id: 'run', n: null, name: it.kind === 'goal' ? 'Goal activity' : 'Run activity', status: lifecycleState(it), ph: [], before: recent.length ? recent : [{ name: it.step, t: it.summary }], blocks, open: true });
    return { contextual: true, groups, live: it.live && it.lifecycle === 'active', model: it.model, note: '', unitLabel: it.kind === 'goal' ? 'Subtasks' : 'Runs', unitValue: it.kind === 'goal' ? `${it.done || 0} of ${it.total || 0}` : String(it.executions?.length || 1), now: Math.max(D.T0, ...groups.flatMap(group => group.ph.map(phase => Date.parse(phase.s) + phase.elapsed))) };
  }
  const idePatch = `diff --git a/runtime/src/main/kotlin/dev/skillbill/ide/IdeStatusService.kt b/runtime/src/main/kotlin/dev/skillbill/ide/IdeStatusService.kt
--- a/runtime/src/main/kotlin/dev/skillbill/ide/IdeStatusService.kt
+++ b/runtime/src/main/kotlin/dev/skillbill/ide/IdeStatusService.kt
@@ -41,5 +41,6 @@
 fun currentPhase(parent: GoalStatus, child: ChildStatus?): Phase {
-  return child?.phase ?: parent.phase
+  val livePhase = parent.livePhase
+  return livePhase ?: child?.phase ?: parent.phase
 }
`;
  const chatContexts = new Map();
  function changesFor(id) {
    if (chatContexts.has(id)) return chatContexts.get(id);
    /* The Diff comments catalog: the SKILL-474 sample, files with parsed hunks and no subtask scopes. */
    if (id === 'review-sample') {
      const R = D.REVIEW_SAMPLE, files = R.files.map((f) => ({ path: f.path, status: f.st, add: f.ins, del: f.del, hunks: f.hunks }));
      return { goal: R.goal, branch: R.branch, subtasks: [], all: files, other: [], working: [] };
    }
    if (id === 'SKILL-512') {
      const it = item(id);
      return { ...D.RECORDED_CHANGES, subtasks: D.RECORDED_CHANGES.subtasks.map((sub, index) => ({ ...sub, state: subtaskState(it, it.subtasks[index]) === 'complete' ? 'done' : subtaskState(it, it.subtasks[index]) })) };
    }
    const it = item(id);
    if (!it) throw new Error(`Changes item is missing: ${id}`);
    const edit = it.latest?.edit;
    const files = edit ? [{ path: edit.path, status: 'M', add: edit.ins, del: edit.del, patch: it.id === 'SKILL-417' ? idePatch : '' }] : [];
    const subtasks = (it.subtasks || []).map((sub) => ({ n: sub.id, name: sub.title, state: subtaskState(it, sub) === 'complete' ? 'done' : subtaskState(it, sub), sha: sub.commit?.slice(0, 8) || null, files: sub.id === it.current ? files : [] }));
    return { goal: `${it.id} · ${it.title}`, subtasks, all: files, other: [], working: edit?.uncommitted === true ? files : [], branch: it.branch };
  }
  function diffFile(file, patches) {
    const hunks = [];
    for (const patch of patches) {
      let hunk, oldLine, newLine;
      for (const line of patch.split('\n')) {
        const start = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)/.exec(line);
        if (start) { oldLine = +start[1]; newLine = +start[2]; hunk = { h: line, src: '', lines: [] }; hunks.push(hunk); continue; }
        if (!hunk || !/^[ +\-]/.test(line)) continue;
        if (line[0] === '+') hunk.lines.push(['a', null, newLine++, line.slice(1)]);
        else if (line[0] === '-') hunk.lines.push(['d', oldLine++, null, line.slice(1)]);
        else hunk.lines.push(['c', oldLine++, newLine++, line.slice(1)]);
      }
    }
    return { path: file.path, st: file.status, ins: file.add, del: file.del, hunks };
  }
  function registerChat(chat, title) {
    const id = `chat:${chat.id}`;
    const files = (chat.changes?.files || []).map(([status, path, add, del]) => ({ status, path, add, del, patch: '' }));
    chatContexts.set(id, { goal: title, branch: chat.branch || 'current branch', subtasks: [], all: files, other: [], working: chat.changes?.uncommitted ? files : [] });
    return id;
  }
  window.DesignFlow = { activityFor, changesFor, diffFile, registerChat, subtaskState };
  const seed = D.items.find((it) => it.id === 'SKILL-417');
  const recorded = D.RECORDED_CHANGES;
  const sample = JSON.parse(JSON.stringify(seed));
  Object.assign(sample, { id: 'SKILL-512', title: 'Changes window with subtask scopes', branch: 'feat/changes-window-scopes', step: 'implement', done: 20, total: recorded.subtasks.length, current: 21, summary: 'Design tokens for diff colors', workflow: 'design-changes-window', invocation: 'design-changes-window', latest: { activity: { label: 'editing diff colors', ago: 10 } } });
  sample.subtasks = recorded.subtasks.map((sub) => ({ id: sub.n, title: sub.name, spec: `spec_subtask_${sub.n}.md`, status: sub.state === 'done' ? 'complete' : sub.state === 'running' ? 'in_progress' : 'pending', commit: sub.state === 'done' ? sub.sha : null, deps: [], reviews: [], blocked: null }));
  delete sample.finish; delete sample.assessment; delete sample.goalQa;
  delete sample.executions; delete sample.attempts; delete sample.startedAgo; delete sample.exec;
  sample.specBundle = sample.subtasks.map((sub) => sub.spec);
  sample.pipeline = { done: ['preplan', 'plan'], now: 'implement' };
  /* Phase ledgers from the recorded run: subtasks 1 to 4 as recorded (with their block incidents), 5 to 20 repeat those
   * ledgers later in time, and subtask 21 is cut short mid-run. Activity and the details panel both read `phaseLog`. */
  const rec = D.ACTIVITY_RECORD, ms = (t) => new Date(t).getTime(), iso = (n) => new Date(n).toISOString();
  const logs = {};
  rec.groups.forEach((g) => { logs[g.n] = (logs[g.n] || []).concat(g.ph); });
  /* The record's two block incidents belong to subtasks 2 and 3 (its blocked stop and its audit_plan_fix gap). */
  const INCIDENT_SUBTASK = [2, 3];
  const base = [1, 2, 3, 4].map((n) => logs[n]);
  let cursor = ms(base[3][base[3].length - 1].e) + 30 * 60000;
  sample.subtasks.forEach((sub) => {
    const n = sub.id;
    if (n <= 4) { sub.phaseLog = logs[n].map((p) => ({ ...p })); }
    else if (n <= 21) {
      const src = base[(n - 1) % 4], shift = cursor - ms(src[0].s);
      sub.phaseLog = src.map((p) => ({ ...p, s: iso(ms(p.s) + shift), e: p.e ? iso(ms(p.e) + shift) : null }));
      if (n === 21) { sub.phaseLog = sub.phaseLog.slice(0, 6); sub.phaseLog[5].e = null; sub.phaseLog[5].t = null; }
      cursor = ms(sub.phaseLog[sub.phaseLog.length - 1].e || sub.phaseLog[sub.phaseLog.length - 1].s) + 2 * 60000;
    }
    sub.blocks = rec.incidents.filter((inc, i) => INCIDENT_SUBTASK[i] === n).map((inc) => ({ at: inc.at, why: inc.why, phase: inc.phase, n: inc.n, disp: inc.disp }));
  });
  sample.started = '31 Oct 20:34';
  D.items.push(sample);
})();
