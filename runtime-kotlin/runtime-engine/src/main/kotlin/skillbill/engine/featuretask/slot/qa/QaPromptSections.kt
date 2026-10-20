package skillbill.engine.featuretask.slot.qa

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding

internal object QaPromptSections {
  const val DIRECTIVE: String =
    "Run report-only mobile QA for the current repository state. Follow the pack QA guidance named below, drive " +
      "the device or simulator yourself, and settle by writing the QA receipt to the path the runtime names."

  const val VALUE_CONTENT: String =
    "The verdict, the flows you exercised and left untested, any findings, and any skip or incomplete reason. " +
      "Name evidence by path only."

  const val FIX_DIRECTIVE: String =
    "Repair the findings recorded by the preceding mobile QA receipt by editing source in the repository root. " +
      "Keep the finding ids and affected acceptance criteria in view. Do not claim that QA findings are closed; a " +
      "later QA receipt decides that."

  const val FIX_VALUE_CONTENT: String =
    "A repair report with one line per open QA finding: `<finding-id>: fixed: <files changed and how>`. Every " +
      "finding must be fixed in source; a finding left unchanged sends this step back to work."

  fun repairSupplement(
    findings: List<QaOpenFinding>,
    receiptFindings: List<Map<String, Any?>>,
    repoRoot: String,
  ): String {
    val details = receiptFindings.associateBy { it[QaReceiptPayloadKeys.ID] as? String }
    val entries =
      findings.joinToString("\n") { finding ->
        val detail = details[finding.id].orEmpty()
        val steps = (detail[QaReceiptPayloadKeys.STEPS] as? List<*>).orEmpty().mapNotNull { it as? String }
        val evidence =
          JsonCodec.anyToStringAnyMapList(detail[QaReceiptPayloadKeys.EVIDENCE_REFS]).orEmpty()
            .mapNotNull { it[QaReceiptPayloadKeys.PATH_REF] as? String }
        listOfNotNull(
          "### ${finding.id}",
          "- Affected criteria: ${finding.affectedCriteria.joinToString().ifBlank { "none named" }}",
          steps.takeIf(List<String>::isNotEmpty)?.joinToString("; ", prefix = "- Steps: "),
          (detail[QaReceiptPayloadKeys.EXPECTED] as? String)?.let { "- Expected: $it" },
          (detail[QaReceiptPayloadKeys.OBSERVED] as? String)?.let { "- Observed: $it" },
          evidence.takeIf(List<String>::isNotEmpty)?.joinToString(", ", prefix = "- Evidence: "),
          "- Receipts: ${finding.receiptRefs.joinToString()}",
        ).joinToString("\n")
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
    |- Evidence paths in the receipt must start with `${qa.expected.evidencePrefix}`.
    |- Receipt path: `${qa.workspace.receiptPath}`. Write exactly one JSON receipt there that matches
    |  `orchestration/contracts/qa-receipt-schema.yaml` with `policy` set to `${qa.policy.wireValue}`.
    |- Tested identity: `identity.head_revision` is `${qa.expected.headRevision}` and `identity.tree_identity` is
    |  `${qa.expected.treeIdentity}`.
    |- Before installing on a device, take its lease with
    |  `skill-bill qa lease acquire --invocation ${qa.workspace.invocationId} --device <serial>`. A `contended`
    |  result means another run owns the device: settle `skipped` with `device_contended`. Release it with
    |  `skill-bill qa lease release --invocation ${qa.workspace.invocationId} --device <serial>` when you are done.
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
    |  exercise no flow: settle `skipped` with `production_only` and name the missing environment in
    |  `reason_detail`.
    |- QA never blocks the run. A missing or contradictory receipt is recorded with a warning and the run continues,
    |  so report honestly: an untested flow means `incomplete`, never `passed`.
    |- An app launch alone is never a pass. `passed` needs at least one exercised flow with a passing assertion.
    |- A simulator run never marks a `hardware_only` flow exercised.
    |- Never put raw device serials, credentials, or log bodies in the receipt. Reference evidence by path and sha256.
    |- Start every long-running process the run needs (local backend, API simulator, mock server, Metro or another
    |  packager, log tailing) detached so the command returns:
    |  `nohup <cmd> > ${qa.workspace.directory}/<name>.log 2>&1 &`. Record its PID and wait for readiness by
    |  polling a health URL or log line with a bounded timeout, never by running it in the foreground.
    |- Never run a command that does not end on its own in the foreground: servers, watch modes, `tail -f`, or
    |  `adb logcat` without `-d`. Use bounded forms such as `adb logcat -d` or `timeout <seconds> <cmd>`.
    |- Before writing the receipt, stop every process this run started by its recorded PID and report it in
    |  `cleanup`. Leave processes that were already running alone. If a stop fails, report
    |  `cleanup.outcome: retained` with a `retained_resources` entry and `guidance` telling the user how to stop it.
    |- Write the receipt last, then exit promptly. Nothing this run started may keep running in your foreground
    |  after the receipt is written.
      """.trimMargin()
  }
}
