package skillbill.workflow.model.goalreview

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.workflow.taskruntime.model.repair.receiptError
import skillbill.workflow.taskruntime.model.repair.requireReceiptFileBasename
import skillbill.workflow.taskruntime.model.repair.requireReceiptIdentityText
import skillbill.workflow.taskruntime.model.repair.requireReceiptSanitizedText
import skillbill.workflow.taskruntime.model.repair.requireReceiptSymbol
import skillbill.workflow.taskruntime.model.repair.salvageCompactReceiptSymbol

internal const val REPAIR_RECEIPT_MAX_ENTRIES: Int = 50
private const val REPAIR_RECEIPT_MAX_NO_EDIT_REASON_UTF8_BYTES: Int = 356
const val REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES: Int = 356
private const val REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES: Int = 256
private const val REPAIR_RECEIPT_MAX_DISTURBANCE_REASON_UTF8_BYTES: Int = 356

private val GIT_COMMIT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")

private fun <T> anchoredToDecodePath(
  path: String,
  decode: () -> T,
): T =
  try {
    decode()
  } catch (error: InvalidFeatureTaskRuntimeRepairReceiptError) {
    if (error.fieldPath.startsWith(path)) {
      throw error
    }
    throw InvalidFeatureTaskRuntimeRepairReceiptError(
      fieldPath = "$path.${error.fieldPath.substringAfterLast('.')}",
      reason = error.reason,
      payloadFreeReason = error.payloadFreeReason,
      cause = error,
    )
  }

enum class FeatureTaskRuntimeRepairOutcome(val wireValue: String) {
  ADDRESSED("addressed"),
  NO_EDIT_REQUIRED("no_edit_required"),

  ATTEMPTED_UNRESOLVED("attempted_unresolved"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeRepairOutcome =
      entries.firstOrNull { it.wireValue == value }
        ?: receiptError(
          "outcome",
          "must be one of ${entries.joinToString { it.wireValue }}.",
        )
  }
}

data class FeatureTaskRuntimeRepairConstructIdentity internal constructor(val key: String) {
  companion object {
    fun of(
      file: String?,
      symbol: String,
    ): FeatureTaskRuntimeRepairConstructIdentity {
      val normalizedSymbol = normalizeIdentityPart(symbol)
      val normalizedFile = file?.let(::normalizeIdentityPart)?.takeIf(String::isNotEmpty)
      val key = if (normalizedFile == null) normalizedSymbol else "$normalizedFile|$normalizedSymbol"
      return FeatureTaskRuntimeRepairConstructIdentity(key)
    }
  }
}

data class FeatureTaskRuntimeRepairConstruct(
  val symbol: String,
  val file: String? = null,
) {
  val identity: FeatureTaskRuntimeRepairConstructIdentity =
    FeatureTaskRuntimeRepairConstructIdentity.of(file, symbol)

  init {
    requireReceiptSymbol(symbol, "construct.symbol")
    file?.let { basename -> requireReceiptFileBasename(basename, "construct.file") }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "symbol" to symbol,
    ).apply { file?.let { put("file", it) } }

  companion object {
    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
    ): FeatureTaskRuntimeRepairConstruct {
      raw.requireOnlyReviewStateKeys(setOf("symbol", "file"), path)
      return anchoredToDecodePath(path) {
        val reader = reviewStateReader(raw, path)
        val rawSymbol = reader.requiredString("symbol")
        FeatureTaskRuntimeRepairConstruct(
          symbol = salvageCompactReceiptSymbol(rawSymbol) ?: rawSymbol,
          file = reader.optionalString("file"),
        )
      }
    }
  }
}

private data class FeatureTaskRuntimeRepairDisturbedRemedy(
  val findingRef: String,
  val reason: String,
) {
  init {
    requireReceiptIdentityText(findingRef, "disturbed_remedies.finding_ref", REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES)
    requireReceiptSanitizedText(
      reason,
      "disturbed_remedies.reason",
      REPAIR_RECEIPT_MAX_DISTURBANCE_REASON_UTF8_BYTES,
    )
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      "finding_ref" to findingRef,
      "reason" to reason,
    )

  companion object {
    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
    ): FeatureTaskRuntimeRepairDisturbedRemedy {
      raw.requireOnlyReviewStateKeys(setOf("finding_ref", "reason"), path)
      return anchoredToDecodePath(path) {
        val reader = reviewStateReader(raw, path)
        FeatureTaskRuntimeRepairDisturbedRemedy(
          findingRef = reader.requiredString("finding_ref"),
          reason = reader.requiredString("reason"),
        )
      }
    }
  }
}

data class FeatureTaskRuntimeRepairReceiptEntry(
  val outcome: FeatureTaskRuntimeRepairOutcome,
  val findingId: String,
  val noEditReason: String? = null,
  val unresolvedReason: String? = null,
) {
  init {
    requireReceiptIdentityText(findingId, "finding_id", REPAIR_RECEIPT_MAX_LABEL_UTF8_BYTES)
  }

  fun findingIdentity(): String = normalizeIdentityPart(findingId)

  internal fun toArtifactMap(): Map<String, Any?> =
    buildMap {
      put(ReviewFindingPayloadKeys.FINDING_ID, findingId)
      put("outcome", outcome.wireValue)
      noEditReason?.let { put("no_edit_reason", it) }
      unresolvedReason?.let { put("unresolved_reason", it) }
    }

  companion object {
    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
      collector: FeatureTaskRuntimeRepairReceiptDecodeObservations.Collector? = null,
    ): FeatureTaskRuntimeRepairReceiptEntry =
      anchoredToDecodePath(path) {
        val reader = reviewStateReader(raw, path)
        FeatureTaskRuntimeRepairReceiptEntry(
          outcome = FeatureTaskRuntimeRepairOutcome.fromWire(reader.requiredString("outcome")),
          findingId = requireFindingRefAlias(raw, path),
          noEditReason =
            forwardOptionalReceiptReason(
              reader.optionalString("no_edit_reason"),
              "$path.no_edit_reason",
              REPAIR_RECEIPT_MAX_NO_EDIT_REASON_UTF8_BYTES,
              collector,
            ),
          unresolvedReason =
            forwardOptionalReceiptReason(
              reader.optionalString("unresolved_reason"),
              "$path.unresolved_reason",
              REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES,
              collector,
            ),
        )
      }
  }
}

data class FeatureTaskRuntimeRepairReceipt(
  val contractVersion: String = FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION,
  val roundNumber: Int,
  val preFixCheckpointSha: String,
  val entries: List<FeatureTaskRuntimeRepairReceiptEntry>,
) {
  init {
    if (contractVersion !in ACCEPTED_REPAIR_RECEIPT_CONTRACT_VERSIONS) {
      receiptError(
        SharedPayloadKeys.CONTRACT_VERSION,
        "must be one of ${ACCEPTED_REPAIR_RECEIPT_CONTRACT_VERSIONS.joinToString { "'$it'" }}.",
      )
    }
    if (roundNumber < 1) {
      receiptError("round_number", "must be a positive integer.")
    }
    if (!GIT_COMMIT_SHA.matches(preFixCheckpointSha)) {
      receiptError(
        "pre_fix_checkpoint_sha",
        "must be a 40- or 64-character lowercase commit SHA.",
      )
    }
    if (entries.size > REPAIR_RECEIPT_MAX_ENTRIES) {
      receiptError("entries", "allows at most $REPAIR_RECEIPT_MAX_ENTRIES entries.")
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
      "round_number" to roundNumber,
      "pre_fix_checkpoint_sha" to preFixCheckpointSha,
      "entries" to entries.map(FeatureTaskRuntimeRepairReceiptEntry::toArtifactMap),
    )

  companion object {
    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
      collector: FeatureTaskRuntimeRepairReceiptDecodeObservations.Collector? = null,
    ): FeatureTaskRuntimeRepairReceipt {
      raw.requireOnlyReviewStateKeys(
        setOf(
          SharedPayloadKeys.CONTRACT_VERSION,
          "round_number",
          "pre_fix_checkpoint_sha",
          "entries",
          "disturbed_remedies",
        ),
        path,
      )
      if (raw.containsKey("disturbed_remedies")) {
        receiptError(
          "disturbed_remedies",
          "is removed; records naming it must be regenerated.",
        )
      }
      val reader = reviewStateReader(raw, path)
      val entries =
        reader.requiredList("entries").mapIndexed { index, value ->
          FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
            value.toReviewStateMap("$path.entries[$index]"),
            "$path.entries[$index]",
            collector,
          )
        }
      return anchoredToDecodePath(path) {
        FeatureTaskRuntimeRepairReceipt(
          contractVersion = reader.requiredString(SharedPayloadKeys.CONTRACT_VERSION),
          roundNumber = reader.requiredInt("round_number"),
          preFixCheckpointSha = reader.requiredString("pre_fix_checkpoint_sha"),
          entries = entries,
        )
      }
    }

    internal fun validateEntries(
      raw: Map<String, Any?>,
      path: String,
      collector: FeatureTaskRuntimeRepairReceiptDecodeObservations.Collector? = null,
    ) {
      reviewStateReader(raw, path).requiredList("entries").forEachIndexed { index, value ->
        FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
          value.toReviewStateMap("$path.entries[$index]"),
          "$path.entries[$index]",
          collector,
        )
      }
    }
  }
}

private val ACCEPTED_REPAIR_RECEIPT_CONTRACT_VERSIONS: Set<String> =
  setOf(
    FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION,
  )

fun featureTaskRuntimeRemediationRoundNumber(completedPassCountAtImplementFixEntry: Int): Int {
  if (completedPassCountAtImplementFixEntry < 1) {
    receiptError(
      "round_number",
      "must be the completed review pass count at implement_fix entry and at least 1.",
    )
  }
  return completedPassCountAtImplementFixEntry
}

fun GoalSubtaskReviewState.upsertRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): GoalSubtaskReviewState {
  val existing = repairReceipts.indexOfFirst { it.roundNumber == receipt.roundNumber }
  val updated =
    if (existing < 0) {
      (repairReceipts + receipt).sortedBy(FeatureTaskRuntimeRepairReceipt::roundNumber)
    } else {
      repairReceipts.toMutableList().apply { set(existing, receipt) }
    }
  return copy(repairReceipts = updated)
}

fun FeatureTaskRuntimeRepairReceipt.coversCarriedFindings(
  carriedFindings: List<GoalSubtaskReviewCompactFinding>,
): Boolean = omittedCarriedFindings(carriedFindings).isEmpty()

fun FeatureTaskRuntimeRepairReceipt.omittedCarriedFindings(
  carriedFindings: List<GoalSubtaskReviewCompactFinding>,
): List<GoalSubtaskReviewCompactFinding> {
  if (carriedFindings.isEmpty()) return emptyList()
  val reportedIds = entries.mapTo(linkedSetOf()) { normalizeIdentityPart(it.findingId) }
  return carriedFindings.filterNot { carried ->
    val id = carried.findingId?.let(::normalizeIdentityPart)
    id != null && id in reportedIds
  }
}

fun FeatureTaskRuntimeRepairReceipt.attemptedUnresolvedEntries(): List<FeatureTaskRuntimeRepairReceiptEntry> =
  entries.filter { it.outcome == FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED }

internal fun compactReviewFindingIdentity(finding: GoalSubtaskReviewCompactFinding): String =
  listOf(finding.severity, finding.label, finding.text).joinToString("|", transform = ::normalizeIdentityPart)

private val FINDING_REF_ALIASES = listOf("finding_id", "finding_ref", DecompositionPlanningPayloadKeys.ID, "ref")
private const val FINDING_REF_NUMERIC_WIDTH = 3

internal fun requireFindingRefAlias(
  raw: Map<String, Any?>,
  path: String,
): String {
  for (key in FINDING_REF_ALIASES) {
    val value = raw[key] as? String ?: continue
    val normalized = canonicalizeFindingRef(value)
    if (normalized != null) return normalized
  }
  val severity = raw["severity"] as? String
  val label = raw["label"] as? String
  val text = raw["text"] as? String
  if (!severity.isNullOrBlank() && !label.isNullOrBlank() && !text.isNullOrBlank()) {
    return "legacy:" + listOf(severity, label, text).joinToString("|", transform = ::normalizeIdentityPart)
  }
  receiptError(
    path,
    "must name the finding under finding_id (aliases finding_ref, id, ref also accepted).",
  )
}

internal fun canonicalizeFindingRef(raw: String): String? {
  val trimmed = raw.trim().removePrefix("#").trim()
  return trimmed.takeIf { it.isNotEmpty() }
}

fun withStableFindingRefs(findings: List<GoalSubtaskReviewCompactFinding>): List<GoalSubtaskReviewCompactFinding> {
  val used = findings.mapNotNull { it.findingId?.let(::normalizeIdentityPart) }.toMutableSet()
  var next = 1
  return findings.map { finding ->
    val existing = finding.findingId?.let(::canonicalizeFindingRef)
    if (existing != null) {
      used += normalizeIdentityPart(existing)
      finding.copy(findingId = existing)
    } else {
      var assigned: String
      do {
        assigned = "F-" + next.toString().padStart(FINDING_REF_NUMERIC_WIDTH, '0')
        next++
      } while (normalizeIdentityPart(assigned) in used)
      used += normalizeIdentityPart(assigned)
      finding.copy(findingId = assigned)
    }
  }
}
