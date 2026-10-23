package skillbill.infrastructure.http.productissue

import skillbill.ports.blockincident.model.ProductIssueResult
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure
import java.io.IOException
import java.net.http.HttpConnectTimeoutException
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubProductIssueAdapterTest {
  @Test
  fun `lost write responses retain uncertainty while read failures stay offline`() {
    val adapter =
      GitHubProductIssueAdapter(
        RemoteTransportPort { _, _, _, _ -> throw IOException("connection reset after request") },
        Clock.systemUTC(),
      )

    assertEquals(ProductIssueResult.Ambiguous, adapter.create("title", "body", emptyList(), "test-token"))
    assertEquals(
      ProductIssueResult.Failed(ProductReportFailure.OFFLINE),
      adapter.findByFingerprint("a".repeat(64), "test-token"),
    )
  }

  @Test
  fun `server errors after writes require reconciliation while refused writes stay definite`() {
    listOf(500, 502, 503).forEach { status ->
      val adapter = respondingWith(status)

      assertEquals(ProductIssueResult.Ambiguous, adapter.create("title", "body", emptyList(), "test-token"))
    }
    assertEquals(
      ProductIssueResult.Failed(ProductReportFailure.PERMISSION_REFUSED),
      respondingWith(403).create("title", "body", emptyList(), "test-token"),
    )
  }

  @Test
  fun `connection establishment timeouts do not imply a submitted issue`() {
    val adapter =
      GitHubProductIssueAdapter(
        RemoteTransportPort { _, _, _, _ -> throw HttpConnectTimeoutException("connect timeout") },
        Clock.systemUTC(),
      )

    assertEquals(
      ProductIssueResult.Failed(ProductReportFailure.OFFLINE),
      adapter.create("title", "body", emptyList(), "test-token"),
    )
  }

  private fun respondingWith(status: Int): GitHubProductIssueAdapter =
    GitHubProductIssueAdapter(
      RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(status, "") },
      Clock.systemUTC(),
    )
}
