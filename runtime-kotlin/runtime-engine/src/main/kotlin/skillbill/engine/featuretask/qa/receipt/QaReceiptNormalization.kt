package skillbill.engine.featuretask.qa.receipt

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaReceiptPayloadKeys

internal data class QaReceiptNormalization(
  val wire: Map<String, Any?>,
  val warnings: List<String>,
)

internal fun normalizeQaReceiptClaims(
  receipt: QaReceipt,
  acceptanceCriteria: Set<String> = emptySet(),
): QaReceiptNormalization? {
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
  val honest = honestVerdict(receipt, flows, acceptanceCriteria, warnings)
  val verdict = honest.verdict
  if (warnings.isEmpty()) return null
  val wire =
    receipt.wire.toMutableMap().apply {
      if (demoted.isNotEmpty()) put(QaReceiptPayloadKeys.FLOWS, demotedFlowsWire(receipt.wire, demoted))
      if (verdict != receipt.verdict) put(QaReceiptPayloadKeys.VERDICT, verdict.wireValue)
      if (verdict != QaVerdict.INCOMPLETE) remove(QaReceiptPayloadKeys.INCOMPLETE_REASON)
      if (verdict == QaVerdict.INCOMPLETE && receipt.verdict != QaVerdict.INCOMPLETE) {
        put(QaReceiptPayloadKeys.INCOMPLETE_REASON, honest.incompleteReason.wireValue)
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

private data class HonestVerdict(
  val verdict: QaVerdict,
  val incompleteReason: QaIncompleteReason = QaIncompleteReason.OTHER,
)

private fun honestVerdict(
  receipt: QaReceipt,
  flows: List<QaFlow>,
  acceptanceCriteria: Set<String>,
  warnings: MutableList<String>,
): HonestVerdict =
  when (receipt.verdict) {
    QaVerdict.PASSED -> passedVerdict(receipt, flows, acceptanceCriteria, warnings)
    QaVerdict.FINDINGS_OPEN ->
      if (receipt.findings.isEmpty()) {
        warnings += "The QA receipt claimed findings_open without a finding; recorded incomplete."
        HonestVerdict(QaVerdict.INCOMPLETE)
      } else {
        HonestVerdict(QaVerdict.FINDINGS_OPEN)
      }
    QaVerdict.INCOMPLETE -> incompleteVerdict(receipt, flows, acceptanceCriteria, warnings)
    QaVerdict.SKIPPED -> HonestVerdict(receipt.verdict)
  }

private fun incompleteVerdict(
  receipt: QaReceipt,
  flows: List<QaFlow>,
  acceptanceCriteria: Set<String>,
  warnings: MutableList<String>,
): HonestVerdict {
  val coversEveryCriterion =
    receipt.incompleteReason == QaIncompleteReason.UNTESTED_FLOWS &&
      acceptanceCriteria.isNotEmpty() &&
      receipt.hasArtifact &&
      receipt.target != null &&
      receipt.findings.isEmpty() &&
      receipt.assertions.none { it.outcome == QaAssertionOutcome.FAIL } &&
      (acceptanceCriteria - passingAssertions(receipt, flows).flatMap(QaAssertion::criteria).toSet()).isEmpty()
  if (!coversEveryCriterion) return HonestVerdict(QaVerdict.INCOMPLETE)
  val untestedNames = flows.filter { it.status == QaFlowStatus.UNTESTED }.joinToString { "'${it.name}'" }
  warnings +=
    "The QA receipt claimed incomplete for untested flow(s) $untestedNames, but every acceptance criterion passed " +
    "on an exercised flow; recorded passed."
  return HonestVerdict(QaVerdict.PASSED)
}

private fun passingAssertions(
  receipt: QaReceipt,
  flows: List<QaFlow>,
): List<QaAssertion> {
  val failedFlows =
    receipt.assertions.filter { it.outcome == QaAssertionOutcome.FAIL }.map(QaAssertion::flowId).toSet()
  val exercised = flows.filter { it.status == QaFlowStatus.EXERCISED }.map(QaFlow::id).toSet()
  return receipt.assertions.filter { assertion ->
    assertion.outcome == QaAssertionOutcome.PASS && assertion.flowId in exercised && assertion.flowId !in failedFlows
  }
}

private fun passedVerdict(
  receipt: QaReceipt,
  flows: List<QaFlow>,
  acceptanceCriteria: Set<String>,
  warnings: MutableList<String>,
): HonestVerdict {
  val failedFlows =
    receipt.assertions.filter { it.outcome == QaAssertionOutcome.FAIL }.map(QaAssertion::flowId).toSet()
  val passingAssertions = passingAssertions(receipt, flows)
  val uncovered = acceptanceCriteria - passingAssertions.flatMap(QaAssertion::criteria).toSet()
  val untested = flows.filter { it.status == QaFlowStatus.UNTESTED }
  val untestedNames = untested.joinToString { "'${it.name}'" }
  val (verdict, warning) =
    when {
      receipt.findings.isNotEmpty() ->
        HonestVerdict(QaVerdict.FINDINGS_OPEN) to
          "$PASSED_CLAIM with ${receipt.findings.size} finding(s); recorded findings_open."
      failedFlows.isNotEmpty() ->
        HonestVerdict(QaVerdict.INCOMPLETE) to
          "$PASSED_CLAIM with a failed assertion and no finding; recorded incomplete."
      untested.isNotEmpty() && (acceptanceCriteria.isEmpty() || uncovered.isNotEmpty()) ->
        HonestVerdict(QaVerdict.INCOMPLETE, QaIncompleteReason.UNTESTED_FLOWS) to
          "$PASSED_CLAIM but left $untestedNames untested" +
          uncovered.takeIf(Set<String>::isNotEmpty)
            ?.let { ", and ${it.sorted().joinToString()} has no passing assertion" }.orEmpty() +
          "; recorded incomplete."
      passingAssertions.isEmpty() ->
        HonestVerdict(QaVerdict.INCOMPLETE) to
          "$PASSED_CLAIM without an exercised flow that has a passing assertion; recorded incomplete."
      untested.isNotEmpty() ->
        HonestVerdict(QaVerdict.PASSED) to
          "The QA receipt left $untestedNames untested, but every acceptance criterion passed on an exercised " +
          "flow; kept passed."
      else -> return HonestVerdict(QaVerdict.PASSED)
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
