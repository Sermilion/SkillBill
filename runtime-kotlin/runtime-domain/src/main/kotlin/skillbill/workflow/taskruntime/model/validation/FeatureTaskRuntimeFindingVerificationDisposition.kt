package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.JsonCodec
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeFindingVerificationRecordError
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance

enum class FeatureTaskRuntimeFindingVerificationDispositionVerdict(val wireValue: String) {
  VERIFIED("verified"),
  REJECTED("rejected"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeFindingVerificationDispositionVerdict =
      entries.firstOrNull { it.wireValue == value.trim().lowercase() }
        ?: throw InvalidFeatureTaskRuntimeFindingVerificationRecordError(
          "finding verification disposition must be verified or rejected, was '$value'.",
        )
  }
}

data class FeatureTaskRuntimeFindingVerificationDisposition(
  val findingId: String,
  val disposition: FeatureTaskRuntimeFindingVerificationDispositionVerdict,
  val reason: String? = null,
  val selectedBoundaryHeadings: List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance> = emptyList(),
  val boundaryContextUnavailable: Boolean = false,
) {
  init {
    if (findingId.isBlank()) {
      throw InvalidFeatureTaskRuntimeFindingVerificationRecordError(
        "finding verification disposition finding_id must be non-blank.",
      )
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    buildMap {
      put(ReviewFindingPayloadKeys.FINDING_ID, findingId)
      put("disposition", disposition.wireValue)
      reason?.let { put("reason", it) }
      if (selectedBoundaryHeadings.isNotEmpty()) {
        put("selected_boundary_headings", selectedBoundaryHeadings.map { it.toArtifactMap() })
      }
      if (boundaryContextUnavailable) put("boundary_context_unavailable", true)
    }

  companion object {
    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
    ): FeatureTaskRuntimeFindingVerificationDisposition {
      val findingId =
        (raw[ReviewFindingPayloadKeys.FINDING_ID] as? String)
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: invalid(path, "finding_id")
      val disposition =
        (raw["disposition"] as? String)
          ?.let(FeatureTaskRuntimeFindingVerificationDispositionVerdict::fromWire)
          ?: invalid(path, "disposition")
      val reason = (raw["reason"] as? String)?.trim()?.takeIf(String::isNotBlank)
      val selectedBoundaryHeadings =
        FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.parseList(
          raw["selected_boundary_headings"],
          "$path.selected_boundary_headings",
        )
      val boundaryContextUnavailable = raw["boundary_context_unavailable"] == true
      return FeatureTaskRuntimeFindingVerificationDisposition(
        findingId = findingId,
        disposition = disposition,
        reason = reason,
        selectedBoundaryHeadings = selectedBoundaryHeadings,
        boundaryContextUnavailable = boundaryContextUnavailable,
      )
    }

    fun parseList(
      raw: Any?,
      path: String,
    ): List<FeatureTaskRuntimeFindingVerificationDisposition> {
      val entries =
        raw as? List<*> ?: throw InvalidFeatureTaskRuntimeFindingVerificationRecordError(
          "$path must be an array of finding verification dispositions.",
        )
      return entries.mapIndexed { index, entry ->
        val map =
          JsonCodec.anyToStringAnyMap(entry)
            ?: throw InvalidFeatureTaskRuntimeFindingVerificationRecordError(
              "$path[$index] must be an object.",
            )
        fromArtifactMap(map, "$path[$index]")
      }
    }

    private fun invalid(
      path: String,
      field: String,
    ): Nothing =
      throw InvalidFeatureTaskRuntimeFindingVerificationRecordError("$path.$field must be a non-blank string.")
  }
}

data class FeatureTaskRuntimeFindingVerificationVerdict(
  val dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
) {
  val verifiedDispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>
    get() = dispositions.filter { it.disposition == FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED }

  val rejectedDispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>
    get() = dispositions.filter { it.disposition == FeatureTaskRuntimeFindingVerificationDispositionVerdict.REJECTED }
}
