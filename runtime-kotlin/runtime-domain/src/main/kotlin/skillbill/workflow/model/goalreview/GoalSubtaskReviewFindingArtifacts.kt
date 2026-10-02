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
    require(severity in FeatureTaskRuntimeReviewSeverity.entries.map { it.wireValue }) {
      "Invalid review finding severity '$severity'."
    }
    require(label.isNotBlank()) { "GoalSubtaskReviewCompactFinding.label must be non-blank." }
    require(text.isNotBlank()) { "GoalSubtaskReviewCompactFinding.text must be non-blank." }
    findingId?.let { require(it.isNotBlank()) { "GoalSubtaskReviewCompactFinding.findingId must be non-blank." } }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "severity" to severity,
      "label" to label,
      "text" to text,
    ).apply { findingId?.let { put(ReviewFindingPayloadKeys.FINDING_ID, it) } }

  companion object {
    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
    ): GoalSubtaskReviewCompactFinding {
      raw.requireOnlyReviewStateKeys(setOf("severity", "label", "text", "finding_id"), path)
      val reader = reviewStateReader(raw, path)
      return GoalSubtaskReviewCompactFinding(
        severity = reader.requiredString("severity"),
        label = reader.requiredString("label"),
        text = reader.requiredString("text"),
        findingId = reader.optionalString("finding_id"),
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
    require(passNumber >= 1) { "Goal review pass number must be a positive integer." }
    require(verdict in GOAL_SUBTASK_REVIEW_PASS_VERDICTS) {
      "Goal review pass verdict is invalid: '${verdict.wireValue}'."
    }
    require(reviewResultArtifact == "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber") {
      "Goal review result artifact must identify its exact review pass."
    }
    require(unresolvedFindingCount >= 0) { "Goal unresolved finding count must be non-negative." }
    require(commitFocusedAccounting == null || executedMode != CodeReviewExecutionMode.INLINE) {
      "An inline review pass has no delegated commit sequence and must omit commit-focused accounting."
    }
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
    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
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
          )
        }
      return GoalSubtaskReviewPassResult(
        passNumber = reader.requiredInt("pass_number"),
        verdict = FeatureTaskRuntimeVerdict.fromWire(reader.requiredString("verdict")),
        reviewResultArtifact = reader.requiredString("review_result_artifact"),
        unresolvedFindingCount = reader.requiredInt("unresolved_finding_count"),
        findings = findings,
        executedMode = reader.optionalString("executed_mode")?.let(CodeReviewExecutionMode::fromWire),
        commitFocusedAccounting =
          raw["commit_focused_accounting"]?.let {
            GoalSubtaskCommitFocusedAccounting.fromArtifactMap(
              it.toReviewStateMap("$path.commit_focused_accounting"),
              "$path.commit_focused_accounting",
            )
          },
      )
    }
  }
}
