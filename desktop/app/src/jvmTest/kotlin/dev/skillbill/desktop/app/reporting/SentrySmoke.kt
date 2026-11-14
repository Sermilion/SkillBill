package dev.skillbill.desktop.app.reporting

import dev.skillbill.desktop.core.data.identity.BuildIdentityRecord
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead
import io.sentry.Sentry
import io.sentry.protocol.SentryId

/** Opt-in delivery check with a synthetic exception, isolated from the operator's runtime and database. */
fun main(args: Array<String>) {
  val dsn = requireNotNull(System.getenv("SENTRY_DSN")) { "Set SENTRY_DSN for the explicit delivery check." }
  Sentry.init { options ->
    options.isDebug = true
    DesktopCrashReporting.configure(
      options, dsn, BuildIdentityRecord("release-health-check", false, "", ""), "validation",
    ) { TelemetryLevelRead.Known(TelemetryLevel.ANONYMOUS, "synthetic") }
  }
  DesktopCrashReporting.startSession()
  if (args.contentEquals(arrayOf("--crash"))) {
    println("Throwing an uncaught SonaSentryUncaughtCrashCheck; this process must exit nonzero.")
    throw SonaSentryUncaughtCrashCheck()
  }
  try {
    if (args.contentEquals(arrayOf("--healthy"))) {
      println("Ending a healthy Sentry session for sona-desktop@release-health-check.")
      return
    }
    val eventId = Sentry.captureException(SonaSentryIntegrationCheck())
    check(eventId != SentryId.EMPTY_ID) { "Sentry rejected the synthetic event locally." }
    Sentry.flush(5000)
    println("Sentry synthetic event: $eventId")
  } finally {
    DesktopCrashReporting.endSession()
    Sentry.close()
  }
}

private class SonaSentryIntegrationCheck : RuntimeException("Synthetic setup check")

private class SonaSentryUncaughtCrashCheck : RuntimeException("Intentional uncaught setup check")
