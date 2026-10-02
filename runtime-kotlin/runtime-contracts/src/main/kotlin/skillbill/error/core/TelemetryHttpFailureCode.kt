package skillbill.error.core

enum class TelemetryHttpFailureCode : RuntimeFailureCode {
  INVALID_TRANSPORT_OUTCOME,
  PROXY_REQUEST_FAILED,
  PROXY_INVALID_RESPONSE,
  RELAY_URL_UNCONFIGURED,
}

fun telemetryProxyRequestFailure(
  statusCode: Int,
  seam: String,
  detail: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    TelemetryHttpFailureCode.PROXY_REQUEST_FAILED,
    "Telemetry proxy request failed at $seam with HTTP $statusCode: $detail",
    cause,
  )
