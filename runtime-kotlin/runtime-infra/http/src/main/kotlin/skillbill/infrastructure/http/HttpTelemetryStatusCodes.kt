package skillbill.infrastructure.http

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.TelemetryHttpFailureCode
import skillbill.telemetry.model.TelemetryDeliveryOutcome

internal const val HTTP_NOT_FOUND: Int = 404
internal const val HTTP_METHOD_NOT_ALLOWED: Int = 405
private const val HTTP_SUCCESS_MIN: Int = 200
private const val HTTP_SUCCESS_MAX: Int = 299
internal val HTTP_SUCCESS_RANGE: IntRange = HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX

private const val HTTP_STATUS_MIN: Int = 100
private const val HTTP_STATUS_MAX: Int = 599
private const val HTTP_CLIENT_ERROR_MIN: Int = 400
private const val HTTP_CLIENT_ERROR_MAX: Int = 499
private const val HTTP_REQUEST_TIMEOUT: Int = 408
private const val HTTP_TOO_MANY_REQUESTS: Int = 429

internal fun classifyDeliveryOutcome(statusCode: Int): TelemetryDeliveryOutcome {
  if (statusCode !in HTTP_STATUS_MIN..HTTP_STATUS_MAX) {
    throw SkillBillRuntimeException(
      TelemetryHttpFailureCode.INVALID_TRANSPORT_OUTCOME,
      "Telemetry transport returned $statusCode, which is not a valid HTTP status code.",
    )
  }
  return when {
    statusCode in HTTP_SUCCESS_RANGE -> TelemetryDeliveryOutcome.ACCEPTED
    statusCode == HTTP_REQUEST_TIMEOUT || statusCode == HTTP_TOO_MANY_REQUESTS -> TelemetryDeliveryOutcome.UNKNOWN
    statusCode in HTTP_CLIENT_ERROR_MIN..HTTP_CLIENT_ERROR_MAX -> TelemetryDeliveryOutcome.REJECTED
    else -> TelemetryDeliveryOutcome.UNKNOWN
  }
}
