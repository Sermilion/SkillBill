package skillbill.engine.featuretask.qa.report

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaOutcomePayloadKeys

internal object QaLegacyReportAdapter {
  data class Result(
    val verdict: QaVerdict,
    val findings: List<LegacyFinding>,
    val diagnostics: List<String>,
  )

  data class LegacyFinding(
    val id: String,
    val title: String,
    val evidencePaths: List<String>,
    val diagnostics: List<String>,
  )

  fun fromReceipt(
    receipt: Map<String, Any?>,
    verdict: QaVerdict,
  ): Result? {
    val findings =
      JsonCodec.anyToStringAnyMapList(receipt[QaOutcomePayloadKeys.FINDINGS]).orEmpty().mapNotNull { finding ->
        finding(finding)
      }
    if (findings.isEmpty()) return null
    val diagnostics = findings.flatMap { it.diagnostics }
    return Result(
      verdict = verdict,
      findings = findings,
      diagnostics = diagnostics,
    )
  }

  private fun finding(raw: Map<String, Any?>): LegacyFinding? {
    val id = (raw[QaOutcomePayloadKeys.ID] as? String)?.takeIf(String::isNotBlank) ?: return null
    val observed = raw[QaOutcomePayloadKeys.OBSERVED] as? String
    val steps = textList(raw[QaOutcomePayloadKeys.STEPS])
    val expected = raw[QaOutcomePayloadKeys.EXPECTED] as? String
    val evidencePaths =
      JsonCodec.anyToStringAnyMapList(raw[QaOutcomePayloadKeys.EVIDENCE_REFS]).orEmpty()
        .mapNotNull { evidence -> evidence[QaOutcomePayloadKeys.PATH] as? String }
    val title = observed?.lineSequence()?.firstOrNull(String::isNotBlank)?.trim().orEmpty().ifBlank { id }
    val missing =
      buildList {
        if (observed.isNullOrBlank()) add(QaOutcomePayloadKeys.OBSERVED)
        if (steps.isEmpty()) add(QaOutcomePayloadKeys.STEPS)
        if (expected.isNullOrBlank()) add(QaOutcomePayloadKeys.EXPECTED)
        if (evidencePaths.isEmpty()) add(QaOutcomePayloadKeys.EVIDENCE_REFS)
      }
    val diagnostics =
      missing.takeIf(List<String>::isNotEmpty)?.let { fields ->
        listOf("legacy QA finding $id reconstructed with partial fields: ${fields.joinToString()}")
      }.orEmpty()
    return LegacyFinding(
      id = id,
      title = title,
      evidencePaths = evidencePaths,
      diagnostics = diagnostics,
    )
  }

  private fun textList(value: Any?): List<String> =
    when (value) {
      is String -> listOf(value)
      is List<*> -> value.filterIsInstance<String>()
      else -> emptyList()
    }
}
