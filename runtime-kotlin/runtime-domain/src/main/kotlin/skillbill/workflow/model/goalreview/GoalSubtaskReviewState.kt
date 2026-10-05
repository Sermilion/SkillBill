package skillbill.workflow.model.goalreview

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.scaffold.wire.optionalList
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.identity.subtask.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidGoalSubtaskReviewStateSchemaError
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
    val reason = validation().violation()
    require(reason == null) { reason.orEmpty() }
  }

  private fun validation() =
    GoalSubtaskReviewStateValidation(
      contractVersion,
      reviewBaseSha,
      baselineUntrackedPaths,
      codeReviewMode,
      reservedPassNumber,
      completedPassCount,
      disposition,
      reviewedTargetSha,
      reviewedTreeSha,
      passResults,
      emittedPassCount,
      blockerDispositions,
      operatorDecision,
      resolvedTier,
      remediationBaseSha,
      repairReceipts,
    )

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
    private val ARTIFACT_KEYS =
      setOf(
        "contract_version",
        "review_base_sha",
        "baseline_untracked_paths",
        "code_review_mode",
        "reserved_pass_number",
        "completed_pass_count",
        "disposition",
        "review_input_artifact",
        "reviewed_delta_digest",
        "reviewed_target_sha",
        "reviewed_tree_sha",
        "pass_results",
        "emitted_pass_count",
        "blocker_dispositions",
        "operator_decision",
        "operator_retry_rounds",
        "resolved_tier",
        "deciding_rule",
        "remediation_base_sha",
        "repair_receipts",
      )

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
      raw.requireOnlyReviewStateKeys(ARTIFACT_KEYS, sourceLabel)
      val reader = reviewStateReader(raw, sourceLabel)
      val contractVersion = reader.requiredString("contract_version")
      val reviewBaseSha = reader.requiredString("review_base_sha")
      val baselineUntrackedPaths = decodeBaselineUntrackedPaths(raw, sourceLabel)
      val codeReviewModeWire = reader.requiredString("code_review_mode")
      val codeReviewMode =
        CodeReviewExecutionMode.fromWireOrNull(codeReviewModeWire)
          ?: reviewStateError(sourceLabel, CodeReviewExecutionMode.unknownWireValueMessage(codeReviewModeWire))
      val reservedPassNumber = reader.optionalInt("reserved_pass_number")
      val completedPassCount = reader.requiredInt("completed_pass_count")
      val disposition = GoalSubtaskReviewDisposition.fromWire(reader.requiredString("disposition"))
      val reviewInputArtifact = reader.optionalString("review_input_artifact")
      val reviewedDeltaDigest = reader.optionalString("reviewed_delta_digest")
      val reviewedTargetSha = reader.optionalString("reviewed_target_sha")
      val reviewedTreeSha = reader.optionalString("reviewed_tree_sha")
      val passResults = decodePassResults(raw, sourceLabel)
      val emittedPassCount = reader.requiredInt("emitted_pass_count")
      val blockerDispositions = decodeBlockerDispositions(raw, sourceLabel)
      val operatorDecision = reader.optionalString("operator_decision")?.let(GoalSubtaskOperatorDecision::fromWire)
      val operatorRetryRounds = reader.optionalInt("operator_retry_rounds") ?: 0
      val resolvedTier = decodeResolvedTier(raw, sourceLabel)
      val decidingRule = reader.optionalString("deciding_rule")
      val remediationBaseSha = reader.optionalString("remediation_base_sha")
      val repairReceipts = decodeRepairReceipts(raw, sourceLabel)
      val violation =
        GoalSubtaskReviewStateValidation(
          contractVersion,
          reviewBaseSha,
          baselineUntrackedPaths,
          codeReviewMode,
          reservedPassNumber,
          completedPassCount,
          disposition,
          reviewedTargetSha,
          reviewedTreeSha,
          passResults,
          emittedPassCount,
          blockerDispositions,
          operatorDecision,
          resolvedTier,
          remediationBaseSha,
          repairReceipts,
        ).violation()
      if (violation != null) reviewStateError(sourceLabel, violation)
      return GoalSubtaskReviewState(
        contractVersion = contractVersion,
        reviewBaseSha = reviewBaseSha,
        baselineUntrackedPaths = baselineUntrackedPaths,
        codeReviewMode = codeReviewMode,
        reservedPassNumber = reservedPassNumber,
        completedPassCount = completedPassCount,
        disposition = disposition,
        reviewInputArtifact = reviewInputArtifact,
        reviewedDeltaDigest = reviewedDeltaDigest,
        reviewedTargetSha = reviewedTargetSha,
        reviewedTreeSha = reviewedTreeSha,
        passResults = passResults,
        emittedPassCount = emittedPassCount,
        blockerDispositions = blockerDispositions,
        operatorDecision = operatorDecision,
        operatorRetryRounds = operatorRetryRounds,
        resolvedTier = resolvedTier,
        decidingRule = decidingRule,
        remediationBaseSha = remediationBaseSha,
        repairReceipts = repairReceipts,
      )
    }

    private fun decodeBaselineUntrackedPaths(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<String> =
      reviewStateReader(raw, sourceLabel).optionalList("baseline_untracked_paths")
        ?.mapIndexed { index, value ->
          (value as? String)?.takeIf(String::isNotBlank)
            ?: reviewStateError("$sourceLabel.baseline_untracked_paths[$index]", "must be a non-blank string.")
        }.orEmpty()

    private fun decodeResolvedTier(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): CodeReviewExecutionMode? =
      reviewStateReader(raw, sourceLabel).optionalString("resolved_tier")?.let { wire ->
        CodeReviewExecutionMode.fromWireOrNull(wire)
          ?: reviewStateError(sourceLabel, CodeReviewExecutionMode.unknownWireValueMessage(wire))
      }

    private fun decodePassResults(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<GoalSubtaskReviewPassResult> =
      reviewStateReader(raw, sourceLabel).requiredList("pass_results").mapIndexed { index, value ->
        GoalSubtaskReviewPassResult.fromArtifactMap(
          value.toReviewStateMap("$sourceLabel.pass_results[$index]"),
          "$sourceLabel.pass_results[$index]",
          onInvalid = { reason -> reviewStateError(sourceLabel, reason) },
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
            onInvalid = { reason -> reviewStateError(sourceLabel, reason) },
          )
        }.orEmpty()

    private fun decodeRepairReceipts(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<FeatureTaskRuntimeRepairReceipt> =
      reviewStateReader(raw, sourceLabel).optionalList("repair_receipts")
        ?.mapIndexed { index, value ->
          val path = "$sourceLabel.repair_receipts[$index]"
          FeatureTaskRuntimeRepairReceipt.fromArtifactMap(
            value.toReviewStateMap(path),
            path,
            onInvalid = { reason, failure -> reviewStateError(path, reason, failure) },
          )
        }.orEmpty()
  }
}

private data class GoalSubtaskReviewStateValidation(
  val contractVersion: String,
  val reviewBaseSha: String,
  val baselineUntrackedPaths: List<String>,
  val codeReviewMode: CodeReviewExecutionMode,
  val reservedPassNumber: Int?,
  val completedPassCount: Int,
  val disposition: GoalSubtaskReviewDisposition,
  val reviewedTargetSha: String?,
  val reviewedTreeSha: String?,
  val passResults: List<GoalSubtaskReviewPassResult>,
  val emittedPassCount: Int,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition>,
  val operatorDecision: GoalSubtaskOperatorDecision?,
  val resolvedTier: CodeReviewExecutionMode?,
  val remediationBaseSha: String?,
  val repairReceipts: List<FeatureTaskRuntimeRepairReceipt>,
) {
  fun violation(): String? = identityViolation() ?: baselineViolation() ?: passViolation() ?: dispositionViolation()

  private fun identityViolation(): String? =
    when {
      contractVersion != GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION ->
        "Unsupported goal review state contract '$contractVersion'. " +
          "Records written before $GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION pin a worktree review " +
          "baseline and carry no reviewed commit identity, are rejected, and must be regenerated."
      resolvedTier == CodeReviewExecutionMode.AUTO ->
        "Goal review resolved tier must be a concrete mode, never 'auto'."
      !GIT_COMMIT_SHA.matches(reviewBaseSha) ->
        "Goal review base SHA must be a 40- or 64-character lowercase commit SHA."
      remediationBaseSha != null && !GIT_COMMIT_SHA.matches(remediationBaseSha) ->
        "Goal remediation base SHA must be a 40- or 64-character lowercase commit SHA."
      else -> reviewedIdentityViolation()
    }

  private fun reviewedIdentityViolation(): String? =
    listOf("reviewed target" to reviewedTargetSha, "reviewed tree" to reviewedTreeSha)
      .firstNotNullOfOrNull { (label, sha) ->
        if (sha != null && !GIT_COMMIT_SHA.matches(sha)) {
          "Goal $label SHA must be a 40- or 64-character lowercase object SHA."
        } else {
          null
        }
      }

  private fun baselineViolation(): String? {
    if (baselineUntrackedPaths.any(String::isBlank)) return "Baseline untracked paths must be non-blank."
    if (baselineUntrackedPaths != baselineUntrackedPaths.distinct().sorted()) {
      return "Baseline untracked paths must be sorted and unique."
    }
    return null
  }

  private fun passViolation(): String? =
    when {
      completedPassCount < 0 -> "Completed review passes must be non-negative."
      passResults.size != completedPassCount -> "Pass result count must equal completed pass count."
      passResults.map(GoalSubtaskReviewPassResult::passNumber) != (1..completedPassCount).toList() ->
        "Pass results must be ordered and contiguous."
      else -> executedModeViolation()
    } ?: reservationViolation()

  private fun reservationViolation(): String? =
    when {
      reservedPassNumber != null && reservedPassNumber != completedPassCount + 1 ->
        "Reserved pass must be the next permitted review pass."
      emittedPassCount !in 0..completedPassCount -> "Emitted pass count cannot exceed completed pass count."
      else -> null
    }

  private fun executedModeViolation(): String? =
    passResults.firstNotNullOfOrNull { result ->
      val executedMode = result.executedMode
      if (
        executedMode != null &&
        executedMode != FeatureTaskRuntimeReviewPassSequence.modeForPass(codeReviewMode, result.passNumber)
      ) {
        "Pass ${result.passNumber} executed mode must match the immutable review pass sequence."
      } else {
        null
      }
    }

  private fun dispositionViolation(): String? =
    when {
      disposition == GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED &&
        (completedPassCount < 1 || passResults.lastOrNull()?.blocksAdvance != true) ->
        "review_cap_reached requires unresolved Blocker or Major findings on a completed pass."
      blockerDispositions.map(GoalSubtaskBlockerDisposition::findingId).distinct().size != blockerDispositions.size ->
        "Each prior Blocker may carry exactly one disposition."
      disposition == GoalSubtaskReviewDisposition.PAUSED &&
        blockerDispositions.none { it.verdict == GoalSubtaskBlockerDispositionVerdict.UNRESOLVED } &&
        passResults.lastOrNull()?.blocksAdvance != true ->
        "paused requires an unresolved Blocker disposition or a Blocker or Major " +
          "the remediation pass itself introduced."
      operatorDecision != null && disposition != GoalSubtaskReviewDisposition.PAUSED ->
        "An operator decision is only recorded against a paused subtask."
      repairReceipts.map(FeatureTaskRuntimeRepairReceipt::roundNumber).distinct().size != repairReceipts.size ->
        "Each remediation round may carry exactly one repair receipt."
      else -> null
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
  throw invalidGoalSubtaskReviewStateSchemaError(
    sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    fieldPath = fieldPath,
    reason = reason,
    cause = cause,
  )
