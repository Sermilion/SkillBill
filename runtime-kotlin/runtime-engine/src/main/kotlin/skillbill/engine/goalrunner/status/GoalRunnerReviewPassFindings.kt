package skillbill.engine.goalrunner.status

import skillbill.goalrunner.model.GoalRunnerReviewFinding
import skillbill.review.model.NumberedFinding
import skillbill.review.model.ReviewFindingStatus
import skillbill.review.parsing.ReviewFindingActionability

internal fun storedReviewPassFindings(
  findings: List<NumberedFinding>,
  statuses: Map<String, ReviewFindingStatus>,
): List<GoalRunnerReviewFinding> =
  findings
    .filter { ReviewFindingActionability.isActionable(it.claimVerdict, it.scopeDisposition) }
    .map { finding ->
      GoalRunnerReviewFinding(
        ref = finding.findingId,
        severity = finding.severity,
        confidence = finding.confidence.takeIf(String::isNotBlank),
        specialist = finding.laneSkillName,
        location = finding.location.takeIf(String::isNotBlank),
        description = finding.description,
        status = statuses[finding.findingId] ?: ReviewFindingStatus.OPEN,
      )
    }
