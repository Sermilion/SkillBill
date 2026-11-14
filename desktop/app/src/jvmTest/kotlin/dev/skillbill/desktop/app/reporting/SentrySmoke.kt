package dev.skillbill.desktop.app.reporting

import dev.skillbill.desktop.core.data.identity.BuildIdentityRecord
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead
import io.sentry.Sentry
import io.sentry.protocol.SentryId

/** Opt-in delivery check with a synthetic exception, isolated from the operator's runtime and database. */
fun main(args: Array<String>) {
  val dsn = requireNotNull(System.getenv("SENTRY_DSN")) { "Set SENTRY_DSN for the explicit delivery check." }
  if (args.contentEquals(arrayOf("--crash"))) {
    DesktopCrashReporting.start(System.getenv(), null) { TelemetryLevelRead.Known(TelemetryLevel.ANONYMOUS, "synthetic") }
    check(Sentry.isEnabled()) { "Sentry did not initialize for the crash check." }
    println("Throwing an uncaught SonaSentryUncaughtCrashCheck; this process must exit nonzero.")
    throw SonaSentryUncaughtCrashCheck()
  }
  try {
    Sentry.init { options ->
      DesktopCrashReporting.configure(
        options, dsn, BuildIdentityRecord("integration-check", false, "", ""), "validation",
      ) { TelemetryLevelRead.Known(TelemetryLevel.ANONYMOUS, "synthetic") }
    }
    val eventId = Sentry.captureException(SonaSentryIntegrationCheck())
    check(eventId != SentryId.EMPTY_ID) { "Sentry rejected the synthetic event locally." }
    Sentry.flush(5000)
    println("Sentry synthetic event: $eventId")
  } finally {
    Sentry.close()
  }
}

private class SonaSentryIntegrationCheck : RuntimeException("Synthetic setup check")

private class SonaSentryUncaughtCrashCheck : RuntimeException("Intentional uncaught setup check")
