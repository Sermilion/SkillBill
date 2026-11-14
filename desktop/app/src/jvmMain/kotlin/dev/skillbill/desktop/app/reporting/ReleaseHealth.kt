package dev.skillbill.desktop.app.reporting

import io.sentry.Hint
import io.sentry.IScopes
import io.sentry.Integration
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryItemType
import io.sentry.SentryOptions
import io.sentry.Session
import io.sentry.transport.ITransport
import java.io.Closeable
import java.io.StringReader

/** Gates session items separately because beforeSend only controls error events. */
internal class ReleaseHealthTransport(
  private val delegate: ITransport,
  private val options: SentryOptions,
  private val allowed: () -> Boolean,
) : ITransport by delegate {
  private var suppressed = false

  override fun send(envelope: SentryEnvelope) = send(envelope, Hint())

  @Synchronized
  override fun send(envelope: SentryEnvelope, hint: Hint) {
    val items = envelope.items.mapNotNull { item ->
      if (item.header.type != SentryItemType.Session) item else sanitize(item)
    }
    if (items.isNotEmpty()) delegate.send(SentryEnvelope(envelope.header, items), hint)
  }

  private fun sanitize(item: SentryEnvelopeItem): SentryEnvelopeItem? = try {
    if (!allowed()) suppressed = true
    if (suppressed) {
      options.logger.log(io.sentry.SentryLevel.INFO, "desktop:sentry-session-dropped reason=telemetry-disabled-or-unavailable")
      null
    } else {
      val session = requireNotNull(options.serializer.deserialize(StringReader(item.data.toString(Charsets.UTF_8)), Session::class.java))
      val sanitized = Session(
        session.status, requireNotNull(session.started), session.timestamp, session.errorCount(),
        null, session.sessionId, session.init, session.sequence, session.duration,
        "0.0.0.0", null, session.environment, session.release, null,
      )
      SentryEnvelopeItem.fromSession(options.serializer, sanitized)
    }
  } catch (failure: Exception) {
    suppressed = true
    options.logger.log(io.sentry.SentryLevel.WARNING, "desktop:sentry-session-dropped type=%s", failure.javaClass.simpleName)
    null
  }
}

/** Ends the shared desktop session before flushing on JVM shutdown, including SIGTERM. */
internal class ReleaseHealthShutdown : Integration, Closeable {
  private var hook: Thread? = null

  override fun register(scopes: IScopes, options: SentryOptions) {
    val thread = Thread({
      scopes.endSession()
      scopes.flush(options.shutdownTimeoutMillis)
    }, "sona-sentry-session-shutdown")
    Runtime.getRuntime().addShutdownHook(thread)
    hook = thread
  }

  override fun close() {
    hook?.let { Runtime.getRuntime().removeShutdownHook(it) }
    hook = null
  }
}
