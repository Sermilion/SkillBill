package skillbill.engine.featuretask.qa.receipt

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaReceiptPayloadKeys

internal data class QaReceiptNormalization(
  val wire: Map<String, Any?>,
  val warnings: List<String>,
)

internal fun normalizeQaReceiptClaims(receipt: QaReceipt): QaReceiptNormalization? {
  val warnings = mutableListOf<String>()
  val demoted = simulatorHardwareFlowIds(receipt)
  if (demoted.isNotEmpty()) {
    warnings +=
      "The QA receipt marked hardware-only flow(s) ${demoted.joinToString { "'$it'" }} exercised on a simulator; " +
      "recorded them untested."
  }
  val flows =
    receipt.flows.map { flow ->
      if (flow.id in demoted) flow.copy(status = QaFlowStatus.UNTESTED, untestedReason = SIMULATOR_REASON) else flow
    }
  val verdict = honestVerdict(receipt, flows, warnings)
  if (warnings.isEmpty()) return null
  val wire =
    receipt.wire.toMutableMap().apply {
      if (demoted.isNotEmpty()) put(QaReceiptPayloadKeys.FLOWS, demotedFlowsWire(receipt.wire, demoted))
      if (verdict != receipt.verdict) put(QaReceiptPayloadKeys.VERDICT, verdict.wireValue)
      if (verdict == QaVerdict.INCOMPLETE && receipt.verdict != QaVerdict.INCOMPLETE) {
        put(QaReceiptPayloadKeys.INCOMPLETE_REASON, QaIncompleteReason.OTHER.wireValue)
        put(
          QaReceiptPayloadKeys.REASON_DETAIL,
          receipt.reasonDetail ?: warnings.joinToString(" ").take(MAX_REASON_DETAIL),
        )
      }
    }
  return QaReceiptNormalization(wire, warnings)
}

internal fun QaReceipt.restrictedToCriteria(accepted: Set<String>): QaReceipt {
  if (accepted.isEmpty()) return this
  val unknown =
    (findings.flatMap(QaFinding::affectedCriteria) + assertions.flatMap(QaAssertion::criteria))
      .filterNot(accepted::contains)
      .toSet()
  if (unknown.isEmpty()) return this
  return copy(
    findings = findings.map { it.copy(affectedCriteria = it.affectedCriteria.filter(accepted::contains)) },
    assertions = assertions.map { it.copy(criteria = it.criteria.filter(accepted::contains)) },
    wire =
      wire +
        mapOf(
          QaReceiptPayloadKeys.FINDINGS to
            restrictedEntries(wire[QaReceiptPayloadKeys.FINDINGS], QaReceiptPayloadKeys.AFFECTED_CRITERIA, accepted),
          QaReceiptPayloadKeys.ASSERTIONS to
            restrictedEntries(wire[QaReceiptPayloadKeys.ASSERTIONS], QaReceiptPayloadKeys.CRITERIA, accepted),
        ),
    warnings =
      warnings +
        (
          "The QA receipt referenced ${unknown.joinToString { "'$it'" }}, which are not admitted acceptance " +
            "criteria; dropped those references."
        ),
  )
}

private fun restrictedEntries(
  entries: Any?,
  key: String,
  accepted: Set<String>,
): List<Map<String, Any?>> =
  JsonCodec.anyToStringAnyMapList(entries).orEmpty().map { entry ->
    entry + (key to (entry[key] as? List<*>).orEmpty().filter { it in accepted })
  }

private fun honestVerdict(
  receipt: QaReceipt,
  flows: List<QaFlow>,
  warnings: MutableList<String>,
): QaVerdict =
  when (receipt.verdict) {
    QaVerdict.PASSED -> passedVerdict(receipt, flows, warnings)
    QaVerdict.FINDINGS_OPEN ->
      if (receipt.findings.isEmpty()) {
        warnings += "The QA receipt claimed findings_open without a finding; recorded incomplete."
        QaVerdict.INCOMPLETE
      } else {
        QaVerdict.FINDINGS_OPEN
      }
    QaVerdict.SKIPPED, QaVerdict.INCOMPLETE -> receipt.verdict
  }

private fun passedVerdict(
  receipt: QaReceipt,
  flows: List<QaFlow>,
  warnings: MutableList<String>,
): QaVerdict {
  val failedFlows =
    receipt.assertions.filter { it.outcome == QaAssertionOutcome.FAIL }.map(QaAssertion::flowId).toSet()
  val exercised = flows.filter { it.status == QaFlowStatus.EXERCISED }.map(QaFlow::id).toSet()
  val passing =
    receipt.assertions.any { assertion ->
      assertion.outcome == QaAssertionOutcome.PASS && assertion.flowId in exercised && assertion.flowId !in failedFlows
    }
  val untested = flows.filter { it.status == QaFlowStatus.UNTESTED }
  val (verdict, warning) =
    when {
      receipt.findings.isNotEmpty() ->
        QaVerdict.FINDINGS_OPEN to "$PASSED_CLAIM with ${receipt.findings.size} finding(s); recorded findings_open."
      failedFlows.isNotEmpty() ->
        QaVerdict.INCOMPLETE to "$PASSED_CLAIM with a failed assertion and no finding; recorded incomplete."
      untested.isNotEmpty() ->
        QaVerdict.INCOMPLETE to
          "$PASSED_CLAIM but left ${untested.joinToString { "'${it.name}'" }} untested; recorded incomplete."
      !passing ->
        QaVerdict.INCOMPLETE to
          "$PASSED_CLAIM without an exercised flow that has a passing assertion; recorded incomplete."
      else -> return QaVerdict.PASSED
    }
  warnings += warning
  return verdict
}

private fun simulatorHardwareFlowIds(receipt: QaReceipt): Set<String> =
  if (receipt.target?.kind == QaTargetKind.SIMULATOR) {
    receipt.flows.filter { it.status == QaFlowStatus.EXERCISED && it.hardwareOnly }.map(QaFlow::id).toSet()
  } else {
    emptySet()
  }

private fun demotedFlowsWire(
  wire: Map<String, Any?>,
  demoted: Set<String>,
): List<Map<String, Any?>> =
  JsonCodec.anyToStringAnyMapList(wire[QaReceiptPayloadKeys.FLOWS]).orEmpty().map { flow ->
    if (flow[QaReceiptPayloadKeys.ID] in demoted) {
      flow +
        mapOf(
          QaReceiptPayloadKeys.STATUS to QaFlowStatus.UNTESTED.wireValue,
          QaReceiptPayloadKeys.UNTESTED_REASON to SIMULATOR_REASON,
        )
    } else {
      flow
    }
  }

private const val PASSED_CLAIM = "The QA receipt claimed passed"
private const val SIMULATOR_REASON = "A simulator cannot exercise a hardware-only flow."
private const val MAX_REASON_DETAIL = 500
