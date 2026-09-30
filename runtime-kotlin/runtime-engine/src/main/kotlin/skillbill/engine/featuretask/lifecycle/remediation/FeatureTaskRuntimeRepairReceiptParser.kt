package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.featureTaskRuntimeRemediationRoundNumber

private const val MAX_REPORT_ENTRIES = 50
private const val MAX_REASON_CHARS = 300

private val SEGMENT_BREAK = Regex("""\R|;|(?<=[.!?])\s""")
private val UNRESOLVED_CUE =
  Regex(
    """\b(unresolved|still (?:open|stands|fails|reproduces)|not (?:fixed|addressed|resolved)|""" +
      """could(?: not|n't)|unable|cannot|can't|blocked|deferred|remains?)\b""",
    RegexOption.IGNORE_CASE,
  )
private val NO_EDIT_CUE =
  Regex(
    """no[_ -]edit|no (?:code )?change|already (?:fixed|handled|addressed|correct)|not a defect|""" +
      """false positive|disregard""",
    RegexOption.IGNORE_CASE,
  )

fun featureTaskRuntimeProseMentions(
  prose: String,
  findingId: String,
): List<String> {
  val mention = Regex("""(?<![A-Za-z0-9-])${Regex.escape(findingId)}(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
  return prose.split(SEGMENT_BREAK).map(String::trim).filter { it.isNotEmpty() && mention.containsMatchIn(it) }
}

fun featureTaskRuntimeRepairReceiptFromProse(
  prose: String,
  carriedFindings: List<GoalSubtaskReviewCompactFinding>,
  remediationBaseSha: String,
  roundNumber: Int,
): FeatureTaskRuntimeRepairReceipt {
  val entries =
    carriedFindings.mapNotNull { finding ->
      val findingId = finding.findingId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      val mentions = featureTaskRuntimeProseMentions(prose, findingId)
      if (mentions.isEmpty()) return@mapNotNull null
      repairEntry(findingId, mentions)
    }
  return FeatureTaskRuntimeRepairReceipt(
    roundNumber = roundNumber,
    preFixCheckpointSha = remediationBaseSha,
    entries = entries.take(MAX_REPORT_ENTRIES),
  )
}

private fun repairEntry(
  findingId: String,
  mentions: List<String>,
): FeatureTaskRuntimeRepairReceiptEntry {
  val reason = mentions.joinToString(" ").take(MAX_REASON_CHARS)
  return when {
    mentions.any(UNRESOLVED_CUE::containsMatchIn) ->
      FeatureTaskRuntimeRepairReceiptEntry(
        FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED,
        findingId,
        unresolvedReason = reason,
      )
    mentions.any(NO_EDIT_CUE::containsMatchIn) ->
      FeatureTaskRuntimeRepairReceiptEntry(
        FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
        findingId,
        noEditReason = reason,
      )
    else -> FeatureTaskRuntimeRepairReceiptEntry(FeatureTaskRuntimeRepairOutcome.ADDRESSED, findingId)
  }
}

fun featureTaskRuntimeRemediationRoundNumberOrNull(reviewState: GoalSubtaskReviewState): Int? =
  runCatching { featureTaskRuntimeRemediationRoundNumber(reviewState.completedPassCount) }
    .getOrElse { error ->
      if (error is InvalidFeatureTaskRuntimeRepairReceiptError) null else throw error
    }
