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
    'zero-account': { name: 'zero-account', path: '~/StudioProjects/ZeroAccount-pr-168', color: 'var(--data-3)', web: 'github.com/0account/zero-account' },
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
  /* Goal finish phases in run order, as the app lists them (FeatureTaskRuntimePhaseIds). */
  const GOAL_FINISH = ['goal_assessment', 'qa', 'qa_fix', 'validate', 'pr', 'monitor', 'monitor_fix'];

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

  /* QA results, shaped like the desktop's GoalQaResult and an execution's qaVerdict, qaReportText and qaDetails (QaRunDetails):
   * outcome (passed | findings | skipped | incomplete | attempted, null while nothing settled), reportPreview (design only: the
   * closed Full report line), findings ({ id, title, fixed,
   * observed, expected, steps, evidencePaths }), reportText, reason, failureKind, and details ({ reportPath,
   * invocationDirectory, skipReason, assertions ({ outcome passed | failed | not_exercised, title, detail, evidencePaths }),
   * coverage ({ kind, verdict, reason }), retainedResources ({ kind, identity }), temporaryEdits ({ path, detail,
   * notRestored }) }); details is null on a run recorded before the format change. `dir` is the invocation directory the
   * QA checklist names when details are missing. The first report is the real one from run qa-4f7e0edf with the home
   * directory anonymized; the rest are sample data. */
  const QA_ROOT = '/home/you/.skill-bill/qa/SkillBill-65f9b9859e5144f1/';
  const QA_RUN = QA_ROOT + 'qa-4f7e0edf-f13e-4805-b84f-21c5e546bd85/';
  const QA_SAMPLE = QA_ROOT + 'qa-sample/';
  const QA_ATL = '/home/you/.skill-bill/qa/atlas-web-0b3e71c2d94a5f18/qa-c09d08f8-0568-49da-93c1-89464a2e6b11/';
  const asserted = (outcome, title, detail, evidencePaths) => ({ outcome, title, detail, evidencePaths: evidencePaths || [] });
  const cov = (kind, verdict, reason) => ({ kind, verdict, reason: reason || null });
  const outOfScope = (kinds) => kinds.map((k) => cov(k, 'skipped', 'empty_scope'));
  const QA_REAL_REPORT = `Verdict: findings_open

I exercised the desktop temp-root helper, tray file cleanup, desktop policy checks, and the benchmark against invocation-owned home and temp directories. The benchmark created and removed its temp workspace but timed out before producing figures. The desktop app process reached \`:app:run\`, but no app window appeared on the private display, so live UI behavior remains incomplete. Android, backend, and web have no applicable acceptance criteria.

## Coverage

- android: skipped [reason: empty_scope]
- desktop: incomplete [reason: capability_absent]
- backend: skipped [reason: empty_scope] [backend: none]
- web: skipped [reason: empty_scope] [backend: none]

## Assertions

- Desktop UI tests: \`TrayIconFilesTest\` passed 3 tests. Repeated icon writes retained only the current and previous icons, identical content reused paths, and closing one tray left another tray's files intact. \`PolicyRulesTest\` passed 6 tests. Evidence: ${QA_RUN}desktop-tests-isolated.log
- Temp-root UI tests: \`DesktopTempRootTest\` passed 4 tests covering state scratch selection, a configured non-shared directory, blank state fallback, and a blocked state path. Evidence: ${QA_RUN}temp-root-tests-isolated.log
- Benchmark temp workspace: the benchmark created \`skill-bill-benchmark-*\` under the invocation temp directory, with synthetic, cold, and scenario subdirectories. It removed that workspace on exit. Evidence: ${QA_RUN}benchmark-temp-observation.txt
- Benchmark completion: the run with \`--bursts 1 --samples 1 --cold-runs 1\` exited with \`TimeoutCancellationException\` after waiting 30000 ms. Evidence: ${QA_RUN}benchmark.log
- Live app: \`:app:run\` reached the launcher on a private Xvfb display. The bounded window probe found only the X root window, and the screenshot showed no app window. I could not exercise live startup or input. Evidence: ${QA_RUN}live-app-probe.txt, ${QA_RUN}desktop-final.png, ${QA_RUN}app-final.log
- The \`TrayHostContent\` to \`NativeTray\` integration was not exercised in a visible app window. The tray file tests passed, but they do not assert that live rendering uses the file-based overload.
- Android, backend, and web were not probed because no acceptance criterion describes those target kinds.

### QA-1 Benchmark run times out before producing figures

Steps: From \`desktop\`, run \`./gradlew :benchmark:run --args='--bursts 1 --samples 1 --cold-runs 1'\` with the invocation-owned home and temp directory.

Expected: the benchmark completes its scenarios and reports figures, then removes its temporary workspace.

Observed: it created and removed its workspace under the invocation temp directory, then exited 1 after a 30000 ms timeout while waiting for a scenario result. Evidence: ${QA_RUN}benchmark.log, ${QA_RUN}benchmark-temp-observation.txt

## Isolation

Desktop module \`:app\`, main class \`dev.skillbill.desktop.app.DesktopLauncherKt\`. The app used ${QA_RUN}home as its home and ${QA_RUN}tmp for \`TMPDIR\` and \`java.io.tmpdir\`. Its state path was inside the isolated home. The benchmark used a synthetic store and the same on-disk temp directory. The private Xvfb display connected, but no visible app window appeared. Backend and web environments were not created because those target kinds have empty scope. No database or outbound substitutes were needed.

## Temporary edits

None.

## Cleanup

Stopped the desktop app and private display processes. Stopped the Gradle and Kotlin compiler daemons created for this QA run. Released the registered process and directory resources; none reported as retained. Cleanup evidence: ${QA_RUN}cleanup-resources.txt, ${QA_RUN}cleanup-directories.txt`;
  const QA_REAL_PASSED = [
    asserted('passed', 'Desktop UI tests', '`TrayIconFilesTest` passed 3 tests. Repeated icon writes retained only the current and previous icons, identical content reused paths, and closing one tray left another tray\'s files intact. `PolicyRulesTest` passed 6 tests.', [QA_RUN + 'desktop-tests-isolated.log']),
    asserted('passed', 'Temp-root UI tests', '`DesktopTempRootTest` passed 4 tests covering state scratch selection, a configured non-shared directory, blank state fallback, and a blocked state path.', [QA_RUN + 'temp-root-tests-isolated.log']),
    asserted('passed', 'Benchmark temp workspace', 'The benchmark created `skill-bill-benchmark-*` under the invocation temp directory, with synthetic, cold, and scenario subdirectories. It removed that workspace on exit.', [QA_RUN + 'benchmark-temp-observation.txt']),
  ];
  const QA_REAL_GAPS = [
    asserted('not_exercised', 'Live app', '`:app:run` reached the launcher on a private Xvfb display. The bounded window probe found only the X root window, and the screenshot showed no app window. I could not exercise live startup or input.', [QA_RUN + 'live-app-probe.txt', QA_RUN + 'desktop-final.png', QA_RUN + 'app-final.log']),
    asserted('not_exercised', 'TrayHostContent to NativeTray', 'Not exercised in a visible app window. The tray file tests passed, but they do not assert that live rendering uses the file-based overload.'),
  ];
  const QA_REAL_FINDING = {
    id: 'QA-1', title: 'Benchmark run times out before producing figures', fixed: false,
    observed: 'it created and removed its workspace under the invocation temp directory, then exited 1 after a 30000 ms timeout while waiting for a scenario result.',
    expected: 'The benchmark completes its scenarios and reports figures, then removes its temporary workspace.',
    steps: 'From `desktop`, run `./gradlew :benchmark:run --args=\'--bursts 1 --samples 1 --cold-runs 1\'` with the invocation-owned home and temp directory.',
    evidencePaths: [QA_RUN + 'benchmark.log', QA_RUN + 'benchmark-temp-observation.txt'],
  };
  const QA_DESKTOP_COVERAGE = [cov('android', 'skipped', 'empty_scope'), cov('desktop', 'incomplete', 'capability_absent'), ...outOfScope(['backend', 'web'])];
  const sampleChecks = (titles, root) => titles.map((t) => asserted('passed', t, 'Exercised through the running app and matched the acceptance criterion.', [(root || QA_SAMPLE) + t.toLowerCase().replace(/\s+/g, '-') + '.png']));
  const QA_SETTINGS_CHECKS = ['Open settings', 'Edit retention', 'Save and reload', 'Reject invalid values', 'Save failure toast', 'Keyboard order', 'Restart keeps values'];
  const QA = {
    /* Standalone QA with an open problem and untested work: the real report. */
    open: {
      runKey: 'inv-4f7e0edf', outcome: 'attempted', reportText: QA_REAL_REPORT, findings: [QA_REAL_FINDING],
      details: { reportPath: QA_RUN + 'report.md', invocationDirectory: QA_RUN, assertions: [...QA_REAL_PASSED, asserted('failed', 'Benchmark completion', 'The run with `--bursts 1 --samples 1 --cold-runs 1` exited with `TimeoutCancellationException` after waiting 30000 ms.', [QA_RUN + 'benchmark.log']), ...QA_REAL_GAPS], coverage: QA_DESKTOP_COVERAGE },
    },
    /* No problems, but work went untested; the report's Unblock section says what QA was missing. */
    incomplete: {
      runKey: 'inv-7a21c0de', outcome: 'incomplete',
      reportText: 'Verdict: incomplete\n\nI exercised the helpers and tests. No app window appeared on the private display, so live UI behavior remains incomplete.\n\n## Coverage\n\n- desktop: incomplete [reason: capability_absent]\n\n## Unblock\n\n#### display\n- Target: desktop\n- Tried: Started the app on a private display, but no window appeared.\n- Evidence: ' + QA_SAMPLE + 'xvfb.log\n- Remedy: Install Xvfb with apt install xvfb.\n```markdown\nInstall Xvfb on the QA host.\nStart Xvfb before launching the desktop app.\n```\n\n#### app_startup_gate\n- Target: desktop\n- Tried: The app startup gate stopped before the first window.\n- Evidence: ' + QA_SAMPLE + 'app-startup.log\n- Remedy: Set SKILL_BILL_RUNTIME_DIR to a writable directory.\n```markdown\nSet SKILL_BILL_RUNTIME_DIR to a writable directory before launch.\n```',
      details: { reportPath: QA_SAMPLE + 'report.md', invocationDirectory: QA_SAMPLE, assertions: [...QA_REAL_PASSED.map((a) => Object.assign({}, a, { evidencePaths: a.evidencePaths.map((p) => p.replace(QA_RUN, QA_SAMPLE)) })), ...QA_REAL_GAPS.map((a) => Object.assign({}, a, { evidencePaths: a.evidencePaths.map((p) => p.replace(QA_RUN, QA_SAMPLE)) }))], coverage: QA_DESKTOP_COVERAGE },
    },
    /* A clean pass: verdict, one sentence, the Passed fold and the targets line. */
    clean: {
      runKey: 'inv-c09d08f8', outcome: 'passed',
      reportText: 'Verdict: passed\n\nCart badge updates on add, remove and quantity change in Chrome and Safari. The badge animation skips frames when 20 or more items are added at once, which no acceptance criterion covers.\n\n## Coverage\n\n- web: passed\n- android: skipped [reason: empty_scope]\n- desktop: skipped [reason: empty_scope]\n- backend: skipped [reason: empty_scope]',
      details: { reportPath: QA_ATL + 'report.md', invocationDirectory: QA_ATL, assertions: sampleChecks(['Add an item', 'Remove an item', 'Change the quantity', 'Badge in Safari', 'Badge after reload'], QA_ATL), coverage: [cov('web', 'passed'), ...outOfScope(['android', 'desktop', 'backend'])] },
    },
    /* design/qa-results "Problems found and fixed" and "Clean pass": settings editing in the desktop app, seven checks. */
    fixed: {
      runKey: 'goal-qa-settings-fixed', outcome: 'passed', fixAttempts: 2,
      reportText: 'Verdict: passed\n\nI exercised settings editing in the running desktop app. Three findings were reported and fixed across two fix attempts.\n\n## Coverage\n\n- desktop: passed',
      findings: [
        { id: 'QA-1', title: 'Save button stays disabled after editing a field', fixed: true, observed: 'The button stayed disabled until the window lost focus.', expected: 'The button enables on the first edit.', steps: 'Open Settings, change the retention field.', evidencePaths: [QA_SAMPLE + 'save-disabled.png'] },
        { id: 'QA-2', title: 'Retention field accepts a negative number', fixed: true, observed: 'A value of -1 saved without an error.', expected: 'The field rejects values below 1.', steps: 'Type -1 in the retention field and save.', evidencePaths: [QA_SAMPLE + 'retention.log'] },
        { id: 'QA-3', title: 'Error toast covers the Save button', fixed: true, observed: 'The toast sat on top of the button for 5 seconds.', expected: 'The toast leaves the button clickable.', steps: 'Trigger a save failure.', evidencePaths: [QA_SAMPLE + 'toast.png'] },
      ],
      details: { reportPath: QA_SAMPLE + 'report.md', invocationDirectory: QA_SAMPLE, assertions: sampleChecks(QA_SETTINGS_CHECKS), coverage: [cov('desktop', 'passed'), ...outOfScope(['android', 'backend', 'web'])] },
    },
    settings: {
      runKey: 'goal-qa-settings-clean', outcome: 'passed',
      reportText: 'Verdict: passed\n\nI exercised settings editing in the running desktop app.\n\n## Coverage\n\n- desktop: passed',
      details: { reportPath: QA_SAMPLE + 'report.md', invocationDirectory: QA_SAMPLE, assertions: sampleChecks(QA_SETTINGS_CHECKS), coverage: [cov('desktop', 'passed'), ...outOfScope(['android', 'backend', 'web'])] },
    },
    /* Every check passed, but QA left a process running and a temporary edit in place. */
    left: {
      runKey: 'inv-3d90be11', outcome: 'passed',
      reportText: 'Verdict: passed\n\nI exercised settings editing in the running desktop app.\n\n## Temporary edits\n\n`desktop/gradle.properties`: set `org.gradle.jvmargs`.\n\n## Cleanup\n\nThe Gradle daemon was retained.',
      details: { reportPath: QA_SAMPLE + 'report.md', invocationDirectory: QA_SAMPLE, assertions: sampleChecks(['Open settings', 'Edit retention', 'Save and reload', 'Restart keeps values']), coverage: [cov('desktop', 'passed'), ...outOfScope(['android', 'backend', 'web'])],
        retainedResources: [{ kind: 'process', identity: '48213', name: 'Gradle daemon' }],
        temporaryEdits: [{ path: 'desktop/gradle.properties', detail: 'set `org.gradle.jvmargs` to `-Xmx1g` for the QA run and did not restore it.', notRestored: true }] },
    },
    /* Nothing to test: empty_scope reads grey. */
    skipped: {
      runKey: 'inv-5be0d113', outcome: 'skipped',
      reportText: 'Verdict: skipped\nReason: empty_scope\n\nThis change only touches documentation.',
      details: { reportPath: QA_SAMPLE + 'report.md', invocationDirectory: QA_SAMPLE, skipReason: 'empty_scope', coverage: outOfScope(['android', 'desktop', 'backend', 'web']) },
    },
    /* QA stopped before it wrote a report. */
    unfinished: { runKey: 'inv-91c4e7a2', outcome: null, reason: 'Agent exited with status 137 during step 4 of 9.', findings: [], details: null },
    /* Recorded before the format change: no details, one evidence path per finding, the report's first paragraph above the fold. */
    legacy: {
      runKey: 'inv-0c6f2b9e', outcome: 'attempted', reportText: QA_REAL_REPORT, dir: QA_RUN, details: null,
      findings: [{ id: 'QA-1', title: 'Benchmark run times out before producing figures', fixed: false, evidencePaths: [QA_RUN + 'benchmark.log'] }],
    },
  };


  /* ---------- Validation runs (design/validation-run) ----------
   * A standalone validation phase's run, shaped like the desktop's Detail for phase_id 'validation': the current
   * execution's liveChecks ('available' when the skill-bill MCP server started for the launch, so the agent can report
   * checks through validation_check), validationOutcome (passed | blocked | failed, derived by the runtime once the checks
   * settle), validationChecks ({ id, name, dir, cmd, attempts: { outcome, at, ms, exit, note } }), the report
   * (terminal_result), currentActivity, the last agent signal (agent_activity_stamps) and validationBranchRuns (every
   * validation run with the same repository and branch, oldest first; `item` names the run's detail when it has one).
   * The reports are real standalone_phase_status rows with home directories anonymized and a provider org id redacted;
   * check names and notes come from those reports, times are sample values. */
  const VAL_REPORT = {"e1a3d2a4":"All the required project checks now pass on the current candidate. Two failures came up in the uncommitted runtime config work and I fixed both in this session. No check was skipped. No suppressions, baseline edits, disabled rules or test skips were added.\n\n**Checks run.** I found them in `AGENTS.md`, `scripts/validate` and the CI workflows (`validate-agent-configs.yml`, `desktop-tray-ci.yml`, `install-smoke-test.yml`). Host: Linux, JDK 21.0.11, Node 26, `DISPLAY=:0`. CI uses `xvfb-run` for the tray build on Linux, but the real display is available here, so I didn't need it.\n\n1. **`runtime-kotlin`: `./gradlew check --no-build-cache`.** Failed twice, then passed:\n   - **Formatting:** `:runtime-application:spotlessKotlinCheck` found violations in `RuntimeConfigEditService.kt`. `./gradlew spotlessApply` fixed them.\n   - **Package cycle:** `:runtime-core:repoTest` failed in `ApplicationPackageAcyclicityArchitectureTest` with \"New package cycle not in baseline: config <-> telemetry\". The uncommitted change made `skillbill.application.config.RuntimeConfigEditService` import `telemetryConfigReadProblem` from `skillbill.application.telemetry.config`. The telemetry package already imports config, so this closed a loop.\n   - **Fix:** `RuntimeConfigEditService.writeTelemetryLevel` (and its private `telemetryReadError`) now takes a `telemetrySettingsProblem: (Path, ConfigDocumentTree) -> ConfigWriteResult.Unreadable?` parameter. Its only caller, `TelemetryService.setLevel`, passes a lambda that calls `telemetryConfigReadProblem` and maps the result to `ConfigWriteResult.Unreadable`. Validation order and behaviour are unchanged: settings-level problems come before the fingerprint mismatch.\n   - **Second attempt:** my first version typed the lambda with `Map<String, Any?>`, which `RuntimeRawMapArchitectureTest` rejected as a public raw map shape. The parameter now takes the typed `ConfigDocumentTree`.\n   - The final run exited 0, including all repoTest architecture tests and the agent-config validation that `check` triggers.\n2. **`npx --yes agnix@0.44.0 --strict .`** passed with no issues.\n3. **`skill-bill validate --repo-root .`** passed (`status: pass`, no issues).\n4. **`scripts/validate_agent_configs`** passed (106 skills, 16 add-on files, 9 packs, 96 native agents).\n5. **`desktop-tray`, following `desktop-tray-ci.yml`:**\n   - `runtime-kotlin :runtime-cli:installDist`, then `./gradlew clean check --no-build-cache` and `./gradlew createDistributable --no-build-cache`: all passed.\n   - `./gradlew :composeApp:packagedDataSmoke --no-build-cache` first failed with \"Dirty runtime and desktop builds need the same SKILL_BILL_INSTALL_RUN_ID\". CI sets that variable for the whole job. I set one shared `SKILL_BILL_INSTALL_RUN_ID`, rebuilt the runtime `installDist` and the tray distributable with it, and the smoke test passed: \"Packaged Room, SQLite runtime projection, detail and DataStore passed without CLI discovery.\" The tray `clean check` ran before I set the variable and was not rerun with it; no source changed in between.\n6. **`desktop/` (the new app on this branch): `./gradlew check --no-build-cache`** passed against the final runtime source through the included build. No CI workflow covers this project yet; I ran it because the branch changes it.\n7. **Install smoke (`install-smoke-test.yml`):**\n   - `:runtime-cli:installDist :runtime-mcp:installDist` passed.\n   - `scripts/runtime_package_parity_smoke_test.sh` passed. The \"parity failed\" lines in its log are the expected rejection scenarios.\n   - `scripts/install_smoke_test.sh` passed: 71 PASS lines, \"all scenarios passed\", run in a fake home.\n\n**Repair diff.** It is confined to `runtime-kotlin/runtime-application/.../config/RuntimeConfigEditService.kt` and `.../telemetry/service/TelemetryService.kt`, on top of the existing uncommitted runtime and desktop config changes. Nothing is committed. Commit and PR are left to later phases.\n\n**Risk.** `/home` is at 99–100% (about 3 GB free) and `/tmp` at 92%. Later build-heavy phases could fail with out-of-disk errors.","c7b9d025":"Validation passed. Every required check I could run on this Linux host passed against one tree snapshot, which didn't change from the first of these final runs to the last.\n\nI worked out the required checks from `AGENTS.md`, `scripts/validate_agent_configs`, and the workflows `validate-agent-configs.yml`, `desktop-ci.yml`, `desktop-tray-ci.yml` and `install-smoke-test.yml`. The host has JDK 21, Node 26 and `npx`, and `DISPLAY=:0`, so CI's `xvfb-run` wrapper wasn't needed. All desktop and smoke builds ran under one shared `SKILL_BILL_INSTALL_RUN_ID`, as CI does. Without it, `:app:packagedDataSmoke` rejects a dirty tree with \"Dirty runtime and desktop builds need the same SKILL_BILL_INSTALL_RUN_ID.\"\n\n**Final results**\n- `runtime-kotlin`: `./gradlew check --no-build-cache --rerun-tasks --continue` passed, with all 265 tasks run fresh.\n- `npx --yes agnix --strict .` passed with no issues.\n- `scripts/validate_agent_configs` passed: 106 skills, 16 add-on files, 9 packs, 96 native agents.\n- `runtime-kotlin`: `./gradlew --no-build-cache :runtime-cli:installDist :runtime-mcp:installDist` passed.\n- `desktop`: `./gradlew clean check --no-build-cache`, then `createDistributable --no-build-cache`, then `:app:packagedDataSmoke --no-build-cache` all passed.\n- `desktop-tray`: `./gradlew clean check --no-build-cache`, then `createDistributable --no-build-cache`, then `:composeApp:packagedDataSmoke --no-build-cache` all passed.\n- `bash scripts/runtime_package_parity_smoke_test.sh` passed. The \"parity failed\" lines in its output are the script's intended rejection cases.\n- `bash scripts/install_smoke_test.sh` passed all 71 scenarios. It runs inside a temporary fake HOME.\n\n**Repairs I made**\n- The first runtime check failed in `CommentAndInterfaceKdocArchitectureTest`. Four KDoc blocks sat outside interfaces, all added on this branch: `PreplanTrackerBlockHooks.kt:10`, `GoalIntake.kt:73`, `StandalonePlanReportBuilder.kt:36` and `IdeStatusProjector.kt:249`. I removed them.\n- Later the same test caught a fifth one at `FeatureTaskRuntimeStatusService.kt:62`, on the new `phaseBriefingTexts` function, and I removed it too.\n- `AGENTS.md` says source must not hold rationale comments, so I added two entries to `runtime-kotlin/agent/decisions.md`. One explains why preplan retries blocks that rest on missing tracker data. The other explains why `phaseBriefingTexts` treats an unreadable briefing map as no briefings.\n\n**Two concurrent writers during this phase**\n- **Another Claude session.** An interactive session (pid 169339) was working in this same checkout. It committed `7f14ec2d4` (\"Name running plans after their seeded spec bundle\") after this session started. It also left uncommitted edits in `RuntimeDetailMapper.kt`, `DetailModels.kt`, `DetailScreen.kt`, `ReportSections.kt`, `FeatureTaskRuntimeStatusService.kt` and its test, `IdeStatusService.kt` and `WorkDetailModels.kt`. I didn't revert any of that work; the only change I made to it was removing the KDoc above. The results above cover the tree with those edits included.\n- **Your own `./gradlew check`.** You started one from the IntelliJ terminal in `runtime-kotlin` while my runtime check was running. The two builds shared `build/test-results`, so four of my test tasks died with `NoSuchFileException`. That was a build collision, not a code failure. I waited for your build to finish, then ran the `--rerun-tasks` pass above so none of the evidence comes from your run's outputs.\n\n**Warnings: coverage I couldn't run here**\n- The macOS and Windows legs of `desktop-ci.yml` and `desktop-tray-ci.yml` (`clean check`, `createDistributable`, `packagedDataSmoke`) didn't run. They need macOS and Windows runners, and this host runs Linux 7.2.4-1-cachyos.\n- The self-hosted macmini leg of `validate-agent-configs.yml` and the macOS bash 3.2 run of `install-smoke-test.yml` didn't run either. They need that macOS host. Only their Linux equivalents ran here.\n\n**For later phases**\n- The working tree now has 13 uncommitted files: my KDoc removals, the two `decisions.md` entries, and the other session's in-progress edits. The commit phase will pick up all of them.\n- Not a check failure, but worth a look in review: `phaseBriefingTexts` catches `SkillBillRuntimeException` and returns an empty map without emitting a record. `AGENTS.md` and `docs/observability-policy.md` ask for a record whenever a failure is swallowed.","1dce0e4f":"Validation is blocked by `./gradlew assemble`: production release requires `androidApp/signing.properties` and upload-key inputs. An operator must provide the release signing configuration before that build and its artifact verification can run.\n\nThe independent Gradle run passed `check`, `spotlessCheck`, `detekt`, Android debug and internal release assembly, native-library verification, and the desktop JAR build. The overview JVM suite passed 289 tests. The nightly integration task ran 74 tests, with 471 filtered out; it did not prove real-endpoint coverage. `git diff --check` and all 26 repository script tests passed.\n\nWarnings: iOS framework links and iOS test targets were skipped because this Linux host lacks macOS and Xcode. `:composeApp:packageDistributionForCurrentOS` was skipped because `dpkg-deb` is unavailable.","c31d70f5":"Feature-task-runtime phase 'validate' stopped because the agent provider refused the request at a usage limit. The phase produced no output and consumed no repair attempt; the run is paused and resumes at 'validate'. Provider said: rate limit exceeded: Rate limit reached for gpt-6.1-terra in organization org-… on tokens per min (TPM): Limit 1000000, Used 817370, Requested 203834. Please try again in 1.272s"};
  const att = (outcome, at, ms, exit, note) => ({ outcome, at, ms, exit: exit == null ? null : exit, note: note || null });
  const chk = (id, name, dir, cmd, attempts) => ({ id, name, dir, cmd, attempts });
  const VAL_CHECKS_447 = [
    chk('runtime-check', 'Runtime checks', 'runtime-kotlin', './gradlew check --no-build-cache', [
      att('failed', '07:12', 150000, 1, '`:runtime-application:spotlessKotlinCheck` found violations in `RuntimeConfigEditService.kt`. `:runtime-core:repoTest` found a new package cycle, config <-> telemetry.'),
      att('failed', '07:15', 140000, 1, '`RuntimeRawMapArchitectureTest` rejected a public `Map<String, Any?>` parameter.'),
      att('passed', '07:18', 190000, 0)]),
    chk('agnix', 'agnix', '.', 'npx --yes agnix@0.44.0 --strict .', [att('passed', '07:21', 18000, 0)]),
    chk('skill-bill-validate', 'Skill Bill validate', '.', 'skill-bill validate --repo-root .', [att('passed', '07:21', 6000, 0)]),
    chk('agent-configs', 'Agent configs', '.', 'scripts/validate_agent_configs', [att('passed', '07:22', 12000, 0, '106 skills, 16 add-on files, 9 packs, 96 native agents.')]),
    chk('tray-check', 'Tray check and distributable', 'desktop-tray', './gradlew clean check createDistributable --no-build-cache', [att('passed', '07:22', 160000, 0)]),
    chk('tray-smoke', 'Tray packaged smoke', 'desktop-tray', './gradlew :composeApp:packagedDataSmoke --no-build-cache', [
      att('failed', '07:25', 35000, 1, 'Dirty runtime and desktop builds need the same `SKILL_BILL_INSTALL_RUN_ID`. Set one shared id, as CI does.'),
      att('passed', '07:26', 58000, 0)]),
    chk('desktop-check', 'Desktop check', 'desktop', './gradlew check --no-build-cache', [att('passed', '07:27', 105000, 0)]),
    chk('install-smoke', 'Install smoke', '.', 'scripts/install_smoke_test.sh', [att('passed', '07:29', 80000, 0, '71 scenarios passed in a fake HOME.')]),
  ];
  /* The same plan on another branch, four checks in: the fifth runs now, three wait. */
  const VAL_CHECKS_RUNNING = VAL_CHECKS_447.map((c, i) => (i < 4 ? c : i === 4 ? Object.assign({}, c, { attempts: [att('running', '09:43', 65000)] }) : Object.assign({}, c, { attempts: [] })));
  const VAL_CHECKS_32 = [
    chk('gradle-check', 'Gradle check', '.', './gradlew check', [att('passed', '14:06', 242000, 0)]),
    chk('formatting', 'Formatting', '.', './gradlew spotlessCheck', [att('passed', '14:10', 41000, 0)]),
    chk('detekt', 'Static analysis', '.', './gradlew detekt', [att('passed', '14:11', 55000, 0)]),
    chk('android-builds', 'Android debug and internal release', '.', './gradlew assembleDebug assembleInternalRelease', [att('passed', '14:12', 192000, 0)]),
    chk('production-release', 'Production release', '.', './gradlew assemble', [att('blocked', '14:15', 48000, 1, 'Needs `androidApp/signing.properties` and the upload-key inputs. An operator must provide the release signing configuration.')]),
    chk('overview-jvm', 'Overview JVM suite', '.', './gradlew :overview:jvmTest', [att('passed', '14:16', 70000, 0, '289 tests passed.')]),
    chk('nightly', 'Nightly integration', '.', './gradlew nightlyIntegrationTest', [att('passed', '14:17', 64000, 0, '74 tests ran, 471 filtered out. Does not prove real-endpoint coverage.')]),
    chk('whitespace', 'Whitespace', '.', 'git diff --check', [att('passed', '14:18', 1000, 0)]),
    chk('script-tests', 'Repository script tests', '.', 'scripts/test', [att('passed', '14:18', 22000, 0, '26 tests passed.')]),
    chk('ios', 'iOS framework links and tests', '.', './gradlew linkDebugFrameworkIosSimulatorArm64 iosSimulatorArm64Test', [att('skipped', '14:19', 0, null, 'This Linux host has no macOS or Xcode.')]),
    chk('desktop-package', 'Desktop package', '.', './gradlew :composeApp:packageDistributionForCurrentOS', [att('skipped', '14:19', 0, null, '`dpkg-deb` is not installed on this host.')]),
  ];
  const BRANCH_447 = 'feat/SKILL-447-build-the-linear-design-skill-bill-desktop-app';
  const BRANCH_085 = 'feat/0ac-85-contacts-phone-discovery';
  const BRANCH_463 = 'feat/SKILL-463-pull-latest-main-make-sure-local';
  const vrun = (seq, lifecycle, agent, started, ms, line, item) => ({ seq, lifecycle, agent, started, ms, line, item: item || null });
  /* The runs on BRANCH_447 as design/validation-run stores them; each scenario lists the rows that existed when it was
   * captured, so a list can show #411 still running. `item` opens that run's detail. */
  const R406 = vrun(406, 'blocked', 'codex', 'Oct 29 14:02', 1576888, 'Stopped at the agent provider\'s usage limit', 'ph-sb-val-limit');
  const R411 = vrun(411, 'active', 'claude', 'Oct 29 14:31', 551689, 'The \'validate\' child is active; source progress is not confirmed.', 'ph-sb-val-running');
  const R413 = vrun(413, 'terminal', 'claude', 'Oct 29 14:41', 1105098, 'All the required project checks now pass on the current candidate.', 'ph-sb-val-repairs');
  const R489 = vrun(489, 'terminal', 'claude', 'Oct 30 20:10', 1868369, 'Validation passed.', 'ph-sb-val-413');
  const VAL_RUNS_085 = [
    vrun(32, 'terminal', 'codex', 'Oct 29 14:05', 843171, 'Validation is blocked by ./gradlew assemble', 'ph-za-validation'),
    vrun(33, 'canceled', 'codex', 'Oct 29 14:26', 1449965, 'Canceled by you.'),
    vrun(40, 'canceled', 'codex', 'Oct 29 15:29', 71794, 'Canceled by you.'),
  ];
  const ACTIVE_LINE = 'The \'validate\' child is active and producing output; source progress is not confirmed.';
  /* One entry per design/validation-run scenario (Redesign view). `wallMs` and `cancelText` are set where the run ended. */
  const VAL = {
    /* Stored rows, no live checks. Running: the live sentence, the ticking time and the newest agent signal. */
    stored: { runSeq: 411, liveChecks: false, outcome: null, checks: [], result: null, activity: R411.line, signal: { label: 'worktree write', ago: '4s ago' }, branchRuns: [R406, R411] },
    /* Finished with repairs: Checks run starts open; Risk is flagged. */
    repairs: { runSeq: 413, liveChecks: false, outcome: null, checks: [], result: VAL_REPORT.e1a3d2a4, branchRuns: [R406, R411, R413] },
    /* Finished with coverage gaps: three flagged parts, the fourth run on the branch. */
    gaps: { runSeq: 489, liveChecks: false, outcome: null, checks: [], result: VAL_REPORT.c7b9d025, branchRuns: [R406, R411, R413, R489] },
    /* A terminal row whose report says blocked: without checks the card stays neutral. */
    blockedReportOnly: { runSeq: 32, liveChecks: false, outcome: null, checks: [], result: VAL_REPORT['1dce0e4f'], branchRuns: VAL_RUNS_085 },
    /* Paused at the provider limit, the only run on the branch then, so no runs list. */
    limit: { runSeq: 406, liveChecks: false, outcome: null, checks: [], result: VAL_REPORT.c31d70f5, branchRuns: [R406] },
    /* Canceled: the card, Run again and Activity. */
    canceled: { runSeq: 15, liveChecks: false, outcome: null, checks: [], result: null, cancelText: 'Canceled by you.', branchRuns: [] },
    /* Live checks available, none reported yet: the Checks section holds its place. */
    waiting: { runSeq: 413, liveChecks: true, outcome: null, checks: [], result: null, activity: ACTIVE_LINE, signal: { label: 'tool stream', ago: '1s ago' },
      branchRuns: [R406, R411, Object.assign({}, R413, { lifecycle: 'active', ms: 24000, line: 'Reading AGENTS.md and the CI workflows', item: null })] },
    /* The skill-bill MCP server never started for this launch (junie), so no checks: the report-only running layout. */
    noChecks: { runSeq: 412, liveChecks: false, outcome: null, checks: [], result: null, activity: ACTIVE_LINE, signal: { label: 'stdout', ago: '3s ago' }, branchRuns: [] },
    /* Live checks reported: four of eight settled, the fifth running. */
    running: { runSeq: 413, liveChecks: true, outcome: null, checks: VAL_CHECKS_RUNNING, result: null, activity: 'Running Tray check and distributable', signal: { label: 'tool stream', ago: '2s ago' },
      branchRuns: [R406, R411, vrun(413, 'active', 'claude', 'Today 09:38', 655000, 'Running Tray check and distributable')] },
    /* Every check reported, so the runtime's outcome colors the card. */
    passed: { runSeq: 413, liveChecks: true, outcome: 'passed', checks: VAL_CHECKS_447, result: VAL_REPORT.e1a3d2a4, branchRuns: [R406, R411, Object.assign({}, R413, { started: 'Today 07:12', item: 'ph-sb-validation-done' })] },
    blocked: { runSeq: 32, liveChecks: true, outcome: 'blocked', checks: VAL_CHECKS_32, result: VAL_REPORT['1dce0e4f'], branchRuns: VAL_RUNS_085 },
  };
  /* Validation runs only reachable by route (#detail/<id>): two running states that would otherwise add rows to the board. */
  const valRoutes = [
    { id: 'ph-sb-val-running', kind: 'phase', phase: 'validation', repo: 'skill-bill', lifecycle: 'active', section: 'phases', title: 'Validation on ' + BRANCH_447, branch: BRANCH_447, step: 'validate', summary: R411.line, goalMs: 551689, live: true, fresh: true, agent: 'claude', invocation: 'inv-b2fc61394d0a7e5c2f81d9a6-411', started: '14:31', updated: '14:40:43', startedAgo: 552, runOptions: { canLaunch: false, canCancel: true }, steps: [['validate', 'now']], valRun: Object.assign({ day: 'Oct 29' }, VAL.stored) },
    { id: 'ph-sb-val-waiting', kind: 'phase', phase: 'validation', repo: 'skill-bill', lifecycle: 'active', section: 'phases', title: 'Validation on ' + BRANCH_447, branch: BRANCH_447, step: 'validate', summary: ACTIVE_LINE, goalMs: 24000, live: true, fresh: true, agent: 'claude', invocation: 'inv-e1a3d2a4c58b07f6d39e21b4-413', started: '14:41', updated: '14:41:24', startedAgo: 24, runOptions: { canLaunch: false, canCancel: true }, steps: [['validate', 'now']], valRun: Object.assign({ day: 'Oct 29' }, VAL.waiting) },
  ];

  /* ---------- Verify runs (design/verify-run) ----------
   * An operation verify run, shaped like the runtime's VerifyRunDetails (runtime-ports idestatus/model): workflow status
   * (running | pending | completed | abandoned | failed) and current step, input_context (intake, target, repository,
   * revisions, review mode, QA selection), steps_json ({ id, status, attempts }), the extracted criteria, non-goals,
   * constraints and rollout, the completeness audit grades ({ n, status PASS | PARTIAL | FAIL, evidence, missing,
   * notFound }), review findings, the flag audit, the unit test value table, the verdict ({ verdict, summary, findings }),
   * QA (parsed from qa_report.report_text like QaRunDetails) and session_notes.superseded_by. `locations` are the file:line
   * references each receipt names; the shared-code hints come from them. `changed` stands in for diff_projection's
   * changed_files: the count, test files and files per directory (the first two path segments).
   * 0AC-85, the stopped and superseded 0AC-86 runs and FP-5175 are real feature_verify_workflows rows with home directories
   * anonymized; the running 0AC-86 run is that row with its review receipt removed; 0AC-91 and SKILL-442 are sample data.
   * The 0AC-85 summary was written by hand from that run's receipts. */
  const VERIFY = {"done085":{"workflowId":"wfv-20261029-080637-eh4f","issueKey":"0AC-85","status":"completed","current":"finish","startedAt":"2026-10-29T08:06:37Z","finishedAt":"2026-10-29T08:11:29.988Z","intake":"https://linear.app/0account/issue/0AC-85/contacts-whatsapp-style-phone-discovery-friends-are-mutual-address","target":"HEAD","repoRoot":"~/StudioProjects/ZeroAccount-pr-168","base":"ef198ee7","head":"6e2dd091","reviewMode":"inline","qaSelection":"off","steps":[{"id":"collect_inputs","status":"completed","attempts":1},{"id":"extract_criteria","status":"completed","attempts":1},{"id":"gather_diff","status":"completed","attempts":1},{"id":"feature_flag_audit","status":"skipped","attempts":1},{"id":"code_review","status":"completed","attempts":1},{"id":"unit_test_value_check","status":"completed","attempts":1},{"id":"completeness_audit","status":"completed","attempts":1},{"id":"qa","status":"skipped","attempts":1},{"id":"verdict","status":"completed","attempts":1}],"criteria":[{"n":1,"text":"On the first visit to contacts, prompt users without a phone on their profile to enter one and confirm that it is correct. A prefilled profile number must not be registered automatically."},{"n":2,"text":"Request contacts permission, read the address book, normalize and hash numbers on the device, and sync them. The sync response populates the contacts screen."},{"n":3,"text":"Show two users as contacts only when each has the other's phone number in their uploaded address book. If either removes the other's number and syncs again, the friendship disappears for both."},{"n":4,"text":"Reject `POST /contacts/sync` with HTTP 400 when the caller has no phone on file. With a phone, each sync replaces the caller's complete uploaded hash set and is idempotent. An empty sync clears the set."},{"n":5,"text":"Provide `GET /contacts` to reopen the same derived contacts view without uploading. Expose only profile ID, first name, and profile image for each match. The list must be available to viewer limiting in 0AC-52."},{"n":6,"text":"Support saving, replacing, and deleting the user's phone. Saving or replacing it must update its hash atomically, remove the old hash on replacement, and make the user undiscoverable on deletion."},{"n":7,"text":"Include `hasPhone` in the profile DTO. Keep self-asserted phone numbers unverified in v1."},{"n":8,"text":"Cover mutual matching and removal, full replacement, phone and hash transactions, duplicate hash handling, soft deletion, and normalization and hash determinism in tests."}],"criteriaRaw":null,"nonGoals":["Friend requests, pending states, acceptance, decline, and a request inbox.","Email-hash discovery, stable public handles, and QR-based adding.","SMS verification, blocking, and new-contact push notifications in v1.","A stored or pre-materialized friendship graph.","A separate endpoint for adding a contact by typing a phone number."],"constraints":["Normalize phone numbers to E.164 using libphonenumber with SIM or region context on the client and equivalent defensive normalization on the server.","Compute `HMAC-SHA256(salt, e164_bytes)` on the device. The salt is environment-specific and shipped with the matching client build. Release builds require the production `CONTACT_HASH_SALT` secret.","Store the user's own phone in plaintext, but never send other people's plaintext numbers to the server. Persist uploaded address-book hashes with a unique `(profile_id, hash)` pair and delete them when the profile is deleted.","Derive friendship on read through mutual hash matches. Limit phone updates and contact sync per account, and cap sync uploads at approximately 5,000 hashes.","Use one migration for `phones` and `contact_book_hashes`. Enforce one active phone per profile and exclude soft-deleted rows.","The backend contacts module follows the standard five-file layout. Handlers require Swagger annotations, context as the first parameter, wrapped errors, and embedded-PostgreSQL repository tests that assert stored rows.","The issue contains conflicting statements about unmatched contacts. Its data model requires storing the full hashed address book, while its privacy section says unmatched hashes are discarded and its done criteria say a non-member leaves no server-side residue. It also says mutual removal dissolves the friendship for both users, while a privacy note says the other user retains their view until they also delete the number."],"rollout":null,"audit":{"verdict":"had_gaps","grades":[{"n":1,"status":"PARTIAL","evidence":["ContactsViewModel.kt:176","ContactsViewModel.kt:200","ContactsScreen.kt:475"],"missing":"The unsaved-changes guard can call `ConfirmPhone` directly when the user saves while leaving, bypassing the confirmation dialog (`ContactsScreen.kt:147`).","notFound":null,"locations":["ContactsScreen.kt:147"]},{"n":2,"status":"PARTIAL","evidence":["ContactsScreen.kt:167","ContactsPermissionHandler.kt:24","PhoneNormalizer.kt:20","ContactBookHasher.kt:28","ZeroContactsRepository.kt:74","ContactsViewModel.kt:287"],"missing":"An empty address book is not uploaded. The client loads the existing view instead, so removing the last number cannot clear the uploaded set (`ZeroContactsRepository.kt:87`).","notFound":null,"locations":["ZeroContactsRepository.kt:87"]},{"n":3,"status":"FAIL","evidence":[],"missing":null,"notFound":"The diff contains no server-side mutual matching or removal implementation. The client also skips an empty sync, preventing removal of the last uploaded number (`ZeroContactsRepository.kt:87`).","locations":["ZeroContactsRepository.kt:87"]},{"n":4,"status":"FAIL","evidence":[],"missing":null,"notFound":"The diff adds a client POST and maps a 400 response, but contains no server handler or persistence implementation establishing rejection, replacement, idempotency, or clearing (`ContactsRemoteDataSource.kt:48`, `ZeroContactsRepository.kt:108`). The client does not send empty syncs (`ZeroContactsRepository.kt:87`).","locations":["ContactsRemoteDataSource.kt:48","ZeroContactsRepository.kt:108","ZeroContactsRepository.kt:87"]},{"n":5,"status":"PARTIAL","evidence":["GET /contacts","ContactsRemoteDataSource.kt:57","ZeroContactsRepository.kt:120"],"missing":"The diff contains no server implementation or viewer-limiting integration. The client response model also accepts a `hash` field beyond the three specified fields (`ContactsDataModels.kt:17`).","notFound":null,"locations":["ContactsDataModels.kt:17"]},{"n":6,"status":"PARTIAL","evidence":["ContactsRemoteDataSource.kt:33","ContactsRemoteDataSource.kt:42","ContactsViewModel.kt:250","ContactsViewModel.kt:271"],"missing":"The diff contains no server-side phone and hash transaction, replacement, or discoverability implementation. The contacts screen has no replacement flow while a phone is present.","notFound":null,"locations":[]},{"n":7,"status":"PARTIAL","evidence":["hasPhone","ProfileResponseDataModel.kt:15"],"missing":"The diff contains no server DTO or phone verification state implementation to establish the unverified behavior.","notFound":null,"locations":[]},{"n":8,"status":"PARTIAL","evidence":["PhoneNormalizerTest.kt:17","ContactBookHasherTest.kt:11","ZeroContactsRepositoryTest.kt:151"],"missing":"The diff contains no server repository tests for mutual matching and removal, full replacement, phone and hash transactions, stored duplicate hashes, or soft deletion. The empty-book client test instead asserts read-only behavior (`ZeroContactsRepositoryTest.kt:168`).","notFound":null,"locations":["ZeroContactsRepositoryTest.kt:168"]}]},"review":{"verdict":"changes_requested","findings":[{"ref":"F-001","severity":"Major","confidence":"High","location":"core/data/src/commonMain/kotlin/com/zeroaccount/core/data/contacts/repository/ZeroContactsRepository.kt:87","description":"When a permitted address book becomes empty, sync performs a GET instead of uploading an empty hash set. The server retains the previous hashes, so removing the last contact leaves the friendship visible to both users. Distinguish an empty successful read from an unavailable or unauthorized read, and upload the former.","specialist":null,"locations":["ZeroContactsRepository.kt:87"]},{"ref":"F-002","severity":"Major","confidence":"High","location":"core/data/src/commonMain/kotlin/com/zeroaccount/core/data/contacts/repository/ZeroContactsRepository.kt:150","description":"The confirmed contacts response exposes profile ID, first name, and image, so `hash` defaults to empty for every returned contact. `friendHashes` is consequently empty and existing friends also appear as invite candidates. Do not rely on an unprovided response field to identify invitees.","specialist":null,"locations":["ZeroContactsRepository.kt:150"]},{"ref":"F-003","severity":"Major","confidence":"High","location":"feature/overview/src/iosMain/kotlin/com/zeroaccount/feature/overview/contacts/ui/ContactsPermissionHandler.kt:31","description":"iOS limited contacts access falls into the denial branch. The address-book reader also accepts only full authorization, so users who grant access to selected contacts cannot sync those contacts. Handle limited authorization in both places.","specialist":null,"locations":["ContactsPermissionHandler.kt:31"]},{"ref":"F-004","severity":"Major","confidence":"High","location":"feature/overview/src/commonMain/kotlin/com/zeroaccount/feature/overview/contacts/ui/ContactsScreen.kt:826","description":"The sync menu dispatches `SyncContacts` directly and bypasses the permission handler. After a user denies access, choosing sync does not request or explain permission; it reads an empty book and displays the old server list as though sync succeeded. Route this action through the permission flow.","specialist":null,"locations":["ContactsScreen.kt:826"]},{"ref":"F-005","severity":"Major","confidence":"High","location":"feature/overview/src/commonMain/kotlin/com/zeroaccount/feature/overview/contacts/ContactsViewModel.kt:256","description":"`PhoneSaved` requests permission before `refreshStatus()` changes the screen to `HasPhone`. If permission is already granted, its callback can dispatch sync while the screen is still in setup, and line 289 discards it. After removing and re-adding a phone, the one-time content effect has already run, so no automatic retry follows. Start sync after the content state is ready.","specialist":null,"locations":["ContactsViewModel.kt:256"]},{"ref":"F-006","severity":"Major","confidence":"High","location":"iosApp/iosApp.xcodeproj/project.pbxproj:264","description":"The permission prompt says contacts are not stored or uploaded, but sync uploads and retains hashes of the address-book numbers. That statement gives users a false description of the data sent to the server. Describe the hashed upload and storage in both build configurations.","specialist":null,"locations":["project.pbxproj:264"]},{"ref":"F-007","severity":"Minor","confidence":"High","location":"feature/overview/src/commonMain/kotlin/com/zeroaccount/feature/overview/contacts/ContactsViewModel.kt:59","description":"Related setup and content values live in separate mutable flows and are updated together in several transitions. This forces maintainers to trace each field and permits intermediate combinations during `combine` emissions. Store each related group in a state data class and update it as one transition.","specialist":"code-quality","locations":["ContactsViewModel.kt:59"]}]},"flagAudit":{"verdict":"skipped","skipReason":"The criteria do not require a feature-flagged rollout, the diff contains no feature flag, and the provided repository policy does not require one."},"unitTestValue":{"overall":"Mixed","summary":"The suite has useful coverage for hash compatibility, phone opt-in, normalization, and sync error handling. Some tests duplicate behavior or pin unrelated menu details. The empty-address-book test also enforces behavior that conflicts with the supplied acceptance criteria.","rows":[{"area":"Menu item lists","verdict":"Weak","confidence":"High","files":["OverviewMenuItemTest.kt:15","line 26"],"why":"Both tests pin the entire menu order to check a feature flag. Adding or reordering an unrelated item breaks them. This is non-critical menu glue.","action":"Delete both.","locations":["OverviewMenuItemTest.kt:15"]},{"area":"Request contracts","verdict":"Weak","confidence":"High","files":["ContactsRemoteDataSourceTest.kt:57","line 84","line 104","line 143"],"why":"They check paths and profile IDs but not HTTP methods. Request bodies are decoded with the same DTO used to serialize them, so an accidental wire-field rename can pass.","action":"Rewrite these contract assertions to check the method and literal JSON field names at the HTTP boundary.","locations":["ContactsRemoteDataSourceTest.kt:57"]},{"area":"Empty address book","verdict":"Weak","confidence":"High for the conflict; medium for intended product behavior","files":["ZeroContactsRepositoryTest.kt:168"],"why":"The test requires an empty read to fetch the list without uploading. The supplied criteria require an empty sync to clear the uploaded set. The test cannot distinguish an unavailable address book from a genuinely empty one.","action":"Delete this assertion of the conflicting behavior. Clarify how the reader signals unavailable versus empty before adding a clearing test.","locations":["ZeroContactsRepositoryTest.kt:168"]},{"area":"Sync result shown to users","verdict":"Weak","confidence":"High","files":["ContactsViewModelTest.kt:525"],"why":"It checks only that one contact appears. A wrong contact would pass, despite the requirement that the sync response populate the screen.","action":"Rewrite the assertion to check the returned contact’s identity and visible fields.","locations":["ContactsViewModelTest.kt:525"]},{"area":"Normalizer examples","verdict":"Weak","confidence":"High","files":["PhoneNormalizerTest.kt:23","line 35"],"why":"The US national-number case repeats the region fallback covered at line 17. The whitespace example mostly pins library parsing behavior rather than a distinct business rule.","action":"Delete these two examples; retain the region, explicit prefix, and invalid-input cases.","locations":["PhoneNormalizerTest.kt:23"]},{"area":"Empty setup","verdict":"Weak","confidence":"Medium","files":["ContactsViewModelTest.kt:186"],"why":"It repeats the empty setup outcome already checked at line 147. It uses a profile with an empty phone list rather than a missing profile, but neither test demonstrates a distinct user-facing rule for that difference.","action":"Delete.","locations":["ContactsViewModelTest.kt:186"]},{"area":"Hash and address-book processing","verdict":"Valuable","confidence":"High","files":["ContactBookHasherTest.kt:11","ZeroContactsRepositoryTest.kt:151"],"why":"Fixed vectors catch incompatible HMAC encoding. The repository test catches duplicate or invalid numbers entering the upload.","action":"Keep.","locations":["ContactBookHasherTest.kt:11","ZeroContactsRepositoryTest.kt:151"]},{"area":"Phone consent and recovery","verdict":"Valuable","confidence":"High","files":["ContactsViewModelTest.kt:164","line 248","line 550"],"why":"These catch automatic registration of a prefilled number and failures to recover after status errors or a phone removed elsewhere.","action":"Keep.","locations":["ContactsViewModelTest.kt:164"]}],"keep":"The fixed HMAC vectors, normalization and invalid-input checks, deduplication and hash-limit checks, phone consent and removal flows, and sync failure recovery.","rewrite":"The four HTTP request contract tests to assert methods and wire JSON; the sync-result UI test to assert the actual contact.","delete":"`OverviewMenuItemTest.kt:15`, `line 26`; `PhoneNormalizerTest.kt:23`, `line 35`; `ContactsViewModelTest.kt:186`; `ZeroContactsRepositoryTest.kt:168`, subject to resolving the stated behavior conflict.","missing":"A genuinely empty address book clearing the remote set, once it can be distinguished from an unavailable read; cancellation propagating through the remote request instead of becoming a network failure; and decoding the returned match hash used to exclude contacts from invitations."},"changed":{"count":77,"tests":6,"dirs":[["feature/overview",32],["core/data",30],["core/domain",5],[".github/workflows",3],["androidApp",1],["androidApp/src",1],["composeApp",1],["gradle",1],["iosApp/Configuration",1],["iosApp/iosApp.xcodeproj",1],["iosApp/iosApp",1]]},"verdict":{"verdict":"REQUEST CHANGES","summary":"This change adds WhatsApp-style phone discovery to the KMP client: a phone setup flow on the contacts screen, on-device E.164 normalization and HMAC hashing of the address book, and calls to the contacts sync and list endpoints. It touches 77 files, mostly `core/data` and `feature/overview`, and contains no server code.","findings":["**REQUEST CHANGES** — Criteria 3 and 4 are missing from the supplied diff, and the other six criteria are partial. The major blockers include empty-set clearing, mutual matching and removal, server-side sync and phone persistence guarantees, permission and sync failures, and inaccurate iOS permission wording."]},"qaVerdict":"skipped","qa":null,"supersededBy":null},"running086":{"workflowId":"wfv-20261106-091502-k7mq","issueKey":"0AC-86","status":"running","current":"code_review","startedAt":"2026-11-02T21:52:14Z","finishedAt":null,"intake":"0AC-86 Acceptance criterion: the JVM desktop app follows the new desktop design in ~/workspaces/StudioProjects/ZeroAccount-pr-168/design/desktop (248 sidebar, split Sessions/Apps, dialogs and menus instead of sheets, pointer density, 1280x800 default and 1000x640 minimum). Out of scope: Android/iOS/WASM and unclaimed proposals (profile-switcher menu, QR drag-and-drop/paste, Contacts without Sync). most important - the app should look like the new designs look visually","target":"feat/0AC-86-implement-the-new-desktop-ui","repoRoot":"~/workspaces/StudioProjects/ZeroAccount-pr-168","base":"96364d51","head":"42d8a8d6","reviewMode":"inline","qaSelection":"on","steps":[{"id":"collect_inputs","status":"completed","attempts":1},{"id":"extract_criteria","status":"completed","attempts":1},{"id":"gather_diff","status":"completed","attempts":1},{"id":"feature_flag_audit","status":"completed","attempts":1},{"id":"code_review","status":"running","attempts":1},{"id":"unit_test_value_check","status":"pending","attempts":0},{"id":"completeness_audit","status":"pending","attempts":0},{"id":"qa","status":"pending","attempts":0},{"id":"verdict","status":"pending","attempts":0}],"criteria":[{"n":1,"text":"The JVM desktop app visually matches the new desktop design in `design/desktop` (same layout, density, chrome, and overlay treatment as the mockups)."},{"n":2,"text":"The JVM target draws through `ZeroPresentation.Desktop` with mobile colors, the desktop type scale, and pointer density from `design/desktop/desktop.css` (rows 40 / 56, controls 36, body 14). Android and iOS tokens stay at their current values, and WASM stays `Legacy`."},{"n":3,"text":"On Desktop, form sheets render as a 480 dialog with a footer (main action last; Esc closes; Enter submits); confirmations and `ZeroDialog` render as a 400 dialog; action sheets and short pickers render as a menu at the pointer. Public overlay contracts stay usable by existing callers; other presentations keep drawing sheets."},{"n":4,"text":"The desktop window opens at 1280 × 800 with a 1000 × 640 minimum. One 248 sidebar in the Overview host replaces `ZeroNavigationRail` and `DesktopSidebar`, and reaches Personal data, ID verification behind `KmpKycEnabled`, Wallet, Contacts, Active sessions, Linked devices, Profiles, Apps, and Settings. Every selection passes through the existing unsaved-changes guard."},{"n":5,"text":"Active sessions and Apps render as a 300 list plus detail pane in the Overview host, without pushing a route to view detail. After a session is terminated, the list and detail stay consistent; an empty list collapses to one centered state."},{"n":6,"text":"Signed-in panes, onboarding, QR import, and the data request use the mockup layouts on Desktop while mobile width branches keep working. Docs describe JVM as drawing through `Desktop`. None of the five unconfirmed proposals is claimed as approved."}],"criteriaRaw":null,"nonGoals":["Android, iOS, and WASM redesign.","Unclaimed proposal work: sidebar profile-switcher menu (`profiles-switcher.html`); QR import drag-and-drop and paste (and the strings “Drop an image here” / “or paste a screenshot”); Contacts without Sync (phone note, “Copy invite link”, and their three strings).","Treating “Overview stops being a page” or “data request full window” as approved product decisions.","Fixing the missing WASM actual for `shouldShowBottomNavigation`.","Deleting shared-screen width branches used by tablets and large phones.","Changing `isSelectedTopLevelDestinationRouteId` or the mobile bottom bar.","Data, API, or backend changes."],"constraints":["JVM only. Presentation is chosen by expect/actual; every 36 dp target stays gated on `ZeroPresentation.Desktop`.","WASM remains `Legacy`, so legacy color schemes and renderers stay in the tree.","Feature modules do not depend on each other. `feature/overview` reaches Profiles through a `composeApp` slot.","The sidebar lives in the Overview host, not in `ZeroApp`, because the host already owns selection, leave guards, QR entry, profile info, sync status, and Settings lifetime.","Ship the recorded baselines: open on Personal data with no Overview rail item; identity row (avatar, name, primary email) opens Profiles with no chevron or menu; QR dialog is Select Image only; Contacts Sync stays and keeps the Unsupported error dialog.","Reuse existing Zero components and MVI contracts. Title bar stays native.","Color roles, semantic status colors, cards, grouped lists, fields, badges, copy, approve-only, one QR entry, blurred phrase words, and no device linking carry over from the mobile redesign."],"rollout":"No guarded rollout. No new feature flag. Existing `KmpKycEnabled` continues to hide ID verification.","audit":null,"review":null,"flagAudit":{"verdict":"pass","flagName":"KmpKycEnabled","pattern":"Simple Conditional","issues":"None","checks":[{"passed":true,"text":"Flag defined in codebase"},{"passed":true,"text":"Rollback safe (flag OFF = identical old behavior)"},{"passed":true,"text":"Minimal flag checks (not scattered)"},{"passed":true,"text":"Legacy code untouched (if applicable)"},{"passed":true,"text":"No hybrid states"},{"passed":true,"text":"Default value is false"}]},"unitTestValue":null,"changed":{"count":234,"tests":26,"dirs":[["feature/overview",75],["core/presentation",70],["design/desktop",41],["feature/onboarding",22],["composeApp/src",14],["feature/accounts",5],["docs",3],["feature/apps",2],["config/detekt",1],["design/redesign",1]]},"verdict":null,"qaVerdict":null,"qa":null,"supersededBy":null},"stopped086":{"workflowId":"wfv-20261102-215213-grld","issueKey":"0AC-86","status":"abandoned","current":"unit_test_value_check","startedAt":"2026-11-02T21:52:14Z","finishedAt":"2026-11-03T11:53:21.220Z","intake":"0AC-86 Acceptance criterion: the JVM desktop app follows the new desktop design in ~/workspaces/StudioProjects/ZeroAccount-pr-168/design/desktop (248 sidebar, split Sessions/Apps, dialogs and menus instead of sheets, pointer density, 1280x800 default and 1000x640 minimum). Out of scope: Android/iOS/WASM and unclaimed proposals (profile-switcher menu, QR drag-and-drop/paste, Contacts without Sync). most important - the app should look like the new designs look visually","target":"feat/0AC-86-implement-the-new-desktop-ui","repoRoot":"~/workspaces/StudioProjects/ZeroAccount-pr-168","base":"96364d51","head":"42d8a8d6","reviewMode":"inline","qaSelection":"on","steps":[{"id":"collect_inputs","status":"completed","attempts":1},{"id":"extract_criteria","status":"completed","attempts":1},{"id":"gather_diff","status":"completed","attempts":1},{"id":"feature_flag_audit","status":"completed","attempts":1},{"id":"code_review","status":"completed","attempts":1},{"id":"unit_test_value_check","status":"running","attempts":1},{"id":"completeness_audit","status":"pending","attempts":0},{"id":"qa","status":"pending","attempts":0},{"id":"verdict","status":"pending","attempts":0}],"criteria":[{"n":1,"text":"The JVM desktop app visually matches the new desktop design in `design/desktop` (same layout, density, chrome, and overlay treatment as the mockups)."},{"n":2,"text":"The JVM target draws through `ZeroPresentation.Desktop` with mobile colors, the desktop type scale, and pointer density from `design/desktop/desktop.css` (rows 40 / 56, controls 36, body 14). Android and iOS tokens stay at their current values, and WASM stays `Legacy`."},{"n":3,"text":"On Desktop, form sheets render as a 480 dialog with a footer (main action last; Esc closes; Enter submits); confirmations and `ZeroDialog` render as a 400 dialog; action sheets and short pickers render as a menu at the pointer. Public overlay contracts stay usable by existing callers; other presentations keep drawing sheets."},{"n":4,"text":"The desktop window opens at 1280 × 800 with a 1000 × 640 minimum. One 248 sidebar in the Overview host replaces `ZeroNavigationRail` and `DesktopSidebar`, and reaches Personal data, ID verification behind `KmpKycEnabled`, Wallet, Contacts, Active sessions, Linked devices, Profiles, Apps, and Settings. Every selection passes through the existing unsaved-changes guard."},{"n":5,"text":"Active sessions and Apps render as a 300 list plus detail pane in the Overview host, without pushing a route to view detail. After a session is terminated, the list and detail stay consistent; an empty list collapses to one centered state."},{"n":6,"text":"Signed-in panes, onboarding, QR import, and the data request use the mockup layouts on Desktop while mobile width branches keep working. Docs describe JVM as drawing through `Desktop`. None of the five unconfirmed proposals is claimed as approved."}],"criteriaRaw":null,"nonGoals":["Android, iOS, and WASM redesign.","Unclaimed proposal work: sidebar profile-switcher menu (`profiles-switcher.html`); QR import drag-and-drop and paste (and the strings “Drop an image here” / “or paste a screenshot”); Contacts without Sync (phone note, “Copy invite link”, and their three strings).","Treating “Overview stops being a page” or “data request full window” as approved product decisions.","Fixing the missing WASM actual for `shouldShowBottomNavigation`.","Deleting shared-screen width branches used by tablets and large phones.","Changing `isSelectedTopLevelDestinationRouteId` or the mobile bottom bar.","Data, API, or backend changes."],"constraints":["JVM only. Presentation is chosen by expect/actual; every 36 dp target stays gated on `ZeroPresentation.Desktop`.","WASM remains `Legacy`, so legacy color schemes and renderers stay in the tree.","Feature modules do not depend on each other. `feature/overview` reaches Profiles through a `composeApp` slot.","The sidebar lives in the Overview host, not in `ZeroApp`, because the host already owns selection, leave guards, QR entry, profile info, sync status, and Settings lifetime.","Ship the recorded baselines: open on Personal data with no Overview rail item; identity row (avatar, name, primary email) opens Profiles with no chevron or menu; QR dialog is Select Image only; Contacts Sync stays and keeps the Unsupported error dialog.","Reuse existing Zero components and MVI contracts. Title bar stays native.","Color roles, semantic status colors, cards, grouped lists, fields, badges, copy, approve-only, one QR entry, blurred phrase words, and no device linking carry over from the mobile redesign."],"rollout":"No guarded rollout. No new feature flag. Existing `KmpKycEnabled` continues to hide ID verification.","audit":null,"review":{"verdict":"changes_requested","findings":[{"ref":"F-001","severity":"Major","confidence":"High","location":"feature/overview/src/jvmMain/kotlin/com/zeroaccount/feature/overview/overview/ui/detail/QrImportDialog.kt:76","description":"`QrScannerDialog` constructs `ZeroQrCodeParser` from `core.data` inside the composable instead of the injected domain `QrCodeParser`. Deep-link handling already uses the DI parser; this path uses a second instance with `Clock.System`. A parse-rule or clock change in one path will not apply to the other, and tests cannot substitute the parser. Concrete failure: an expired QR that the injected parser would reject can still navigate from the file-picker path, or the reverse.","specialist":"architecture","locations":["QrImportDialog.kt:76"]},{"ref":"F-002","severity":"Major","confidence":"High","location":"feature/overview/src/jvmMain/kotlin/com/zeroaccount/feature/overview/overview/ui/detail/QrImportDialog.kt:151","description":"`selectAndDecodeQrImage` shows a blocking `JFileChooser` on the Swing EDT and only skips `resume` after Compose cancellation. Dismissing the QR sheet cancels `rememberCoroutineScope` but leaves the chooser open. Choosing a file then does nothing (`continuation.isActive` is false), and a second QR click can open another chooser. Concrete failure: user closes the overlay, picks a file in the leftover dialog, and import never runs.","specialist":"platform-correctness","locations":["QrImportDialog.kt:151"]},{"ref":"F-003","severity":"Major","confidence":"High","location":"composeApp/src/commonMain/kotlin/com/zeroaccount/zeroaccount/deeplink/DeepLinkBridge.kt:428","description":"While a link is `AwaitingDecision`, `drain` skips it and dispatches the next pending URL. That second dispatch calls `requestExternalLeave`, which `cancel()`s the first prompt and reports `false`, dropping the first link. Concrete failure: dirty Personal data, first approval URL shows the unsaved-changes prompt, a second forwarded URL arrives, the first URL is discarded, and the second navigates after a fresh prompt. Two queued approvals while signed in are enough to trigger this.","specialist":"reliability","locations":["DeepLinkBridge.kt:428"]},{"ref":"F-004","severity":"Major","confidence":"High","location":"core/presentation/ui/src/commonMain/kotlin/com/zeroaccount/core/presentation/ui/unsavedchanges/UnsavedChangesPrompt.kt:93","description":"The desktop unsaved-changes prompt uses `ZeroDesktopDialog` without `onEnter`. `ZeroDialog` and non-destructive `ZeroConfirmationBottomSheet` submit on Enter; this prompt is the one sidebar and deep-link leave both use. Focus starts on the title, so the first Enter does nothing and the pending leave stays blocked until the user clicks Save or Discard.","specialist":"ui","locations":["UnsavedChangesPrompt.kt:93"]},{"ref":"F-005","severity":"Major","confidence":"High","location":"feature/overview/src/jvmMain/kotlin/com/zeroaccount/feature/overview/overview/ui/DesktopAvatarAction.kt:112","description":"The camera control that updates the portrait is a 24dp `Surface`. Desktop tokens set a 36dp minimum, and when a portrait already exists the 80dp avatar opens preview instead of update. Pointer and assistive users miss or cannot reliably activate the only update control.","specialist":"ux-accessibility","locations":["DesktopAvatarAction.kt:112"]},{"ref":"F-006","severity":"Major","confidence":"High","location":"composeApp/src/jvmTest/kotlin/com/zeroaccount/zeroaccount/deeplink/DeepLinkBridgeTest.kt:43","description":"The new `AwaitingDecision` / `requestExternalLeave` disposition has no test. Existing tests mock `dispatchAcceptedDeepLink` as always `Consumed` or use a clean form that accepts immediately. The second-link drop in F-003 and a cancelled leave both stay green.","specialist":"testing","locations":["DeepLinkBridgeTest.kt:43"]},{"ref":"F-007","severity":"Minor","confidence":"High","location":"feature/overview/src/jvmMain/kotlin/com/zeroaccount/feature/overview/overview/ui/detail/ActiveSessionsDetailContent.kt:67","description":"Apps selection uses parent-scoped `rememberSplitSelection`; sessions keep `selectedId` in a local `remember`. The two desktop split panes now own selection differently, so a later restore or terminate fix has to be applied twice.","specialist":"code-quality","locations":["ActiveSessionsDetailContent.kt:67"]}]},"flagAudit":{"verdict":"pass","flagName":"KmpKycEnabled","pattern":"Simple Conditional","issues":"None","checks":[{"passed":true,"text":"Flag defined in codebase"},{"passed":true,"text":"Rollback safe (flag OFF = identical old behavior)"},{"passed":true,"text":"Minimal flag checks (not scattered)"},{"passed":true,"text":"Legacy code untouched (if applicable)"},{"passed":true,"text":"No hybrid states"},{"passed":true,"text":"Default value is false"}]},"unitTestValue":null,"changed":{"count":234,"tests":26,"dirs":[["feature/overview",75],["core/presentation",70],["design/desktop",41],["feature/onboarding",22],["composeApp/src",14],["feature/accounts",5],["docs",3],["feature/apps",2],["config/detekt",1],["design/redesign",1]]},"verdict":null,"qaVerdict":null,"qa":null,"supersededBy":null,"staleReason":"no_terminal_before_threshold"},"superseded086":{"workflowId":"wfv-20261102-214452-unmf","issueKey":"0AC-86","status":"abandoned","current":"extract_criteria","startedAt":"2026-11-02T21:44:52Z","finishedAt":"2026-11-02T21:54:23.398Z","intake":"0AC-86 Acceptance criterion: the JVM desktop app follows the new desktop design in ~/workspaces/StudioProjects/ZeroAccount-pr-168/design/desktop (248 sidebar, split Sessions/Apps, dialogs and menus instead of sheets, pointer density, 1280x800 default and 1000x640 minimum). Out of scope: Android/iOS/WASM and unclaimed proposals (profile-switcher menu, QR drag-and-drop/paste, Contacts without Sync).","target":"feat/0AC-86-implement-the-new-desktop-ui","repoRoot":"~/workspaces/StudioProjects/ZeroAccount-pr-168","base":"96364d51","head":"42d8a8d6","reviewMode":"inline","qaSelection":"on","steps":[{"id":"collect_inputs","status":"completed","attempts":1},{"id":"extract_criteria","status":"pending","attempts":1},{"id":"gather_diff","status":"pending","attempts":0},{"id":"feature_flag_audit","status":"pending","attempts":0},{"id":"code_review","status":"pending","attempts":0},{"id":"unit_test_value_check","status":"pending","attempts":0},{"id":"completeness_audit","status":"pending","attempts":0},{"id":"qa","status":"pending","attempts":0},{"id":"verdict","status":"pending","attempts":0}],"criteria":[{"n":1,"text":"The JVM target draws through `ZeroPresentation.Desktop` with mobile colors, the desktop type scale, and pointer density from `design/desktop/desktop.css` (rows 40 / 56, controls 36, body 14). Android and iOS tokens stay at their current values."},{"n":2,"text":"On Desktop, form sheets render as a 480 dialog with a footer (main action last; Esc closes; Enter submits); confirmations render as a 400 dialog; action sheets and short pickers render as a menu at the pointer. Public overlay contracts stay usable by existing callers."},{"n":3,"text":"The desktop window opens at 1280 × 800 with a 1000 × 640 minimum. One 248 sidebar in the Overview host replaces `ZeroNavigationRail` and `DesktopSidebar`, and reaches Personal data, ID verification behind `KmpKycEnabled`, Wallet, Contacts, Active sessions, Linked devices, Profiles, Apps, and Settings. Every selection passes through the existing unsaved-changes guard."},{"n":4,"text":"Active sessions and Apps render as a split view (300 list plus detail pane) in the Overview host, without pushing a route to view detail. The list stays visible; an empty list collapses to one centered state; list and detail stay consistent after a session is terminated."},{"n":5,"text":"Signed-in panes, onboarding, QR import, and the data request use the `design/desktop` mockup layouts on Desktop. Mobile width branches keep working. Docs describe JVM as drawing through Desktop. None of the five unconfirmed proposals is claimed as approved."}],"criteriaRaw":null,"nonGoals":["Android, iOS, and WASM UI changes. WASM stays on the `Legacy` presentation.","Unclaimed proposals: sidebar profile-switcher menu (`profiles-switcher.html`); QR import drag-and-drop and paste; Contacts without Sync (phone note, “Copy invite link”, and their strings).","Treating “Overview stops being a page” or “data request full window” as approved product decisions; those stay unconfirmed.","Fixing the missing WASM actual for `shouldShowBottomNavigation`.","Deleting the 900 dp `BoxWithConstraints` width branches used by tablets and large phones.","Changing `isSelectedTopLevelDestinationRouteId` or the mobile bottom bar.","Data, API, or backend changes."],"constraints":["Presentation is chosen by expect/actual; every 36 dp desktop target stays gated on `ZeroPresentation.Desktop`.","Feature modules do not depend on each other. `feature/overview` reaches Profiles through a slot supplied by `composeApp`.","The sidebar lives in the Overview host, not in `ZeroApp`, because the host already owns selection, the personal-data leave guard, QR entry, profile info, sync status, and the Settings ViewModel lifetime.","Overlay public contracts stay caller-usable; other presentations keep drawing sheets.","Identity-row baseline: avatar, name, and primary email open the Profiles pane, with no chevron or menu; switching stays on Profiles.","QR import baseline: Select Image only.","Contacts baseline: Sync stays and keeps the existing Unsupported error dialog.","Title bar stays native."],"rollout":"No guarded rollout. The spec requires no new feature flag. Existing `KmpKycEnabled` continues to gate ID verification visibility.","audit":null,"review":null,"flagAudit":null,"unitTestValue":null,"changed":null,"verdict":null,"qaVerdict":null,"qa":null,"supersededBy":"wfv-20261102-215213-grld"},"parked5175":{"workflowId":"wfv-20261103-115223-xwef","issueKey":"FP-5175","status":"pending","current":"extract_criteria","startedAt":"2026-11-03T11:52:23Z","finishedAt":null,"intake":"https://linear.app/capmo/issue/FP-5175/android-numeric-keyboard-overlays-mfa-screen","target":"HEAD","repoRoot":"~/workspaces/IdeaProjects/SkillBill-qa-desktop-scope","base":"9ccc5f24","head":"e0608122","reviewMode":"inline","qaSelection":"off","steps":[{"id":"collect_inputs","status":"completed","attempts":1},{"id":"extract_criteria","status":"pending","attempts":1},{"id":"gather_diff","status":"pending","attempts":0},{"id":"feature_flag_audit","status":"pending","attempts":0},{"id":"code_review","status":"pending","attempts":0},{"id":"unit_test_value_check","status":"pending","attempts":0},{"id":"completeness_audit","status":"pending","attempts":0},{"id":"qa","status":"pending","attempts":0},{"id":"verdict","status":"pending","attempts":0}],"criteria":[],"criteriaRaw":"Unable to extract criteria. Both connected Linear tools returned \"Could not find referenced Issue\" for [FP-5175](https://linear.app/capmo/issue/FP-5175/android-numeric-keyboard-overlays-mfa-screen), so its description and comments were unavailable.","nonGoals":[],"constraints":[],"rollout":null,"audit":null,"review":null,"flagAudit":null,"unitTestValue":null,"changed":null,"verdict":null,"qaVerdict":null,"qa":null,"supersededBy":null},"approve091":{"workflowId":"wfv-20261104-140210-p3nd","issueKey":"0AC-91","status":"completed","current":"finish","startedAt":"2026-11-04T14:02:10Z","finishedAt":"2026-11-04T14:09:41Z","intake":"spec:.skill-bill/specs/0AC-91/spec.md","target":"feat/0AC-91-session-timeout","repoRoot":"~/StudioProjects/ZeroAccount-pr-168","base":"1c4e9a0d","head":"8f20b7e1","reviewMode":"inline","qaSelection":"on","steps":[{"id":"collect_inputs","status":"completed","attempts":1},{"id":"extract_criteria","status":"completed","attempts":1},{"id":"gather_diff","status":"completed","attempts":1},{"id":"feature_flag_audit","status":"completed","attempts":1},{"id":"code_review","status":"completed","attempts":1},{"id":"unit_test_value_check","status":"completed","attempts":1},{"id":"completeness_audit","status":"completed","attempts":1},{"id":"qa","status":"completed","attempts":1},{"id":"verdict","status":"completed","attempts":1}],"criteria":[{"n":1,"text":"Sign the user out after 15 minutes without input on every platform."},{"n":2,"text":"Warn 60 seconds before sign-out with a dialog that keeps the session alive."},{"n":3,"text":"Keep unsaved form drafts across the forced sign-out."},{"n":4,"text":"Read the timeout from remote config, falling back to 15 minutes."},{"n":5,"text":"Cover the timer, the warning and the draft restore in tests."}],"criteriaRaw":null,"nonGoals":["Server-side session revocation.","Per-screen timeout overrides."],"constraints":["Reuse the existing `IdleTracker`."],"rollout":"Behind `SessionTimeoutEnabled`, default off.","audit":{"verdict":"all_pass","grades":[{"n":1,"status":"PASS","evidence":["IdleTimeout.kt:40","IdleTimeoutTest.kt:12"],"missing":null,"notFound":null,"locations":[]},{"n":2,"status":"PASS","evidence":["TimeoutWarningDialog.kt:22","OverviewViewModel.kt:118"],"missing":null,"notFound":null,"locations":[]},{"n":3,"status":"PASS","evidence":["DraftStore.kt:51","DraftStoreTest.kt:30"],"missing":null,"notFound":null,"locations":[]},{"n":4,"status":"PASS","evidence":["SessionTimeoutConfig.kt:9","RemoteFlags.kt:64"],"missing":null,"notFound":null,"locations":[]},{"n":5,"status":"PASS","evidence":["IdleTimeoutTest.kt:12","OverviewViewModelTest.kt:88","DraftStoreTest.kt:30"],"missing":null,"notFound":null,"locations":[]}]},"review":{"verdict":"approved_with_fixes","findings":[{"ref":"F-001","severity":"Minor","confidence":"High","location":"feature/overview/src/commonMain/kotlin/TimeoutWarningDialog.kt:41","description":"The countdown text updates every second without a live-region politeness setting, so screen readers announce all 60 updates. Announce only the first warning and the last 10 seconds.","specialist":"ux-accessibility","locations":["TimeoutWarningDialog.kt:41"]},{"ref":"F-002","severity":"Minor","confidence":"Medium","location":"core/session/src/commonMain/kotlin/IdleTimeout.kt:73","description":"`restart()` and `reset()` do the same work under two names. Keep one.","specialist":"code-quality","locations":["IdleTimeout.kt:73"]}]},"flagAudit":{"verdict":"pass","flagName":"SessionTimeoutEnabled","pattern":"Simple Conditional","issues":"None","checks":[{"passed":true,"text":"Flag defined in codebase"},{"passed":true,"text":"Rollback safe (flag OFF = identical old behavior)"},{"passed":true,"text":"Minimal flag checks (not scattered)"},{"passed":true,"text":"Default value is false"}]},"unitTestValue":{"overall":"Valuable","summary":"Three focused tests that each catch a realistic regression.","rows":[{"area":"Idle timer","verdict":"Valuable","confidence":"High","files":["IdleTimeoutTest.kt:12"],"why":"Uses virtual time to prove sign-out fires at exactly 15 minutes and not before.","action":"Keep.","locations":["IdleTimeoutTest.kt:12"]},{"area":"Draft restore","verdict":"Valuable","confidence":"High","files":["DraftStoreTest.kt:30"],"why":"Catches drafts lost across a forced sign-out.","action":"Keep.","locations":["DraftStoreTest.kt:30"]},{"area":"Warning dialog state","verdict":"Weak","confidence":"Medium","files":["OverviewViewModelTest.kt:88"],"why":"Asserts a boolean flips but not that \"Stay signed in\" resets the timer.","action":"Rewrite to assert the timer restarts.","locations":["OverviewViewModelTest.kt:88"]}],"keep":"Idle timer and draft restore tests.","rewrite":"The warning dialog state test.","delete":"None.","missing":"Remote config value below the 1-minute floor."},"changed":{"count":9,"tests":3,"dirs":[["core/session",3],["feature/overview",3],["core/data",2],["core/config",1]]},"verdict":{"verdict":"APPROVE WITH FIXES","summary":"This change signs users out after 15 minutes without input on every platform, warns 60 seconds before with a dialog that keeps the session alive, and restores unsaved form drafts after signing in again. The timeout comes from remote config behind `SessionTimeoutEnabled`, which defaults to off.","findings":["**APPROVE WITH FIXES** — All five criteria are met with file evidence. Two minor review findings and one open QA finding remain; none blocks the criteria.","Warning: the verdict said APPROVE while the QA report has open findings; recorded APPROVE WITH FIXES so the QA findings stay in the consolidated findings."]},"qaVerdict":"findings_open","qa":{"runKey":"verify:wfv-20261104-140210-p3nd","outcome":"findings","reportText":"Verdict: findings_open\n\nI ran the desktop app from the verify checkout with an isolated home and a 20-second idle timeout from remote config, then let sessions expire with and without an open form.\n\n## Coverage\n\n- desktop: findings_open\n- android: skipped [reason: empty_scope]\n- backend: skipped [reason: empty_scope] [backend: none]\n\n## Assertions\n\n- [passed] Idle sign-out: With no input the app signed out at 20 s and returned to the sign-in screen. Evidence: ~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/idle-signout.png\n- [passed] Stay signed in: Choosing Stay signed in on the warning dialog restarted the timer, and sign-out came 20 s later. Evidence: ~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/stay-signed-in.log\n- [passed] Draft restore: A half-filled contact form came back after signing in again. Evidence: ~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/draft-restore.png\n- [failed] Warning dialog focus: Typing in a text field when the warning opened lost the typed character. See QA-1.\n- [not_exercised] Remote config floor: The QA config service rejected values below 10 s, so the 1-minute floor could not be driven from config.\n\n### QA-1 Warning dialog takes focus from the field being typed in\n\nSteps: Open Personal data, start typing in First name, and stop until the warning dialog opens at 10 s, then type one more character.\n\nExpected: The field keeps focus or the typed character reaches it; the dialog does not swallow input.\n\nObserved: The dialog took focus and the character was lost. Closing the dialog left the field without focus. Evidence: ~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/focus-steal.mp4, ~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/focus-steal.log\n\n## Isolation\n\nDesktop module `:composeApp`, run from the verify checkout at 8f20b7e with home ~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/home. Remote config came from a local stub serving `session_timeout_seconds=20`.\n\n## Temporary edits\n\nNone.\n\n## Cleanup\n\nStopped the desktop app and the config stub. No resources retained.","findings":[{"id":"QA-1","title":"Warning dialog takes focus from the field being typed in","fixed":false,"observed":"The dialog took focus and the character was lost. Closing the dialog left the field without focus.","expected":"The field keeps focus or the typed character reaches it; the dialog does not swallow input.","steps":"Open Personal data, start typing in First name, and stop until the warning dialog opens at 10 s, then type one more character.","evidencePaths":["~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/focus-steal.mp4","~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/focus-steal.log"],"locations":[]}],"details":{"assertions":[{"outcome":"passed","title":"Idle sign-out","detail":"With no input the app signed out at 20 s and returned to the sign-in screen.","evidencePaths":["~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/idle-signout.png"]},{"outcome":"passed","title":"Stay signed in","detail":"Choosing Stay signed in on the warning dialog restarted the timer, and sign-out came 20 s later.","evidencePaths":["~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/stay-signed-in.log"]},{"outcome":"passed","title":"Draft restore","detail":"A half-filled contact form came back after signing in again.","evidencePaths":["~/.skill-bill/qa/ZeroAccount-3be1/qa-7c21d9e4/draft-restore.png"]},{"outcome":"failed","title":"Warning dialog focus","detail":"Typing in a text field when the warning opened lost the typed character. See QA-1.","evidencePaths":[]},{"outcome":"not_exercised","title":"Remote config floor","detail":"The QA config service rejected values below 10 s, so the 1-minute floor could not be driven from config.","evidencePaths":[]}],"coverage":[{"kind":"desktop","verdict":"findings_open","reason":null},{"kind":"android","verdict":"skipped","reason":"empty_scope"},{"kind":"backend","verdict":"skipped","reason":"empty_scope"}]}},"supersededBy":null}};
  /* Sample: parked at extract_criteria with four criteria waiting for the operator. */
  VERIFY.parked442 = {
    workflowId: 'wfv-20261107-093105-c4ts', issueKey: 'SKILL-442', status: 'pending', current: 'extract_criteria', startedAt: '2026-11-07T09:31:05Z', finishedAt: null,
    intake: 'spec:.skill-bill/specs/SKILL-442/spec.md', target: 'feat/SKILL-442-cross-criterion-audit', repoRoot: '~/IdeaProjects/skill-bill', base: '4be0d113', head: '9c2f7e10', reviewMode: 'inline', qaSelection: 'off',
    steps: ['collect_inputs', 'extract_criteria', 'gather_diff', 'feature_flag_audit', 'code_review', 'unit_test_value_check', 'completeness_audit', 'qa', 'verdict'].map((id, i) => ({ id, status: i === 0 ? 'completed' : 'pending', attempts: i < 2 ? 1 : 0 })),
    criteria: [
      { n: 1, text: 'Grade each acceptance criterion against the whole diff, not only the files its own subtask touched.' },
      { n: 2, text: 'Report a criterion as Partial when its evidence covers some but not all of the cases it names.' },
      { n: 3, text: 'Name the missing case in one sentence for every Partial or Missing grade.' },
      { n: 4, text: 'Keep the existing receipt format so older runs still parse.' },
    ],
    criteriaRaw: null, nonGoals: ['Grading non-goals.', 'Changing the review step.'], constraints: ['Reuse CompletenessAuditReceipt.'], rollout: null,
    audit: null, review: null, flagAudit: null, unitTestValue: null, changed: null, verdict: null, qaVerdict: null, qa: null, supersededBy: null,
  };

  /* Standalone plan runs (design/plan-detail; desktop DetailModels StandalonePlanProgress, planActivity, planOutputLines).
   * start: the run's first second of the day. stage: the current stage (0 preplan, 1 plan, 2 plan_subtasks, 3 finished).
   * times: seconds spent in each stage. elapsed: seconds since start. Events, one per stream-json tool_use, assistant text
   * block or runtime phase event: [offset s, stage, kind, verb, text, subtask, duration, more files]; kinds read, write,
   * search, cmd, note, mcp, phase, err. states: one letter per subtask (q queued, w writing, s saved, b blocked).
   * Briefings follow the launch briefing layout: run setup (layer 1, the same in every stage), the preplan digest handed to
   * plan (layer 2), and one raw briefing per stage with its size (null when the runtime did not save one). An agent without
   * a structured stream (noStream) records only phase changes and saved files; `output` is its plain transcript. */
  const hms = (h, m, s) => h * 3600 + m * 60 + s;
  const planEv = (a) => ({ at: a[0], stage: a[1], kind: a[2], verb: a[3], text: a[4], sub: a[5] || null, dur: a[6] || null, kids: a[7] || null });
  const planRun = (o) => Object.assign({ noStream: false, stuck: false, blocked: null, wave: null, states: null, output: null, finished: null }, o, { events: o.events.filter((e) => e[0] <= o.elapsed).map(planEv) });
  const digestPart = (head, preview, items, tag) => ({ head, preview, items, tag: tag || null });

  const B513 = 'specs/SKILL-513-live-plan-progress/';
  const SUBS_513 = [['Project the planning snapshot for standalone plan runs', 'planning-snapshot'], ['Expose the decomposition manifest once plan settles', 'manifest-read'], ['Keep the latest output line per planning unit', 'unit-output-line'], ['Map planning stages and units into the desktop model', 'desktop-model'], ['Render the stage strip and live status line', 'stage-strip'], ['Render the spec bundle with per-unit states', 'bundle-rows'], ['Open saved specs and implement from the bundle', 'open-and-implement']];
  const spec513 = (id) => `${B513}spec_subtask_${id}_${SUBS_513[id - 1][1]}.md`;
  const PLAN_513 = planRun({
    bundle: B513, start: hms(9, 37, 12), stage: 2, times: [184, 352, 162], elapsed: 698, states: 'sswwwqq', wave: 'Wave 1 of 2 · 5 at a time',
    subtasks: SUBS_513, agents: ['claude · sonnet', 'claude · opus', 'claude · opus'],
    run: { size: 'MEDIUM', ceremony: ['preplan full', 'review branch diff', 'audit per criterion'], mandates: [], acs: [
      'The detail screen shows the current planning stage, with the time spent in each stage.',
      'While a plan runs, the screen shows the latest agent step and how long ago the agent last produced output.',
      'Each subtask spec appears with its state (queued, writing, saved, blocked) as soon as the manifest is saved.',
      'Saved specs open from the detail screen, and Implement turns on when every spec is saved.'] },
    digest: [
      digestPart('Mode and sizing', 'MEDIUM, decomposed. One intake covers four outcomes across runtime, contracts and desktop.', ['Runtime: project the planning snapshot for standalone plans and keep the latest line per unit.', 'Contracts: expose stage times, the manifest and unit states on the work detail read.', 'Desktop: map the new fields and render them. Specs must stay independent of each other.']),
      digestPart('Current code (verified by reading)', '7 files read: PlanFanOutStrategy, UnitAttributedOutputSink, GoalPlanningStatusSnapshot, DetailScreen, …', ['`PlanFanOutStrategy` runs subtask specs in waves of 5.', '`UnitAttributedOutputSink` already prefixes each line with `[subtask N]`.', 'Standalone plan runs get `planning = null`, so the desktop sees nothing until the report lands.'], 'code'),
      digestPart('Risks', 'Subtasks 2 and 4 both touch the planning fields in RuntimeWorkMapper.', ['Two subtasks may rewrite the same mapper fields. Keep the field list in subtask 4 only.', 'A new column needs a named migration and a DesktopReadSchema bump.'], 'risk'),
      digestPart('Not read', 'ChatComposer, ShellFrame and the terminal resize UI were not opened.', ['No tracker tools were called.', 'The plan may need one targeted read of RuntimeWorkMapper to settle field ownership.'], 'unread'),
    ],
    briefs: [{ stage: 'preplan', size: 3.4, layers: 'run setup only' }, { stage: 'plan', size: 19.6, layers: 'run setup + preplan digest' }, { stage: 'plan_subtasks', size: null, layers: 'the runtime did not save one' }],
    events: [
      [0, 0, 'phase', 'Phase started', ''],
      [6, 0, 'read', 'Read', 'intake/SKILL-513-live-plan-progress.md', null, '0.1s'],
      [19, 0, 'search', 'Searched', '"planningProgress" in desktop/core · 9 matches', null, '0.4s'],
      [31, 0, 'read', 'Read', 'desktop/feature/detail/…/DetailScreen.kt', null, '0.1s', ['desktop/feature/detail/…/RunActions.kt', 'desktop/feature/detail/…/PlanStatus.kt', 'desktop/core/data/…/RuntimeWorkMapper.kt']],
      [58, 0, 'read', 'Read', 'runtime-engine/…/plan/PlanFanOutStrategy.kt', null, '0.1s'],
      [95, 0, 'cmd', 'Ran', 'git log --oneline -8 -- runtime-engine/…/plan', null, '0.3s'],
      [124, 0, 'read', 'Read', 'runtime-domain/…/GoalPlanningStatusSnapshot.kt', null, '0.1s'],
      [150, 0, 'note', '', 'Standalone plan runs get planning = null today, so the desktop has nothing to show until the report lands. The snapshot, the manifest and the [subtask N] prefix can each be read on their own.'],
      [182, 0, 'mcp', 'Settled', 'feature_task_phase_complete · decomposed', null, '0.2s'],
      [184, 0, 'phase', 'Phase finished', ''],
      [185, 1, 'phase', 'Phase started', ''],
      [201, 1, 'read', 'Read', 'memory/specs-must-be-independent.md', null, '0.1s'],
      [262, 1, 'read', 'Read', 'runtime-infra/…/UnitAttributedOutputSink.kt', null, '0.1s'],
      [407, 1, 'note', '', 'Draft: 7 subtasks, 3 of them independent of the rest of the plan.'],
      [432, 1, 'write', 'Wrote', B513 + 'spec.md', null, '0.1s'],
      [446, 1, 'search', 'Searched', '"bundleFiles" in desktop · 4 matches', null, '0.3s'],
      [492, 1, 'write', 'Wrote', B513 + 'decomposition-manifest.yaml', null, '0.1s'],
      [520, 1, 'note', '', 'Subtask 4 maps the new fields; 5 to 7 render them. Each spec stands on the current tree.'],
      [536, 1, 'phase', 'Phase finished', ''],
      [537, 2, 'phase', 'Phase started', 'wave 1 · subtasks 1 to 5'],
      [545, 2, 'read', 'Read', 'runtime-domain/…/GoalPlanningStatusSnapshot.kt', 1, '0.1s'],
      [560, 2, 'read', 'Read', 'runtime-engine/…/DecompositionManifestReader.kt', 2, '0.1s'],
      [571, 2, 'read', 'Read', 'runtime-infra/…/UnitAttributedOutputSink.kt', 3, '0.1s'],
      [580, 2, 'read', 'Read', 'core/data/…/RuntimeWorkMapper.kt', 4, '0.1s'],
      [592, 2, 'read', 'Read', 'feature/detail/…/PlanStatus.kt', 5, '0.1s'],
      [645, 2, 'write', 'Wrote', spec513(1), 1, '0.1s'],
      [661, 2, 'cmd', 'Ran', './gradlew :desktop:core:data:compileKotlinJvm --dry-run', 3, '4.1s'],
      [678, 2, 'write', 'Wrote', spec513(2), 2, '0.1s'],
      [690, 2, 'note', '', 'Draft: stage strip replaces the pipeline pills', 5],
      [695, 2, 'note', '', 'Write acceptance criteria for the [subtask N] line buffer', 3],
    ],
  });

  const BATL = 'specs/ATL-410-saved-cart-reminders/';
  const PLAN_ATL = planRun({
    bundle: BATL, start: hms(9, 46, 40), stage: 0, times: [128, null, null], elapsed: 128, subtasks: null, agents: ['claude · sonnet', 'claude · opus', 'claude · opus'],
    run: { size: 'SMALL', ceremony: ['preplan light', 'review branch diff', 'audit per criterion'], mandates: [], acs: ['A shopper with a saved cart gets one reminder email after 24 hours.', 'The reminder links back to the saved cart with every item still in it.', 'A shopper who checks out before the reminder gets no email.'] },
    digest: [], briefs: [{ stage: 'preplan', size: 2.6, layers: 'run setup only' }, { stage: 'plan', size: null, layers: '' }, { stage: 'plan_subtasks', size: null, layers: '' }],
    events: [
      [0, 0, 'phase', 'Phase started', ''],
      [5, 0, 'read', 'Read', 'intake/ATL-410-saved-cart-reminders.md', null, '0.1s'],
      [17, 0, 'search', 'Searched', '"savedCart" in src · 14 matches', null, '0.4s'],
      [34, 0, 'read', 'Read', 'src/cart/saved/SavedCartStore.ts', null, '0.1s', ['src/cart/saved/SavedCartBanner.tsx', 'src/notifications/scheduler.ts']],
      [71, 0, 'cmd', 'Ran', 'git log --oneline -6 -- src/cart', null, '0.3s'],
      [118, 0, 'read', 'Read', 'src/notifications/EmailTemplates.ts', null, '0.1s'],
      [124, 0, 'note', '', 'Reminders can ride on the existing notification scheduler; the email template set has no cart template yet.'],
    ],
  });

  const BNEWS = 'specs/NEWS-180-read-later-sync/';
  const PLAN_NEWS = planRun({
    bundle: BNEWS, start: hms(9, 39, 10), stage: 1, times: [204, 335, null], elapsed: 540, subtasks: null, noStream: true, agents: ['junie', 'junie', 'junie'],
    run: { size: 'MEDIUM', ceremony: ['preplan full', 'review branch diff', 'audit per criterion'], mandates: ['Keep the offline queue format readable by the 4.x app.'], acs: ['Articles saved for later on one device show on the others within a minute.', 'Saving offline queues the change and syncs it when the device is back online.', 'Removing an article on one device removes it everywhere.'] },
    digest: [
      digestPart('Mode and sizing', 'MEDIUM, decomposed. Sync, offline queue and the reader list.', ['Sync: one endpoint pair for read-later items.', 'Offline: reuse the existing write queue.', 'Reader: badge and list changes only.']),
      digestPart('Current code (verified by reading)', '4 files read: SyncClient, OfflineQueue, ReaderListScreen, ArticleStore', ['`OfflineQueue` already batches writes and retries with backoff.', '`SyncClient` has no read-later route yet.'], 'code'),
      digestPart('Risks', 'Queue format changes would break the 4.x app.', ['Add a new record type instead of changing the existing one.'], 'risk'),
      digestPart('Not read', 'The settings screens and the widget were not opened.', ['No tracker tools were called.'], 'unread'),
    ],
    briefs: [{ stage: 'preplan', size: 3.0, layers: 'run setup only' }, { stage: 'plan', size: 11.8, layers: 'run setup + preplan digest' }, { stage: 'plan_subtasks', size: null, layers: '' }],
    output: [[2, 'Reading intake/NEWS-180-read-later-sync.md'], [40, 'Looking at the sync module and the offline queue'], [118, 'The offline queue already batches writes. Read-later can reuse it.'], [203, 'Preplan finished: decomposed, 3 outcomes'], [206, 'Planning the subtasks'], [470, 'Saved spec.md'], [512, 'Working out the order of the subtasks']],
    events: [
      [0, 0, 'phase', 'Phase started', ''],
      [204, 0, 'phase', 'Phase finished', ''],
      [205, 1, 'phase', 'Phase started', ''],
      [470, 1, 'write', 'Wrote', BNEWS + 'spec.md', null, '0.1s'],
    ],
  });

  const BPAY = 'specs/PAY-91-refund-status-webhooks/';
  const SUBS_PAY = [['Verify webhook signatures from the processor', 'verify-signatures'], ['Map processor refund states to ours', 'map-states'], ['Persist refund status transitions', 'persist-transitions'], ['Notify the customer when a refund settles', 'notify-settled'], ['Backfill open refunds', 'backfill']];
  const specPay = (id) => `${BPAY}spec_subtask_${id}_${SUBS_PAY[id - 1][1]}.md`;
  const PLAN_PAY = planRun({
    bundle: BPAY, start: hms(9, 31, 5), stage: 2, times: [150, 260, 469], elapsed: 880, stuck: true, states: 'sswwq', wave: 'Wave 1 of 2 · 4 at a time', subtasks: SUBS_PAY, agents: ['claude · sonnet', 'claude · opus', 'claude · opus'],
    run: { size: 'MEDIUM', ceremony: ['preplan full', 'review branch diff', 'audit per criterion'], mandates: [], acs: ['A refund webhook with a bad signature is rejected and logged.', 'Every processor refund state maps to one of ours.', 'The customer gets one email when a refund settles.', 'Open refunds from before the change get their current state.'] },
    digest: [
      digestPart('Mode and sizing', 'MEDIUM, decomposed. Webhooks, refund states, notifications and a backfill.', ['Webhooks: verify and route refund events.', 'States: one mapping table.', 'Notify and backfill stand on their own.']),
      digestPart('Current code (verified by reading)', '5 files read: ProcessorWebhookController, RefundRepository, RefundState, CustomerMailer, …', ['Refund states live in two enums today.', '`ProcessorWebhookController` drops events it cannot map.'], 'code'),
      digestPart('Risks', 'Two enums for one state; the mapping must pick one.', ['Keep `RefundState` and retire `ProcessorRefundStatus`.'], 'risk'),
      digestPart('Not read', 'The admin refund screens were not opened.', ['No tracker tools were called.'], 'unread'),
    ],
    briefs: [{ stage: 'preplan', size: 3.1, layers: 'run setup only' }, { stage: 'plan', size: 14.2, layers: 'run setup + preplan digest' }, { stage: 'plan_subtasks', size: 6.4, layers: 'run setup + subtask slice' }],
    events: [
      [0, 0, 'phase', 'Phase started', ''],
      [4, 0, 'read', 'Read', 'intake/PAY-91-refund-status-webhooks.md', null, '0.1s'],
      [22, 0, 'search', 'Searched', '"refund_status" in src · 11 matches', null, '0.3s'],
      [48, 0, 'read', 'Read', 'src/webhooks/ProcessorWebhookController.kt', null, '0.1s', ['src/refunds/RefundRepository.kt', 'src/refunds/RefundState.kt']],
      [96, 0, 'note', '', 'Refund states live in two enums today. The webhook handler drops events it cannot map.'],
      [149, 0, 'mcp', 'Settled', 'feature_task_phase_complete · decomposed', null, '0.2s'],
      [150, 0, 'phase', 'Phase finished', ''],
      [151, 1, 'phase', 'Phase started', ''],
      [232, 1, 'write', 'Wrote', BPAY + 'spec.md', null, '0.1s'],
      [396, 1, 'write', 'Wrote', BPAY + 'decomposition-manifest.yaml', null, '0.1s'],
      [410, 1, 'phase', 'Phase finished', ''],
      [411, 2, 'phase', 'Phase started', 'wave 1 · subtasks 1 to 4'],
      [418, 2, 'read', 'Read', 'src/webhooks/SignatureVerifier.kt', 1, '0.1s'],
      [425, 2, 'read', 'Read', 'src/refunds/RefundState.kt', 2, '0.1s'],
      [431, 2, 'read', 'Read', 'src/refunds/RefundRepository.kt', 3, '0.1s'],
      [440, 2, 'read', 'Read', 'src/notify/CustomerMailer.kt', 4, '0.1s'],
      [488, 2, 'write', 'Wrote', specPay(1), 1, '0.1s'],
      [512, 2, 'write', 'Wrote', specPay(2), 2, '0.1s'],
      [530, 2, 'cmd', 'Ran', './gradlew :refunds:compileKotlin --dry-run', 3, '3.8s'],
      [541, 2, 'note', '', 'Draft: transitions table keyed by refund id and processor event id', 3],
    ],
  });

  const BRT = 'specs/RT-97-lease-reaper-metrics/';
  const SUBS_RT = [['Count reaped leases per owner', 'count-reaped'], ['Expose reaper counters on the metrics port', 'metrics-port'], ['Record reaper latency', 'reaper-latency'], ['Alert when leases pile up', 'pile-up-alert']];
  const specRt = (id) => `${BRT}spec_subtask_${id}_${SUBS_RT[id - 1][1]}.md`;
  const PLAN_RT = planRun({
    bundle: BRT, start: hms(8, 52, 30), stage: 2, times: [119, 201, 168], elapsed: 489, states: 'ssbq', wave: 'Wave 1 of 2 · next wave waits', subtasks: SUBS_RT, agents: ['codex · gpt-6.1-sol', 'codex · gpt-6.1-sol', 'codex · gpt-6.1-sol'],
    blocked: { ids: [3], reason: 'Subtasks 2 and 3 both add counters to `LeaseMetrics`, and the manifest says 3 depends only on 1.', unit: 'Overlaps subtask 2 on LeaseMetrics counters' },
    run: { size: 'SMALL', ceremony: ['preplan light', 'review branch diff', 'audit per criterion'], mandates: [], acs: ['The metrics port reports how many leases the reaper removed, per owner.', 'Reaper latency shows as a histogram.', 'An alert fires when more than 50 leases wait to be reaped.'] },
    digest: [
      digestPart('Mode and sizing', 'SMALL, decomposed. Counters, latency and one alert.', ['Counters and latency share LeaseMetrics.', 'The alert reads the counters only.']),
      digestPart('Current code (verified by reading)', '3 files read: LeaseReaper, LeaseMetrics, MetricsPort', ['`LeaseMetrics` has one gauge today.', '`LeaseReaper` logs each reap without counting it.'], 'code'),
      digestPart('Risks', 'Subtasks that add counters may collide in LeaseMetrics.', ['Give one subtask ownership of LeaseMetrics.'], 'risk'),
      digestPart('Not read', 'The alerting rules repository was not opened.', ['No tracker tools were called.'], 'unread'),
    ],
    briefs: [{ stage: 'preplan', size: 2.8, layers: 'run setup only' }, { stage: 'plan', size: 9.7, layers: 'run setup + preplan digest' }, { stage: 'plan_subtasks', size: null, layers: 'the runtime did not save one' }],
    events: [
      [0, 0, 'phase', 'Phase started', ''],
      [6, 0, 'read', 'Read', 'intake/RT-97-lease-reaper-metrics.md', null, '0.1s'],
      [30, 0, 'read', 'Read', 'runtime-infra/…/LeaseReaper.kt', null, '0.1s', ['runtime-infra/…/LeaseMetrics.kt', 'runtime-ports/…/MetricsPort.kt']],
      [61, 0, 'search', 'Searched', '"reaped" in runtime-infra · 6 matches', null, '0.2s'],
      [118, 0, 'mcp', 'Settled', 'feature_task_phase_complete · decomposed', null, '0.2s'],
      [119, 0, 'phase', 'Phase finished', ''],
      [120, 1, 'phase', 'Phase started', ''],
      [214, 1, 'write', 'Wrote', BRT + 'spec.md', null, '0.1s'],
      [301, 1, 'write', 'Wrote', BRT + 'decomposition-manifest.yaml', null, '0.1s'],
      [320, 1, 'phase', 'Phase finished', ''],
      [321, 2, 'phase', 'Phase started', 'wave 1 · subtasks 1 to 3'],
      [330, 2, 'read', 'Read', 'runtime-infra/…/LeaseReaper.kt', 1, '0.1s'],
      [336, 2, 'read', 'Read', 'runtime-infra/…/LeaseMetrics.kt', 2, '0.1s'],
      [342, 2, 'read', 'Read', 'runtime-infra/…/LeaseMetrics.kt', 3, '0.1s'],
      [402, 2, 'write', 'Wrote', specRt(1), 1, '0.1s'],
      [431, 2, 'write', 'Wrote', specRt(2), 2, '0.1s'],
      [477, 2, 'note', '', 'Subtasks 2 and 3 both add counters to LeaseMetrics. The manifest says 3 depends only on 1.', 3],
      [489, 2, 'err', 'Blocked', 'overlaps subtask 2 on LeaseMetrics counters', 3],
    ],
  });

  const B511 = 'specs/SKILL-511-spec-bundle-file-order/';
  const SUBS_511 = [['Order bundle files in the detail read', 'order-read'], ['Compare digit runs as numbers', 'natural-order'], ['Show goal spec files in the details pane', 'pane-files'], ['Open subtask specs from the inspector', 'inspector-open']];
  const spec511 = (id) => `${B511}spec_subtask_${id}_${SUBS_511[id - 1][1]}.md`;
  const PLAN_511 = planRun({
    bundle: B511, start: hms(8, 41, 2), stage: 3, times: [141, 209, 222], elapsed: 572, states: 'ssss', finished: '08:50', subtasks: SUBS_511, agents: ['claude · sonnet', 'claude · opus', 'claude · opus'],
    run: { size: 'SMALL', ceremony: ['preplan light', 'review branch diff', 'audit per criterion'], mandates: [], acs: ['Bundle files list the manifest, spec.md, then the rest by name.', 'spec_subtask_10.md sorts after spec_subtask_9.md.', 'Subtask specs open from the subtask inspector.'] },
    digest: [
      digestPart('Mode and sizing', 'SMALL, decomposed. Ordering and two places that list files.', ['Ordering is one shared helper.', 'The pane and the inspector call it.']),
      digestPart('Current code (verified by reading)', '4 files read: SpecBundleFileList, DetailPane, SubtaskInspector, NaturalOrder', ['`SpecBundleFileList` keeps the runtime order.', 'There is no natural sort helper in core/ui.'], 'code'),
      digestPart('Risks', 'The tray lists files too and may need the same order.', ['Leave the tray alone; it lists no bundle files today.'], 'risk'),
      digestPart('Not read', 'The tray and the chat screens were not opened.', ['No tracker tools were called.'], 'unread'),
    ],
    briefs: [{ stage: 'preplan', size: 2.4, layers: 'run setup only' }, { stage: 'plan', size: 8.9, layers: 'run setup + preplan digest' }, { stage: 'plan_subtasks', size: 5.1, layers: 'run setup + subtask slice' }],
    events: [
      [0, 0, 'phase', 'Phase started', ''],
      [5, 0, 'read', 'Read', 'intake/SKILL-511-spec-bundle-file-order.md', null, '0.1s'],
      [26, 0, 'read', 'Read', 'desktop/feature/detail/…/SpecBundleFileList.kt', null, '0.1s', ['desktop/feature/detail/…/DetailPane.kt']],
      [52, 0, 'search', 'Searched', '"bundleFiles" in desktop · 4 matches', null, '0.3s'],
      [140, 0, 'mcp', 'Settled', 'feature_task_phase_complete · decomposed', null, '0.2s'],
      [141, 0, 'phase', 'Phase finished', ''],
      [142, 1, 'phase', 'Phase started', ''],
      [260, 1, 'write', 'Wrote', B511 + 'spec.md', null, '0.1s'],
      [338, 1, 'write', 'Wrote', B511 + 'decomposition-manifest.yaml', null, '0.1s'],
      [350, 1, 'phase', 'Phase finished', ''],
      [351, 2, 'phase', 'Phase started', 'wave 1 · subtasks 1 to 4'],
      [360, 2, 'read', 'Read', 'desktop/feature/detail/…/SpecBundleFileList.kt', 1, '0.1s'],
      [366, 2, 'read', 'Read', 'desktop/core/ui/…/NaturalOrder.kt', 2, '0.1s'],
      [372, 2, 'read', 'Read', 'desktop/feature/detail/…/DetailPane.kt', 3, '0.1s'],
      [379, 2, 'read', 'Read', 'desktop/feature/detail/…/SubtaskInspector.kt', 4, '0.1s'],
      [455, 2, 'write', 'Wrote', spec511(2), 2, '0.1s'],
      [481, 2, 'write', 'Wrote', spec511(1), 1, '0.1s'],
      [530, 2, 'write', 'Wrote', spec511(4), 4, '0.1s'],
      [566, 2, 'write', 'Wrote', spec511(3), 3, '0.1s'],
      [570, 2, 'mcp', 'Settled', 'feature_task_phase_complete · 4 specs saved', null, '0.2s'],
      [572, 2, 'phase', 'Phase finished', ''],
    ],
  });
  const planItem = (o) => Object.assign({ kind: 'phase', phase: 'plan', branch: null, fresh: true, live: o.lifecycle === 'active', runOptions: { canLaunch: false, canCancel: o.lifecycle === 'active' } }, o, { goalMs: o.planRun.elapsed * 1000 });

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
    { id: 'ph-sb-validation', kind: 'phase', phase: 'validation', repo: 'skill-bill', lifecycle: 'active', section: 'phases', title: 'Validation on fix/ide-status-readonly-lease', branch: 'fix/ide-status-readonly-lease', step: 'validate', summary: 'Running Tray check and distributable', goalMs: 10 * M + 55000, live: true, fresh: true, model: 'claude-sonnet-5-5', effort: 'high', agent: 'claude', invocation: 'inv-5c028fed5cc5adf53a7a6453-7d1', started: '09:38', updated: '09:48:40', startedAgo: 660, runOptions: { canLaunch: false, canCancel: true }, steps: [['validate', 'now']], valRun: Object.assign({ day: 'Today' }, VAL.running) },
    { id: 'ph-rk-validation', kind: 'phase', phase: 'validation', repo: 'runtime-kotlin', lifecycle: 'active', section: 'phases', title: 'Validation on feat/RT-94-lease-reaper', branch: 'feat/RT-94-lease-reaper', step: 'validate', summary: 'The validate child is active and producing output', goalMs: 5 * M + 12000, live: true, fresh: true, agent: 'junie', invocation: 'inv-9a0e3c7714b2d6f08e51ac3d-2f6', started: '09:43', updated: '09:48:12', startedAgo: 312, runOptions: { canLaunch: false, canCancel: true }, steps: [['validate', 'now']], valRun: Object.assign({ day: 'Today' }, VAL.noChecks) },
    { id: 'ph-sb-val-limit', kind: 'phase', phase: 'validation', repo: 'skill-bill', lifecycle: 'blocked', section: 'needs', title: 'Validation on ' + BRANCH_447, branch: BRANCH_447, step: 'validate', summary: 'Paused at the agent provider\'s usage limit; resumes at validate', goalMs: 1576888, live: false, fresh: true, model: 'gpt-6.1-terra', effort: 'high', agent: 'codex', invocation: 'inv-c31d70f5a2e94b0d6f13c8e7-406', started: '14:02', updated: 'Yesterday 14:28', startedAgo: 70400, finishedAgo: 68800, runOptions: { canLaunch: true, canCancel: true, launchLabel: 'Resume', agentId: 'codex' }, steps: [['validate', 'wait']], valRun: Object.assign({ day: 'Yesterday' }, VAL.limit) },
    { id: 'ph-atl-pr', kind: 'phase', phase: 'pr', repo: 'atlas-web', lifecycle: 'active', section: 'phases', title: 'pr on feat/ATL-402-checkout-retry', branch: 'feat/ATL-402-checkout-retry', step: 'monitor_fix', exec: '1/3', summary: 'Fixing 1 failing check: lint', goalMs: 21 * M + 8000, live: true, fresh: true, model: 'claude-sonnet-5-5', effort: 'medium', agent: 'claude', invocation: 'inv-7f3c6534839588bb13e22a54-a91', started: '09:27', updated: '09:48:02', startedAgo: 1380, runOptions: { canLaunch: false, canCancel: true }, steps: [['commit_push', 'done'], ['pr', 'done'], ['monitor', 'done'], ['monitor_fix', 'now']] },
    { id: 'ph-pay-qa', kind: 'phase', phase: 'qa', repo: 'payments-svc', lifecycle: 'failed', section: 'needs', title: 'qa on PAY-72-passkey-enrollment', branch: 'PAY-72-passkey-enrollment', step: 'qa', summary: 'QA stopped before it wrote a report', goalMs: 7 * M + 40000, live: false, fresh: true, model: 'claude-sonnet-5-5', effort: 'medium', agent: 'claude', invocation: 'inv-91c4e7a2b05d38f6c1e9a7d4-3b8', started: '09:20', updated: '09:27:52', startedAgo: 1860, finishedAgo: 1400, runOptions: { canLaunch: true, canCancel: false, launchLabel: 'Run again', agentId: 'claude' }, steps: [['qa', 'fail']], qa: QA.unfinished },
    planItem({ id: 'ph-sb-plan', issue: 'SKILL-513', repo: 'skill-bill', lifecycle: 'active', section: 'phases', title: 'Live plan progress on the detail screen', step: 'plan_subtasks', summary: 'Writing specs for subtasks 3 to 5. 2 of 7 saved.', model: 'claude-opus-5-5', effort: 'high', agent: 'claude', invocation: 'inv-6b3e0c91a7d24f5880c1e3b2-5a1', started: '09:37', updated: '09:48:50', startedAgo: 698, planRun: PLAN_513 }),
    planItem({ id: 'ph-atl-plan', issue: 'ATL-410', repo: 'atlas-web', lifecycle: 'active', section: 'phases', title: 'Saved cart reminders', step: 'preplan', summary: 'Reading the repository and the intake before it splits the work.', model: 'claude-sonnet-5-5', effort: 'high', agent: 'claude', invocation: 'inv-0e7d4a2b9c13f6e58d20a7c4-c34', started: '09:46', updated: '09:48:48', startedAgo: 128, planRun: PLAN_ATL }),
    planItem({ id: 'ph-news-plan', issue: 'NEWS-180', repo: 'newsreader', lifecycle: 'active', section: 'phases', title: 'Read-later sync across devices', step: 'plan', summary: 'Splitting the work into subtasks.', model: null, effort: null, agent: 'junie', invocation: 'inv-1d4e5b886988a1005708520a-b20', started: '09:39', updated: '09:48:30', startedAgo: 540, planRun: PLAN_NEWS }),
    planItem({ id: 'ph-pay-plan', issue: 'PAY-91', repo: 'payments-svc', lifecycle: 'active', section: 'phases', title: 'Refund status webhooks', step: 'plan_subtasks', summary: 'No agent output for 5m 39s while writing specs for subtasks 3 and 4.', model: 'claude-opus-5-5', effort: 'high', agent: 'claude', invocation: 'inv-93c1d07e5a2b48f6d1c0e9a7-77e', started: '09:31', updated: '09:40:06', startedAgo: 880, planRun: PLAN_PAY }),
    planItem({ id: 'ph-rk-plan', issue: 'RT-97', repo: 'runtime-kotlin', lifecycle: 'blocked', section: 'needs', title: 'Lease reaper metrics', step: 'plan_subtasks', summary: 'Planning stopped at subtask 3. 2 of 4 saved, 1 not started.', reason: 'plan_subtask_blocked: Subtasks 2 and 3 both add counters to LeaseMetrics, and the manifest says 3 depends only on 1.', model: 'gpt-6.1-sol', effort: 'high', agent: 'codex', invocation: 'inv-4a8e2d6c10b93f7e5d1a2c08-9d2', started: '08:52', updated: '09:00:39', startedAgo: 3380, finishedAgo: 2890, runOptions: { canLaunch: true, canCancel: true, launchLabel: 'Resume', agentId: 'codex' }, planRun: PLAN_RT }),

    /* Operations: verify, update-check, release, unit-test-value-check, feature-guard, feature-guard-cleanup, pr-review-fix */
    { id: 'op-verify', kind: 'operation', op: 'verify', issue: '0AC-86', repo: 'zero-account', lifecycle: 'active', section: 'operations', title: 'Verify 0AC-86', branch: 'feat/0AC-86-implement-the-new-desktop-ui', step: 'code_review', summary: 'Step 5 of 9 · Code review', goalMs: 4 * M + 12000, live: true, fresh: true, model: 'claude-opus-5-5', effort: 'high', agent: 'claude', invocation: 'inv-8e21aeb36f6e3a2506c6e2da-f40', workflow: VERIFY.running086.workflowId, started: '09:45', updated: '09:49:20', startedAgo: 252, runOptions: { canLaunch: false, canCancel: true }, steps: [], verifyRun: VERIFY.running086 },
    { id: 'op-verify-confirm', kind: 'operation', op: 'verify', issue: 'SKILL-442', repo: 'skill-bill', lifecycle: 'blocked', section: 'needs', title: 'Verify SKILL-442', branch: 'feat/SKILL-442-cross-criterion-audit', step: 'extract_criteria', summary: 'Awaiting confirmation · 4 criteria to confirm', goalMs: 70000, live: false, fresh: true, model: 'claude-opus-5-5', effort: 'high', agent: 'claude', invocation: 'inv-c4ts9310a5e2f7b8d6c1e0a4-1b2', workflow: VERIFY.parked442.workflowId, started: '09:31', updated: '09:32:15', startedAgo: 1100, runOptions: { canLaunch: false, canCancel: true }, steps: [], verifyRun: VERIFY.parked442 },
    { id: 'op-verify-nocrit', kind: 'operation', op: 'verify', issue: 'FP-5175', repo: 'skill-bill', lifecycle: 'blocked', section: 'needs', title: 'Verify FP-5175', branch: null, step: 'extract_criteria', summary: 'Awaiting confirmation · no criteria extracted', goalMs: 52000, live: false, fresh: true, model: 'claude-opus-5-5', effort: 'high', agent: 'claude', invocation: 'inv-xwef1152a3d6c0e9b7f4128a-6d0', workflow: VERIFY.parked5175.workflowId, started: '08:52', updated: '08:53:15', startedAgo: 3400, runOptions: { canLaunch: false, canCancel: true }, steps: [], verifyRun: VERIFY.parked5175 },
    { id: 'op-prfix', kind: 'operation', op: 'pr-review-fix', repo: 'atlas-web', lifecycle: 'active', section: 'operations', title: 'pr-review-fix on #318', branch: 'feat/ATL-399-cart-badge', step: 'pr-review-fix', summary: 'Proposing fixes for 4 review comments', goalMs: 2 * M, live: true, fresh: true, model: 'claude-sonnet-5-5', effort: 'medium', agent: 'claude', invocation: 'inv-3b7a1dc070603fe1ae258162-0c1', started: '09:47', updated: '09:49:01', startedAgo: 180, runOptions: { canLaunch: false, canCancel: true }, steps: [] },

    /* Finished today */
    planItem({ id: 'ph-sb-plan-done', issue: 'SKILL-511', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '08:50', title: 'Spec bundle file order in the detail', step: 'plan_subtasks', summary: 'Plan ready. 4 subtasks, all specs saved.', model: 'claude-opus-5-5', effort: 'high', agent: 'claude', invocation: 'inv-2f71c08d4e9a3b6c5d7e10a2-e11', started: '08:41', updated: '08:50:34', startedAgo: 4060, finishedAgo: 3488, runOptions: { canLaunch: false, canCancel: false }, planRun: PLAN_511 }),
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
      /* GoalQaResult: two problems found and fixed in one fix attempt, five tagged checks passed. */
      goalQa: { runKey: 'goal-qa-SKILL-416', outcome: 'passed', fixAttempts: 1,
        reportText: 'Verdict: passed\n\nI exercised standalone phase records on the work board, in the tray and in History. Two findings were reported and fixed in one fix attempt.\n\n## Coverage\n\n- desktop: passed\n- android: skipped [reason: empty_scope]\n- backend: skipped [reason: empty_scope]\n- web: skipped [reason: empty_scope]',
        findings: [
          { id: 'QA-1', title: 'Phase rows show a stale clock after the phase finishes', fixed: true, observed: 'the row kept counting for one refresh after the phase settled.', evidencePaths: [QA_SAMPLE + 'stale-clock.png'] },
          { id: 'QA-2', title: 'History filter drops operations', fixed: true, observed: 'the Operations tab listed nothing while two operations had finished.', evidencePaths: [QA_SAMPLE + 'history-operations.png'] },
        ],
        details: { reportPath: QA_SAMPLE + 'report.md', invocationDirectory: QA_SAMPLE, assertions: sampleChecks(['Phase row on the board', 'Phase row in the tray', 'Clock stops at finish', 'History lists phases', 'History lists operations']), coverage: [cov('desktop', 'passed'), ...outOfScope(['android', 'backend', 'web'])] } },
      assessment: { state: 'COMPLETED', round: 1, fixRan: true, findings: [
        { ref: 'GA-002', severity: 'Major', path: 'BoardQuery.kt', line: 88, scenario: 'A phase finished at midnight is counted in both days.', verification: 'CONFIRMED', state: 'FIXED' },
        { ref: 'GA-004', severity: 'Minor', path: 'TrayRows.kt', line: 41, scenario: 'Phase id is truncated before the branch name.', verification: 'REFUTED', state: 'REFUTED' },
      ] },
      executions: [{ inv: 'inv-0f2eb990d8f4d5aabc0bf3da-a17', life: 'completed', start: '05:39', startAgo: 4 * 3600 + 12 * 60, end: '07:46', endAgo: 2 * 3600 + 5 * 60, result: 'goal_completion' }],
      specBundle: ['spec.md', 'spec_subtask_1.md', 'spec_subtask_2.md', 'spec_subtask_3.md', 'traceability.md', 'decomposition-manifest.yaml'],
    },
    { id: 'ph-sb-validation-done', kind: 'phase', phase: 'validation', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '07:31', title: 'Validation on ' + BRANCH_447, branch: BRANCH_447, step: 'validate', summary: 'Passed · 8 checks, 3 failed attempts fixed', goalMs: 1105098, live: false, fresh: true, agent: 'claude', model: 'claude-sonnet-5-5', effort: 'high', invocation: 'inv-77aab57ef65d29f5758c8f12-19b', started: '07:12', updated: '07:31:00', startedAgo: 8700, finishedAgo: 8340, runOptions: { canLaunch: false, canCancel: false }, steps: [['validate', 'done']], valRun: Object.assign({ day: 'Today', wallMs: 1105000 }, VAL.passed) },
    { id: 'op-verify-fixes', kind: 'operation', op: 'verify', issue: '0AC-91', repo: 'zero-account', lifecycle: 'terminal', section: 'finished', finishedAt: '07:24', title: 'Verify 0AC-91', branch: 'feat/0AC-91-session-timeout', step: 'verdict', summary: 'APPROVE WITH FIXES · 5 of 5 criteria met', goalMs: 7 * M + 31000, live: false, fresh: true, agent: 'claude', model: 'claude-opus-5-5', effort: 'high', invocation: 'inv-p3nd14029b6e1d0c7a5f3e82-7c4', workflow: VERIFY.approve091.workflowId, started: '07:16', updated: '07:24:41', startedAgo: 9300, finishedAgo: 8850, runOptions: { canLaunch: false, canCancel: false }, steps: [], verifyRun: VERIFY.approve091 },
    { id: 'ph-sb-qa', kind: 'phase', phase: 'qa', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '07:12', title: 'qa on feat/SKILL-498-desktop-temp-root', branch: 'feat/SKILL-498-desktop-temp-root', step: 'qa', summary: 'QA verdict: attempted · 1 problem open', goalMs: 23 * M, live: false, fresh: true, agent: 'codex', model: 'gpt-6-luna', effort: 'high', invocation: 'inv-4f7e0edff13e4805b84f21c5-e54', started: '06:49', updated: '07:12:40', startedAgo: 9900, finishedAgo: 8520, runOptions: { canLaunch: false, canCancel: false }, steps: [['qa', 'done']], qa: QA.open },
    { id: 'ph-atl-qa', kind: 'phase', phase: 'qa', repo: 'atlas-web', lifecycle: 'terminal', section: 'finished', finishedAt: '06:58', title: 'qa on feat/ATL-399-cart-badge', branch: 'feat/ATL-399-cart-badge', step: 'qa', summary: 'QA verdict: passed · 5 checks', goalMs: 14 * M, live: false, fresh: true, agent: 'claude', model: 'claude-sonnet-5-5', effort: 'medium', invocation: 'inv-c09d08f805689da93c189464-2e6', started: '06:44', updated: '06:58:20', startedAgo: 11160, finishedAgo: 10320, runOptions: { canLaunch: false, canCancel: false }, steps: [['qa', 'done']], qa: QA.clean },
    { id: 'op-update', kind: 'operation', op: 'update-check', repo: 'skill-bill', lifecycle: 'terminal', section: 'finished', finishedAt: '06:12', title: 'update-check', branch: null, step: 'update-check', summary: 'Completed · no newer release', goalMs: 4000, live: false, fresh: true, agent: null, invocation: 'inv-11fe3acd2aaab73b37d001bc-0a2', started: '06:12', updated: '06:12:04', startedAgo: 13080, finishedAgo: 13080, runOptions: { canLaunch: false, canCancel: false }, steps: [] },
  ];

  /* History: finished within the retention window (HistoryFilter ALL | GOALS | PHASES | OPERATIONS). `item` carries detail
   * facts for entries that are no longer on the board (the detail reads them through Screens.lookup). */
  const QA_MODEL = { claude: 'claude-sonnet-5-5', codex: 'gpt-6-luna' };
  const qaPast = (branch, agent, qa) => ({ branch, agent, model: QA_MODEL[agent], step: 'qa', steps: [['qa', 'done']], qa });
  /* Finished validation and Verify runs that left the board: `started` is the day and time, `finished` the time. */
  /* `ago` (seconds since it started) gives Activity its started and finished rows. */
  const valPast = (branch, agent, started, finished, wallMs, run, ago) => ({ title: 'Validation on ' + branch, branch, agent, model: QA_MODEL[agent], step: 'validate', steps: [['validate', 'done']], started: started.split(' ').pop(), finishedAt: finished, startedAgo: ago, finishedAgo: ago - Math.round(wallMs / 1000), valRun: Object.assign({ day: started.split(' ').slice(0, -1).join(' '), wallMs }, run) });
  const verifyPast = (run, started, o) => Object.assign({ issue: run.issueKey, op: 'verify', branch: run.target, agent: 'claude', model: 'claude-opus-5-5', step: run.current, started, workflow: run.workflowId, verifyRun: run }, o || {});
  const history = [
    { id: 'SKILL-416', kind: 'goal', repo: 'skill-bill', lifecycle: 'terminal', title: 'Standalone phase records on the work board', ms: 2 * H + 4 * M, at: '07:46' },
    { id: 'op-verify-fixes', kind: 'operation', label: 'verify', repo: 'zero-account', lifecycle: 'terminal', title: 'Verify 0AC-91', ms: 7 * M + 31000, at: '07:24' },
    { id: 'ph-sb-validation-done', kind: 'phase', label: 'validation', repo: 'skill-bill', lifecycle: 'terminal', title: BRANCH_447, ms: 1105098, at: '07:31' },
    { id: 'ph-sb-qa', kind: 'phase', label: 'qa', repo: 'skill-bill', lifecycle: 'terminal', title: 'feat/SKILL-498-desktop-temp-root', ms: 23 * M, at: '07:12' },
    { id: 'ph-atl-qa', kind: 'phase', label: 'qa', repo: 'atlas-web', lifecycle: 'terminal', title: 'feat/ATL-399-cart-badge', ms: 14 * M, at: '06:58' },
    { id: 'ATL-399', kind: 'goal', repo: 'atlas-web', lifecycle: 'terminal', title: 'Cart badge counts', ms: 1 * H + 12 * M, at: '06:40' },
    { id: 'ph-sb-qa-gaps', kind: 'phase', label: 'qa', repo: 'skill-bill', lifecycle: 'terminal', title: 'feat/SKILL-505-tray-icon-files', ms: 19 * M, at: '06:31', item: qaPast('feat/SKILL-505-tray-icon-files', 'claude', QA.incomplete) },
    { id: 'op-update', kind: 'operation', label: 'update-check', repo: 'skill-bill', lifecycle: 'terminal', title: 'No newer release', ms: 4000, at: '06:12' },
    { id: 'ph-sb-qa-left', kind: 'phase', label: 'qa', repo: 'skill-bill', lifecycle: 'terminal', title: 'feat/SKILL-489-retention-setting', ms: 11 * M, at: '05:02', item: qaPast('feat/SKILL-489-retention-setting', 'claude', QA.left) },
    { id: 'DSN-30', kind: 'goal', repo: 'design-tokens', lifecycle: 'failed', title: 'Token naming lint', ms: 9 * M, at: '03:48' },
    { id: 'ph-sb-qa-docs', kind: 'phase', label: 'qa', repo: 'skill-bill', lifecycle: 'terminal', title: 'docs/phase-instructions', ms: 2 * M, at: '02:15', item: qaPast('docs/phase-instructions', 'codex', QA.skipped) },
    { id: 'PAY-18', kind: 'goal', repo: 'payments-svc', lifecycle: 'canceled', title: 'Refund ledger export', ms: 26 * M, at: 'Yesterday 22:10' },
    { id: 'ph-sb-qa-legacy', kind: 'phase', label: 'qa', repo: 'skill-bill', lifecycle: 'terminal', title: 'feat/SKILL-470-benchmark-temp-dirs', ms: 21 * M, at: 'Yesterday 19:40', item: qaPast('feat/SKILL-470-benchmark-temp-dirs', 'codex', QA.legacy) },
    { id: 'op-release', kind: 'operation', label: 'release', repo: 'skill-bill', lifecycle: 'terminal', title: 'Release v0.9.4', ms: 7 * M, at: 'Yesterday 18:02' },
    { id: 'op-verify-stopped', kind: 'operation', label: 'verify', repo: 'zero-account', lifecycle: 'canceled', title: 'Verify 0AC-86', ms: 14 * H + M, at: 'Nov 3 11:53', item: verifyPast(VERIFY.stopped086, 'Nov 2 21:52', { runOptions: { canLaunch: true, canCancel: false, launchLabel: 'Resume', agentId: 'claude' } }) },
    { id: 'op-verify-superseded', kind: 'operation', label: 'verify', repo: 'zero-account', lifecycle: 'canceled', title: 'Verify 0AC-86', ms: 9 * M + 31000, at: 'Nov 2 21:54', item: verifyPast(VERIFY.superseded086, 'Nov 2 21:44') },
    { id: 'ph-sb-val-411', kind: 'phase', label: 'validation', repo: 'skill-bill', lifecycle: 'canceled', title: BRANCH_463, ms: 616, at: 'Nov 2 20:19', item: Object.assign(valPast(BRANCH_463, 'claude', 'Nov 2 20:18', '20:19', 82000, VAL.canceled, 264000), { reason: 'Canceled by you.' }) },
    { id: 'ph-sb-val-413', kind: 'phase', label: 'validation', repo: 'skill-bill', lifecycle: 'terminal', title: BRANCH_447, ms: 1868369, at: 'Oct 30 20:41', item: valPast(BRANCH_447, 'claude', 'Oct 30 20:10', '20:41', 1868000, VAL.gaps, 517200) },
    { id: 'op-verify-085', kind: 'operation', label: 'verify', repo: 'zero-account', lifecycle: 'terminal', title: 'Verify 0AC-85', ms: 4 * M + 52000, at: 'Oct 29 08:11', item: verifyPast(VERIFY.done085, 'Oct 29 08:06') },
    { id: 'ph-za-validation', kind: 'phase', label: 'validation', repo: 'zero-account', lifecycle: 'terminal', title: BRANCH_085, ms: 843171, at: 'Oct 29 14:19', item: valPast(BRANCH_085, 'codex', 'Oct 29 14:05', '14:19', 843000, VAL.blocked, 638700) },
  ];

  /* The same runs as #413 and ph-za-validation, recorded before live checks: reachable by route, not listed in History. */
  const valRoute = (id, repo, branch, agent, started, finished, wallMs, activeMs, run, ago) => Object.assign({ id, kind: 'phase', phase: 'validation', repo, lifecycle: 'terminal', section: 'history', goalMs: activeMs, live: false, fresh: true, runOptions: { canLaunch: false, canCancel: false } }, valPast(branch, agent, started, finished, wallMs, run, ago));
  valRoutes.push(
    valRoute('ph-sb-val-repairs', 'skill-bill', BRANCH_447, 'claude', 'Oct 29 14:41', '14:59', 1105000, 1105098, VAL.repairs, 637800),
    valRoute('ph-za-val-report', 'zero-account', BRANCH_085, 'codex', 'Oct 29 14:05', '14:19', 843000, 843171, VAL.blockedReportOnly, 638700));

  /* Spec pane (design/specs-panel; desktop feature/specs). Projects from specBrowserService.projects(): `checkout` false has
   * no checkout on this machine, `problem` failed to read. Bundles from snapshot(): `status` is the tree status the spec
   * files give, `run` the SpecBundleRunState the control read gives (not_started, running, pausing, paused, blocked,
   * canceled, complete, unavailable), `ever` whether run history exists (everStarted), `agent` the run options' agent,
   * `reason` the control's unavailableReason, `folder` the store folder ('done' for finished specs). subState per subtask. */
  const projects = [
    { id: 'l-f2ea', name: 'skill-bill', root: '~/IdeaProjects/skill-bill', store: '~/.skill-bill/projects/l-f2ea…/specs', checkout: true },
    { id: 'l-9c1b', name: 'newsreader', root: '~/dev/newsreader', store: '~/.skill-bill/projects/l-9c1b…/specs', checkout: true },
    { id: 'l-51d0', name: 'payments-svc', root: null, store: '~/.skill-bill/projects/l-51d0…/specs', checkout: false },
    { id: 'l-a7e2', name: 'atlas-web', root: '~/dev/atlas-web', store: '~/.skill-bill/projects/l-a7e2…/specs', checkout: true, problem: 'The spec store could not be read. Check that ~/.skill-bill/projects/l-a7e2…/specs exists and is readable, then retry.' },
  ];
  const spec = (key, title, run, o) => Object.assign({ key, title, run, project: 'l-f2ea', folder: null, ever: run !== 'not_started' && run !== 'unavailable', agent: null, fav: false, reason: null, subtasks: [], subState: [] }, o || {});
  const specs = [
    spec('SKILL-442', 'Cross-criterion audit, test-value probes and goal assessment', 'running', { status: 'in_progress', agent: 'claude', fav: true, subtasks: ['Interaction scenario authoring and catalog', 'Interaction trace audit mode', 'Accepted-criterion re-check', 'Validate test probes'], subState: ['complete', 'complete', 'in_progress', 'pending'] }),
    spec('SKILL-417', 'Keep IDE status on the live goal phase after children finish', 'pausing', { status: 'in_progress', agent: 'codex', subtasks: ['Standalone status store keeps the workflow execution', 'IDE status selects the live goal phase', 'Both IDE plugins accept the post-child parent snapshot'], subState: ['complete', 'in_progress', 'pending'] }),
    spec('SKILL-468', 'Goal worktree cleanup after merge', 'paused', { status: 'in_progress', agent: 'codex', subtasks: ['Detect a merged branch', 'Remove the goal worktree', 'Prune checkpoint refs'], subState: ['complete', 'paused', 'pending'] }),
    spec('SKILL-438', 'Retry budget for flaky validation commands', 'blocked', { status: 'blocked', agent: 'claude', subtasks: ['Flake classifier', 'Retry budget config', 'Validation runner retries', 'Report retries in history'], subState: ['complete', 'blocked', 'pending', 'pending'] }),
    spec('SKILL-451', 'Linear progress sync through the connector', 'unavailable', { status: 'pending', reason: 'Run controls are unavailable: the runtime could not read this goal.', subtasks: ['Connector progress events', 'Issue state mapping', 'Sync retries'], subState: ['pending', 'pending', 'pending'] }),
    spec('SKILL-444', 'Spec browser pane in the desktop app', 'not_started', { status: 'pending', subtasks: ['Spec tree projection', 'Run actions toolbar', 'Purge flow', 'Pane states'], subState: ['pending', 'pending', 'pending', 'pending'] }),
    spec('SKILL-482', 'Two column repository branch picker', 'not_started', { status: 'pending', ever: true, subtasks: ['Picker layout', 'Branch loading'], subState: ['pending', 'pending'] }),
    spec('SKILL-401', 'Agent profile picker', 'canceled', { status: 'canceled', agent: 'claude', subtasks: ['Profiles', 'Picker'], subState: ['complete', 'skipped'] }),
    spec('SKILL-446', 'Move write history to goal finalization', 'complete', { status: 'complete', folder: 'done', agent: 'claude', subtasks: ['Finalization hook', 'History writer', 'Remove the per-phase write'], subState: ['complete', 'complete', 'complete'] }),
    spec('SKILL-443', 'Decompose terminal decoder reads a cleared terminal as absent', 'complete', { status: 'complete', folder: 'done', agent: 'codex', subtasks: ['Decoder states', 'Absent terminal reads'], subState: ['complete', 'complete'] }),
    spec('SKILL-420', 'Backend and web QA phase', 'complete', { status: 'complete', folder: 'done', agent: 'claude', subtasks: ['QA targets', 'Backend journeys', 'Web journeys', 'Report'], subState: ['complete', 'complete', 'complete', 'complete'] }),
  ];

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

  /* ---------- New task (desktop feature/chat newtask) ---------- */
  /* Branches per repository, shaped like RepositoryBranches: the default branch, then local and remote branches with the age
   * of their last commit. The picker lists these repositories in this order. */
  const LONG_BRANCH = 'PAY-86-implement-the-new-desktop-app-onboarding-flow';
  const REPO_BRANCHES = {
    'skill-bill': { parent: '~/IdeaProjects', defaultBranch: 'main', local: [['feat/SKILL-464-descriptive-titles', '2h'], ['feat/SKILL-480-fix-audit-recovery-path', '1d'], ['feat/SKILL-479-goal-worktrees', '1d'], ['feat/SKILL-474-run-board-filters', '3d'], ['fix/agent-detection-timeout', '5d']], remote: [['origin/feat/SKILL-471-spec-inventory', '6d'], ['origin/release/0.3', '2w']] },
    'newsreader': { parent: '~/dev', defaultBranch: 'develop', local: [['feature/offline-sync', '4h'], ['feature/reader-themes', '2d'], ['fix/article-parser-crash', '6d']], remote: [['origin/main', '1w'], ['origin/release/2.14', '3w']] },
    'atlas-web': { parent: '~/dev', defaultBranch: 'main', local: [['feature/csv-export', '1d'], ['fix/history-pagination', '4d']], remote: [['origin/release/3.2', '2w']] },
    'payments-svc': { parent: '~/dev', defaultBranch: 'main', local: [[LONG_BRANCH, '3h'], ['PAY-79-session-refresh', '2d'], ['PAY-72-passkey-enrollment', '4d'], ['chore/gradle-9-upgrade', '1w']], remote: [['origin/PAY-68-audit-log-export', '2w'], ['origin/release/1.8', '1mo']] },
    'design-tokens': { parent: '~/dev', defaultBranch: 'main', local: [['fix/contrast-export', '1d'], ['feature/motion-tokens', '1w']], remote: [] },
  };

  /* Saved chats, shaped like ChatHistoryEntry: agent, age in minutes (updatedAt), title, the latest lines (role operator or
   * agent; none for a session read from an agent CLI, whose lines load on resume), screenshots the first message started with,
   * and newTask { repositoryRoot, baseBranch }. Newest first. */
  const SHOT = (n) => `Screenshot_20261106_${n}.png`;
  const said = (you, agent) => [{ role: 'operator', text: you }, { role: 'agent', text: agent }];
  const followUp = [{ role: 'operator', text: 'ok, can we fix that and also show which repo each chat is from' }, { role: 'agent', text: 'Yes. I would cap the height, add a filter field and put the repository and branch under each title.' }];
  const saved = (id, agent, m, title, repo, branch, lines, o) => Object.assign({ sessionId: 'ses-' + id, agent, updatedMin: m, title, lines, images: [], newTask: { repositoryRoot: repo, baseBranch: branch } }, o || {});
  const CHAT_HISTORY = [
    saved('specs-slow', 'codex', 1, 'Specs panel is slow to open the first time', 'skill-bill', null, said('when i open Specs panel (at least first time after app start) it takes a few seconds to show anything', 'The first open reads every bundle under the specs root on the UI dispatcher. With 510 bundles that is about 2.8 seconds on your machine.')),
    saved('qa-header', 'codex', 1, 'QA results header while the run is still going', 'skill-bill', 'feat/SKILL-504-redesign-qa-results-view', said('this is how the QA results header looks when the run is still going', 'The header reads the run state once at open, so it keeps showing Running after the last step. It should follow the run.'), { images: [SHOT('080102')] }),
    saved('qa-mock', 'claude', 2, 'Does the QA UI match yesterday’s mock?', 'skill-bill', null, said('can you check if current UI for QA matches the mock we did yesterday?', 'Mostly. The findings list matches. The header still shows the old step counter and the empty state has the old wording.').concat(followUp)),
    saved('verify-park', 'claude', 4, 'When Verify should park for confirmation', 'skill-bill', null, [
      { role: 'operator', text: 'i would like to talk about Verify operation and when it should park' },
      { role: 'agent', text: 'Verify parks at awaiting_confirmation after it lists the criteria it could not prove. Right now it also parks when every criterion passed, which is one click with nothing to decide.' },
      { role: 'operator', text: 'yes, that second park feels pointless' },
      { role: 'agent', text: 'Then the rule would be: park only when at least one criterion is unproven or contradicted. A clean pass finishes on its own and records the audit.' },
    ]),
    saved('env-unavailable', 'claude', 24, 'Verdict incomplete: environment_unavailable', 'skill-bill', null, said('why did the verdict come back incomplete with environment_unavailable?', 'The QA target app was not running when the verdict step started, so no scenario could run. The verdict records that instead of failing.')),
    saved('shot-only', 'claude', 40, SHOT('072033'), 'skill-bill', null, [{ role: 'operator', text: '' }, { role: 'agent', text: 'This is the run board with two goals in Needs you. Both show the same block reason, which comes from the shared incident.' }], { images: [SHOT('072033')] }),
    saved('branch-twice', 'claude', 53, 'Same branch listed twice in the picker', 'skill-bill', 'feat/SKILL-507-new-task-chats-across-repos', said('both of these show the same branch twice', 'The local branch and its remote tracking branch both pass the filter. The picker should drop a remote branch that a local branch already tracks.').concat(followUp), { images: [SHOT('070915'), SHOT('070921')] }),
    saved('bg-service', 'codex', 130, 'Background service skillbill-run-47b03a11 stopped', 'skill-bill', null, said('the background service skillbill-run-47b03a11 stopped, why?', 'The supervisor killed it after the lease expired. The lease was not renewed because the host slept for 40 minutes.')),
    saved('recent-incomplete', 'claude', 190, 'Recent chats feel incomplete', 'skill-bill', null, said('recent chats feel incomplete', 'The list only reads chats saved by this app. Sessions started in the agent CLI are not merged in yet.'), { images: [SHOT('121804')] }),
    saved('plan-subtasks', 'claude', 262, 'Plan detail misses the subtask list', 'skill-bill', null, said('plan detail misses the subtask list', 'The plan detail reads the manifest before the subtask specs are written, so the list is empty until you reopen it.'), { images: [SHOT('081402')] }),
    saved('token-cost', 'claude', 600, 'Token cost per phase on the run page', 'skill-bill', null, [
      { role: 'operator', text: 'what do you think about showing the token cost per phase on the run page?' },
      { role: 'agent', text: 'The data is there: each phase record already stores input and output tokens. A small column on the phase list would cost little. I would show totals per phase and keep per-call detail in the inspector.', actions: ['Run phase:goal', 'Use SKILL-514'] },
    ]),
    saved('planning-context', 'claude', 660, 'Planning phase context budget', 'skill-bill', null, said('I want to discuss Planning phase and how much context it gets', 'Planning gets the intake, AGENTS.md and the files it asks for. It does not get earlier specs unless the intake names them.')),
    saved('restart-483', 'claude', 1380, 'Restart SKILL-483', 'skill-bill', null, said('lets restart SKILL-483', 'SKILL-483 is paused after subtask 2. Resume picks up at subtask 3 with the same branch.')),
    saved('ram', 'claude', 1500, 'Have we fixed RAM overuse with many runs open?', 'skill-bill', null, said('have we fixed skill bill ram overuse when many runs are open?', 'Partly. The board no longer keeps a transcript per run in memory. Open run pages still do.')),
    saved('board-counts', 'codex', 1560, 'Board column counts are off by one', 'skill-bill', null, said('the board column counts are off by one', 'The count includes the archived run that is filtered out of the list. The fix is to count after the filter.'), { images: [SHOT('091200')] }),
    saved('tws-1', 'codex', 1620, 'TWS-1 shows monitoring after the PR merged', 'skill-bill', null, said('TWS-1 shows as monitoring, but the PR was merged an hour ago', 'The monitor step polls CI only. A merge without a new CI run never ends it.')),
    saved('cli-wmha', 'codex', 1700, 'wftr-20261104-162307-wmha', 'skill-bill', null, []),
    saved('selection-bar', 'codex', 1720, 'Specs panel selection bar design', 'skill-bill', 'feat/SKILL-503-specs-panel-selection-bar', said('i want to implement new design for the specs panel selection bar', 'I read the mock. The bar replaces the toolbar while specs are selected and holds Start, Pause and Cancel for the selection.')),
    saved('cli-audit', 'codex', 1800, 'Exact replay of the failed audit step', 'skill-bill', null, []),
    saved('cli-agent-picker', 'cursor', 1850, 'The live desktop app flow is blocked on the agent picker', 'skill-bill', null, []),
    saved('offline-banner', 'claude', 30, 'Offline banner covers the toolbar', 'newsreader', 'feature/offline-sync', said('why is the offline banner on top of the toolbar', 'The banner is drawn in the root Box after the Scaffold, so it sits above the TopAppBar. Moving it into the Scaffold topBar slot under the app bar fixes the overlap.'), { images: [SHOT('231400')] }),
    saved('reader-spacing', 'codex', 545, 'Spacing in the reader settings sheet', 'newsreader', 'feature/reader-themes', said('spacing in the reader settings sheet', 'The sheet uses 24px padding where the rest of the app uses 16px.'), { images: [SHOT('230012')] }),
    saved('passkey', 'claude', 80, 'Passkey enrollment screen design', 'payments-svc', 'PAY-72-passkey-enrollment', said('i want to implement this new design for the passkey enrollment screen', 'I read the mock. Three states: intro, system prompt open, success. The current screen has no success state, it navigates straight away.')),
    saved('full-qa', 'claude', 670, 'Full QA of this branch before the PR', 'payments-svc', LONG_BRANCH, said('need to run full QA of this branch before i open the PR', 'QA needs the sandbox gateway running. Start it and I will run the payment scenarios.')),
    saved('gradle-9', 'codex', 1400, 'Migrate the build to Gradle 9', 'payments-svc', 'chore/gradle-9-upgrade', said('migrate the build to gradle 9', 'Two plugins block it: the old protobuf plugin and a custom task that uses Project at execution time.')),
    saved('contrast', 'cursor', 900, 'Dark palette contrast in the token export', 'design-tokens', 'fix/contrast-export', said('the dark palette fails contrast after export', 'The export rounds alpha before it blends, so faint text lands at 3.9:1. Blending first keeps it at 4.6:1.')),
    saved('csv-export', 'codex', 1500, 'Add CSV export to the history screen', 'atlas-web', 'feature/csv-export', said('add CSV export to the history screen', 'The history query already returns every column. Export can stream it without a new endpoint.')),
  ].sort((a, b) => a.updatedMin - b.updatedMin);

  /* The window's open chats in tab order (NewTaskChats): `session` names the saved chat it continues. One replies with three
   * messages queued behind it, one has a reply you have not read, one is idle with suggested actions, and the selected one is
   * empty, so it shows the home. */
  const LONG_REPLY = 'Yes. SpecsRepository can return names and keys from the directory listing first, then fill in titles and status as each spec.md is parsed. '
    + 'The panel shows rows within one frame and updates them in place. The parse moves to Dispatchers.IO and runs in batches of 50, so a scroll during the first load stays smooth. '
    + 'Archived bundles are listed last and parsed only when their group is expanded, which removes about 300 of the 510 reads from the first open.';
  const NEW_TASK_CHATS = [
    { session: 'ses-token-cost' },
    { session: 'ses-specs-slow', extra: [{ role: 'operator', text: 'can we show the list before the bodies load?' }], reply: LONG_REPLY, shown: 150, queue: [
      { text: 'Once that lands, write the list-first loading up as a short spec under docs/plans.' },
      { text: '[Image #1] This is what the Specs panel shows for the first three seconds.', images: 1 },
      { text: 'Run phase:goal', action: true },
    ] },
    { session: 'ses-offline-banner', unread: true },
    { session: null, repo: 'skill-bill', agent: 'claude' },
  ];
  const NEW_TASK_SELECTED = 3;
  /* Canned agent replies for the prototype's simulated turns. */
  const CHAT_REPLIES = [
    'I checked the code path. The state lives in one place today, so the change is mostly moving it into a per-chat holder and keeping the screen as a thin view over the selected one.',
    'Short answer: yes. Two things need care. The agent session must keep running when its chat is not on screen, and the saved draft must update from whichever chat changed.',
    'I would keep it simple for the first cut and leave persistence of the open set for later, unless you want it now.',
  ];
  const NEW_TASK_STEPS = ['plan', 'implement', 'audit', 'review', 'validate', 'qa', 'pr'];
  const QUICK_STARTS = ['Review my branch', 'Run the checks', 'QA my branch', 'Open a PR'];

  /* ---------- QA device (sidebar footer) ---------- */
  /* Android devices the app found: connected phones and running emulators from `adb devices -l`, stopped AVDs from
   * `emulator -list-avds`, and the QA device lease that holds one. kind phone | emu; transport USB | Wi-Fi; state ready |
   * leased | unauthorized | offline | stopped. A list is null when adb is not found. `live` is what the sidebar shows; the
   * other lists are the states on the Components page. */
  const DEVICE_LISTS = {
    mixed: [
      { id: 'p8', kind: 'phone', name: 'Pixel 8 Pro', transport: 'USB', api: 'Android 15', state: 'ready' },
      { id: 's23', kind: 'phone', name: 'Galaxy S23', transport: 'Wi-Fi', api: 'Android 14', state: 'ready' },
      { id: 'emu1', kind: 'emu', name: 'Pixel_7_API_34', running: true, state: 'leased', lease: 'SKILL-514' },
      { id: 'avd1', kind: 'emu', name: 'Pixel_Tablet_API_35', running: false, state: 'stopped' },
      { id: 'avd2', kind: 'emu', name: 'Small_Phone_API_29', running: false, state: 'stopped' },
    ],
    one: [{ id: 'p8', kind: 'phone', name: 'Pixel 8 Pro', transport: 'USB', api: 'Android 15', state: 'ready' }],
    unauth: [
      { id: 'p6', kind: 'phone', name: 'Pixel 6a', transport: 'USB', state: 'unauthorized' },
      { id: 'avd1', kind: 'emu', name: 'Pixel_7_API_34', running: false, state: 'stopped' },
    ],
    offline: [
      { id: 's23', kind: 'phone', name: 'Galaxy S23', transport: 'Wi-Fi', api: 'Android 14', state: 'offline' },
      { id: 'p8', kind: 'phone', name: 'Pixel 8 Pro', transport: 'USB', api: 'Android 15', state: 'ready' },
    ],
    avdOnly: [
      { id: 'avd1', kind: 'emu', name: 'Pixel_7_API_34', running: false, state: 'stopped' },
      { id: 'avd2', kind: 'emu', name: 'Pixel_Tablet_API_35', running: false, state: 'stopped' },
    ],
    none: [],
    noAdb: null,
  };
  const QA_DEVICES = { live: 'mixed', lists: DEVICE_LISTS };

  /* Build indicator: build tool commands the runtime finds in a run's agent process tree, matched
   * against the repository's platform pack. `types` is what SKILL-417 builds per project type (the #build/<state>/<type>
   * routes switch it); `runs` are the fixed builds of other runs on the board. `usual` is the median of the same command's
   * last five runs in the repository. */
  const BUILD = {
    live: { state: 'running', type: 'android' },
    focus: 'SKILL-417',
    types: {
      android: { tool: 'Gradle 8.10', kind: 'Android', cmd: './gradlew :app:assembleDebug', short: 'gradlew assembleDebug', usual: 3 * M + 10000 },
      kotlin: { tool: 'Gradle 8.10', kind: 'Kotlin/JVM', cmd: './gradlew :runtime-engine:test', short: 'gradlew test', usual: 2 * M + 20000 },
      ts: { tool: 'pnpm 9', kind: 'TypeScript', cmd: 'pnpm build', short: 'pnpm build', usual: 48000 },
      go: { tool: 'Go 1.23', kind: 'Go', cmd: 'go test ./...', short: 'go test', usual: 1 * M + 5000 },
      rust: { tool: 'Cargo', kind: 'Rust', cmd: 'cargo build --release', short: 'cargo build', usual: 4 * M },
      ios: { tool: 'Xcode 16', kind: 'iOS', cmd: 'xcodebuild -scheme Newsreader build', short: 'xcodebuild', usual: 5 * M + 30000 },
    },
    runs: {
      'ph-sb-validation': { tool: 'Gradle 8.10', kind: 'Kotlin/JVM', cmd: './gradlew clean check createDistributable --no-build-cache', short: 'gradlew check', state: 'running', ms: 6 * M + 5000, usual: 4 * M + 10000, pid: 51877, started: '14:24', prev: ['done', '4m 02s', '41m ago'] },
      'ph-rk-validation': { tool: 'Gradle 8.10', kind: 'Kotlin/JVM', cmd: './gradlew build', short: 'gradlew build', state: 'failed', ms: 48000, usual: 2 * M, exit: 1, ago: '1m ago', pid: 50211, started: '14:29' },
      'ph-atl-pr': { tool: 'pnpm 9', kind: 'TypeScript', cmd: 'pnpm build', short: 'pnpm build', state: 'running', ms: 22000, usual: 1 * M + 5000, pid: 52003, started: '14:30' },
      'PAY-19': { tool: 'Go 1.23', kind: 'Go', cmd: 'go test ./...', short: 'go test', state: 'passed', ms: 1 * M + 12000, usual: 1 * M + 10000, exit: 0, ago: '4m ago', pid: 49120, started: '14:26' },
    },
  };

  const USAGE = {
    providers: ['Codex', 'Claude', 'Cursor'],
    accounts: { Codex: 'Personal account', Claude: 'Work account', Cursor: 'Personal account, legacy plan' },
    cursorScenarios: {
      normal: { status: 'Available', note: 'Usage resets in 12 days', windows: [{ label: 'Cursor models', remaining: 56, reset: 'Resets Nov 19 at 09:00' }, { label: 'Other models', remaining: 73, reset: 'Resets Nov 19 at 09:00' }] },
      low: { status: 'Running low', tone: 'low', note: 'Usage resets in 12 days', message: 'Your included Cursor models allowance is running low.', windows: [{ label: 'Cursor models', remaining: 9, reset: 'Resets Nov 19 at 09:00' }, { label: 'Other models', remaining: 42, reset: 'Resets Nov 19 at 09:00' }] },
      exhausted: { status: 'Limit reached', tone: 'empty', note: 'Usage resets in 12 days', message: 'Included Cursor models allowance used up. Other models still have included usage.', windows: [{ label: 'Cursor models', remaining: 0, reset: 'Resets Nov 19 at 09:00' }, { label: 'Other models', remaining: 42, reset: 'Resets Nov 19 at 09:00' }] },
      stale: { status: 'Out of date', stale: true, note: 'Last checked 24m ago', message: 'Could not refresh usage. These are the last known values.', windows: [{ label: 'Cursor models', remaining: 56, reset: 'Last reported reset: Nov 19 at 09:00' }, { label: 'Other models', remaining: 73, reset: 'Last reported reset: Nov 19 at 09:00' }] },
      unavailable: { status: 'Unavailable', note: 'Usage could not be retrieved', message: 'No usage data available for this account. Try refreshing.', windows: [{ label: 'Cursor models', remaining: null }, { label: 'Other models', remaining: null }] },
      loading: { status: 'Checking', loading: true, note: 'Checking account usage', windows: [{ label: 'Cursor models', remaining: null }, { label: 'Other models', remaining: null }] },
    },
    scenarios: {
      normal: { status: 'Available', note: 'Session resets in 2h 14m', windows: [{ label: '5-hour', remaining: 38, reset: 'Resets today at 16:30' }, { label: 'Weekly', remaining: 72, reset: 'Resets Thu, Nov 12 at 09:00' }] },
      low: { status: 'Running low', tone: 'low', note: 'Session resets in 2h 14m', message: 'Your session allowance is running low.', windows: [{ label: '5-hour', remaining: 9, reset: 'Resets today at 16:30' }, { label: 'Weekly', remaining: 42, reset: 'Resets Thu, Nov 12 at 09:00' }] },
      exhausted: { status: 'Limit reached', tone: 'empty', note: 'Session resets in 2h 14m', message: 'Session allowance used up. Resets today at 16:30.', windows: [{ label: '5-hour', remaining: 0, reset: 'Resets today at 16:30' }, { label: 'Weekly', remaining: 42, reset: 'Resets Thu, Nov 12 at 09:00' }] },
      stale: { status: 'Out of date', stale: true, note: 'Last checked 24m ago', message: 'Could not refresh usage. These are the last known values.', windows: [{ label: '5-hour', remaining: 38, reset: 'Last reported reset: today at 16:30' }, { label: 'Weekly', remaining: 72, reset: 'Last reported reset: Thu, Nov 12 at 09:00' }] },
      unavailable: { status: 'Unavailable', note: 'Usage could not be retrieved', message: 'No usage data available for this account. Try refreshing.', windows: [{ label: '5-hour', remaining: null }, { label: 'Weekly', remaining: null }] },
      loading: { status: 'Checking', loading: true, note: 'Checking account usage', windows: [{ label: '5-hour', remaining: null }, { label: 'Weekly', remaining: null }] },
    },
  };

  /* Goal QA while it runs: the stage QA is on, the live preview and the screenshots it has saved.
   * Stage names follow the desktop target in platform-packs/kmp/qa/kmp-qa.md. `video` marks the stages where the preview can
   * show frames. The runtime does not report stages yet; see R6 in HANDOFF.md. Sample data modeled on the
   * SKILL-516 QA runs of 2026-11-07. */
  const QA_STAGES = [
    { id: 'prepare', name: 'Prepare checkout' },
    { id: 'uitests', name: 'Compose UI tests', headless: true },
    { id: 'display', name: 'Start private display' },
    { id: 'launch', name: 'Launch app', video: true },
    { id: 'exercise', name: 'Exercise running app', video: true },
    { id: 'report', name: 'Write report' },
    { id: 'cleanup', name: 'Clean up' },
  ];
  const QA_LIVE_DONE = { prepare: '0:52', uitests: '7:31', display: '0:09', launch: '1:22' };
  /* Each scenario: `at` (the stage QA is on), `states` overrides (warn, skip), `times` for finished stages, `lines` (one
   * detail line per stage), `preview` (later, waiting, live, reconnect, nocapture, none, ended), `shots` (evidence/ file
   * names, oldest first), `callout`, `hint` and `rounds` for the goal QA fix loop. */
  const QA_LIVE = {
    prepare: { label: 'Preparing the checkout', elapsed: '0:38', at: 'prepare',
      lines: { prepare: 'Isolating app data under `qa-7c1e/home`' }, preview: { kind: 'later', reachAt: 'launch' } },
    uitests: { label: 'Compose UI tests', elapsed: '5:12', at: 'uitests', times: { prepare: '0:52' },
      lines: { uitests: 'Running `:feature:detail:jvmTest --rerun-tasks` · 3 of 6 test classes' },
      preview: { kind: 'later', reachAt: 'launch', why: 'UI tests run without a display. Video starts when QA launches the app, after these tests and the display start.' },
      hint: 'The last QA run on this repository reached Launch app 9m 40s in.' },
    display: { label: 'Display up, app not drawn', elapsed: '10:05', at: 'launch', times: { prepare: '0:52', uitests: '7:31', display: '0:09' },
      lines: { display: 'Display `:97` with capture · framebuffer protected', launch: 'Gradle is building `:desktop:app:run` · waiting for the window' },
      preview: { kind: 'waiting', text: 'Display :97 is ready. Waiting for the app window.', sub: 'Frames appear when the app draws its first window.' } },
    live: { label: 'Live', elapsed: '13:48', at: 'exercise', times: QA_LIVE_DONE,
      lines: { exercise: 'Criterion 3 of 5 · clicking "QA" in the finish tabs' },
      preview: { kind: 'live', caption: 'Criterion 3 of 5', info: ':97 · 1600×1000' }, shots: ['app-start.png', 'finish-tabs.png', 'qa-tab-open.png'] },
    restart: { label: 'Display restarting', elapsed: '12:31', at: 'exercise', times: QA_LIVE_DONE, states: { exercise: 'warn' },
      lines: { exercise: 'Display `:97` exited with SIGBUS at 21:59:40. QA is restarting it with capture.' },
      preview: { kind: 'reconnect', text: 'The display crashed at 21:59:40. QA is restarting it. Video resumes when the app draws again.' }, shots: ['app-start.png', 'finish-tabs.png'] },
    nocapture: { label: 'Restarted without capture', elapsed: '15:02', at: 'exercise', times: QA_LIVE_DONE, states: { exercise: 'warn' },
      lines: { exercise: 'Running on display `:98` without capture · screenshots only' },
      preview: { kind: 'nocapture', text: 'QA restarted the display without capture, so there is no video for the rest of this attempt. Screenshots below still update.' },
      callout: { text: 'Display `:97` crashed at 21:30 with SIGBUS. QA restarted it as `:98` without `-fbdir`, which breaks the rule in kmp-qa.md. QA will record this as a live-preview limitation.', chip: { icon: 'qaFile', label: 'xvfb.log', mono: true } },
      shots: ['app-start.png', 'finish-tabs.png', 'after-restart.png'] },
    nodisplay: { label: 'No display', elapsed: '9:40', at: 'report', times: { prepare: '0:52', uitests: '7:31' }, states: { display: 'skip', launch: 'skip', exercise: 'skip' },
      lines: { display: 'No Xvfb on this machine · listed under Unblock', report: 'Writing `report.md`' },
      preview: { kind: 'none', text: 'QA could not start a private display. It ran the Compose UI tests only.' },
      callout: { text: 'Install `xorg-server-xvfb` so QA can run the app on a private display. The report lists this under Unblock.', chip: { icon: 'qaOpen', label: 'Open Unblock' } } },
    fixloop: { label: 'Round 2 after a fix', elapsed: '3:05', at: 'prepare', round: 2,
      lines: { prepare: 'Reusing isolated data from round 1' }, preview: { kind: 'later', reachAt: 'launch' },
      hint: 'Round 1 reached Launch app 9m 40s in.',
      rounds: [{ icon: 'qaBug', label: 'QA 1 · 1 finding', state: 'done' }, { icon: 'check', label: 'qa_fix', state: 'done' }, { label: 'QA 2', state: 'now' }] },
    ended: { label: 'Finished', elapsed: '18:22', done: true,
      times: Object.assign({}, QA_LIVE_DONE, { exercise: '6:40', report: '1:20', cleanup: '0:28' }),
      preview: { kind: 'ended', at: '22:05' }, shots: ['app-start.png', 'finish-tabs.png', 'qa-tab-open.png', 'preview-blank.png', 'resize-900.png'] },
  };


  /* ---------- Phase peek (design/phase-peek): one card per phase under the details-pane phase tabs ----------
   * A card: title, agent, model (with effort), round, when, dur, word (overrides the state word), tone (overrides the
   * edge color), why (HTML strip), nowTitle and now (blocks while it runs or waits), madeTitle, madeEnd (text, or
   * { sort: true } for the assessment order control) and made (blocks), results (blocks), activity ([time, html, tone])
   * and open ({ label, toast, external }) for the footer button. Blocks: signal, edit, cmd, cells, text, stats, out,
   * finds, checks, facts; and for goal finish: assessment (it.assessment), qaLive (it.qaLive), qaRun, qaResults
   * (it.goalQa), qaWait and qaFinding. Sources: phase records, settlement envelopes, the edit journal,
   * agent_activity_stamps and the phase receipts (HANDOFF.md 4.1). Monitor CI checks are not persisted yet. */
  const PEEK = (function () {
    const OPUS = 'claude-opus-5-5 · high', SONNET = 'claude-sonnet-5-5 · high', GPT = 'gpt-6.1-sol · high';
    const path = (p) => `<span class="mono" title="${p}">${p.split('/').pop()}</span>`;
    function subCards(o) {
      return {
        preplan: { title: 'Preplan', agent: 'claude', model: OPUS, when: o.t[0], dur: '1m 52s', made: [{ kind: 'text', html: o.preplan }],
          activity: [[o.t[0], 'Started · <em>attempt 1</em>'], [o.t[1], 'Settled <em>· done</em>', 'done']] },
        plan: { title: 'Plan', agent: 'claude', model: OPUS, when: o.t[1], dur: '3m 05s',
          made: [{ kind: 'out', items: [{ icon: 'fileText', html: `<span class="mono">${o.spec}</span>`, end: `${o.criteria} criteria`, toast: `Opened ${o.spec}` }] }, { kind: 'text', html: o.plan }],
          activity: [[o.t[1], 'Started'], [o.t[2], `Wrote ${o.spec}`, 'done']] },
        implement: { title: 'Implement', agent: 'claude', model: SONNET, round: o.implRound || 'attempt 1', when: o.t[2], dur: o.implDur || '14m 20s', madeEnd: 'from the edit journal',
          made: [
            { kind: 'stats', items: [[String(o.files.length), 'files'], [`+${o.ins}`, 'added', 'ok'], [`−${o.del}`, 'removed', 'bad'], [String(o.newFiles), 'new']] },
            { kind: 'out', items: o.files.slice(0, 4).map(([p, i, d, n]) => ({ icon: n ? 'file' : 'pencil', html: `${path(p)}${n ? ' <em>new</em>' : ''}`, end: `+${i} −${d}`, tip: p, toast: `Opened ${p.split('/').pop()} in the diff` })) },
          ].concat(o.files.length > 4 ? [{ kind: 'text', html: `<span class="note">${o.files.length - 4} more files in Changes</span>` }] : []),
          activity: o.implAct, open: { label: 'Open changes', toast: 'Opened the Changes window filtered to implement' } },
        simplify: { title: 'Simplify', agent: 'claude', model: SONNET, when: o.t[3], dur: '4m 41s',
          made: [{ kind: 'stats', items: [['3', 'files'], ['+18', 'added', 'ok'], ['−56', 'removed', 'bad'], ['−38', 'net']] }, { kind: 'text', html: o.simplify }],
          activity: [[o.t[3], 'Started'], [o.t[4], 'Settled <em>· done</em>', 'done']] },
        audit: { title: 'Audit', agent: 'claude', model: OPUS, when: o.t[4], dur: '2m 10s', word: 'Satisfied', madeTitle: 'Acceptance criteria', madeEnd: `${o.audit.length} of ${o.audit.length} covered`,
          made: [{ kind: 'checks', rows: o.audit.map((c) => [c, 'ok']) }], activity: [[o.t[4], 'Started'], [o.t[5], 'Verdict <b>satisfied</b>', 'done']] },
      };
    }
    const S2 = {
      t: ['08:12', '08:14', '08:17', '08:52', '08:57', '08:59'],
      preplan: 'Change sits in <b>IdeStatusService</b> and its presenter. Risk: the lease check reads the parent snapshot before the child settles. Plan keeps the poll interval as it is.',
      spec: 'spec_subtask_02.md', criteria: 3,
      plan: 'Three criteria: the live goal phase wins after a child finishes, a finished child label clears on the next poll, and the lease check runs once per poll.',
      files: [['runtime/src/main/kotlin/dev/skillbill/ide/IdeStatusService.kt', 128, 42], ['runtime/src/main/kotlin/dev/skillbill/ide/IdeStatusPresenter.kt', 31, 9], ['runtime/src/test/kotlin/dev/skillbill/ide/IdeStatusServiceTest.kt', 96, 0, true], ['runtime/src/main/kotlin/dev/skillbill/ide/GoalPhaseSnapshot.kt', 22, 4], ['runtime/src/main/kotlin/dev/skillbill/ide/LeaseCheck.kt', 6, 11], ['runtime/src/test/kotlin/dev/skillbill/ide/fixtures/GoalSnapshots.kt', 40, 0, true]],
      ins: 323, del: 66, newFiles: 2, implRound: 'attempt 2', implDur: '21m 02s',
      implAct: [['08:17', 'Attempt 1 started'], ['08:38', 'Attempt 1 hit the 20 min timeout <em>· relaunched</em>', 'wait'], ['08:39', 'Attempt 2 started'], ['08:52', 'Settled <em>· 6 files, +323 −66</em>', 'done']],
      simplify: 'Merged two snapshot lookups into <span class="mono">GoalPhaseSnapshot.current()</span> and dropped an unused lease cache.',
      audit: ['Live goal phase wins after a child finishes', 'Finished child label clears on the next poll', 'Lease check runs once per poll'],
    };
    const S1 = {
      t: ['21:38', '21:40', '21:44', '22:10', '22:15', '22:17'],
      preplan: 'Status store already keeps the workflow id. Only the execution row is missing, so the change stays in one table and its reader.',
      spec: 'spec_subtask_01.md', criteria: 2,
      plan: 'Two criteria: the store keeps the execution for a standalone phase, and a restart reads it back.',
      files: [['runtime/src/main/kotlin/dev/skillbill/status/StandaloneStatusStore.kt', 64, 18], ['runtime/src/main/kotlin/dev/skillbill/status/StandaloneStatusMigration.kt', 41, 0, true], ['runtime/src/test/kotlin/dev/skillbill/status/StandaloneStatusStoreTest.kt', 88, 3]],
      ins: 193, del: 21, newFiles: 1, implDur: '25m 48s',
      implAct: [['21:44', 'Started'], ['22:10', 'Settled <em>· 3 files, +193 −21</em>', 'done']],
      simplify: 'Folded the row mapper into the store and removed a duplicate null check.',
      audit: ['Store keeps the execution for a standalone phase', 'A restart reads the execution back'],
    };
    const reviewNow = {
      title: 'Review', agent: 'claude', model: 'claude-opus-5-5 · xhigh', round: 'pass 2', when: 'since 09:31', dur: '17m 20s',
      now: [{ kind: 'signal', live: true, text: 'Verifying 3 review findings', ago: '14s ago' }, { kind: 'edit', agent: 'claude', path: 'runtime/src/main/kotlin/dev/skillbill/ide/IdeStatusService.kt', ins: 12, del: 4, ago: '2m ago' }],
      madeTitle: 'Pass 1 findings', madeEnd: 'changes requested',
      made: [{ kind: 'finds', rows: [['major', 'The status reads the child phase after the parent snapshot is replaced', 'fixed'], ['minor', 'The lease check runs twice per poll', 'fixed'], ['minor', 'A finished child keeps its phase label for one poll', 'open']] }],
      activity: [['09:14', 'Pass 1 started'], ['09:22', 'Pass 1 <b>changes requested</b> <em>· 3 findings</em>', 'wait'], ['09:23', 'Fix 1 <em>· 2 files, +31 −12</em>', 'done'], ['09:31', 'Pass 2 started', 'now']],
      open: { label: 'Open review', toast: 'Opened review run rv-5c21 for subtask 02' },
    };
    const tail1 = {
      review: { title: 'Review', agent: 'claude', model: 'claude-opus-5-5 · xhigh', round: 'pass 1', when: '22:17', dur: '6m 12s', word: 'Approved',
        made: [{ kind: 'text', html: 'Approved on the first pass. No findings.' }], activity: [['22:17', 'Pass 1 started'], ['22:23', 'Pass 1 <b>approved</b>', 'done']],
        open: { label: 'Open review', toast: 'Opened review run rv-19aa for subtask 01' } },
      build: { title: 'Build', agent: 'claude', model: SONNET, when: '22:23', dur: '3m 02s', word: 'Passed', madeTitle: 'Build receipt',
        made: [{ kind: 'cmd', text: './gradlew :runtime:assemble' }, { kind: 'stats', items: [['passed', 'result', 'ok'], ['3m 02s', 'took'], ['0', 'warnings']] }],
        activity: [['22:23', 'Started'], ['22:26', 'Build passed', 'done']] },
      validate: { title: 'Validate', agent: 'claude', model: SONNET, when: '22:26', dur: '5m 40s', word: 'Passed', madeTitle: 'Checks', madeEnd: '4 of 4 passed',
        made: [{ kind: 'checks', rows: [['Unit tests', 'ok', '2m 51s', ':runtime:test'], ['Detekt', 'ok', '41s', ':runtime:detekt'], ['ktlint', 'ok', '18s', 'ktlintCheck'], ['Contract tests', 'ok', '1m 50s', ':contracts:test']] }],
        activity: [['22:26', 'Started'], ['22:32', 'Validation passed <em>· 4 checks</em>', 'done']] },
      write_history: { title: 'Write history', agent: 'claude', model: SONNET, when: '22:32', dur: '48s', madeTitle: 'History receipt',
        made: [{ kind: 'out', items: [{ icon: 'history', html: '<span class="mono">history/2026-10.md</span>', end: '1 entry', toast: 'Opened history/2026-10.md' }] }, { kind: 'stats', items: [['yes', 'written', 'ok'], ['2', 'decisions'], ['1', 'path']] }],
        activity: [['22:32', 'Started'], ['22:33', 'Wrote 1 entry, 2 decisions', 'done']] },
      commit_push: { title: 'Commit and push', agent: 'claude', model: SONNET, when: '22:33', dur: '22s', madeTitle: 'Commit',
        made: [{ kind: 'out', items: [{ icon: 'commit', html: '<span class="mono">a41c9e2d</span> Keep the workflow execution in the standalone status store', toast: 'Copied a41c9e2d31400fb0f88b0c0c93e893410f9e06c2' }, { icon: 'branch', html: 'Pushed to <span class="mono">feat/SKILL-417-keep-ide-status…</span>', end: '4 paths' }] }],
        activity: [['22:33', 'Committed 4 paths'], ['22:33', 'Pushed', 'done']] },
    };
    const validateBlocked = {
      title: 'Validate', agent: 'claude', model: SONNET, round: 'attempt 3', when: 'since 10:50', dur: '21m 14s',
      why: '<b>Blocked:</b> the IntelliJ plugin test needs a JetBrains IDE download, and the sandbox has no network. Three attempts hit the same error.',
      now: [{ kind: 'signal', icon: 'list', wrap: true, text: 'Waits for you: allow network for the plugin test, or skip that check for this subtask.' }],
      madeTitle: 'Checks', madeEnd: '3 passed · 1 failed',
      made: [{ kind: 'cells', cells: ['ok', 'ok', 'ok', 'bad'] }, { kind: 'checks', rows: [['Unit tests', 'ok', '2m 40s', ':runtime:test'], ['Detekt', 'ok', '44s', ':runtime:detekt'], ['VS Code extension tests', 'ok', '1m 12s', 'npm test'], ['IntelliJ plugin tests', 'bad', 'exit 1', ':intellij:test']] }],
      activity: [['10:50', 'Attempt 1 <em>· plugin test failed, exit 1</em>', 'fail'], ['10:58', 'Attempt 2 <em>· same error</em>', 'fail'], ['11:06', 'Attempt 3 <em>· same error</em>', 'fail'], ['11:11', 'Blocked, waiting for you', 'wait']],
      open: { label: 'Open validation run', toast: 'Opened the validation run detail' },
    };

    /* Goal finish. goal_assessment and qa read the goal's assessment, goalQa and qaLive records; these cards add the header and activity. */
    const DIR = '/home/you/.skill-bill/qa/SkillBill-65f9b9859e5144f1/qa-7c1e/';
    const CHECKS = ['Pills open a card', 'Running phase opens by default', 'QA card shows the live preview', 'Preview survives a display restart', 'Toast clears the composer', 'Arrow keys move between pills'];
    const REPORT = 'Verdict: passed\n\nI exercised the goal finish pane on the desktop app: the phase pills, the QA card while QA ran, and the card after a display restart. Round 1 found one problem; qa_fix fixed it and round 2 passed.\n\n## Coverage\n\n- desktop: passed\n- android: skipped [reason: empty_scope]\n- backend: skipped [reason: empty_scope]\n- web: skipped [reason: empty_scope]';
    const QA1 = { id: 'QA-1', title: 'Toast overlaps the composer on a 1280px window', observed: 'the "Copied" toast covered the send button for 2 seconds.', expected: 'Toasts sit above the composer and never cover its buttons.', steps: '1. Resize the window to 1280×800\n2. Copy a commit id from the pane\n3. Look at the composer', evidencePaths: [DIR + 'evidence/toast-composer.png'] };
    const checks = (n) => CHECKS.slice(0, n).map((t) => asserted('passed', t, 'Exercised through the running app and matched the acceptance criterion.', []));
    const coverage = [cov('desktop', 'passed'), ...outOfScope(['android', 'backend', 'web'])];
    const qaRec = (key, outcome, fixAttempts, findings, n) => ({ runKey: key, outcome, fixAttempts, reportText: REPORT, reportPreview: 'I exercised the goal finish pane on the desktop app…', findings, details: { reportPath: DIR + 'report.md', invocationDirectory: DIR, assertions: checks(n), coverage } });
    const qa = {
      fixed: qaRec('goal-qa-SKILL-512', 'passed', 1, [Object.assign({}, QA1, { fixed: true })], 6),
      round1: qaRec('goal-qa-SKILL-512-r1', 'findings', 0, [QA1], 5),
      clean: qaRec('goal-qa-SKILL-512-clean', 'passed', 0, [], 6),
    };
    const assessment = { state: 'COMPLETED', round: 1, fixRan: true, exit: 'all findings resolved', fixCommit: '5e0c7a19', findings: [
      { ref: 'GA-001', severity: 'Minor', path: 'ClipboardImagePaste.kt', line: 58, scenario: 'Paste ignores images over 8 MB and shows no message.', verification: 'REFUTED', state: 'REFUTED' },
      { ref: 'GA-002', severity: 'Major', path: 'QaPreviewHost.kt', line: 112, scenario: 'The live preview stops drawing frames after the display reconnects.', verification: 'CONFIRMED', state: 'FIXED' },
      { ref: 'GA-003', severity: 'Major', path: 'QaStageList.kt', line: 40, scenario: 'Stage times reset to zero when the QA tab is opened again.', verification: 'CONFIRMED', state: 'FIXED' },
    ] };
    const qaLive = { label: 'Live', elapsed: '13:48', at: 'exercise', round: 1, times: { prepare: '0:52', uitests: '7:31', display: '0:09', launch: '1:22' },
      lines: { exercise: 'Criterion 3 of 6 · opening the QA card while QA runs' }, preview: { kind: 'live', caption: 'Criterion 3 of 6', info: ':97 · 1600×1000' },
      shots: ['app-start.png', 'finish-pane.png', 'qa-card-open.png'] };
    const SHOTS = ['app-start.png', 'finish-pane.png', 'qa-card-open.png', 'stage-list.png', 'resize-900.png', 'toast-composer.png'];
    const qaRun = (rounds, total, at, time) => ({ kind: 'qaRun', rounds, total, at, time, shots: SHOTS.slice(-3).reverse() });
    const valChecks = (last) => [['Unit tests', 'ok', '3m 10s', ':desktop:app:test'], ['Detekt', 'ok', '52s', 'detektAll'], ['ktlint', 'ok', '20s', 'ktlintCheck'], ['Compose screenshot tests', last, last === 'run' ? '1m 40s' : '2m 31s', ':desktop:app:verifyRoborazzi'], ['Contract tests', last === 'run' ? 'wait' : 'ok', last === 'run' ? '' : '1m 05s', ':contracts:test']];
    const monChecks = [['build-desktop (ubuntu)', 'ok', '6m 02s'], ['build-desktop (macos)', 'run', '7m 40s'], ['runtime-tests', 'ok', '9m 15s'], ['lint', 'ok', '1m 22s'], ['screenshot-diff', 'bad', '3m 48s']];
    const valNow = {
      title: 'Validate', agent: 'codex', model: GPT, round: 'attempt 1', when: 'since 11:22', dur: '6m 02s',
      now: [{ kind: 'cells', cells: ['ok', 'ok', 'ok', 'run', ''] }, { kind: 'signal', live: true, text: 'Check 4 of 5 · Compose screenshot tests', ago: '1m 40s' }, { kind: 'cmd', text: './gradlew :desktop:app:verifyRoborazzi' }],
      madeTitle: 'Checks', madeEnd: '3 passed · 1 running · 1 waiting', made: [{ kind: 'checks', rows: valChecks('run') }],
      activity: [['11:22', 'Started <em>· 5 checks from the validation receipt</em>'], ['11:27', '3 checks passed', 'done'], ['11:27', 'Compose screenshot tests running', 'now']],
      open: { label: 'Open validation run', toast: 'Opened the validation run detail' },
    };
    const monNow = {
      title: 'Monitor', agent: 'codex', model: GPT, round: 'cycle 2', when: 'since 11:44', dur: '12m 30s',
      now: [{ kind: 'signal', live: true, text: 'Waiting for build-desktop (macos)', ago: '20s ago' }, { kind: 'signal', icon: 'list', text: 'gh pr checks 482 · 3 passed, 1 running', ago: '45s ago' }],
      madeTitle: 'CI on #482', madeEnd: 'last poll 45s ago', made: [{ kind: 'checks', rows: monChecks.map((c) => (c[0] === 'screenshot-diff' ? ['screenshot-diff', 'ok', '3m 51s'] : c)) }],
      activity: [['11:32', 'Cycle 1 started'], ['11:41', '<b>screenshot-diff</b> failed', 'fail'], ['11:42', 'monitor_fix pushed <span class="mono">9b2f0c44</span>', 'done'], ['11:44', 'Cycle 2 started', 'now']],
      open: { label: 'Open checks on GitHub', external: true, toast: 'Opened github.com/skill-bill/skill-bill/pull/482/checks' },
    };
    const finish = {
      assess: { title: 'Goal assessment', agent: 'codex', model: GPT, round: 'round 1', when: '10:48', dur: '9m 30s', word: 'Completed', madeTitle: 'Findings', madeEnd: { sort: true },
        made: [{ kind: 'assessment' }],
        activity: [['10:48', 'Round 1 started'], ['10:55', '3 findings <em>· 2 major, 1 minor</em>', 'wait'], ['10:56', 'GA-001 refuted <em>· 8 MB limit shows a toast</em>'], ['10:57', 'Fixed GA-002 and GA-003 <em>· 5e0c7a19</em>', 'done']] },
      qaLive: { title: 'QA', agent: 'codex', model: 'gpt-6-luna · medium', round: 'round 1', when: 'since 10:58', dur: '13m 48s', nowTitle: 'Live', now: [{ kind: 'qaLive' }], madeTitle: 'Results', made: [{ kind: 'qaWait' }],
        activity: [['10:58', 'Started on <span class="mono">:desktop:app</span>'], ['11:06', 'Compose UI tests passed <em>· 7:31</em>', 'done'], ['11:08', 'App launched <em>· video on</em>', 'done'], ['11:09', 'Exercising criterion 3 of 6', 'now']] },
      qaRound1: { title: 'QA', agent: 'codex', model: 'gpt-6-luna · medium', round: 'round 1', when: '10:58', dur: '12m 10s', word: '1 problem · fixing', tone: 'wait', madeTitle: 'Run', madeEnd: 'qa-7c1e',
        made: [qaRun([{ icon: 'qaBug', label: 'QA 1 · 1 finding', state: 'done' }, { label: 'qa_fix', state: 'now' }, { label: 'QA 2', state: 'pending' }], 11, '11:10', '16:40')],
        results: [{ kind: 'qaResults', fixing: true }],
        activity: [['10:58', 'Round 1 started on <span class="mono">:desktop:app</span>'], ['11:08', 'App launched <em>· video on</em>', 'done'], ['11:10', '<b>1 finding</b> <em>· QA-1</em>', 'wait'], ['11:10', 'Handed to qa_fix']] },
      qa: { title: 'QA', agent: 'codex', model: 'gpt-6-luna · medium', round: 'round 2', when: '10:58', dur: '18m 04s', word: 'Passed after fixes', madeTitle: 'Run', madeEnd: 'qa-7c1e',
        made: [qaRun([{ icon: 'qaBug', label: 'QA 1 · 1 finding', state: 'done' }, { icon: 'check', label: 'qa_fix', state: 'done' }, { icon: 'check', label: 'QA 2', state: 'done' }], 14, '11:16', '18:04')],
        results: [{ kind: 'qaResults' }],
        activity: [['10:58', 'Round 1 started on <span class="mono">:desktop:app</span>'], ['11:10', 'Round 1 <b>1 finding</b> <em>· QA-1</em>', 'wait'], ['11:16', 'qa_fix fixed QA-1', 'done'], ['11:17', 'Round 2 started'], ['11:22', 'Round 2 <b>passed</b> <em>· 6 checks</em>', 'done']] },
      qaClean: { title: 'QA', agent: 'codex', model: 'gpt-6-luna · medium', round: 'round 1', when: '10:58', dur: '16m 40s', word: 'Passed', madeTitle: 'Run', madeEnd: 'qa-7c1e',
        made: [qaRun(null, 11, '11:16', '16:40')], results: [{ kind: 'qaResults' }],
        activity: [['10:58', 'Started on <span class="mono">:desktop:app</span>'], ['11:15', 'Passed <em>· 6 checks, no findings</em>', 'done']] },
      qaFixNow: { title: 'QA fix', agent: 'claude', model: SONNET, round: 'attempt 1 of 3', when: 'since 11:10', dur: '4m 05s',
        now: [{ kind: 'signal', live: true, text: 'Fixing QA-1 · Toast overlaps the composer', ago: '12s ago' }, { kind: 'edit', agent: 'claude', path: 'desktop/feature/chat/src/commonMain/kotlin/ComposerToastHost.kt', ins: 14, del: 3, ago: '40s ago' }],
        madeTitle: 'Fixing', made: [{ kind: 'qaFinding', finding: QA1, state: 'Fixing', tone: 'blocked' }, { kind: 'facts', items: [['Next', 'QA round 2 on the fixed tree']] }],
        activity: [['11:10', 'Started <em>· 1 open finding</em>'], ['11:12', 'Reproduced QA-1 at 1280×800'], ['11:13', 'Editing ComposerToastHost.kt', 'now']] },
      qaFix: { title: 'QA fix', agent: 'claude', model: SONNET, round: 'attempt 1 of 3', when: '11:10', dur: '6m 11s', word: 'Fixed',
        made: [{ kind: 'qaFinding', finding: QA1, state: 'Fixed', tone: 'done' }, { kind: 'out', items: [{ icon: 'pencil', html: path('desktop/feature/chat/src/commonMain/kotlin/ComposerToastHost.kt'), end: '+14 −3' }, { icon: 'commit', html: '<span class="mono">c0d4e8f1</span> Lift the toast above the composer', toast: 'Copied c0d4e8f1' }] }],
        activity: [['11:10', 'Started <em>· 1 open finding</em>'], ['11:16', 'Fixed QA-1 <em>· tested tree c0d4e8f1</em>', 'done']] },
      valNow,
      valDone: Object.assign({}, valNow, { when: '11:22', dur: '8m 46s', word: 'Passed', now: null, madeEnd: '5 of 5 passed', made: [{ kind: 'checks', rows: valChecks('ok') }], activity: [['11:22', 'Started'], ['11:31', 'Validation passed <em>· 5 checks</em>', 'done']] }),
      pr: { title: 'Pull request', agent: 'codex', model: GPT, when: '11:31', dur: '1m 12s', word: 'Opened',
        made: [{ kind: 'out', items: [{ icon: 'pr', html: '<b>#482</b> Keep QA live preview working across display crashes', external: true, toast: 'Opened github.com/skill-bill/skill-bill/pull/482' }, { icon: 'branch', html: '<span class="mono">feat/SKILL-512-qa-live-preview</span> → <span class="mono">main</span>' }] }],
        activity: [['11:31', 'Started'], ['11:32', 'Created PR <b>#482</b>', 'done']] },
      monNow,
      monDone: Object.assign({}, monNow, { round: 'cycle 1', when: '11:32', dur: '9m 05s', word: 'Green', now: null, madeEnd: 'all passed', made: [{ kind: 'checks', rows: monChecks.map((c) => [c[0], 'ok', c[2]]) }], activity: [['11:32', 'Cycle 1 started'], ['11:41', 'All 5 checks passed', 'done']] }),
      monFix: { title: 'Monitor fix', agent: 'claude', model: SONNET, round: 'repair 1', when: '11:41', dur: '1m 50s',
        why: 'Fixed <b>screenshot-diff</b>. The preview test baseline still had the old reconnect banner.',
        made: [{ kind: 'out', items: [{ icon: 'image', html: path('desktop/app/src/test/snapshots/QaPreview_reconnect.png'), end: 'updated' }, { icon: 'commit', html: '<span class="mono">9b2f0c44</span> Refresh the reconnect banner baseline', toast: 'Copied 9b2f0c44' }] }],
        activity: [['11:41', 'Started <em>· 1 failing check</em>'], ['11:42', 'Pushed <span class="mono">9b2f0c44</span>', 'done']] },
    };
    return {
      sub1: Object.assign(subCards(S1), tail1),
      sub2: Object.assign(subCards(S2), { review: reviewNow }),
      sub3: Object.assign(subCards(S2), { review: Object.assign({}, tail1.review, { when: '10:41' }), build: Object.assign({}, tail1.build, { when: '10:47' }), validate: validateBlocked }),
      finish, qa, assessment, qaLive,
    };
  })();
  (function () {
    const it = items.find((x) => x.id === 'SKILL-417');
    it.subtasks[0].phases = { cards: PEEK.sub1, note: '58m total' };
    it.subtasks[1].phases = { cards: PEEK.sub2 };
  })();
  window.DATA = { T0, REPOS, AGENTS, AGENT_CHOICES, PHASE_ORDER, GOAL_FINISH, items, history, valRoutes, projects, specs, NOTIFICATIONS, CLAUDE_DIRS, PHASE_GROUPS, MATRIX, BUDGET,
    REPO_BRANCHES, LONG_BRANCH, CHAT_HISTORY, NEW_TASK_CHATS, NEW_TASK_SELECTED, CHAT_REPLIES, NEW_TASK_STEPS, QUICK_STARTS,
    QA, QA_DEVICES, QA_STAGES, QA_LIVE, VAL, VERIFY, USAGE, BUILD, PEEK };
})();
