package skillbill.infrastructure.sqlite.review.stage

import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.review.core.queryLatestFindingOutcomes
import skillbill.infrastructure.sqlite.review.core.rate
import skillbill.review.context.model.claim.ReviewClaimVerdictAdmission
import skillbill.review.context.model.launch.ReviewSpecAdjudicationAdmission
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewExecutionMode
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewRejectedVerdictCounts
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustmentCounts
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageMetrics
import skillbill.review.model.ReviewStageVerdictDistribution
import java.sql.Connection

internal fun aggregateReviewStageMetrics(
  connection: Connection,
  reviewRunId: String,
  runFindingCount: Int,
): ReviewStageMetrics {
  val verdicts = fetchFindingVerdicts(connection, reviewRunId)
  val resolvedTier = resolvedTier(fetchReviewExecutionMode(connection, reviewRunId))
  return aggregateReviewStageMetrics(verdicts, resolvedTier, runFindingCount)
}

internal fun aggregateReviewStageMetrics(
  verdicts: List<ReviewFindingVerdict>,
  resolvedTier: String,
  runFindingCount: Int,
): ReviewStageMetrics {
  val verification = distribution(verdicts.filter { it.stage == ReviewStage.VERIFICATION })
  val adjudication = distribution(verdicts.filter { it.stage == ReviewStage.ADJUDICATION })
  val denominator =
    runFindingCount.takeIf { it > 0 }
      ?: verdicts.map { it.findingRef }.toSet().size
  return ReviewStageMetrics(
    verification = verification,
    adjudication = adjudication,
    verificationRefutationRate = rate(verification.refuted, denominator),
    adjudicationRefutationRate = rate(adjudication.refuted, denominator),
    rejectedVerdictCounts =
      ReviewRejectedVerdictCounts(
        uncitedRefutations =
          verdicts.count {
            it.rejectionReason == ReviewClaimVerdictAdmission.UNCITED_REFUTATION
          },
        uncitedDowngrades =
          verdicts.count {
            it.rejectionReason == ReviewSpecAdjudicationAdmission.UNCITED_DOWNGRADE
          },
        findingMutations =
          verdicts.count {
            it.rejectionReason == ReviewClaimVerdictAdmission.ALTERED_CLAIM ||
              it.rejectionReason == ReviewSpecAdjudicationAdmission.ALTERED_CLAIM
          },
      ),
    severityAdjustmentCounts =
      ReviewSeverityAdjustmentCounts(
        raised =
          verdicts.count {
            it.severityAdjustment?.direction == ReviewSeverityAdjustmentDirection.RAISE
          },
        lowered =
          verdicts.count {
            it.severityAdjustment?.direction == ReviewSeverityAdjustmentDirection.LOWER
          },
      ),
    resolvedTier = resolvedTier,
  )
}

internal fun resolvedTier(executionMode: ReviewExecutionMode?): String =
  when (executionMode) {
    ReviewExecutionMode.INLINE -> ReviewExecutionMode.INLINE.wireValue
    ReviewExecutionMode.DELEGATED -> ReviewExecutionMode.DELEGATED.wireValue
    else -> "unresolved"
  }

internal fun fetchReviewExecutionMode(
  connection: Connection,
  reviewRunId: String,
): ReviewExecutionMode? =
  connection.prepareStatement(
    "SELECT ${ReviewFinishedTelemetryPayloadKeys.EXECUTION_MODE} FROM review_runs WHERE review_run_id = ?",
  ).use { statement ->
    statement.bindAll(reviewRunId)
    statement.executeQuery().use { resultSet ->
      if (resultSet.next()) {
        resultSet.getString(ReviewFinishedTelemetryPayloadKeys.EXECUTION_MODE)?.let(ReviewExecutionMode::fromWire)
      } else {
        null
      }
    }
  }

internal fun loadReviewRunTiers(connection: Connection): Map<String, String> =
  connection.prepareStatement(
    "SELECT review_run_id, ${ReviewFinishedTelemetryPayloadKeys.EXECUTION_MODE} FROM review_runs",
  ).use { statement ->
    statement.executeQuery().use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          put(
            resultSet.getString(ReviewVerificationSignalKeys.REVIEW_RUN_ID),
            resolvedTier(
              resultSet.getString(
                ReviewFinishedTelemetryPayloadKeys.EXECUTION_MODE,
              )?.let(ReviewExecutionMode::fromWire),
            ),
          )
        }
      }
    }
  }

internal fun stageMetricsByResolvedTier(connection: Connection): Map<String, ReviewStageMetrics> {
  val tiers = loadReviewRunTiers(connection)
  val grouped = linkedMapOf<String, MutableList<Pair<String, List<ReviewFindingVerdict>>>>()
  listOf("inline", "delegated", "unresolved").forEach { grouped[it] = mutableListOf() }
  tiers.forEach { (runId, tier) ->
    grouped.getValue(tier).add(runId to fetchFindingVerdicts(connection, runId))
  }
  return grouped.mapValues { (tier, runs) ->
    val verdicts = runs.flatMap { it.second }
    val findingCount = runs.sumOf { (runId, _) -> queryLatestFindingOutcomes(connection, runId).size }
    aggregateReviewStageMetrics(verdicts, tier, findingCount)
  }
}

private fun distribution(verdicts: List<ReviewFindingVerdict>): ReviewStageVerdictDistribution {
  val dispositions = verdicts.mapNotNull { it.scopeDisposition }
  return ReviewStageVerdictDistribution(
    confirmed = verdicts.count { it.claimVerdict == ReviewClaimVerdict.CONFIRMED },
    refuted = verdicts.count { it.claimVerdict == ReviewClaimVerdict.REFUTED },
    unresolved = verdicts.count { it.claimVerdict == ReviewClaimVerdict.UNRESOLVED },
    inScope = dispositions.count { it == ReviewScopeDisposition.IN_SCOPE },
    outOfScopePreexisting = dispositions.count { it == ReviewScopeDisposition.OUT_OF_SCOPE_PREEXISTING },
    specDeviation = dispositions.count { it == ReviewScopeDisposition.SPEC_DEVIATION },
    specAcceptedTradeoff = dispositions.count { it == ReviewScopeDisposition.SPEC_ACCEPTED_TRADEOFF },
    findingCount = verdicts.size,
  )
}
