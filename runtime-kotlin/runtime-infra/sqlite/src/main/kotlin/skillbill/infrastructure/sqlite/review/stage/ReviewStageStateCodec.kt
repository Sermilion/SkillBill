package skillbill.infrastructure.sqlite.review.stage

import skillbill.contracts.JsonCodec
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.infrastructure.sqlite.telemetry.SqliteReviewTelemetryPayloadKeys
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewFindingCitation

internal fun encodePassClaims(findings: List<ParallelReviewMergedFinding>): String =
  JsonCodec.mapToJsonString(
    mapOf(
      ReviewVerificationSignalKeys.REVIEW_FINDINGS to
        findings.map { finding ->
          mapOf(
            ReviewFindingPayloadKeys.F_NUMBER to finding.fNumber,
            SqliteReviewTelemetryPayloadKeys.AGENT_IDS to finding.agentIds,
            ReviewFinishedTelemetryPayloadKeys.SEVERITY to finding.severity.name,
            ReviewFinishedTelemetryPayloadKeys.CONFIDENCE to finding.confidence,
            ReviewFinishedTelemetryPayloadKeys.LOCATION to finding.location,
            ReviewFinishedTelemetryPayloadKeys.DESCRIPTION to finding.description,
            SqliteReviewTelemetryPayloadKeys.SPECIALIST_SKILL_NAMES to finding.specialistSkillNames,
            SqliteReviewTelemetryPayloadKeys.ORIGIN_LAYER_CHAINS to finding.originLayerChains,
            ReviewFindingPayloadKeys.REPOSITORY_PATH to finding.repositoryPath,
            SqliteReviewTelemetryPayloadKeys.LINE to finding.line,
            SqliteReviewTelemetryPayloadKeys.COMMIT_SHAS to finding.commitShas,
          )
        },
    ),
  )

internal fun decodePassClaims(raw: String): List<ParallelReviewMergedFinding> {
  val root =
    JsonCodec.parseObjectOrNull(raw)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?: return emptyList()
  val items = root[ReviewVerificationSignalKeys.REVIEW_FINDINGS] as? List<*> ?: return emptyList()
  return items.mapNotNull { item ->
    val map = JsonCodec.anyToStringAnyMap(item) ?: return@mapNotNull null
    val fNumber = map[ReviewFindingPayloadKeys.F_NUMBER] as? String ?: return@mapNotNull null
    val severityName = map[ReviewFinishedTelemetryPayloadKeys.SEVERITY] as? String ?: return@mapNotNull null
    val severity =
      ParallelReviewSeverity.entries.firstOrNull { it.name == severityName }
        ?: return@mapNotNull null
    ParallelReviewMergedFinding(
      fNumber = fNumber,
      agentIds = stringList(map[SqliteReviewTelemetryPayloadKeys.AGENT_IDS]),
      severity = severity,
      confidence = map[ReviewFinishedTelemetryPayloadKeys.CONFIDENCE] as? String ?: "",
      location = map[ReviewFinishedTelemetryPayloadKeys.LOCATION] as? String ?: "",
      description = map[ReviewFinishedTelemetryPayloadKeys.DESCRIPTION] as? String ?: "",
      specialistSkillNames = stringList(map[SqliteReviewTelemetryPayloadKeys.SPECIALIST_SKILL_NAMES]),
      originLayerChains = chainList(map[SqliteReviewTelemetryPayloadKeys.ORIGIN_LAYER_CHAINS]),
      repositoryPath = map[ReviewFindingPayloadKeys.REPOSITORY_PATH] as? String,
      line = intValue(map[SqliteReviewTelemetryPayloadKeys.LINE]),
      commitShas = stringList(map[SqliteReviewTelemetryPayloadKeys.COMMIT_SHAS]),
    )
  }
}

internal fun encodeCitations(citations: List<ReviewFindingCitation>): String =
  ReviewFindingCitation.encodeList(citations)

internal fun decodeCitations(raw: String?): List<ReviewFindingCitation> = ReviewFindingCitation.decodeList(raw)

private fun stringList(raw: Any?): List<String> = (raw as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

private fun chainList(raw: Any?): List<List<String>> = (raw as? List<*>)?.map(::stringList) ?: emptyList()

private fun intValue(raw: Any?): Int? =
  when (raw) {
    is Int -> raw
    is Long -> raw.toInt()
    else -> null
  }
