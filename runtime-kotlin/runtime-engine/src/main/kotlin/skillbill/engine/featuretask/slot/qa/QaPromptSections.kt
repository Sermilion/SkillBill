package skillbill.engine.featuretask.slot.qa

import skillbill.contracts.qa.QA_CHECKLIST_CONTRACT_VERSION
import skillbill.engine.featuretask.model.execution.QaDeclaredTarget
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.QaNamedDeviceKind
import skillbill.engine.featuretask.model.execution.QaPolicy
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
      "map the acceptance criteria to target kinds using its pack QA guidance, then drive each in-scope app, " +
      "service, browser, device or simulator yourself. " +
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
    |${deviceLeaseLines(qa.workspace.invocationId, qa.namedDevice)}
    |
    |${environmentResourceRules(qa.workspace.invocationId)}
    |$ENVIRONMENT_AND_ISOLATION_RULES
    |
    |$BACKEND_AND_WEB_FLOW_RULES
    |
    |${reportRules(qa)}
    |
    |$UNBLOCK_RULES
    |Rules:
    |
    |- QA may build and install the app artifact the pack guidance names, and may run device or instrumentation
    |  tests for the flows the acceptance criteria cover.
    |- When a criterion or changed behavior needs a state the environment does not produce on its own, QA forces
    |  it. Edit any file in the QA checkout to inject a fault, force an error response, add a debug hook, or shorten
    |  a timeout, or change the environment, for example by cutting the network while a response streams. Rebuild
    |  after such an edit. Edit nothing outside the QA checkout and the invocation directory, and never commit,
    |  push, or open a pull request. QA reports findings; it does not repair them.
    |- That the environment could not produce a state is never a reason to leave a flow unexercised. Report a flow
    |  as not exercised only when a forced attempt failed, and name what you tried and why it failed.
    |- For device QA, build, install, launch, and sign in as needed. An existing signed-in session may be used;
    |  synthetic account provenance is not a prerequisite. Every in-app action needed by the acceptance criteria
    |  is allowed, including creating, editing, deleting records, and logging out.
    |- For device QA, look for a staging environment switch in the app, build flavors, or configuration. Select
    |  staging when available; otherwise continue with the app's default environment. Record which was used.
    |  Do not require a local backend, disposable database, or outbound substitutes to exercise device flows.
    |  Backend isolation requirements below apply only to backend and web targets, including in mixed runs.
    |- Derive flows from this run's acceptance criteria. Do not add flows the criteria do not ask for; report anything
    |  else you notice as a finding.
    |$SCOPE_RULES
    |- QA never blocks the run. A missing report is recorded with a warning and the run continues, so report
    |  honestly: a listed flow left untested means `incomplete`.
    |- Launching or loading the app alone is never a pass. `passed` needs at least one exercised flow with an
    |  asserted expected outcome.
    |- A simulator run cannot exercise a hardware-only flow; list it as untested.
    |- Never put raw device serials, credentials, or log bodies in the report. Reference evidence by path.
    |- Start every long-running process the run needs (local backend, API simulator, mock server, Metro or another
    |  packager, log tailing) detached with `nohup` or `setsid nohup` so the command returns:
    |  `nohup <cmd> > ${qa.workspace.directory}/<name>.log 2>&1 &` or
    |  `setsid nohup <cmd> > ${qa.workspace.directory}/<name>.log 2>&1 < /dev/null &`. Record its PID, register it with
    |  `--kind process --identity <pid>`, and wait for readiness by polling a health URL or log line with a
    |  bounded timeout, never by running it in the foreground.
    |- Never run a command that does not end on its own in the foreground: servers, watch modes, `tail -f`, or
    |  `adb logcat` without `-d`. Use bounded forms such as `adb logcat -d` or `timeout <seconds> <cmd>`.
    |- Before writing the report, stop every process this run started by its recorded PID and describe it under a
    |  `## Cleanup` heading. Leave processes that were already running alone. If a stop fails, say which process
    |  stayed and how the user can stop it.
    |- Write the report last, then exit promptly. Nothing this run started may keep running in your foreground
    |  after the report is written.
      """.trimMargin() +
      progressRules(qa)
  }

  private val SCOPE_RULES: String =
    """
    |- Build the QA checkout as it is and run every flow the acceptance criteria describe. Do not inspect git
    |  history, branches, diffs, or source code to decide whether the change is present or whether a flow is worth
    |  running. Behavior that is missing or wrong is a failed assertion and a finding, never a reason to skip a flow.
    |- Before checking prerequisites or building, map each acceptance criterion to the target kinds it actually
    |  describes. A declared kind is a capability, not a requirement to test it. Report a kind with no applicable
    |  criterion as `skipped` with reason `empty_scope`; do not probe its tools or devices. Shared code alone does
    |  not put every dependent app in scope. Explicit cross-platform behavior or regression criteria do.
    |- For an in-scope target, leave a flow untested only when the tools needed to perform QA are unavailable: no
    |  usable device, simulator, browser driver, toolchain, or environment. Name the missing tool and how to provide it.
    |  Record it as an entry under `## Unblock`.
    |- When an acceptance criterion is only an issue key or a tracker URL, read that issue through a connected tracker
    |  and test its requirements and acceptance criteria. If no tracker tool can read it, settle `skipped` with
    |  reason `empty_scope` and name the reference you could not read.
    """.trimMargin()

  private val ENVIRONMENT_AND_ISOLATION_RULES: String =
    """
    |Backend and web environment isolation:
    |
    |These requirements apply only to backend and web targets, not device QA. A backend or web setup failure
    |does not prevent testing an available device app.
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
    |  response content, and the relevant persisted or asynchronous side effect. Cover the authorization and
    |  validation boundaries the criteria touch, such as an unauthorized request and an invalid payload. A `200`
    |  or a health check alone is never a pass. Seeding the database may prepare initial state but never bypasses
    |  the operation under test. An asynchronous outcome not observed within its bounded wait leaves the assertion incomplete, not
    |  passed.
    |- Web: discover browser capabilities inside this worker. Never assume or substitute the parent session's
    |  browser tools or connectors. Use an available CLI browser driver, such as Playwright, when suitable. When no
    |  driver is usable, skip the web target with reason `browser_unavailable` and name the missing capability.
    |- Exercise the web journeys the criteria describe with assertions on visible outcomes, navigation, form validation, and
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

  private val UNBLOCK_RULES: String =
    """
    |Unblock:
    |
    |- Write exactly one `## Unblock` section only when a target or assertion ends `incomplete` or `skipped` because
    |  a capability is missing. Put it after the verdict and reason lines, and make it the report's last `##` section.
    |- Write one entry per missing capability under `#### <capability>`. Use one snake_case token, such as `display`,
    |  `input`, `app_startup_gate`, `device`, `backend`, or `credentials`.
    |- Under each heading, write the labels in this order:
    |  `- Target: <kind>`, `- Tried: <what was attempted and why it failed>`, `- Evidence: <comma-separated paths>`,
    |  and `- Remedy: <install command, env var name, or setup step>`.
    |  Evidence paths must start with the required evidence prefix.
    |- Follow each entry with one fenced block opened by ```markdown containing text the operator can paste into
    |  `.skill-bill/phases/qa.md` so the next QA run performs the step itself. Never start a line inside the block with
    |  `#`, because the runtime reads headings even inside fences. Use prose or bullets instead.
    |- Name environment variables, never their values. Existing report redaction rules still apply.
    |- `## Unblock` adds to the `Reason:` token and the `## Coverage` line; it does not replace either one.
    """.trimMargin()

  private fun reportRules(qa: QaExecutionContext): String =
    """
    |Report:
    |
    |- The first line names the verdict: `Verdict: passed`, `Verdict: findings_open`, `Verdict: incomplete`, or
    |  `Verdict: skipped`. Say why when it is `incomplete` or `skipped`.
    |- For a `skipped` or `incomplete` verdict, the second line is `Reason: <token>`, using one of these tokens:
    |  $REASON_TOKENS. Add any detail after the token or in the body.
    |- Write each finding under its own `### <id> <title>` heading, for example `### QA-1 Save button stays disabled`.
    |  Under it write the labelled lines `Steps:`, `Expected:`, `Observed:` and `Evidence:`. Use `#` or `##` for
    |  every other heading so a finding section ends where the next heading starts. The `####` entry headings
    |  inside `## Unblock` are the only exception.
    |- Under a `## Coverage` heading, write one bullet per target kind listed in the QA run section, in the form
    |  `- <kind>: <passed|findings_open|skipped|incomplete> [reason: <token>] [backend: real|mocked|none]`. The
    |  `backend:` field is required on a `web` line.
    |- Under a `## Assertions` heading, write one bullet per assertion, including those you did not exercise, in the
    |  form `- [passed|failed|not_exercised] <title>: <detail>. Evidence: <paths>`. A `failed` assertion must have a
    |  finding. A `not_exercised` assertion names what you tried and why it failed.
    |- Under a `## Isolation` heading, record the selected environment or app defaults. For backend and web
    |  targets also state the disposable database or its absence, outbound substitutes, and rejected configuration.
    |  Never include secret values.
    |- When you capture a screenshot, save it under `${qa.workspace.directory}/evidence/` and immediately retain it with
    |  `skill-bill qa screenshot --invocation ${qa.workspace.invocationId} --file <absolute path> --label <label>`.
    |  Put the returned `path` in the inventory so the report points to the retained file.
    |  Include every captured image, even when no finding uses it. Capture the final useful rendered state with `--frame`. Add a `## Screenshots`
    |  section with one line per image in capture order:
    |  `- <UTC ISO-8601 time> | <png|jpeg> | <label> | <absolute path>`.
    |  The path must start with `${qa.expected.evidencePrefix}`.
    |  Do not list framebuffer files or files in the QA checkout.
    |- Under a `## Temporary edits` heading, write one bullet per file you changed in the QA checkout, in the form
    |  `` - `<path>`: <what changed> ``, and name the flows the change made possible. Prefix a bullet with
    |  `[not restored]` only when the edit outlives the QA checkout. Write `None.` when you changed nothing.
    """.trimMargin()

  private fun progressRules(qa: QaExecutionContext): String {
    if (qa.policy == QaPolicy.OFF) return ""
    val invocationId = qa.workspace.invocationId
    if (qa.policy != QaPolicy.STANDALONE) {
      return "\n\nLive progress:\n\n" + goalStageRules(invocationId)
    }
    return "\n\n" +
      """
    |Live progress:
    |
    |- Write a plan file in the invocation directory with this shape, then register it before the first flow with
    |  `skill-bill qa plan --invocation $invocationId --file <path>`:
    |  `{"contract_version": "$QA_CHECKLIST_CONTRACT_VERSION", "cases": [{"id": "<case id>", "title": "<title>",
    |  "steps": [{"text": "<step>"}]}]}`.
    |- Report each target kind listed in the QA run section with
    |  `skill-bill qa target --invocation $invocationId --name <kind> --state running|ready|skipped
    |  [--detail <text>]`.
    |- Mark each step `running` and then `passed` or `failed` with
    |  `skill-bill qa step --invocation $invocationId --case <case id> --step <number> --status <status>
    |  [--note <text>] [--finding <finding id> [--finding-title <title>]] [--evidence <path>]...`. Steps count
    |  from 1 within their case. Pass the finding id you use in the report, and evidence paths that start with
    |  `${qa.expected.evidencePrefix}`.
    |- Report each step when it happens, not in a batch at the end: the runtime rejects progress once the QA run
    |  has finished, and steps never reported stay pending. Send one command per step with every value written
    |  out, without shell variables, loops or functions, because the permission policy can deny those. If the
    |  harness denies a progress command, send each step again as its own literal command.
    |- Progress commands never decide the verdict; the report does. A non-zero exit or a printed `rejected` or
    |  `failed` means nothing changed: continue QA without retrying the same command.
    |- The redaction rules for the report also apply to notes and target details.
      """.trimMargin()
  }

  private fun goalStageRules(invocationId: String): String =
    "Report real execution stages when they start and settle with `skill-bill qa stage --invocation " +
      "$invocationId --name <stage> --state running|done|warn|skipped [--detail <short reason>]`. Use " +
      "pack-declared operation names, report only work that happened, and explain skipped display work on " +
      "headless runs. The runtime records timestamps and leaves unreported stages unknown. " +
      "Progress commands never decide the verdict; the report does. A non-zero exit or a printed `rejected` or " +
      "`failed` means nothing changed: continue QA without retrying the same command."

  private fun deviceLeaseLines(
    invocationId: String,
    namedDevice: QaNamedDevice?,
  ): String =
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
    """.trimMargin() + namedDevice?.let(::namedDeviceLine).orEmpty()

  private fun namedDeviceLine(device: QaNamedDevice): String =
    when (device.kind) {
      QaNamedDeviceKind.SERIAL -> {
        val serialArgument = "'${device.value.replace("'", "'\"'\"'")}'"
        "\n- The operator named device serial `${device.value}` for this run. " +
          "Use it for `adb -s $serialArgument` and lease it as above. Never substitute another target. " +
          "If `adb devices` does not list it, or it is unauthorized, offline, or leased by another run, " +
          "settle `skipped` and say why."
      }
      QaNamedDeviceKind.AVD ->
        "\n- The operator named AVD `${device.value}` for this run. " +
          "Use it, and boot it by the pack's emulator-boot steps when it is not running. " +
          "Never substitute another target. If `emulator -list-avds` does not list it, " +
          "settle `skipped` and name the listed AVDs."
    }

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
