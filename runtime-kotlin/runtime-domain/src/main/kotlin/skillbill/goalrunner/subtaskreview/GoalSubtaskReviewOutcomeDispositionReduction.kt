package skillbill.goalrunner.subtaskreview

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.reviewFindingIdentityKey
import skillbill.goalrunner.model.toOutcomeRecord
import skillbill.goalrunner.subtaskreview.model.UnaddressedFindingLedgerScope
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingSettlement
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.parsing.ReviewFindingActionability
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.model.goalreview.normalizeIdentityPart
import skillbill.workflow.model.goalreview.reviewStateError

internal object GoalSubtaskReviewOutcomeDispositionReduction {
  fun reviewFindingOutcomes(
    supersededFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  ): List<ReviewFindingOutcomeRecord> {
    val dispositionsByFindingId = blockerDispositions.associateBy(GoalSubtaskBlockerDisposition::findingId)
    val stillReported = currentFindings.mapTo(mutableSetOf(), UnaddressedFinding::findingKey)
    val dispositionVerdictsByKey =
      supersededFindings.mapNotNull { finding ->
        dispositionsByFindingId[finding.findingId]?.let { finding.findingKey to it.verdict }
      }.toMap()

    fun supersededOutcome(finding: UnaddressedFinding): ReviewFindingOutcome =
      when (dispositionVerdictsByKey[finding.findingKey]) {
        GoalSubtaskBlockerDispositionVerdict.RESOLVED -> ReviewFindingOutcome.ADDRESSED
        GoalSubtaskBlockerDispositionVerdict.UNRESOLVED -> ReviewFindingOutcome.CARRIED
        null -> ReviewFindingOutcome.ADDRESSED
      }

    val supersededOutcomes =
      supersededFindings
        .filter { finding -> finding.findingKey !in stillReported }
        .map { finding -> finding.toOutcomeRecord(supersededOutcome(finding)) }
    val currentOutcomes =
      currentFindings
        .map { finding -> finding.toOutcomeRecord(ReviewFindingOutcome.CARRIED) }
    return supersededOutcomes + currentOutcomes
  }

  fun settledFindingOutcomes(
    output: Any,
    scope: UnaddressedFindingLedgerScope,
  ): List<ReviewFindingOutcomeRecord> {
    val reviewRunId = GoalSubtaskReviewStructuredFindingsParse.reviewRunIdOf(output)
    val openCount = GoalSubtaskReviewStructuredFindingsParse.structuredFindings(output).size
    return GoalSubtaskReviewStructuredFindingsParse.settledFindings(output).mapIndexed { index, finding ->
      ReviewFindingOutcomeRecord(
        workflowId = scope.workflowId,
        reviewPassNumber = scope.reviewPassNumber,
        findingOrdinal = openCount + index + 1,
        outcome =
          if (finding.settlement == ReviewFindingSettlement.FIXED) {
            ReviewFindingOutcome.ADDRESSED
          } else {
            ReviewFindingOutcome.REJECTED
          },
        reviewRunId = reviewRunId,
        findingId = finding.findingId,
        findingKey = reviewFindingIdentityKey(finding.location, finding.message),
      )
    }
  }

  fun repairedFindingOutcomes(
    ledger: List<UnaddressedFinding>,
    receipt: FeatureTaskRuntimeRepairReceipt,
  ): List<ReviewFindingOutcomeRecord> {
    val closed =
      receipt.entries
        .filter { it.outcome != FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED }
        .mapTo(mutableSetOf(), FeatureTaskRuntimeRepairReceiptEntry::findingIdentity)
    val latestPass = ledger.maxOfOrNull(UnaddressedFinding::reviewPassNumber)
    return ledger
      .filter { it.reviewPassNumber == latestPass }
      .filter { it.verificationDisposition != UNADDRESSED_FINDING_REJECTED_DISPOSITION }
      .filter { finding -> finding.findingId?.let(::normalizeIdentityPart) in closed }
      .map { finding -> finding.toOutcomeRecord(ReviewFindingOutcome.ADDRESSED) }
  }

  fun blockerDispositions(
    output: Any,
    priorBlockerFindingIds: List<String> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> {
    val dispositions =
      output.asGoalSubtaskReviewPhaseOutputMap()[SharedPayloadKeys.PRODUCED_OUTPUTS]
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.get("blocker_dispositions")
        ?.let { it as? List<*> }
        ?.mapIndexed(::blockerDisposition)
        .orEmpty()
    if (priorBlockerFindingIds.isEmpty()) return dispositions
    val expected = priorBlockerFindingIds.toSet()
    val emitted = dispositions.map(GoalSubtaskBlockerDisposition::findingId).toSet()
    (emitted - expected).sorted().takeIf { it.isNotEmpty() }?.let { unknown ->
      reviewStateError(
        "produced_outputs.blocker_dispositions",
        "dispositions ${unknown.joinToString()} do not correspond to any Blocker the prior pass " +
          "emitted (expected ${expected.sorted().joinToString()}).",
      )
    }
    return dispositions
  }

  fun refutedBlockerSupersedes(
    priorFindings: List<UnaddressedFinding>,
    currentFindings: List<UnaddressedFinding>,
    recordedVerdicts: List<ReviewFindingVerdict> = emptyList(),
  ): List<GoalSubtaskBlockerDisposition> {
    if (priorFindings.isEmpty()) return emptyList()
    val currentByKey = currentFindings.associateBy(UnaddressedFinding::findingKey)
    val byRef = recordedVerdicts.groupBy(ReviewFindingVerdict::findingRef)
    return priorFindings.mapNotNull { prior ->
      if (prior.severity != "blocker") return@mapNotNull null
      val current = currentByKey[prior.findingKey] ?: return@mapNotNull null
      val findingId = prior.findingId ?: return@mapNotNull null
      val currentId = current.findingId ?: return@mapNotNull null
      val verification =
        ReviewFindingActionability.verificationVerdict(byRef[currentId].orEmpty())
          ?: ReviewFindingActionability.verificationVerdict(byRef[findingId].orEmpty())
      if (verification?.claimVerdict != ReviewClaimVerdict.REFUTED) return@mapNotNull null
      val evidence = verification.citations.map { citation -> "${citation.path}:${citation.line}" }
      if (evidence.isEmpty()) return@mapNotNull null
      GoalSubtaskBlockerDisposition(
        findingId = findingId,
        verdict = GoalSubtaskBlockerDispositionVerdict.RESOLVED,
        evidence = evidence,
      )
    }
  }
}

private fun blockerDisposition(
  index: Int,
  entry: Any?,
): GoalSubtaskBlockerDisposition {
  val path = "produced_outputs.blocker_dispositions[$index]"
  val disposition =
    JsonCodec.anyToStringAnyMap(entry)
      ?: reviewStateError(path, "must be an object.")
  val evidence =
    (disposition["evidence"] as? List<*>)
      ?.mapNotNull { it as? String }
      ?.map(String::trim)
      ?.filter(String::isNotBlank)
      .orEmpty()
  if (evidence.isEmpty()) {
    reviewStateError("$path.evidence", "must cite the specific changed lines that settle the Blocker.")
  }
  return GoalSubtaskBlockerDisposition(
    findingId =
      (disposition[ReviewFindingPayloadKeys.FINDING_ID] as? String)?.trim()?.takeIf(String::isNotBlank)
        ?: reviewStateError("$path.finding_id", "must be a non-blank prior Blocker finding id."),
    verdict =
      GoalSubtaskBlockerDispositionVerdict.fromWire(
        (disposition[SharedPayloadKeys.VERDICT] as? String)?.trim()
          ?: reviewStateError("$path.verdict", "must be resolved or unresolved."),
      ),
    evidence = evidence,
  )
}
