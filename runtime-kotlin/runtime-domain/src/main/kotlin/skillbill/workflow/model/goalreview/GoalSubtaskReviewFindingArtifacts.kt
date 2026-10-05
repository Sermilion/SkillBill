package skillbill.workflow.model.goalreview

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity

internal val GOAL_SUBTASK_REVIEW_PASS_VERDICTS: Set<FeatureTaskRuntimeVerdict> =
  setOf(
    FeatureTaskRuntimeVerdict.APPROVED,
    FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
    FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED,
    FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER,
  )

data class GoalSubtaskReviewCompactFinding(
  val severity: String,
  val label: String,
  val text: String,
  val findingId: String? = null,
) {
  val isBlocker: Boolean get() = severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY

  val blocksAdvance: Boolean get() = severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY || severity == "major"

  init {
    val reason = violation(severity, label, text, findingId)
    require(reason == null) { reason.orEmpty() }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "severity" to severity,
      "label" to label,
      "text" to text,
    ).apply { findingId?.let { put(ReviewFindingPayloadKeys.FINDING_ID, it) } }

  companion object {
    private fun violation(severity: String, label: String, text: String, findingId: String?): String? =
      when {
        severity !in FeatureTaskRuntimeReviewSeverity.entries.map { it.wireValue } ->
          "Invalid review finding severity '$severity'."
        label.isBlank() -> "GoalSubtaskReviewCompactFinding.label must be non-blank."
        text.isBlank() -> "GoalSubtaskReviewCompactFinding.text must be non-blank."
        findingId != null && findingId.isBlank() -> "GoalSubtaskReviewCompactFinding.findingId must be non-blank."
        else -> null
      }

    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
      onInvalid: (String) -> Nothing = { reason -> reviewStateError(path, reason) },
    ): GoalSubtaskReviewCompactFinding {
      raw.requireOnlyReviewStateKeys(setOf("severity", "label", "text", "finding_id"), path)
      val reader = reviewStateReader(raw, path)
      val severity = reader.requiredString("severity")
      val label = reader.requiredString("label")
      val text = reader.requiredString("text")
      val findingId = reader.optionalString("finding_id")
      violation(severity, label, text, findingId)?.let(onInvalid)
      return GoalSubtaskReviewCompactFinding(
        severity = severity,
        label = label,
        text = text,
        findingId = findingId,
      )
    }
  }
}

data class GoalSubtaskReviewPassResult(
  val passNumber: Int,
  val verdict: FeatureTaskRuntimeVerdict,
  val reviewResultArtifact: String,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val executedMode: CodeReviewExecutionMode? = null,
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
) {
  init {
    val reason =
      violation(
        passNumber,
        verdict,
        reviewResultArtifact,
        unresolvedFindingCount,
        executedMode,
        commitFocusedAccounting,
      )
    require(reason == null) { reason.orEmpty() }
  }

  val blocksAdvance: Boolean get() = blocksAdvance(unresolvedFindingCount, findings)

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "pass_number" to passNumber,
      SharedPayloadKeys.VERDICT to verdict.wireValue,
      "review_result_artifact" to reviewResultArtifact,
      "unresolved_finding_count" to unresolvedFindingCount,
      ReviewVerificationSignalKeys.REVIEW_FINDINGS to findings.map(GoalSubtaskReviewCompactFinding::toArtifactMap),
    ).apply {
      executedMode?.let { put("executed_mode", it.wireValue) }
      commitFocusedAccounting?.let { put("commit_focused_accounting", it.toArtifactMap()) }
    }

  companion object {
    private fun violation(
      passNumber: Int,
      verdict: FeatureTaskRuntimeVerdict,
      reviewResultArtifact: String,
      unresolvedFindingCount: Int,
      executedMode: CodeReviewExecutionMode?,
      commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting?,
    ): String? =
      when {
        passNumber < 1 -> "Goal review pass number must be a positive integer."
        verdict !in GOAL_SUBTASK_REVIEW_PASS_VERDICTS ->
          "Goal review pass verdict is invalid: '${verdict.wireValue}'."
        reviewResultArtifact != "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber" ->
          "Goal review result artifact must identify its exact review pass."
        unresolvedFindingCount < 0 -> "Goal unresolved finding count must be non-negative."
        commitFocusedAccounting != null && executedMode == CodeReviewExecutionMode.INLINE ->
          "An inline review pass has no delegated commit sequence and must omit commit-focused accounting."
        else -> null
      }

    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
      onInvalid: (String) -> Nothing = { reason -> reviewStateError(path, reason) },
    ): GoalSubtaskReviewPassResult {
      raw.requireOnlyReviewStateKeys(
        setOf(
          "pass_number",
          "verdict",
          "review_result_artifact",
          "unresolved_finding_count",
          "findings",
          "executed_mode",
          "commit_focused_accounting",
        ),
        path,
      )
      val reader = reviewStateReader(raw, path)
      val findings =
        reader.requiredList("findings").mapIndexed { index, value ->
          GoalSubtaskReviewCompactFinding.fromArtifactMap(
            value.toReviewStateMap("$path.findings[$index]"),
            "$path.findings[$index]",
            onInvalid = onInvalid,
          )
        }
      val passNumber = reader.requiredInt("pass_number")
      val verdict = FeatureTaskRuntimeVerdict.fromWire(reader.requiredString("verdict"))
      val reviewResultArtifact = reader.requiredString("review_result_artifact")
      val unresolvedFindingCount = reader.requiredInt("unresolved_finding_count")
      val executedMode = reader.optionalString("executed_mode")?.let { wire ->
        CodeReviewExecutionMode.fromWireOrNull(wire)
          ?: onInvalid(CodeReviewExecutionMode.unknownWireValueMessage(wire))
      }
      val commitFocusedAccounting =
        raw["commit_focused_accounting"]?.let {
          GoalSubtaskCommitFocusedAccounting.fromArtifactMap(
            it.toReviewStateMap("$path.commit_focused_accounting"),
            "$path.commit_focused_accounting",
            onInvalid = onInvalid,
          )
        }
      violation(
        passNumber,
        verdict,
        reviewResultArtifact,
        unresolvedFindingCount,
        executedMode,
        commitFocusedAccounting,
      )?.let(onInvalid)
      return GoalSubtaskReviewPassResult(
        passNumber = passNumber,
        verdict = verdict,
        reviewResultArtifact = reviewResultArtifact,
        unresolvedFindingCount = unresolvedFindingCount,
        findings = findings,
        executedMode = executedMode,
        commitFocusedAccounting = commitFocusedAccounting,
      )
    }
  }
}
