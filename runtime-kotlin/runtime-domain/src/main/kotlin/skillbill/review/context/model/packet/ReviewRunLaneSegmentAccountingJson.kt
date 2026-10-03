package skillbill.review.context.model.packet

import skillbill.contracts.JsonCodec
import skillbill.error.core.JsonWrongRootTypeError
import skillbill.error.core.MalformedJsonTextError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidReviewContextSchemaError
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull
import skillbill.workflow.model.persistence.artifact.asExactLongOrNull

object ReviewRunLaneSegmentAccountingJson {
  fun encode(segments: List<ReviewLaneSegmentAccounting>): String? {
    if (segments.isEmpty()) return null
    return JsonCodec.valueToJsonString(
      segments.map { segment ->
        linkedMapOf(
          "segment_id" to segment.segmentId,
          "measured_bytes" to segment.measuredBytes,
          "entry_count" to segment.entryCount,
          "composition_digest" to segment.compositionDigest,
        )
      },
    )
  }

  fun decode(raw: String?): List<ReviewLaneSegmentAccounting> {
    if (raw.isNullOrBlank()) return emptyList()
    val trimmed = raw.trim()
    if (trimmed == "[]") return emptyList()
    val elements =
      try {
        JsonCodec.parseJsonArrayStrict(trimmed)
      } catch (error: MalformedJsonTextError) {
        throw segmentAccountingSchemaError("Segment accounting JSON is malformed: ${error.message.orEmpty()}", error)
      } catch (error: JsonWrongRootTypeError) {
        throw segmentAccountingSchemaError("Segment accounting JSON is malformed: ${error.message.orEmpty()}", error)
      }
    return elements.mapIndexed { index, element ->
      decodeSegment(element, index)
    }
  }

  private fun decodeSegment(
    element: Any?,
    index: Int,
  ): ReviewLaneSegmentAccounting {
    val map =
      element as? Map<*, *>
        ?: throw segmentAccountingSchemaError("Segment accounting entry [$index] must be an object.")
    val segmentId = map.requiredSegmentString("segment_id", index, "is missing")
    val measuredBytes = map.requiredSegmentLong("measured_bytes", index)
    val entryCount = map.requiredSegmentInt("entry_count", index)
    val compositionDigest = map.requiredSegmentString("composition_digest", index, "is missing")
    return try {
      ReviewLaneSegmentAccounting(
        segmentId = segmentId,
        measuredBytes = measuredBytes,
        entryCount = entryCount,
        compositionDigest = compositionDigest,
      )
    } catch (error: IllegalArgumentException) {
      throw segmentAccountingSchemaError(
        "Segment accounting entry [$index] violates its value constraints.",
        error,
      )
    }
  }

  private fun Map<*, *>.requiredSegmentString(
    field: String,
    index: Int,
    failure: String,
  ): String =
    this[field] as? String
      ?: throw segmentAccountingSchemaError("Segment accounting entry [$index] $failure $field.")

  private fun Map<*, *>.requiredSegmentLong(
    field: String,
    index: Int,
  ): Long =
    this[field].asExactLongOrNull()
      ?: throw segmentAccountingSchemaError("Segment accounting entry [$index].$field must be an integer.")

  private fun Map<*, *>.requiredSegmentInt(
    field: String,
    index: Int,
  ): Int =
    this[field].asExactIntOrNull()
      ?: throw segmentAccountingSchemaError("Segment accounting entry [$index].$field must be an integer.")

  private fun segmentAccountingSchemaError(
    reason: String,
    cause: Throwable? = null,
  ): SkillBillRuntimeException =
    invalidReviewContextSchemaError(
      sourceLabel = "review_run_lane_segment_accounting",
      reason = reason,
      cause = cause,
    )
}

fun List<String>.toStoredSegmentIdList(): String = joinToString(",")

fun String.toStoredSegmentIdList(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }
