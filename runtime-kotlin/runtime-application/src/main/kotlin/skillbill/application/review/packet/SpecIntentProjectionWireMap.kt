package skillbill.application.review.packet

import skillbill.contracts.JsonCodec
import skillbill.review.context.model.execution.SpecIntentProjection

class SpecIntentProjectionWireMap private constructor(
  private val delegate: Map<String, Any?>,
) : Map<String, Any?> by delegate {
  companion object {
    fun from(map: Map<String, Any?>): SpecIntentProjectionWireMap = SpecIntentProjectionWireMap(map.toMap())
  }
}

internal fun specIntentProjectionUtf8Bytes(wire: SpecIntentProjectionWireMap): Int =
  JsonCodec.mapToJsonString(wire).toByteArray(Charsets.UTF_8).size

internal fun specIntentProjectionUtf8Bytes(projection: SpecIntentProjection): Int =
  specIntentProjectionUtf8Bytes(projection.toProjectionPayload())
