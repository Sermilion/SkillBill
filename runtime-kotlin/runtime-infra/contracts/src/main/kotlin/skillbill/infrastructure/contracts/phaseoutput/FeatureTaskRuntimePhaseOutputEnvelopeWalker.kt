package skillbill.infrastructure.contracts.phaseoutput

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.repair.task.salvageCompactReceiptSymbol

internal object FeatureTaskRuntimePhaseOutputEnvelopeWalker {
  fun select(
    text: String,
    phaseId: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision? =
    selectMatching(text, phaseId, recoverSummary = false)
      ?: selectMatching(text, phaseId, recoverSummary = true)

  private fun selectMatching(
    text: String,
    phaseId: String,
    recoverSummary: Boolean,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision? {
    val matches = linkedMapOf<String, WalkedEnvelope>()
    StructuralRepairSyntax.balancedTopLevelObjectSpans(text).forEach { span ->
      considerSpan(text, span, phaseId, recoverSummary)?.let { envelope ->
        matches[canonical(envelope.node)] = envelope
      }
    }
    if (matches.isEmpty()) return null
    if (matches.size > 1) {
      return StructuralRepairDecisions.reject(
        FeatureTaskRuntimePhaseOutputFailureCode.MULTIPLE_OUTPUT_CANDIDATES,
        "Phase output contains multiple conflicting schema candidates.",
      )
    }
    val selected = matches.values.single()
    val extraCloser = unmatchedCloserOutside(text, selected.sourceStart, selected.sourceEnd)
    val originalSlice = originalSliceForDigest(text, selected, extraCloser)
    val evidence =
      when {
        selected.spliced || extraCloser != null ->
          repairEvidence(
            originalSlice,
            selected.envelopeText,
            phaseId,
            FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
            selected.spliceOffset ?: extraCloser ?: selected.sourceStart,
          )
        selected.shapeAligned ->
          repairEvidence(
            originalSlice,
            selected.envelopeText,
            phaseId,
            FeatureTaskRuntimePhaseOutputRepairOperation.RESTORE_EXPECTED_SHAPE,
            selected.sourceStart,
          )
        else -> null
      }
    return StructuralRepairDecisions.accepted(selected.envelopeText, selected.node, evidence)
  }

  private fun considerSpan(
    text: String,
    span: IntRange,
    phaseId: String,
    recoverSummary: Boolean,
  ): WalkedEnvelope? {
    val summarySource = if (recoverSummary) text.substring(0, span.first) else null
    val spliced =
      if (StructuralRepairSyntax.looksLikeObjectFieldContinuation(text, span.last + 1)) {
        val repaired = text.removeRange(span.last, span.last + 1).substring(span.first)
        shapedEnvelope(repaired, span, spliceOffset = span.last, phaseId, summarySource)
      } else {
        null
      }
    return spliced ?: shapedEnvelope(text.substring(span), span, spliceOffset = null, phaseId, summarySource)
  }

  private fun shapedEnvelope(
    slice: String,
    span: IntRange,
    spliceOffset: Int?,
    phaseId: String,
    summarySource: String?,
  ): WalkedEnvelope? {
    val parsed = parseObject(slice) ?: return null
    val (alignedShape, shapeChanged) = PhaseOutputExpectedShape.align(parsed)
    val (aligned, summaryRecovered) =
      summarySource
        ?.let { PhaseOutputExpectedShape.withRecoveredSummary(alignedShape, phaseId, it) }
        ?: (alignedShape to false)
    val changed = shapeChanged || summaryRecovered
    if (!PhaseOutputExpectedShape.matches(aligned, phaseId)) return null
    val envelopeText = if (changed) PhaseOutputExpectedShape.writeJson(aligned) else slice
    return WalkedEnvelope(
      envelopeText = envelopeText,
      node = aligned,
      sourceStart = span.first,
      sourceEnd = span.last + 1,
      spliced = spliceOffset != null,
      shapeAligned = changed,
      spliceOffset = spliceOffset,
    )
  }

  private fun repairEvidence(
    originalText: String,
    repairedText: String,
    phaseId: String,
    operation: FeatureTaskRuntimePhaseOutputRepairOperation,
    offset: Int,
  ) = FeatureTaskRuntimePhaseOutputRepairEvidence(
    format = FeatureTaskRuntimePhaseOutputFormat.JSON,
    originalDigest = StructuralRepairSyntax.sha256Hex(originalText),
    repairedDigest = StructuralRepairSyntax.sha256Hex(repairedText),
    operation = operation,
    sourceLocation = StructuralRepairSyntax.sourceLocation(phaseId, originalText, offset),
  )

  private fun originalSliceForDigest(
    text: String,
    selected: WalkedEnvelope,
    extraCloser: Int?,
  ): String {
    if (selected.spliced || selected.shapeAligned) return text.substring(selected.sourceStart)
    if (extraCloser == null) return text.substring(selected.sourceStart, selected.sourceEnd)
    if (extraCloser < selected.sourceStart) return text
    return text.substring(selected.sourceStart, extraCloser + 1)
  }

  private fun parseObject(slice: String): JsonNode? =
    when (val parsed = StrictPhaseOutputParser.parseDocument(slice)) {
      is StrictParse.Success -> parsed.node.takeIf { it.isObject }
      is StrictParse.Failure -> null
    }

  private fun canonical(node: JsonNode): String =
    when {
      node.isObject ->
        node.fieldNames().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { field ->
          "\"$field\":${canonical(node.path(field))}"
        }
      node.isArray -> node.joinToString(prefix = "[", postfix = "]", transform = ::canonical)
      else -> node.toString()
    }

  private fun unmatchedCloserOutside(
    text: String,
    sourceStart: Int,
    sourceEnd: Int,
  ): Int? {
    val start = sourceStart.coerceAtLeast(0)
    val end = sourceEnd.coerceAtMost(text.length)
    val outside = text.removeRange(start, end)
    val outsideOffset =
      StructuralRepairSyntax.scanDelimiters(outside)
        .unmatchedClosingOffsets.firstOrNull() ?: return null
    return if (outsideOffset < start) outsideOffset else outsideOffset + (end - start)
  }

  private data class WalkedEnvelope(
    val envelopeText: String,
    val node: JsonNode,
    val sourceStart: Int,
    val sourceEnd: Int,
    val spliced: Boolean,
    val shapeAligned: Boolean,
    val spliceOffset: Int?,
  )
}

internal object PhaseOutputExpectedShape {
  private val mapper = ObjectMapper()
  private const val SUMMARY_FIELD = SharedPayloadKeys.SUMMARY
  private const val RECOVERED_SUMMARY_MAX_CHARS = 2_000
  private val FENCED_BLOCK = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
  private val FENCE_MARKER_LINE = Regex("(?m)^[ \\t]*```[A-Za-z0-9_-]*[ \\t]*$")
  private val PARAGRAPH_BREAK = Regex("\\r?\\n[ \\t]*\\r?\\n")
  private val WHITESPACE_RUN = Regex("\\s+")

  fun matches(
    node: JsonNode,
    phaseId: String,
  ): Boolean {
    if (!node.isObject) return false
    if (node.path(SharedPayloadKeys.PHASE_ID).asText("") != phaseId) return false
    return REQUIRED_FIELDS.all { field -> node.hasNonNull(field) }
  }

  val REQUIRED_FIELDS: List<String> =
    listOf(
      SharedPayloadKeys.CONTRACT_VERSION,
      SharedPayloadKeys.PHASE_ID,
      SharedPayloadKeys.STATUS,
      SharedPayloadKeys.SUMMARY,
      SharedPayloadKeys.PRODUCED_OUTPUTS,
    )

  private val HOISTABLE_FIELDS: List<String> = REQUIRED_FIELDS + SharedPayloadKeys.VERDICT

  val ENVELOPE_ROOT_FIELDS: Set<String> =
    setOf(
      SharedPayloadKeys.CONTRACT_VERSION,
      SharedPayloadKeys.PHASE_ID,
      SharedPayloadKeys.STATUS,
      SharedPayloadKeys.FAILURE_DISPOSITION,
      SharedPayloadKeys.SUMMARY,
      SharedPayloadKeys.PRODUCED_OUTPUTS,
      SharedPayloadKeys.DERIVED_NOTES,
      SharedPayloadKeys.VERDICT,
    )

  fun align(node: JsonNode): Pair<JsonNode, Boolean> {
    val root = (node as? ObjectNode)?.deepCopy() ?: return node to false
    val produced = root.get(SharedPayloadKeys.PRODUCED_OUTPUTS) as? ObjectNode ?: return node to false
    var changed = false
    HOISTABLE_FIELDS.forEach { field ->
      if (!root.hasNonNull(field) && produced.hasNonNull(field)) {
        root.set<JsonNode>(field, produced.get(field))
        produced.remove(field)
        changed = true
      }
    }
    if (demoteStrayRootFields(root, produced)) changed = true
    if (normalizeDerivedNotes(root)) changed = true
    if (salvageRepairReceiptSymbols(produced)) changed = true
    return root to changed
  }

  private fun normalizeDerivedNotes(root: ObjectNode): Boolean {
    val notes = root.get(SharedPayloadKeys.DERIVED_NOTES) ?: return false
    if (notes.isTextual) return false
    val flattened = flattenNotes(notes)
    if (flattened.isBlank()) {
      root.remove(SharedPayloadKeys.DERIVED_NOTES)
    } else {
      root.put(SharedPayloadKeys.DERIVED_NOTES, flattened)
    }
    return true
  }

  private fun flattenNotes(notes: JsonNode): String =
    when {
      notes.isNull -> ""
      notes.isArray ->
        notes.asSequence()
          .map(::flattenNotes)
          .filter(String::isNotBlank)
          .joinToString(separator = "\n")
      notes.isObject ->
        notes.properties().asSequence()
          .map { (name, value) -> "$name: ${flattenNotes(value)}" }
          .filter { it.isNotBlank() }
          .joinToString(separator = "\n")
      notes.isValueNode -> notes.asText("").trim()
      else -> ""
    }

  private fun demoteStrayRootFields(
    root: ObjectNode,
    produced: ObjectNode,
  ): Boolean {
    val stray = root.fieldNames().asSequence().filterNot(ENVELOPE_ROOT_FIELDS::contains).toList()
    if (stray.isEmpty()) return false
    stray.forEach { field ->
      if (!produced.has(field)) produced.set<JsonNode>(field, root.get(field))
      root.remove(field)
    }
    return true
  }

  fun withRecoveredSummary(
    node: JsonNode,
    phaseId: String,
    precedingText: String,
  ): Pair<JsonNode, Boolean> {
    val root = (node as? ObjectNode)?.takeIf { onlySummaryIsMissing(it, phaseId) } ?: return node to false
    val recovered = root.deepCopy()
    recovered.put(SUMMARY_FIELD, proseSummary(precedingText) ?: absentSummaryMarker(phaseId))
    return recovered to true
  }

  private fun onlySummaryIsMissing(
    root: ObjectNode,
    phaseId: String,
  ): Boolean =
    root.path(SharedPayloadKeys.PHASE_ID).asText("") == phaseId &&
      !root.hasNonNull(SUMMARY_FIELD) &&
      REQUIRED_FIELDS.none { field -> field != SUMMARY_FIELD && !root.hasNonNull(field) }

  private fun absentSummaryMarker(phaseId: String): String =
    "Phase '$phaseId' reported no summary; its produced_outputs carries the phase's output."

  private fun proseSummary(precedingText: String): String? =
    precedingText
      .replace(FENCED_BLOCK, " ")
      .replace(FENCE_MARKER_LINE, "")
      .split(PARAGRAPH_BREAK)
      .lastOrNull(String::isNotBlank)
      ?.replace(WHITESPACE_RUN, " ")
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?.take(RECOVERED_SUMMARY_MAX_CHARS)

  fun writeJson(node: JsonNode): String = mapper.writeValueAsString(node)

  fun alignDecision(
    decision: FeatureTaskRuntimePhaseOutputStructuralRepairDecision,
    phaseId: String,
    originalText: String,
  ): FeatureTaskRuntimePhaseOutputStructuralRepairDecision {
    val accepted = decision as? FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted ?: return decision
    val (alignedShape, shapeChanged) = align(accepted.node)

    val (aligned, summaryRecovered) = withRecoveredSummary(alignedShape, phaseId, precedingText = "")
    val changed = shapeChanged || summaryRecovered
    if (!changed) return accepted
    val repairedText = writeJson(aligned)
    val evidence =
      accepted.evidence?.copy(repairedDigest = StructuralRepairSyntax.sha256Hex(repairedText))
        ?: FeatureTaskRuntimePhaseOutputRepairEvidence(
          format = FeatureTaskRuntimePhaseOutputFormat.JSON,
          originalDigest = StructuralRepairSyntax.sha256Hex(originalText),
          repairedDigest = StructuralRepairSyntax.sha256Hex(repairedText),
          operation = FeatureTaskRuntimePhaseOutputRepairOperation.RESTORE_EXPECTED_SHAPE,
          sourceLocation = StructuralRepairSyntax.sourceLocation(phaseId, originalText, 0),
        )
    return StructuralRepairDecisions.accepted(repairedText, aligned, evidence)
  }
}

private fun salvageRepairReceiptSymbols(produced: ObjectNode): Boolean {
  val entries =
    (produced.get("repair_receipt") as? ObjectNode)?.get("entries") as? ArrayNode
      ?: return false
  var changed = false
  for (entryNode in entries) {
    val constructs = (entryNode as? ObjectNode)?.get("constructs") as? ArrayNode ?: continue
    if (salvageConstructSymbols(constructs)) changed = true
  }
  return changed
}

private fun salvageConstructSymbols(constructs: ArrayNode): Boolean {
  var changed = false
  for (constructNode in constructs) {
    val construct = constructNode as? ObjectNode ?: continue
    if (salvageConstructSymbol(construct)) changed = true
  }
  return changed
}

private fun salvageConstructSymbol(construct: ObjectNode): Boolean {
  val symbolNode = construct.get("symbol")?.takeIf { it.isTextual } ?: return false
  val salvaged = salvageCompactReceiptSymbol(symbolNode.asText()) ?: return false
  construct.put("symbol", salvaged)
  return true
}
