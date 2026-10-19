package skillbill.engine.featuretask.qa.receipt

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.error.featuretask.qa.qaReceiptMissing
import skillbill.error.featuretask.qa.qaReceiptUnsupportedClaim
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.qa.QaWorkspacePort
import skillbill.workflow.taskruntime.model.qa.QaFindingVerification
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairState

@Inject
class QaReceiptSettlement(
  private val validator: QaReceiptValidator,
  private val workspaces: QaWorkspacePort,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun recordDiagnostics(receipt: QaReceipt) {
    receipt.skipReason?.let { reason ->
      diagnostics.info(
        "qa skipped: seam=qa_receipt value_expected=executed value_used=${reason.wireValue} reason=${reason.wireValue}",
      )
    }
    receipt.fallbacks.forEach { fallback ->
      diagnostics.info(
        "qa fallback: seam=qa_receipt value_expected=requested_target value_used=${fallback.kind.wireValue} " +
          "reason=${fallback.kind.wireValue}",
      )
    }
    if (receipt.cleanup.outcome == QaCleanupOutcome.RETAINED) {
      receipt.cleanup.retainedResources.forEach { resource ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "qa resource retained: seam=qa_cleanup value_expected=released value_used=retained " +
            "kind=${resource.kind.take(MAX_RECORD_KIND)} identity=${resource.identity.take(MAX_RECORD_IDENTITY)} " +
            "reason=agent_reported",
        )
      }
    }
  }

  internal fun observe(
    previous: QaRepairState,
    receipt: QaReceipt,
    qa: QaExecutionContext,
  ): QaRepairState {
    val current =
      receipt.findings.map { finding ->
        QaOpenFinding(finding.id, finding.affectedCriteria, listOf(qa.workspace.receiptPath.toString()))
      }
    val verification =
      if (receipt.verdict == QaVerdict.PASSED) {
        val originals =
          previous.openFindings.flatMap(QaOpenFinding::receiptRefs).distinct().associateWith { reference ->
            retainedQaReceipt(qa, reference)
          }
        previous.openFindings.flatMap { finding ->
          finding.receiptRefs.mapNotNull { reference ->
            originals[reference]?.let { original -> verification(finding, reference, original, receipt) }
          }
        }
      } else {
        emptyList()
      }
    return previous.observe(current, previous.verifiedClosure(verification), qa.expected.treeIdentity)
  }

  fun settle(qa: QaExecutionContext): QaReceipt {
    val raw =
      workspaces.readReceipt(qa.workspace.receiptPath)
        ?: throw qaReceiptMissing(qa.workspace.receiptPath.toString())
    val receipt = validator.validate(raw.content, qa.expected)
    if (receipt.policy != qa.policy) {
      throw qaReceiptUnsupportedClaim("receipt policy does not match the admitted QA policy '${qa.policy.wireValue}'.")
    }
    return receipt
  }

  private fun verification(
    finding: QaOpenFinding,
    reference: String,
    original: QaReceipt,
    receipt: QaReceipt,
  ): QaFindingVerification {
    if (original.findings.none { it.id == finding.id }) {
      throw invalidQaReceipt("retained receipt does not contain finding '${finding.id}'.")
    }
    val matchingTarget = original.target == null || original.target.platform == receipt.target?.platform
    val originalFlows = original.flows.filter { it.status == QaFlowStatus.EXERCISED }
    val eligibleFlows =
      receipt.flows.filter { flow ->
        flow.status == QaFlowStatus.EXERCISED && matchingTarget &&
          (originalFlows.none { it.id == flow.id && it.hardwareOnly } || receipt.target?.kind == QaTargetKind.PHYSICAL)
      }.map(QaFlow::id).toSet()
    val passing = receipt.assertions.filter { it.outcome == QaAssertionOutcome.PASS && it.flowId in eligibleFlows }
    return QaFindingVerification(
      findingId = finding.id,
      receiptRef = reference,
      observedTreeIdentity = original.treeIdentity,
      testedTreeIdentity = receipt.treeIdentity,
      requiredFlowIds = originalFlows.map(QaFlow::id).toSet(),
      passingFlowIds = passing.map(QaAssertion::flowId).toSet(),
      passingCriteria = passing.flatMap(QaAssertion::criteria).toSet(),
    )
  }

  private fun retainedQaReceipt(
    qa: QaExecutionContext,
    reference: String,
  ): QaReceipt? {
    val qaRoot = checkNotNull(qa.workspace.directory.parent)
    val raw = workspaces.readRetainedReceipt(qaRoot, reference)
    if (raw == null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "qa closure deferred: seam=qa_findings value_expected=retained_receipt value_used=missing " +
          "reason=receipt_unavailable",
      )
      return null
    }
    val wire =
      JsonCodec.parseObjectOrNull(raw.content)?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)
    val identity = JsonCodec.anyToStringAnyMap(wire?.get(QaReceiptPayloadKeys.IDENTITY))
    val head = identity?.get(QaReceiptPayloadKeys.HEAD_REVISION) as? String
    val tree = identity?.get(QaReceiptPayloadKeys.TREE_IDENTITY) as? String
    if (head.isNullOrBlank() || tree.isNullOrBlank()) {
      invalid("retained QA receipt has no tested identity.")
    }
    val receipt = validator.validate(raw.content, QaExpectedIdentity(head, tree, raw.evidencePrefix))
    if (raw.receiptReference != reference) invalid("retained QA receipt identity does not match its reference.")
    return receipt
  }

  private fun invalid(
    reason: String,
    cause: Throwable? = null,
  ): Nothing = throw invalidQaReceipt(reason, cause)

  private companion object {
    const val MAX_RECORD_KIND = 64
    const val MAX_RECORD_IDENTITY = 200
  }
}
