package dev.skillbill.desktop.app.reporting

import dev.skillbill.desktop.core.data.identity.BuildIdentityRecord
import dev.skillbill.desktop.core.domain.model.TelemetryEffectiveLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Mechanism
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal object DesktopCrashReporting {
  private const val DEFAULT_DSN = "https://d89b832a03f4cd4773f0d44537e0dc4f@o4512428581191680.ingest.de.sentry.io/4512428598624336"
  private val logger = System.getLogger("dev.skillbill.desktop.reporting")

  fun start(environment: Map<String, String>, resourcesDirectory: String?, telemetry: suspend () -> TelemetryLevelRead) {
    val dsn = environment["SENTRY_DSN"] ?: DEFAULT_DSN
    if (dsn.isBlank()) {
      logger.log(System.Logger.Level.INFO, "desktop:sentry-disabled reason=empty-dsn")
      return
    }
    val identity = readIdentity(resourcesDirectory)
    try {
      Sentry.init { options ->
        configure(options, dsn, identity, environment["SENTRY_ENVIRONMENT"], telemetry)
      }
    } catch (failure: Exception) {
      logger.log(System.Logger.Level.WARNING, "desktop:sentry-init-failed type=${failure.javaClass.simpleName}")
    }
  }

  fun capture(failure: Throwable) {
    if (failure !is CancellationException) Sentry.captureException(failure)
  }

  internal fun configure(
    options: SentryOptions,
    dsn: String,
    identity: BuildIdentityRecord?,
    environment: String?,
    telemetry: suspend () -> TelemetryLevelRead,
  ) {
    options.dsn = dsn
    options.release = identity?.let { "sona-desktop@${it.releaseTag.ifBlank { it.commit }}${if (it.dirty) "+dirty" else ""}" }
    options.environment = environment ?: if (identity != null && !identity.dirty && identity.releaseTag.isNotBlank()) "production" else "development"
    options.dataCollection.userInfo = false
    options.dataCollection.filePaths = false
    options.isAttachServerName = false
    options.isAttachThreads = false
    options.isSendModules = false
    options.isEnableAutoSessionTracking = false
    options.isSendClientReports = false
    options.shutdownTimeoutMillis = 2000
    options.addInAppInclude("dev.skillbill.desktop")
    options.addInAppInclude("skillbill")
    options.setBeforeSend { event, _ ->
      if (event.throwable is CancellationException) return@setBeforeSend null
      val allowed = try {
        reportingAllowed(runBlocking(Dispatchers.IO) { telemetry() })
      } catch (failure: Exception) {
        logger.log(System.Logger.Level.WARNING, "desktop:sentry-policy-unavailable type=${failure.javaClass.simpleName}")
        false
      }
      if (allowed) sanitizedEvent(event) else {
        logger.log(System.Logger.Level.INFO, "desktop:sentry-event-dropped reason=telemetry-disabled-or-unavailable")
        null
      }
    }
  }

  internal fun reportingAllowed(read: TelemetryLevelRead): Boolean = when (read) {
    is TelemetryLevelRead.Known -> read.level != TelemetryLevel.OFF
    is TelemetryLevelRead.Snapshot -> (read.value.effectiveLevel as? TelemetryEffectiveLevel.Known)?.level?.let { it != TelemetryLevel.OFF } == true
    is TelemetryLevelRead.Failed, is TelemetryLevelRead.Unreadable -> false
  }

  private fun readIdentity(resourcesDirectory: String?): BuildIdentityRecord? = try {
    val text = if (resourcesDirectory != null) {
      Files.readString(Path.of(resourcesDirectory).resolve(BuildIdentityRecord.FILE_NAME))
    } else {
      javaClass.classLoader.getResourceAsStream(BuildIdentityRecord.FILE_NAME)?.bufferedReader()?.use { it.readText() }
    }
    val identity = text?.let(BuildIdentityRecord::parse)
    if (identity == null) logger.log(System.Logger.Level.WARNING, "desktop:sentry-release-unavailable reason=missing-or-invalid-identity")
    identity
  } catch (failure: Exception) {
    logger.log(System.Logger.Level.WARNING, "desktop:sentry-release-unavailable type=${failure.javaClass.simpleName}")
    null
  }
}

/** Keeps only error types, source locations and build metadata. Exception messages can contain prompts and credentials. */
internal fun sanitizedEvent(source: SentryEvent): SentryEvent = SentryEvent(source.timestamp).apply {
  eventId = source.eventId
  release = source.release
  environment = source.environment
  platform = source.platform
  level = source.level
  sdk = source.sdk
  // Sentry enriches an absent or null address from the connection. This unspecified address carries no client identity.
  user = User().apply { ipAddress = "0.0.0.0" }
  exceptions = source.exceptions?.map { exception ->
    SentryException().apply {
      type = exception.type
      module = exception.module
      mechanism = exception.mechanism?.let { original -> Mechanism().apply { type = original.type; isHandled = original.isHandled } }
      stacktrace = exception.stacktrace?.let { trace ->
        SentryStackTrace(trace.frames?.map { frame ->
          SentryStackFrame().apply {
            filename = frame.filename?.substringAfterLast('/')?.substringAfterLast('\\')
            function = frame.function
            module = frame.module
            lineno = frame.lineno
            colno = frame.colno
            isInApp = frame.isInApp
          }
        })
      }
    }
  }
}
