package skillbill.application.learning

import skillbill.contracts.JsonCodec
import skillbill.contracts.learning.LearningAppliedSessionWire
import skillbill.contracts.learning.LearningEntryDto
import skillbill.learnings.model.LearningEntry
import skillbill.learnings.model.LearningRecord
import skillbill.learnings.model.LearningScope

fun learningReference(record: LearningRecord): String = "L-%03d".format(record.id)

fun learningEntry(record: LearningRecord): LearningEntry =
  LearningEntry(
    id = record.id,
    reference = learningReference(record),
    scope = LearningScope.fromWireName(record.scope),
    scopeKey = record.scopeKey,
    status = record.status,
    title = record.title,
    ruleText = record.ruleText,
    rationale = record.rationale,
    sourceReviewRunId = record.sourceReviewRunId,
    sourceFindingId = record.sourceFindingId,
  )

private fun learningEntryDto(entry: LearningEntry): LearningEntryDto =
  LearningEntryDto(
    reference = entry.reference,
    scope = entry.scope.wireName,
    scopeKey = entry.scopeKey,
    status = entry.status,
    title = entry.title,
    ruleText = entry.ruleText,
    rationale = entry.rationale,
    sourceReviewRunId = entry.sourceReviewRunId,
    sourceFindingId = entry.sourceFindingId,
  )

internal fun learningEntryDto(record: LearningRecord): LearningEntryDto = learningEntryDto(learningEntry(record))

private fun learningAppliedSessionWire(
  skillName: String?,
  payloadEntries: List<LearningEntryDto>,
): LearningAppliedSessionWire =
  LearningAppliedSessionWire(
    skillName = skillName,
    entries = payloadEntries,
    scopeCounts = scopeCountsFromDtos(payloadEntries),
  )

internal fun learningEntrySessionJson(
  skillName: String?,
  entries: List<LearningEntry>,
): String =
  JsonCodec.mapToJsonString(learningAppliedSessionWire(skillName, entries.map(::learningEntryDto)).toPayload()) + "\n"

private fun scopeCountsFromDtos(payloads: List<LearningEntryDto>): Map<String, Int> =
  linkedMapOf<String, Int>().apply {
    payloads.forEach { payload ->
      val scope = LearningScope.fromWireNameOrNull(payload.scope) ?: return@forEach
      put(scope.wireName, getOrDefault(scope.wireName, 0) + 1)
    }
  }
