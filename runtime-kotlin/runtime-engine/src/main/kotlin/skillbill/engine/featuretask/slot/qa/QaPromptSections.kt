package skillbill.engine.featuretask.slot.qa

import skillbill.engine.featuretask.model.execution.QaDeclaredTarget
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.ports.qa.model.qaEnvironmentName
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding

internal object QaPromptSections {
  data class RepairFinding(
    val id: String,
    val title: String,
    val section: String?,
    val evidencePaths: List<String>,
  )

  const val DIRECTIVE: String =
    "Run QA for the current repository state from the QA checkout named below. For each declared target, " +
      "follow its pack QA guidance and drive the app, service, browser, device or simulator it names yourself. " +
      "Report what you find without repairing it, and settle by writing the QA report to the path the runtime names."

  const val VALUE_CONTENT: String =
    "The verdict, the flows you exercised and left untested, any findings, and any reason QA was skipped or " +
      "incomplete. Name evidence by path only."

  const val FIX_DIRECTIVE: String =
    "Repair the findings recorded by the preceding QA report by editing source in the repository root. " +
      "Keep the finding ids in view. Do not claim that QA findings are closed; a later QA report decides that."

  const val FIX_VALUE_CONTENT: String =
    "A repair report with one line per open QA finding: `<finding-id>: fixed: <files changed and how>`. Every " +
      "finding must be fixed in source; a finding left unchanged sends this step back to work."

  fun repairSupplement(
    findings: List<QaOpenFinding>,
    sections: Map<String, RepairFinding>,
    repoRoot: String,
  ): String {
    val entries =
      findings.joinToString("\n\n") { finding ->
        val report = sections[finding.id] ?: RepairFinding(finding.id, finding.id, null, emptyList())
        val section =
          report.section
            ?: "${report.id}: ${report.title}\n" +
            "QA finding ${report.id} has no readable report section; repairing from its id."
        val evidence =
          report.evidencePaths.takeIf(List<String>::isNotEmpty)?.joinToString(", ", prefix = "\n\nEvidence: ")
        section + evidence.orEmpty()
      }
    return "\n\n" +
      """
    |## Open QA findings to repair
    |
    |$entries
    |
    |Rules:
    |
    |- Edit source in `$repoRoot`, on the current branch. Leave the changes uncommitted; the goal commits them.
    |- Fix every finding listed above. None may be left unchanged or explained away; QA builds the current code
    |  itself, so a finding it reports is present at HEAD.
    |- QA runs again on your changes next and decides whether each finding is closed.
    |- When a finding reproduces an endpoint, database, asynchronous, or UI failure and the repository has a
    |  suitable test harness, add or extend a repository test that reproduces it, so the regression flow is
    |  retained.
    |- Never change QA environment configuration, fixtures, or mocks to hide a finding.
      """.trimMargin()
  }

  fun supplement(qa: QaExecutionContext): String {
    val target = qa.target as QaExecutionTarget.Supported
    return "\n\n" +
      """
    |## QA run
    |
    |${targetLines(target.targets)}
    |- Report coverage for every kind listed above.
    |- QA checkout: `${target.checkout}`. This temporary worktree holds exactly the tree under test plus the
    |  repository's ignored local configuration files. Build, install, and test from it. The runtime deletes it
    |  when QA ends.
    |- Invocation directory: `${qa.workspace.directory}`. Write every generated script and evidence file there,
    |  outside the QA checkout, which is deleted.
    |- Environment name: `${qaEnvironmentName(qa.workspace.invocationId)}`.
    |- Evidence paths in the report must start with `${qa.expected.evidencePrefix}`.
    |- Report path: `${qa.workspace.reportPath}`. Write exactly one markdown report there.
    |${deviceLeaseLines(qa.workspace.invocationId)}
    |
    |${environmentResourceRules(qa.workspace.invocationId)}
    |
    |$ENVIRONMENT_AND_ISOLATION_RULES
    |
    |$BACKEND_AND_WEB_FLOW_RULES
    |
    |$REPORT_RULES
    |
    |Rules:
    |
    |- QA may build and install the app artifact the pack guidance names, and may run device or instrumentation
    |  tests for the changed flows.
    |- QA may edit any file in the QA checkout to reach a state the criteria need but the environment cannot
    |  produce: inject a fault, force an error response, add a debug hook, or shorten a timeout. Rebuild after such
    |  an edit. Edit nothing outside the QA checkout and the invocation directory, and never commit, push, or open
    |  a pull request. QA reports findings; it does not repair them.
    |- QA runs on a test account with test data. Within the environment and isolation rules, every in-app
    |  action is allowed: create, edit, and delete records, add profiles, remove cards, log out. Never leave a flow
    |  untested to protect device or account state.
    |- When the app, build, or repository offers an environment, flavor, profile, or server choice, use a local or
    |  disposable isolated one when available, else staging. Never use production. When only production is
    |  available, exercise no flow: report `skipped` and name the missing environment.
    |- Derive flows from this run's acceptance criteria. Do not add flows the criteria do not ask for; report anything
    |  else you notice as a finding.
    |- QA never blocks the run. A missing report is recorded with a warning and the run continues, so report
    |  honestly: a listed flow left untested means `incomplete`.
    |- Launching or loading the app alone is never a pass. `passed` needs at least one exercised flow with an
    |  asserted expected outcome.
    |- A simulator run cannot exercise a hardware-only flow; list it as untested.
    |- Never put raw device serials, credentials, or log bodies in the report. Reference evidence by path.
    |- Start every long-running process the run needs (local backend, API simulator, mock server, Metro or another
    |  packager, log tailing) detached so the command returns:
    |  `nohup <cmd> > ${qa.workspace.directory}/<name>.log 2>&1 &`. Record its PID, register it with
    |  `--kind process --identity <pid>`, and wait for readiness by polling a health URL or log line with a
    |  bounded timeout, never by running it in the foreground.
    |- Never run a command that does not end on its own in the foreground: servers, watch modes, `tail -f`, or
    |  `adb logcat` without `-d`. Use bounded forms such as `adb logcat -d` or `timeout <seconds> <cmd>`.
    |- Before writing the report, stop every process this run started by its recorded PID and describe it under a
    |  `## Cleanup` heading. Leave processes that were already running alone. If a stop fails, say which process
    |  stayed and how the user can stop it.
    |- Write the report last, then exit promptly. Nothing this run started may keep running in your foreground
    |  after the report is written.
      """.trimMargin()
  }

  private val ENVIRONMENT_AND_ISOLATION_RULES: String =
    """
    |Environment and isolation:
    |
    |- Use the repository's existing QA Compose files, test profiles, Testcontainers fixtures, or local isolated
    |  setups, and pick exactly one as the environment owner. Inspect effective configuration, not names such as
    |  `test` or `local`.
    |- Use Docker Compose for multi-service backends when suitable. Name the project with the environment name
    |  above, or a name that starts with it (`docker compose -p <environment name>`). Use fresh owned volumes
    |  and free ports. Never attach production or developer database volumes, shared external networks, or the
    |  host Docker socket.
    |- When Docker is unavailable but an isolated local setup works, it is not a skip. A running Docker daemon does
    |  not make an unsafe configuration usable.
    |- Before the first write, check the effective database connection, inherited secrets, persistent storage, and
    |  outbound effects (email, payments, object storage, webhooks, identity providers, queues). Use the supported
    |  engine in a disposable instance. An isolated temporary SQLite file is valid only when SQLite is the
    |  production engine.
    |- When a database connection points at production or a personal development database, a secret's origin is
    |  unknown, storage is shared and persistent, or a required outbound effect has no local substitute, test
    |  implementation, or declared isolated sandbox: mutate nothing against it. Leave the affected flow untested,
    |  or skip the target with reason `isolation_rejected`. Never send a synthetic write to an uncertain endpoint.
    |- Setup per target is capped at 15 minutes of wall time. Setup means dependencies, migrations, seeding, app
    |  start, and readiness. On overrun, stop setup, clean up, and skip the target with reason
    |  `setup_budget_exceeded`. Bound each readiness probe or asynchronous wait at 120 seconds. Readiness must
    |  distinguish still starting, startup failure, missing configuration, and healthy.
    |- When no isolated environment can be established, skip the target with reason `environment_unavailable`. Use
    |  `docker_unavailable` instead when the only viable environment needs Docker and Docker or its daemon is
    |  unavailable.
    |- Write temporary configuration, scripts, and data only in the invocation directory or in resources
    |  registered under the environment name, never in the operator's checkout.
    """.trimMargin()

  private val BACKEND_AND_WEB_FLOW_RULES: String =
    """
    |Backend and web flows:
    |
    |- Backend: authenticate as synthetic QA users and call the acceptance endpoints. Assert the status, the
    |  response content, and the relevant persisted or asynchronous side effect. Cover changed authorization and
    |  validation boundaries, such as an unauthorized request and an invalid payload. A `200` or a health check
    |  alone is never a pass. Seeding the database may prepare initial state but never bypasses the operation under
    |  test. An asynchronous outcome not observed within its bounded wait leaves the assertion incomplete, not
    |  passed.
    |- Web: discover browser capabilities inside this worker. Never assume or substitute the parent session's
    |  browser tools or connectors. Use an available CLI browser driver, such as Playwright, when suitable. When no
    |  driver is usable, skip the web target with reason `browser_unavailable` and name the missing capability.
    |- Exercise the changed web journeys with assertions on visible outcomes, navigation, form validation, and
    |  access control. A page load is never a completed journey. A screenshot is evidence for an assertion, never a
    |  substitute for one.
    |- Bind cookies, storage state, and traffic to the QA environment. Never reuse an operator or production
    |  browser profile.
    |- Record for every web assertion whether it reached the disposable backend or a mock. Mock-backed UI
    |  assertions never establish backend coverage. A static or UI-only frontend may run without Docker. In a
    |  full-stack run, report the backend outcome and the browser outcome separately.
    |- Never put tokens, connection strings, synthetic user passwords, sensitive response bodies, or full logs in
    |  the report. Reference evidence by path under the invocation directory.
    """.trimMargin()

  private val REASON_TOKENS: String = QaSkipReason.entries.joinToString(", ") { "`${it.wireValue}`" }

  private val REPORT_RULES: String =
    """
    |Report:
    |
    |- The first line names the verdict: `Verdict: passed`, `Verdict: findings_open`, `Verdict: incomplete`, or
    |  `Verdict: skipped`. Say why when it is `incomplete` or `skipped`.
    |- For a `skipped` or `incomplete` verdict, the second line is `Reason: <token>`, using one of these tokens:
    |  $REASON_TOKENS. Add any detail after the token or in the body.
    |- Write each finding under its own `### <id> <title>` heading, for example `### QA-1 Save button stays disabled`.
    |  Under it give the steps, the expected result, the observed result, and evidence paths. Use `#` or `##` for
    |  every other heading so a finding section ends where the next heading starts.
    |- Describe the flows you exercised and the flows you left untested, with the reason, in your own words.
    |- Under a `## Coverage` heading, write one bullet per target kind listed in the QA run section, in the form
    |  `- <kind>: <passed|findings_open|skipped|incomplete> [reason: <token>] [backend: real|mocked|none]`. The
    |  `backend:` field is required on a `web` line.
    |- Under a `## Assertions` heading, list each exercised assertion with its flow and outcome.
    |- Under a `## Isolation` heading, state the environment mechanism, the disposable database or its absence,
    |  the outbound substitutes, and any rejected configuration. Never include secret values.
    |- Under a `## Temporary edits` heading, list every file you changed in the QA checkout, what you changed, and
    |  the flows the change made possible. Write `None.` when you changed nothing.
    """.trimMargin()

  private fun deviceLeaseLines(invocationId: String): String =
    """
    |- Before installing on a device, take its lease with
    |  `skill-bill qa lease acquire --invocation $invocationId --device <serial>`. A `contended`
    |  result means another run owns the device: do not use it or release its lease. When no target was named,
    |  pick a free authorized target and lease it, then follow the pack guidance to try another untried target if
    |  needed. Preserve a named target; skip with the reason if it cannot be used. If no candidate can be used,
    |  report `skipped` and describe the obstacles;
    |  call it device-contended only when every authorized candidate is leased by someone else.
    |  Release leases this invocation acquired before switching targets and when done with
    |  `skill-bill qa lease release --invocation $invocationId --device <serial>`.
    """.trimMargin()

  private fun environmentResourceRules(invocationId: String): String =
    """
    |Environment resources:
    |
    |- Use the environment name, or a name that starts with it, as the Compose project name (`-p`) and for every
    |  container, volume and network you create.
    |- Before starting each Compose project, standalone container, named volume, network or background process,
    |  register it with
    |  `skill-bill qa resource register --invocation $invocationId --kind <kind> --identity <identity>`.
    |  The kinds are `compose_project`, `container`, `volume`, `network`, `process` and `directory`. Do not use a
    |  resource whose registration returns `conflict` or `rejected`.
    |- Release what you no longer need with
    |  `skill-bill qa resource release --invocation $invocationId --kind <kind> --identity <identity>`.
    |  The runtime tears down everything still registered when QA ends.
    |- Never run Docker prune commands. Never stop or remove a container, volume, network, Compose project or
    |  process you did not create in this run. Leave the operator's development stack alone.
    |- List under `## Cleanup` every resource that release reported as `retained`.
    """.trimMargin()

  private fun targetLines(targets: List<QaDeclaredTarget>): String =
    targets.joinToString("\n") { declared ->
      "- Target `${declared.packSlug}`: kinds ${declared.kinds.joinToString(", ") { it.wireValue }}; " +
        "guidance `${declared.guidancePath.value}`. Read it first and follow it."
    }
}
