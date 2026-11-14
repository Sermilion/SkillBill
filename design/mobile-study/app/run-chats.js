/* Sona mobile study · run chats
 * Desktop has two chat kinds: New task chats (pick a repository and base branch, start new work) and run chats
 * (one per run, opened from the run's detail; desktop feature/chat ChatWorkspace, toggleChat(item)). A run chat is bound
 * to the run's original source checkout and branch: fixes asked for there land in that checkout, never in a QA checkout.
 * A parked Verify shows its proposal card in its run chat (Confirm, Adjust, Dismiss), and a goal waiting for an operator
 * decision is settled there. Example values only. */
(function () {
  'use strict';
  const D = window.MD;
  const run = (id) => D.RUNS.find((r) => r.id === id);
  const bind = (c) => { const r = run(c.run); return Object.assign({ kind: 'run', repo: r.repo, branch: r.branch, path: r.path, agent: r.agent }, c); };

  /* The NEWS-12 chat was started from the run, so it is a run chat. */
  const news = D.CHATS.find((c) => c.id === 'c2');
  Object.assign(news, bind({ run: 'NEWS-12', title: 'Why is NEWS-12 failing' }));

  D.CHATS.push(
    bind({
      id: 'rc-inf7', run: 'INF-7', title: 'State lock on staging', status: 'waiting', time: '13:40',
      messages: [
        ['user', 'Why is INF-7 blocked?'],
        ['agent', 'validate runs `terraform plan` on staging, and the plan needs the state lock. Lock `6c0f2a91` on `staging.tfstate` is held by run `tf-apply-8812`, started 12:51 by ci@runner-12. Nothing in this goal is wrong; the plan just cannot start.'],
        ['agent', 'Once that apply finishes or someone releases the lock, tick the prerequisite and Recover. I will not force-unlock it from here.'],
      ],
      suggestions: ['Who holds the lock?', 'Recover when it clears'],
    }),
    bind({
      id: 'rc-dsn31', run: 'DSN-31', title: 'Verify DSN-31', status: 'waiting', time: '13:50',
      messages: [['agent', 'I read DSN-31 from Linear and the diff on `feat/DSN-31-figma-export`. Here is what I would audit. Nothing runs until you confirm.']],
      proposal: { kind: 'verify', title: 'Verify proposal · DSN-31', state: 'open' },
    }),
    bind({
      id: 'rc-505', run: 'SKILL-505', title: 'Subtask 2 made no changes', status: 'waiting', time: '11:12',
      messages: [
        ['agent', 'Subtask 2 asked me to delete the status client in the IntelliJ plugin. The plugin already stopped calling `/status` in 0.40, and the client class was removed then. There was nothing left to change, so implement finished with no code changes.'],
        ['agent', 'Pick how the goal should continue. accept_and_advance is what I would choose: the criterion is already met.'],
      ],
      decision: { subtask: 2, state: 'open' },
    }),
  );

  /* Empty run chat shown the first time a run's chat opens. */
  D.runChatIntro = (r, label) => `This chat is about ${label}. I can read its records and the checkout at ${r.path || r.repo} on ${r.branch}. Fixes you ask for go to that checkout.`;
})();
