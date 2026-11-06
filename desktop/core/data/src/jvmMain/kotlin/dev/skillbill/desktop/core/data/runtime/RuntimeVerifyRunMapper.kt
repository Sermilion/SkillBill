package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.VerifyCompletenessAudit
import dev.skillbill.desktop.core.domain.model.VerifyCriterionGrade
import dev.skillbill.desktop.core.domain.model.VerifyFlagAudit
import dev.skillbill.desktop.core.domain.model.VerifyFlagCheck
import dev.skillbill.desktop.core.domain.model.VerifyReviewFinding
import dev.skillbill.desktop.core.domain.model.VerifyReviewSection
import dev.skillbill.desktop.core.domain.model.VerifyRunCriterion
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.VerifyRunStep
import dev.skillbill.desktop.core.domain.model.VerifyRunVerdict
import dev.skillbill.desktop.core.domain.model.VerifyTestValueRow
import dev.skillbill.desktop.core.domain.model.VerifyUnitTestValue
import skillbill.ports.idestatus.model.VerifyRunDetails as RuntimeVerifyRunDetails
import kotlin.time.toKotlinInstant

internal fun RuntimeVerifyRunDetails.toDomain() = VerifyRunDetails(
  workflowId, issueKey, workflowStatus, currentStepId, startedAt?.toKotlinInstant(), finishedAt?.toKotlinInstant(), intake, target,
  repoRoot, baseRevision, headRevision, reviewMode, qaSelection, specPath,
  steps.map { VerifyRunStep(it.stepId, it.status, it.attemptCount) }, criteria.map { VerifyRunCriterion(it.number, it.text) },
  criteriaRaw, nonGoals, technicalConstraints, rolloutExpectation,
  completenessAudit?.let { audit -> VerifyCompletenessAudit(audit.verdict, audit.findings, audit.grades.map { grade ->
    VerifyCriterionGrade(grade.number, grade.status.name, grade.text, grade.evidence, grade.missing, grade.notFound, grade.locations)
  }) },
  reviewFindings?.let { review -> VerifyReviewSection(review.verdict, review.findings, review.items.map {
    VerifyReviewFinding(it.ref, it.severity, it.confidence, it.location, it.description, it.specialists, it.locations)
  }) },
  flagAudit?.let { flag -> VerifyFlagAudit(flag.verdict, flag.findings, flag.skipReason, flag.flagName, flag.pattern, flag.issues, flag.checks.map { VerifyFlagCheck(it.passed, it.text) }) },
  unitTestValue?.let { tests -> VerifyUnitTestValue(tests.verdict, tests.findings, tests.skipReason, tests.overall, tests.summary,
    tests.rows.map { VerifyTestValueRow(it.area, it.verdict, it.confidence, it.files, it.why, it.action, it.locations) }, tests.keep, tests.rewrite, tests.delete, tests.missingCases) },
  changedFiles, verdict?.let { VerifyRunVerdict(it.verdict, it.findings, it.summary) }, qa?.toDomain(), qaVerdict, qaOutcome,
  qaFindingLocations, supersededBy, staleReason, qaReportText,
)
