package skillbill.infrastructure.http
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

private const val DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS = 10L
private const val DEFAULT_HTTP_REQUEST_TIMEOUT_MINUTES = 4L

internal val DEFAULT_HTTP_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(DEFAULT_HTTP_CONNECT_TIMEOUT_SECONDS)
internal val DEFAULT_HTTP_REQUEST_TIMEOUT: Duration = Duration.ofMinutes(DEFAULT_HTTP_REQUEST_TIMEOUT_MINUTES)

class JdkHttpRemoteTransport(
  private val httpClient: HttpClient,
  private val requestTimeout: Duration,
) : RemoteTransportPort {
  override fun execute(
    method: String,
    url: String,
    bodyJson: String?,
    headers: Map<String, String>,
  ): RemoteTransportResponse {
    val requestBuilder =
      HttpRequest
        .newBuilder(httpRequestUri(url))
        .timeout(requestTimeout)
        .method(method, bodyPublisher(bodyJson))
    headers.forEach(requestBuilder::header)
    val response = httpClient.send(requestBuilder.build(), BodyHandlers.ofString())
    return RemoteTransportResponse(
      statusCode = response.statusCode(),
      body = response.body().orEmpty(),
      headers = response.headers().map().mapValues { (_, values) -> values.joinToString(", ") },
    )
  }

  companion object {
    fun create(
      connectTimeout: Duration? = null,
      requestTimeout: Duration? = null,
    ): RemoteTransportPort =
      if (connectTimeout == null && requestTimeout == null) {
        JdkHttpRequester
      } else {
        JdkHttpRemoteTransport(
          httpClient =
            HttpClient
              .newBuilder()
              .connectTimeout(connectTimeout ?: DEFAULT_HTTP_CONNECT_TIMEOUT)
              .build(),
          requestTimeout = requestTimeout ?: DEFAULT_HTTP_REQUEST_TIMEOUT,
        )
      }
  }
}

object JdkHttpRequester : RemoteTransportPort by JdkHttpRemoteTransport(
  httpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(DEFAULT_HTTP_CONNECT_TIMEOUT)
      .build(),
  requestTimeout = DEFAULT_HTTP_REQUEST_TIMEOUT,
)

private fun bodyPublisher(bodyJson: String?): HttpRequest.BodyPublisher =
  if (bodyJson == null) {
    HttpRequest.BodyPublishers.noBody()
  } else {
    HttpRequest.BodyPublishers.ofString(bodyJson)
  }
