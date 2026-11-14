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
        options.setTransportFactory { configured, _ ->
          ReleaseHealthTransport(RecordingTransport(payloads), configured) { DesktopCrashReporting.policyAllows { TelemetryLevelRead.Known(TelemetryLevel.ANONYMOUS, "unused") } }
        }
      }
      Thread { DesktopCrashReporting.startSession() }.apply { start(); join() }
      Sentry.configureScope { scope ->
        scope.setExtra("prompt", "private-prompt")
        scope.setTag("repository", "/home/private-repo")
        scope.addBreadcrumb(Breadcrumb().apply { message = "private-breadcrumb" })
        scope.user = User().apply { email = "private-user@example.com" }
      }
      val failure = IllegalStateException("secret-access-token")
      Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), failure)
      assertEquals(listOf<Throwable>(failure), delegated)
      val payload = payloads.single { it.contains("\"exception\"") }
      assertTrue(payloads.any { it.contains("\"status\":\"crashed\"") })
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

  @Test
  fun `healthy session ends without installation identity user agent or client address`() {
    val payloads = mutableListOf<String>()
    try {
      Sentry.init { options ->
        configure(options) { TelemetryLevelRead.Known(TelemetryLevel.ANONYMOUS, "unused") }
        options.distinctId = "private-install-id"
        options.setTransportFactory { configured, _ -> ReleaseHealthTransport(RecordingTransport(payloads), configured) { true } }
      }
      Sentry.configureScope { it.user = User().apply { ipAddress = "192.0.2.123" } }
      DesktopCrashReporting.startSession()
      DesktopCrashReporting.endSession()
      assertEquals(2, payloads.size)
      assertTrue(payloads.first().contains("\"init\":true"))
      assertTrue(payloads.last().contains("\"status\":\"exited\""))
      payloads.forEach { payload ->
        assertTrue(payload.contains("sona-desktop@1.2.3"))
        assertTrue(payload.contains("\"ip_address\":\"0.0.0.0\""))
        listOf("private-install-id", "192.0.2.123", "\"did\"", "user_agent").forEach { assertFalse(payload.contains(it)) }
      }
    } finally {
      Sentry.close()
    }
  }

  @Test
  fun `turning telemetry off or losing policy suppresses remaining session updates even after opt in`() {
    listOf<TelemetryLevelRead>(TelemetryLevelRead.Known(TelemetryLevel.OFF, "unused"), TelemetryLevelRead.Unreadable(null, "invalid")).forEach { denied ->
      val payloads = mutableListOf<String>()
      var read: TelemetryLevelRead = TelemetryLevelRead.Known(TelemetryLevel.ANONYMOUS, "unused")
      try {
        Sentry.init { options ->
          configure(options) { read }
          options.setTransportFactory { configured, _ ->
            ReleaseHealthTransport(RecordingTransport(payloads), configured) { DesktopCrashReporting.policyAllows { read } }
          }
        }
        DesktopCrashReporting.startSession()
        read = denied
        DesktopCrashReporting.endSession()
        read = TelemetryLevelRead.Known(TelemetryLevel.ANONYMOUS, "unused")
        DesktopCrashReporting.startSession()
        DesktopCrashReporting.endSession()
        assertEquals(1, payloads.size)
      } finally {
        Sentry.close()
      }
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
