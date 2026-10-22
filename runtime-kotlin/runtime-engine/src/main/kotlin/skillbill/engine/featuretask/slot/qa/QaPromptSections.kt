package skillbill.engine.featuretask.slot.qa

import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding

internal object QaPromptSections {
  data class RepairFinding(
    val id: String,
    val title: String,
    val section: String?,
    val evidencePaths: List<String>,
  )

  const val DIRECTIVE: String =
    "Run report-only mobile QA for the current repository state. Follow the pack QA guidance named below, drive " +
      "the device or simulator yourself, and settle by writing the QA report to the path the runtime names."

  const val VALUE_CONTENT: String =
    "The verdict, the flows you exercised and left untested, any findings, and any reason QA was skipped or " +
      "incomplete. Name evidence by path only."

  const val FIX_DIRECTIVE: String =
    "Repair the findings recorded by the preceding mobile QA report by editing source in the repository root. " +
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
      """.trimMargin()
  }

  fun supplement(qa: QaExecutionContext): String {
    val target = qa.target as QaExecutionTarget.Supported
    return "\n\n" +
      """
    |## QA run
    |
    |- Guidance: `${target.guidancePath.value}`. Read it first and follow it.
    |- Platforms the pack supports: ${target.platforms.joinToString(", ") { it.wireValue }}.
    |- Invocation directory: `${qa.workspace.directory}`. Write every generated script and evidence file there.
    |- Evidence paths in the report must start with `${qa.expected.evidencePrefix}`.
    |- Report path: `${qa.workspace.reportPath}`. Write exactly one markdown report there.
    |- Before installing on a device, take its lease with
    |  `skill-bill qa lease acquire --invocation ${qa.workspace.invocationId} --device <serial>`. A `contended`
    |  result means another run owns the device: do not use it or release its lease. When no target was named,
    |  pick a free authorized target and lease it, then follow the pack guidance to try another untried target if
    |  needed. Preserve a named target; skip with the reason if it cannot be used. If no candidate can be used,
    |  report `skipped` and describe the obstacles;
    |  call it device-contended only when every authorized candidate is leased by someone else.
    |  Release leases this invocation acquired before switching targets and when done with
    |  `skill-bill qa lease release --invocation ${qa.workspace.invocationId} --device <serial>`.
    |
    |Report:
    |
    |- The first line names the verdict: `Verdict: passed`, `Verdict: findings_open`, `Verdict: incomplete`, or
    |  `Verdict: skipped`. Say why when it is `incomplete` or `skipped`.
    |- Write each finding under its own `### <id> <title>` heading, for example `### QA-1 Save button stays disabled`.
    |  Under it give the steps, the expected result, the observed result, and evidence paths. Use `#` or `##` for
    |  every other heading so a finding section ends where the next heading starts.
    |- Describe the flows you exercised and the flows you left untested, with the reason, in your own words.
    |
    |Rules:
    |
    |- QA may build and install the app artifact the pack guidance names, and may run device or instrumentation
    |  tests for the changed flows. QA stays report-only: never edit tracked files, commit, push, or open a pull
    |  request.
    |- QA runs on a test account with test data. Every in-app action is allowed: create, edit, and delete
    |  records, add profiles, remove cards, log out. Never leave a flow untested to protect device or account state.
    |- When the app or build offers an environment, flavor, or server choice, use Local when a local backend or
    |  simulator backend is available, else staging. Never use production. When only production is available,
    |  exercise no flow: report `skipped` and name the missing environment.
    |- Derive flows from this run's acceptance criteria. Do not add flows the criteria do not ask for; report anything
    |  else you notice as a finding.
    |- QA never blocks the run. A missing report is recorded with a warning and the run continues, so report
    |  honestly: a listed flow left untested means `incomplete`.
    |- An app launch alone is never a pass. `passed` needs at least one exercised flow that behaved as expected.
    |- A simulator run cannot exercise a hardware-only flow; list it as untested.
    |- Never put raw device serials, credentials, or log bodies in the report. Reference evidence by path.
    |- Start every long-running process the run needs (local backend, API simulator, mock server, Metro or another
    |  packager, log tailing) detached so the command returns:
    |  `nohup <cmd> > ${qa.workspace.directory}/<name>.log 2>&1 &`. Record its PID and wait for readiness by
    |  polling a health URL or log line with a bounded timeout, never by running it in the foreground.
    |- Never run a command that does not end on its own in the foreground: servers, watch modes, `tail -f`, or
    |  `adb logcat` without `-d`. Use bounded forms such as `adb logcat -d` or `timeout <seconds> <cmd>`.
    |- Before writing the report, stop every process this run started by its recorded PID and describe it under a
    |  `## Cleanup` heading. Leave processes that were already running alone. If a stop fails, say which process
    |  stayed and how the user can stop it.
    |- Write the report last, then exit promptly. Nothing this run started may keep running in your foreground
    |  after the report is written.
      """.trimMargin()
  }
}
