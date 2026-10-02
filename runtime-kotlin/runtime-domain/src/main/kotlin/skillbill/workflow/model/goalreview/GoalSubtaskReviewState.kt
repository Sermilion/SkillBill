package skillbill.workflow.model.goalreview

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.scaffold.wire.optionalList
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.identity.subtask.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewPassSequence

data class GoalSubtaskReviewRevision(
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
  val reviewedRevision: GoalSubtaskReviewedRevision? = null,
)

data class GoalSubtaskReviewState(
  val reviewBaseSha: String,
  val baselineUntrackedPaths: List<String> = emptyList(),
  val codeReviewMode: CodeReviewExecutionMode,
  val reservedPassNumber: Int? = null,
  val completedPassCount: Int = 0,
  val disposition: GoalSubtaskReviewDisposition = GoalSubtaskReviewDisposition.PENDING,
  val reviewInputArtifact: String? = null,
  val reviewedDeltaDigest: String? = null,
  val reviewedTargetSha: String? = null,
  val reviewedTreeSha: String? = null,
  val passResults: List<GoalSubtaskReviewPassResult> = emptyList(),
  val emittedPassCount: Int = 0,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  val operatorDecision: GoalSubtaskOperatorDecision? = null,
  val operatorRetryRounds: Int = 0,
  val resolvedTier: CodeReviewExecutionMode? = null,
  val decidingRule: String? = null,
  val remediationBaseSha: String? = null,
  val repairReceipts: List<FeatureTaskRuntimeRepairReceipt> = emptyList(),
  val contractVersion: String = GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION,
) {
  init {
    require(contractVersion == GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION) {
      "Unsupported goal review state contract '$contractVersion'. " +
        "Records written before $GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION pin a worktree review " +
        "baseline and carry no reviewed commit identity, are rejected, and must be regenerated."
    }
    resolvedTier?.let { tier ->
      require(tier != CodeReviewExecutionMode.AUTO) {
        "Goal review resolved tier must be a concrete mode, never 'auto'."
      }
    }
    require(GIT_COMMIT_SHA.matches(reviewBaseSha)) {
      "Goal review base SHA must be a 40- or 64-character lowercase commit SHA."
    }
    remediationBaseSha?.let { sha ->
      require(GIT_COMMIT_SHA.matches(sha)) {
        "Goal remediation base SHA must be a 40- or 64-character lowercase commit SHA."
      }
    }
    listOf("reviewed target" to reviewedTargetSha, "reviewed tree" to reviewedTreeSha).forEach { (label, sha) ->
      sha?.let {
        require(GIT_COMMIT_SHA.matches(it)) {
          "Goal $label SHA must be a 40- or 64-character lowercase object SHA."
        }
      }
    }
    require(baselineUntrackedPaths.all(String::isNotBlank)) { "Baseline untracked paths must be non-blank." }
    require(baselineUntrackedPaths == baselineUntrackedPaths.distinct().sorted()) {
      "Baseline untracked paths must be sorted and unique."
    }
    require(completedPassCount >= 0) { "Completed review passes must be non-negative." }
    require(passResults.size == completedPassCount) { "Pass result count must equal completed pass count." }
    require(passResults.map(GoalSubtaskReviewPassResult::passNumber) == (1..completedPassCount).toList()) {
      "Pass results must be ordered and contiguous."
    }
    passResults.forEach { result ->
      result.executedMode?.let { executedMode ->
        require(executedMode == FeatureTaskRuntimeReviewPassSequence.modeForPass(codeReviewMode, result.passNumber)) {
          "Pass ${result.passNumber} executed mode must match the immutable review pass sequence."
        }
      }
    }
    reservedPassNumber?.let { reserved ->
      require(reserved == completedPassCount + 1) {
        "Reserved pass must be the next permitted review pass."
      }
    }
    require(emittedPassCount in 0..completedPassCount) { "Emitted pass count cannot exceed completed pass count." }
    require(
      disposition != GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED ||
        (
          completedPassCount >= 1 &&
            passResults.lastOrNull()?.blocksAdvance == true
        ),
    ) { "review_cap_reached requires unresolved Blocker or Major findings on a completed pass." }
    require(
      blockerDispositions.map(GoalSubtaskBlockerDisposition::findingId).distinct().size == blockerDispositions.size,
    ) {
      "Each prior Blocker may carry exactly one disposition."
    }
    require(
      disposition != GoalSubtaskReviewDisposition.PAUSED ||
        blockerDispositions.any { it.verdict == GoalSubtaskBlockerDispositionVerdict.UNRESOLVED } ||
        passResults.lastOrNull()?.blocksAdvance == true,
    ) {
      "paused requires an unresolved Blocker disposition or a Blocker or Major the remediation pass itself introduced."
    }
    require(operatorDecision == null || disposition == GoalSubtaskReviewDisposition.PAUSED) {
      "An operator decision is only recorded against a paused subtask."
    }
    require(repairReceipts.map(FeatureTaskRuntimeRepairReceipt::roundNumber).distinct().size == repairReceipts.size) {
      "Each remediation round may carry exactly one repair receipt."
    }
  }

  val repairLedger: FeatureTaskRuntimeRepairLedger
    get() = featureTaskRuntimeFoldRepairLedger(repairReceipts, passResults)

  val priorReviewContext: FeatureTaskRuntimePriorReviewContext?
    get() =
      passResults.lastOrNull()?.let { previous ->
        FeatureTaskRuntimePriorReviewContext(
          passNumber = previous.passNumber,
          findings = previous.findings,
          dispositions = blockerDispositions,
        ).takeUnless(FeatureTaskRuntimePriorReviewContext::isEmpty)
      }

  val reviewCapReached: Boolean get() = disposition == GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED

  val reviewSkippedByUser: Boolean get() =
    passResults.lastOrNull()?.verdict == FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER

  fun reserveNextPass(): GoalSubtaskReviewState =
    when {
      reviewCapReached -> this
      reviewSkippedByUser -> this
      reservedPassNumber != null -> this
      completedPassCount >= 1 -> this
      else -> copy(reservedPassNumber = 1)
    }

  fun completeReservedPass(
    verdict: FeatureTaskRuntimeVerdict,
    unresolvedFindingCount: Int,
    findings: List<GoalSubtaskReviewCompactFinding>,
    blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
    revision: GoalSubtaskReviewRevision = GoalSubtaskReviewRevision(),
  ): GoalSubtaskReviewState {
    if (reservedPassNumber == null && passResults.isNotEmpty()) {
      return this
    }
    val effectiveCommitFocusedAccounting = revision.commitFocusedAccounting
    val reviewedRevision = revision.reviewedRevision
    val passNumber =
      reservedPassNumber
        ?: reviewStateError("reserved_pass_number", "must be present before completing a review pass.")
    require(
      blockerDispositions.map(GoalSubtaskBlockerDisposition::findingId).distinct().size == blockerDispositions.size,
    ) {
      "Each prior Blocker may carry exactly one disposition."
    }
    val disposedPass = blockerDispositions.isNotEmpty()
    val executedMode = FeatureTaskRuntimeReviewPassSequence.modeForPass(codeReviewMode, passNumber)
    val result =
      GoalSubtaskReviewPassResult(
        passNumber = passNumber,
        verdict = verdict,
        reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber",
        unresolvedFindingCount = unresolvedFindingCount,
        findings = findings,
        executedMode = executedMode,
        commitFocusedAccounting =
          effectiveCommitFocusedAccounting
            ?.takeIf { executedMode != CodeReviewExecutionMode.INLINE },
      )
    return copy(
      reservedPassNumber = null,
      completedPassCount = passNumber,
      disposition = GoalSubtaskReviewDisposition.PENDING,
      reviewedTargetSha = reviewedRevision?.targetSha ?: reviewedTargetSha,
      reviewedTreeSha = reviewedRevision?.treeSha ?: reviewedTreeSha,
      passResults = passResults + result,
      blockerDispositions = if (disposedPass) blockerDispositions else this.blockerDispositions,
      operatorDecision = null,
      operatorRetryRounds = 0,
    )
  }

  fun approvalCovers(revision: GoalSubtaskReviewedRevision): Boolean =
    reviewedTargetSha == revision.targetSha && reviewedTreeSha == revision.treeSha

  fun approvalInvalidatedBy(revision: GoalSubtaskReviewedRevision): Boolean =
    reviewedTargetSha != null && reviewedTreeSha != null && !approvalCovers(revision)

  val pausedForOperatorDecision: Boolean get() = disposition == GoalSubtaskReviewDisposition.PAUSED

  val unresolvedBlockerDispositions: List<GoalSubtaskBlockerDisposition>
    get() = blockerDispositions.filter { it.verdict == GoalSubtaskBlockerDispositionVerdict.UNRESOLVED }

  internal fun boundedDispositionSummary(): Map<String, Any?> =
    linkedMapOf(
      "pass" to completedPassCount,
      "disposition_counts" to
        GoalSubtaskBlockerDispositionVerdict.entries.associate { verdict ->
          verdict.wireValue to blockerDispositions.count { it.verdict == verdict }
        },
      "verdicts" to blockerDispositions.map { it.verdict.wireValue },
    )

  fun acknowledgeSummariesThrough(passNumber: Int): GoalSubtaskReviewState =
    copy(emittedPassCount = passNumber.coerceIn(emittedPassCount, completedPassCount))

  fun toPersistenceWire(): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
      "review_base_sha" to reviewBaseSha,
      "code_review_mode" to codeReviewMode.wireValue,
      "completed_pass_count" to completedPassCount,
      "disposition" to disposition.wireValue,
      "pass_results" to passResults.map(GoalSubtaskReviewPassResult::toArtifactMap),
      "emitted_pass_count" to emittedPassCount,
      "blocker_dispositions" to blockerDispositions.map(GoalSubtaskBlockerDisposition::toArtifactMap),
    ).apply {
      if (baselineUntrackedPaths.isNotEmpty()) put("baseline_untracked_paths", baselineUntrackedPaths)
      reservedPassNumber?.let { put("reserved_pass_number", it) }
      reviewInputArtifact?.let { put("review_input_artifact", it) }
      reviewedDeltaDigest?.let { put("reviewed_delta_digest", it) }
      reviewedTargetSha?.let { put("reviewed_target_sha", it) }
      reviewedTreeSha?.let { put("reviewed_tree_sha", it) }
      operatorDecision?.let { put("operator_decision", it.wireValue) }
      if (operatorRetryRounds > 0) put("operator_retry_rounds", operatorRetryRounds)
      resolvedTier?.let { put("resolved_tier", it.wireValue) }
      decidingRule?.let { put("deciding_rule", it) }
      remediationBaseSha?.let { put("remediation_base_sha", it) }
      if (repairReceipts.isNotEmpty()) {
        put("repair_receipts", repairReceipts.map(FeatureTaskRuntimeRepairReceipt::toArtifactMap))
      }
    }

  companion object {
    fun initial(
      reviewBaseSha: String,
      baselineUntrackedPaths: Collection<String> = emptyList(),
      codeReviewMode: CodeReviewExecutionMode,
    ): GoalSubtaskReviewState =
      GoalSubtaskReviewState(
        reviewBaseSha = reviewBaseSha,
        baselineUntrackedPaths =
          baselineUntrackedPaths.map(
            String::trim,
          ).filter(String::isNotBlank).distinct().sorted(),
        codeReviewMode = codeReviewMode,
      )

    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      sourceLabel: String = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    ): GoalSubtaskReviewState {
      raw.requireOnlyReviewStateKeys(
        setOf(
          "contract_version", "review_base_sha", "baseline_untracked_paths", "code_review_mode", "reserved_pass_number",
          "completed_pass_count", "disposition", "review_input_artifact", "reviewed_delta_digest",
          "reviewed_target_sha", "reviewed_tree_sha", "pass_results",
          "emitted_pass_count", "blocker_dispositions", "operator_decision", "operator_retry_rounds",
          "resolved_tier", "deciding_rule",
          "remediation_base_sha",
          "repair_receipts",
        ),
        sourceLabel,
      )
      return try {
        val reader = reviewStateReader(raw, sourceLabel)
        GoalSubtaskReviewState(
          contractVersion = reader.requiredString("contract_version"),
          reviewBaseSha = reader.requiredString("review_base_sha"),
          baselineUntrackedPaths =
            reader.optionalList("baseline_untracked_paths")
              ?.mapIndexed { index, value ->
                (value as? String)?.takeIf(String::isNotBlank)
                  ?: reviewStateError("$sourceLabel.baseline_untracked_paths[$index]", "must be a non-blank string.")
              }
              .orEmpty(),
          codeReviewMode =
            CodeReviewExecutionMode.fromWire(
              reader.requiredString("code_review_mode"),
            ),
          reservedPassNumber = reader.optionalInt("reserved_pass_number"),
          completedPassCount = reader.requiredInt("completed_pass_count"),
          disposition = GoalSubtaskReviewDisposition.fromWire(reader.requiredString("disposition")),
          reviewInputArtifact = reader.optionalString("review_input_artifact"),
          reviewedDeltaDigest = reader.optionalString("reviewed_delta_digest"),
          reviewedTargetSha = reader.optionalString("reviewed_target_sha"),
          reviewedTreeSha = reader.optionalString("reviewed_tree_sha"),
          passResults = decodePassResults(raw, sourceLabel),
          emittedPassCount = reader.requiredInt("emitted_pass_count"),
          blockerDispositions = decodeBlockerDispositions(raw, sourceLabel),
          operatorDecision =
            reader.optionalString("operator_decision")
              ?.let(GoalSubtaskOperatorDecision::fromWire),
          operatorRetryRounds = reader.optionalInt("operator_retry_rounds") ?: 0,
          resolvedTier =
            reader.optionalString("resolved_tier")
              ?.let(CodeReviewExecutionMode::fromWire),
          decidingRule = reader.optionalString("deciding_rule"),
          remediationBaseSha = reader.optionalString("remediation_base_sha"),
          repairReceipts = decodeRepairReceipts(raw, sourceLabel),
        )
      } catch (error: InvalidGoalSubtaskReviewStateSchemaError) {
        throw error
      } catch (error: IllegalArgumentException) {
        reviewStateError(sourceLabel, error.message.orEmpty(), error)
      }
    }

    private fun decodePassResults(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<GoalSubtaskReviewPassResult> =
      reviewStateReader(raw, sourceLabel).requiredList("pass_results").mapIndexed { index, value ->
        GoalSubtaskReviewPassResult.fromArtifactMap(
          value.toReviewStateMap("$sourceLabel.pass_results[$index]"),
          "$sourceLabel.pass_results[$index]",
        )
      }

    private fun decodeBlockerDispositions(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<GoalSubtaskBlockerDisposition> =
      reviewStateReader(raw, sourceLabel).optionalList("blocker_dispositions")
        ?.mapIndexed { index, value ->
          GoalSubtaskBlockerDisposition.fromArtifactMap(
            value.toReviewStateMap("$sourceLabel.blocker_dispositions[$index]"),
            "$sourceLabel.blocker_dispositions[$index]",
          )
        }.orEmpty()

    private fun decodeRepairReceipts(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<FeatureTaskRuntimeRepairReceipt> =
      reviewStateReader(raw, sourceLabel).optionalList("repair_receipts")
        ?.mapIndexed { index, value ->
          try {
            FeatureTaskRuntimeRepairReceipt.fromArtifactMap(
              value.toReviewStateMap("$sourceLabel.repair_receipts[$index]"),
              "$sourceLabel.repair_receipts[$index]",
            )
          } catch (error: InvalidFeatureTaskRuntimeRepairReceiptError) {
            reviewStateError("$sourceLabel.repair_receipts[$index]", error.payloadFreeReason, error)
          }
        }.orEmpty()
  }
}

internal fun blocksAdvance(
  unresolvedFindingCount: Int,
  findings: List<GoalSubtaskReviewCompactFinding>,
): Boolean =
  unresolvedFindingCount > 0 && (findings.isEmpty() || findings.any(GoalSubtaskReviewCompactFinding::blocksAdvance))

data class GoalSubtaskReviewedRevision(val targetSha: String, val treeSha: String) {
  init {
    require(GIT_COMMIT_SHA.matches(targetSha) && GIT_COMMIT_SHA.matches(treeSha)) {
      "A reviewed revision needs 40- or 64-character lowercase target and tree SHAs."
    }
  }
}

private val GIT_COMMIT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")

internal fun reviewStateError(
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): Nothing =
  throw InvalidGoalSubtaskReviewStateSchemaError(
    sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    fieldPath = fieldPath,
    reason = reason,
    cause = cause,
  )
