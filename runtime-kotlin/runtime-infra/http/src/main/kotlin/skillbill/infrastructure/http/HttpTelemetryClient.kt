package skillbill.infrastructure.http
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.TelemetryHttpFailureCode
import skillbill.error.core.rethrowUnless
import skillbill.error.core.telemetryProxyRequestFailure
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.ports.telemetry.transport.TelemetryClient
import skillbill.telemetry.TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryDeliveryReport
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetryRemoteStatsResult
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.parseRemoteStatsWindow
import skillbill.telemetry.validateIngestCapabilities
import skillbill.telemetry.validateRemoteStatsCapabilities
import skillbill.telemetry.validateRemoteStatsRequest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

@Inject
class HttpTelemetryClient(
  private val requester: RemoteTransportPort,
  private val environmentContext: EnvironmentContext,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) : TelemetryClient {
  override fun sendBatch(
    settings: TelemetrySettings,
    rows: List<TelemetryOutboxRecord>,
  ): TelemetryDeliveryReport {
    requireConfiguredRelayUrl(settings)
    val response =
      requester.execute(
        "POST",
        settings.proxyUrl,
        JsonCodec.mapToJsonString(telemetryProxyBatchPayload(settings, rows).toPayload()),
        requestHeaders("POST"),
      )
    return TelemetryDeliveryReport(
      outcome = classifyDeliveryOutcome(response.statusCode),
      detail = deliveryDetail(response),
    )
  }

  override fun fetchProxyCapabilities(settings: TelemetrySettings): TelemetryProxyCapabilities {
    requireConfiguredRelayUrl(settings)
    val capabilitiesUrl = settings.proxyUrl.trimEnd('/') + "/capabilities"
    val request =
      JsonRequest(
        method = "GET",
        url = capabilitiesUrl,
        payload = null,
        errorContext = "Telemetry proxy capabilities request",
        headers = proxyAuthHeaders(environmentContext.environment),
      )
    val response = executeJsonRequest(request, requester)
    if (response.statusCode == HTTP_NOT_FOUND || response.statusCode == HTTP_METHOD_NOT_ALLOWED) {
      diagnostics.warning(
        "seam=telemetry.capabilities.fallback expected=capabilities response " +
          "used=typed default for HTTP ${response.statusCode}",
      )
      return TelemetryProxyCapabilities.defaultProxyCapabilities(settings.proxyUrl, capabilitiesUrl)
    }
    return successfulJsonBody(response, request.errorContext)
      .toTelemetryProxyCapabilities(settings.proxyUrl, capabilitiesUrl, diagnostics)
      .also(::validateIngestCapabilities)
  }

  override fun fetchRemoteStats(
    settings: TelemetrySettings,
    request: RemoteStatsRequest,
  ): TelemetryRemoteStatsResult {
    validateRemoteStatsRequest(request)
    requireConfiguredRelayUrl(settings)
    val (resolvedDateFrom, resolvedDateTo) =
      parseRemoteStatsWindow(
        request.since,
        request.dateFrom,
        request.dateTo,
        LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC),
      )
    val capabilities = fetchProxyCapabilities(settings)
    validateRemoteStatsCapabilities(
      request = request,
      settings = settings,
      capabilities = capabilities,
    )
    val statsUrl = settings.proxyUrl.trimEnd('/') + "/stats"
    val payload =
      requestJson(
        request =
          JsonRequest(
            method = "POST",
            url = statsUrl,
            payload =
              RemoteStatsQueryPayload(
                workflow = request.workflow,
                dateFrom = resolvedDateFrom,
                dateTo = resolvedDateTo,
                groupBy = request.groupBy,
              ).toPayload(),
            errorContext = "Remote telemetry stats request",
            headers = proxyAuthHeaders(environmentContext.environment),
          ),
        requester = requester,
      )
    return payload.toTelemetryRemoteStatsResult(
      context =
        RemoteStatsResultContext(
          workflow = request.workflow,
          dateFrom = resolvedDateFrom,
          dateTo = resolvedDateTo,
          groupBy = request.groupBy,
          statsUrl = statsUrl,
          capabilities = capabilities,
        ),
    )
  }
}

internal data class RemoteStatsResultContext(
  val workflow: String,
  val dateFrom: String,
  val dateTo: String,
  val groupBy: String,
  val statsUrl: String,
  val capabilities: TelemetryProxyCapabilities,
)

private data class JsonRequest(
  val method: String,
  val url: String,
  val payload: Map<String, Any?>?,
  val errorContext: String,
  val headers: Map<String, String>,
)

private fun requestJson(
  request: JsonRequest,
  requester: RemoteTransportPort,
): Map<String, Any?> = successfulJsonBody(executeJsonRequest(request, requester), request.errorContext)

private fun executeJsonRequest(
  request: JsonRequest,
  requester: RemoteTransportPort,
): RemoteTransportResponse =
  requester.execute(
    request.method,
    request.url,
    request.payload?.let(JsonCodec::mapToJsonString),
    requestHeaders(request.method) + request.headers,
  )

private fun successfulJsonBody(
  response: RemoteTransportResponse,
  errorContext: String,
): Map<String, Any?> {
  ensureSuccessfulResponse(response, errorContext)
  return decodeJsonObject(response.body, errorContext)
}

private fun ensureSuccessfulResponse(
  response: RemoteTransportResponse,
  errorContext: String,
) {
  if (response.statusCode !in HTTP_SUCCESS_RANGE) {
    throw telemetryProxyRequestFailure(
      statusCode = response.statusCode,
      seam = errorContext,
      detail = boundedHttpFailureDetail(response, errorContext),
    )
  }
}

private fun decodeJsonObject(
  body: String,
  errorContext: String,
): Map<String, Any?> {
  if (body.isBlank()) {
    return invalidJsonResponse(errorContext, "empty response body")
  }
  val decoded =
    try {
      JsonCodec.parseValue(body)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      return invalidJsonResponse(errorContext, "$errorContext returned invalid JSON.")
    }
  return JsonCodec.anyToStringAnyMap(decoded)
    ?: if (isJsonNonObjectRoot(body)) {
      invalidJsonResponse(errorContext, "$errorContext returned a non-object JSON payload.")
    } else {
      invalidJsonResponse(errorContext, "$errorContext returned invalid JSON.")
    }
}

private fun invalidJsonResponse(
  errorContext: String,
  detail: String,
): Nothing =
  throw SkillBillRuntimeException(
    TelemetryHttpFailureCode.PROXY_INVALID_RESPONSE,
    "Telemetry proxy response invalid at $errorContext: $detail",
  )

private fun isJsonNonObjectRoot(body: String): Boolean {
  val normalized = body.trim()
  return normalized.startsWith("[") ||
    normalized.startsWith("\"") ||
    normalized == "true" ||
    normalized == "false" ||
    normalized == "null" ||
    normalized.toBigDecimalOrNull() != null
}

private fun deliveryDetail(response: RemoteTransportResponse): String {
  val body = response.body.trim().take(DELIVERY_DETAIL_MAX_LENGTH)
  return if (body.isEmpty()) {
    "HTTP ${response.statusCode}"
  } else {
    "HTTP ${response.statusCode}: $body"
  }
}

private fun boundedHttpFailureDetail(
  response: RemoteTransportResponse,
  errorContext: String,
): String {
  val message =
    if (response.body.isBlank()) {
      "$errorContext failed with HTTP ${response.statusCode}."
    } else {
      "$errorContext failed with HTTP ${response.statusCode}. ${response.body.trim()}"
    }
  return message.take(HTTP_BOUNDED_DETAIL_MAX_LENGTH)
}

private fun requireConfiguredRelayUrl(settings: TelemetrySettings) {
  if (settings.proxyUrl.isBlank()) {
    throw SkillBillRuntimeException(
      TelemetryHttpFailureCode.RELAY_URL_UNCONFIGURED,
      "Telemetry relay URL is not configured.",
    )
  }
}

private fun proxyAuthHeaders(environment: Map<String, String>): Map<String, String> =
  environment[TELEMETRY_PROXY_STATS_TOKEN_ENVIRONMENT_KEY]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { mapOf(HttpHeaders.AUTHORIZATION to "Bearer $it") }
    ?: emptyMap()

private fun requestHeaders(method: String): Map<String, String> =
  if (method == "GET") {
    mapOf(HttpHeaders.USER_AGENT to TELEMETRY_USER_AGENT)
  } else {
    mapOf(
      HttpHeaders.CONTENT_TYPE to JSON_CONTENT_TYPE,
      HttpHeaders.USER_AGENT to TELEMETRY_USER_AGENT,
    )
  }

private const val DELIVERY_DETAIL_MAX_LENGTH: Int = 300
