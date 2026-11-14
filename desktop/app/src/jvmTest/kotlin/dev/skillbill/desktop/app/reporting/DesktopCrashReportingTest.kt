package dev.skillbill.desktop.app.reporting

import dev.skillbill.desktop.core.data.identity.BuildIdentityRecord
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead
import io.sentry.Hint
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryOptions
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import io.sentry.hints.DiskFlushNotification
import io.sentry.util.HintUtils
import io.sentry.protocol.User
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCrashReportingTest {
  @Test
  fun `uncaught errors reach the transport without prompt text or user context and still delegate`() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    val delegated = mutableListOf<Throwable>()
    Thread.setDefaultUncaughtExceptionHandler { _, failure -> delegated += failure }
    val payloads = mutableListOf<String>()
    try {
      Sentry.init { options ->
        configure(options) { TelemetryLevelRead.Known(TelemetryLevel.ANONYMOUS, "unused") }
        options.setTransportFactory { _, _ -> RecordingTransport(payloads) }
      }
      Sentry.configureScope { scope ->
        scope.setExtra("prompt", "private-prompt")
        scope.setTag("repository", "/home/private-repo")
        scope.addBreadcrumb(Breadcrumb().apply { message = "private-breadcrumb" })
        scope.user = User().apply { email = "private-user@example.com" }
      }
      val failure = IllegalStateException("secret-access-token")
      Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), failure)
      assertEquals(listOf<Throwable>(failure), delegated)
      val payload = payloads.single()
      assertTrue(payload.contains("IllegalStateException"))
      assertTrue(payload.contains("DesktopCrashReportingTest.kt"))
      assertTrue(payload.contains("sona-desktop@1.2.3"))
      assertTrue(payload.contains("UncaughtExceptionHandler"))
      assertTrue(payload.contains("\"ip_address\":\"0.0.0.0\""))
      listOf("secret-access-token", "private-prompt", "private-repo", "private-breadcrumb", "private-user", "server_name").forEach {
        assertFalse(payload.contains(it), "The envelope leaked $it")
      }
    } finally {
      Sentry.close()
      Thread.setDefaultUncaughtExceptionHandler(previous)
    }
  }

  @Test
  fun `telemetry off and unreadable policy drop events after initialization and cancellation is ignored`() {
    val payloads = mutableListOf<String>()
    var read: TelemetryLevelRead = TelemetryLevelRead.Known(TelemetryLevel.FULL, "unused")
    try {
      Sentry.init { options ->
        configure(options) { read }
        options.setTransportFactory { _, _ -> RecordingTransport(payloads) }
      }
      DesktopCrashReporting.capture(IllegalStateException("first"))
      read = TelemetryLevelRead.Known(TelemetryLevel.OFF, "unused")
      DesktopCrashReporting.capture(IllegalArgumentException("off"))
      read = TelemetryLevelRead.Unreadable(null, "invalid")
      DesktopCrashReporting.capture(UnsupportedOperationException("invalid"))
      read = TelemetryLevelRead.Known(TelemetryLevel.FULL, "unused")
      DesktopCrashReporting.capture(CancellationException("cancelled"))
      assertEquals(1, payloads.size)
    } finally {
      Sentry.close()
    }
  }

  private fun configure(options: SentryOptions, policy: suspend () -> TelemetryLevelRead) {
    DesktopCrashReporting.configure(
      options, "https://public@example.com/1", BuildIdentityRecord("abc123", false, "1.2.3", "private-install-id"), "test", policy,
    )
  }
}

private class RecordingTransport(private val payloads: MutableList<String>) : ITransport {
  override fun send(envelope: SentryEnvelope, hint: Hint) {
    envelope.items.forEach { payloads += it.data.toString(Charsets.UTF_8) }
    (HintUtils.getSentrySdkHint(hint) as? DiskFlushNotification)?.markFlushed()
  }

  override fun flush(timeoutMillis: Long) = Unit

  override fun getRateLimiter(): RateLimiter? = null

  override fun close() = Unit

  override fun close(isRestarting: Boolean) = Unit
}
