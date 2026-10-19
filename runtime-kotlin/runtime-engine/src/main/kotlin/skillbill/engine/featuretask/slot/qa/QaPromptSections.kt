package skillbill.engine.featuretask.slot.qa

import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget

internal object QaPromptSections {
  const val DIRECTIVE: String =
    "Run report-only mobile QA for the current repository state. Follow the pack QA guidance named below, drive " +
      "the device or simulator yourself, and settle by writing the QA receipt to the path the runtime names."

  const val VALUE_CONTENT: String =
    "The verdict, the flows you exercised and left untested, any findings, and any skip or incomplete reason. " +
      "Name evidence by path only."

  const val FIX_DIRECTIVE: String =
    "Repair only findings recorded by the preceding mobile QA receipt. Keep the finding ids and affected acceptance " +
      "criteria in view. Do not claim that QA findings are closed; a later QA receipt decides that."

  const val FIX_VALUE_CONTENT: String =
    "Return a concise repair report naming the findings addressed and the files changed."

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
    |- QA is report-only. Never edit tracked files, commit, push, or open a pull request.
    |- An app launch alone is never a pass. `passed` needs at least one exercised flow with a passing assertion.
    |- A simulator run never marks a `hardware_only` flow exercised.
    |- Never put raw device serials, credentials, or log bodies in the receipt. Reference evidence by path and sha256.
      """.trimMargin()
  }
}
