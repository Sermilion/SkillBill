package skillbill.infrastructure.http

import skillbill.error.core.telemetryProxyRequestFailure
import java.net.URI

internal const val HTTP_BOUNDED_DETAIL_MAX_LENGTH: Int = 300

internal fun httpRequestUri(url: String): URI =
  try {
    URI.create(url)
  } catch (cause: IllegalArgumentException) {
    throw telemetryProxyRequestFailure(
      statusCode = 0,
      seam = "http.uri.parse",
      detail =
        cause.message
          ?.take(HTTP_BOUNDED_DETAIL_MAX_LENGTH)
          .orEmpty()
          .ifBlank { "invalid URL" },
      cause,
    )
  }
