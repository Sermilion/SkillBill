package skillbill.contracts.learning

import skillbill.contracts.JsonPayloadContract

data class LearningEntryDto(
  val reference: String,
  val scope: String,
  val scopeKey: String,
  val status: String,
  val title: String,
  val ruleText: String,
  val rationale: String,
  val sourceReviewRunId: String?,
  val sourceFindingId: String?,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    mapOf(
      LearningPayloadKeys.REFERENCE to reference,
      LearningPayloadKeys.SCOPE to scope,
      LearningPayloadKeys.SCOPE_KEY to scopeKey,
      LearningPayloadKeys.STATUS to status,
      LearningPayloadKeys.TITLE to title,
      LearningPayloadKeys.RULE_TEXT to ruleText,
      LearningPayloadKeys.RATIONALE to rationale,
      LearningPayloadKeys.SOURCE_REVIEW_RUN_ID to sourceReviewRunId,
      LearningPayloadKeys.SOURCE_FINDING_ID to sourceFindingId,
    )
}

private data class LearningSummaryWire(
  val reference: String,
  val scope: String,
  val title: String,
  val ruleText: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    mapOf(
      LearningPayloadKeys.REFERENCE to reference,
      LearningPayloadKeys.SCOPE to scope,
      LearningPayloadKeys.TITLE to title,
      LearningPayloadKeys.RULE_TEXT to ruleText,
    )

  companion object {
    fun fromEntry(entry: LearningEntryDto): LearningSummaryWire =
      LearningSummaryWire(
        reference = entry.reference,
        scope = entry.scope,
        title = entry.title,
        ruleText = entry.ruleText,
      )
  }
}

data class LearningAppliedSessionWire(
  val skillName: String?,
  val entries: List<LearningEntryDto>,
  val scopeCounts: Map<String, Int>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      LearningPayloadKeys.SKILL_NAME to skillName,
      LearningPayloadKeys.APPLIED_LEARNING_COUNT to entries.size,
      LearningPayloadKeys.APPLIED_LEARNING_REFERENCES to entries.map { it.reference },
      LearningPayloadKeys.APPLIED_LEARNINGS to summarizeLearningReferences(entries),
      LearningPayloadKeys.SCOPE_COUNTS to scopeCounts,
      LearningPayloadKeys.LEARNINGS to entries.map(LearningSummaryWire::fromEntry).map(LearningSummaryWire::toPayload),
    )
}

data class LearningListContract(
  val dbPath: String,
  val learnings: List<LearningEntryDto>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      LearningPayloadKeys.DB_PATH to dbPath,
      LearningPayloadKeys.LEARNINGS to learnings.map(LearningEntryDto::toPayload),
    )
}

data class LearningRecordContract(
  val dbPath: String,
  val learning: LearningEntryDto,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf<String, Any?>().apply {
      putAll(learning.toPayload())
      put(LearningPayloadKeys.DB_PATH, dbPath)
    }
}

data class LearningResolveContract(
  val dbPath: String,
  val repoScopeKey: String?,
  val skillName: String?,
  val reviewSessionId: String?,
  val scopePrecedence: List<String>,
  val learnings: List<LearningEntryDto>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      LearningPayloadKeys.DB_PATH to dbPath,
      LearningPayloadKeys.REPO_SCOPE_KEY to repoScopeKey,
      LearningPayloadKeys.SKILL_NAME to skillName,
      LearningPayloadKeys.SCOPE_PRECEDENCE to scopePrecedence,
      LearningPayloadKeys.APPLIED_LEARNINGS to summarizeLearningReferences(learnings),
      LearningPayloadKeys.LEARNINGS to learnings.map(LearningEntryDto::toPayload),
    ).also { payload ->
      reviewSessionId?.takeIf(String::isNotBlank)?.let { payload[LearningPayloadKeys.REVIEW_SESSION_ID] = it }
    }
}

data class LearningDeleteContract(
  val dbPath: String,
  val deletedLearningId: Int,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      LearningPayloadKeys.DB_PATH to dbPath,
      LearningPayloadKeys.DELETED_LEARNING_ID to deletedLearningId,
    )
}

const val NO_APPLIED_LEARNINGS: String = "none"

fun summarizeAppliedLearnings(references: List<String>): String =
  if (references.isEmpty()) NO_APPLIED_LEARNINGS else references.joinToString(", ")

private fun summarizeLearningReferences(entries: List<LearningEntryDto>): String =
  summarizeAppliedLearnings(entries.map(LearningEntryDto::reference))
