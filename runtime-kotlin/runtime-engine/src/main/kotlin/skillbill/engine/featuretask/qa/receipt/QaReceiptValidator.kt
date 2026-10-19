package skillbill.engine.featuretask.qa.receipt

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QA_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.error.featuretask.qa.qaReceiptIdentityMismatch
import skillbill.error.featuretask.qa.qaReceiptUnsupportedClaim
import skillbill.ports.qa.QaReceiptSchemaPort

@Inject
class QaReceiptValidator(
  private val schema: QaReceiptSchemaPort,
) {
  fun validate(
    rawJson: String,
    expected: QaExpectedIdentity,
  ): QaReceipt {
    val wire =
      JsonCodec.parseObjectOrNull(rawJson)?.let { JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it)) }
        ?: invalid("the receipt is not a JSON object.")
    schema.violation(rawJson)?.let(::invalid)
    val version = wire[QaReceiptPayloadKeys.CONTRACT_VERSION]
    if (version != QA_RECEIPT_CONTRACT_VERSION) {
      invalid("contract_version '$version' is not '$QA_RECEIPT_CONTRACT_VERSION'.")
    }
    val receipt = decodeQaReceipt(wire)
    checkIdentity(receipt, expected)
    checkClaims(receipt, expected)
    return receipt
  }

  fun skipped(
    reason: QaSkipReason,
    detail: String,
    expected: QaExpectedIdentity,
    policy: QaPolicy = QaPolicy.STANDALONE,
  ): QaReceipt =
    validate(
      JsonCodec.mapToJsonString(
        mapOf(
          QaReceiptPayloadKeys.CONTRACT_VERSION to QA_RECEIPT_CONTRACT_VERSION,
          QaReceiptPayloadKeys.VERDICT to QaVerdict.SKIPPED.wireValue,
          QaReceiptPayloadKeys.IDENTITY to
            mapOf(
              QaReceiptPayloadKeys.HEAD_REVISION to expected.headRevision,
              QaReceiptPayloadKeys.TREE_IDENTITY to expected.treeIdentity,
            ),
          QaReceiptPayloadKeys.ARTIFACT to null,
          QaReceiptPayloadKeys.POLICY to policy.wireValue,
          QaReceiptPayloadKeys.TARGET to null,
          QaReceiptPayloadKeys.FLOWS to emptyList<Any?>(),
          QaReceiptPayloadKeys.ASSERTIONS to emptyList<Any?>(),
          QaReceiptPayloadKeys.FINDINGS to emptyList<Any?>(),
          QaReceiptPayloadKeys.SKIP_REASON to reason.wireValue,
          QaReceiptPayloadKeys.REASON_DETAIL to detail.take(MAX_REASON_DETAIL),
          QaReceiptPayloadKeys.CLEANUP to cleanupWire(QaCleanupOutcome.RELEASED, emptyList(), null),
        ),
      ),
      expected,
    )

  fun withRetainedResources(
    receipt: QaReceipt,
    retained: List<QaRetainedResource>,
    guidance: String,
    expected: QaExpectedIdentity,
  ): QaReceipt {
    if (retained.isEmpty()) return receipt
    val merged = (receipt.cleanup.retainedResources + retained).distinct().take(MAX_RETAINED_RESOURCES)
    val wire =
      receipt.wire + (
        QaReceiptPayloadKeys.CLEANUP to
          cleanupWire(QaCleanupOutcome.RETAINED, merged, receipt.cleanup.guidance ?: guidance.take(MAX_GUIDANCE))
      )
    return validate(JsonCodec.mapToJsonString(wire), expected)
  }

  private fun checkIdentity(
    receipt: QaReceipt,
    expected: QaExpectedIdentity,
  ) {
    if (receipt.headRevision != expected.headRevision) {
      throw qaReceiptIdentityMismatch(
        QaReceiptPayloadKeys.HEAD_REVISION,
        expected.headRevision,
        receipt.headRevision,
      )
    }
    val headOnly = receipt.verdict == QaVerdict.SKIPPED && !receipt.hasArtifact
    if (!headOnly && receipt.treeIdentity != expected.treeIdentity) {
      throw qaReceiptIdentityMismatch(
        QaReceiptPayloadKeys.TREE_IDENTITY,
        expected.treeIdentity,
        receipt.treeIdentity,
      )
    }
  }

  private fun checkClaims(
    receipt: QaReceipt,
    expected: QaExpectedIdentity,
  ) {
    unsupportedClaim(receipt)?.let { unsupported(it) }
    val flowIds = receipt.flows.map(QaFlow::id)
    if (flowIds.size != flowIds.toSet().size) {
      unsupported("flow identifiers must be unique.")
    }
    val findingIds = receipt.findings.map(QaFinding::id)
    if (findingIds.size != findingIds.toSet().size) {
      unsupported("finding identifiers must be unique.")
    }
    val undeclaredAssertion = receipt.assertions.firstOrNull { it.flowId !in flowIds }
    if (undeclaredAssertion != null) {
      unsupported("assertion '${undeclaredAssertion.flowId}' references an undeclared flow.")
    }
    val foreign =
      receipt.findings.flatMap(QaFinding::evidencePaths).firstOrNull { path ->
        !path.startsWith(expected.evidencePrefix) || path.split('/').any { it == ".." }
      }
    if (foreign != null) {
      unsupported(
        "evidence '$foreign' is outside this run's directory '${expected.evidencePrefix}'.",
      )
    }
  }

  private fun unsupportedClaim(receipt: QaReceipt): String? {
    val exercised = receipt.flows.filter { it.status == QaFlowStatus.EXERCISED }
    val failedFlows =
      receipt.assertions.filter { it.outcome == QaAssertionOutcome.FAIL }.map(
        QaAssertion::flowId,
      ).toSet()
    val passedFlows =
      receipt.assertions.filter { it.outcome == QaAssertionOutcome.PASS && it.flowId !in failedFlows }
        .map(QaAssertion::flowId).toSet()
    return when {
      receipt.target?.kind == QaTargetKind.SIMULATOR && exercised.any(QaFlow::hardwareOnly) ->
        "a simulator run cannot exercise a hardware-only flow."
      receipt.verdict == QaVerdict.FINDINGS_OPEN && receipt.findings.isEmpty() ->
        "findings_open needs at least one finding."
      receipt.assertions.any { it.outcome == QaAssertionOutcome.FAIL } && receipt.findings.isEmpty() ->
        "failed assertions need a finding with an affected criterion before qa_fix can repair them."
      receipt.verdict != QaVerdict.PASSED -> null
      exercised.none { it.id in passedFlows } ->
        "passed needs at least one exercised flow with a passing assertion."
      receipt.flows.any { it.status == QaFlowStatus.UNTESTED } -> "passed cannot leave a selected flow untested."
      receipt.findings.isNotEmpty() || receipt.assertions.any { it.outcome == QaAssertionOutcome.FAIL } ->
        "passed cannot carry findings or failed assertions."
      else -> null
    }
  }

  private fun unsupported(reason: String): Nothing = throw qaReceiptUnsupportedClaim(reason)

  private fun invalid(reason: String): Nothing = throw invalidQaReceipt(reason)

  private fun cleanupWire(
    outcome: QaCleanupOutcome,
    retained: List<QaRetainedResource>,
    guidance: String?,
  ): Map<String, Any?> =
    buildMap {
      put(QaReceiptPayloadKeys.OUTCOME, outcome.wireValue)
      put(
        QaReceiptPayloadKeys.RETAINED_RESOURCES,
        retained.map {
          mapOf(QaReceiptPayloadKeys.KIND to it.kind, QaReceiptPayloadKeys.IDENTITY to it.identity.take(MAX_IDENTITY))
        },
      )
      guidance?.let { put(QaReceiptPayloadKeys.GUIDANCE, it) }
    }

  private companion object {
    const val MAX_REASON_DETAIL = 500
    const val MAX_GUIDANCE = 500
    const val MAX_IDENTITY = 200
    const val MAX_RETAINED_RESOURCES = 16
  }
}
