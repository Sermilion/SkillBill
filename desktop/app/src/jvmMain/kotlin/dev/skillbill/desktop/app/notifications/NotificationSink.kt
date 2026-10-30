package dev.skillbill.desktop.app.notifications

import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.notification
import dev.skillbill.desktop.core.common.Diagnostics
import kotlinx.coroutines.CancellationException
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import dev.skillbill.desktop.core.common.di.AppScope

interface NotificationSink {
  fun send(title: String, body: String, onClick: () -> Unit)

  fun sendAuthorized(title: String, body: String, authorizeAndSend: (() -> Unit) -> Unit, onClick: () -> Unit) {
    authorizeAndSend { send(title, body, onClick) }
  }
}

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class NucleusNotificationSink(private val diagnostics: Diagnostics) : NotificationSink {
  override fun send(title: String, body: String, onClick: () -> Unit) = sendAuthorized(title, body, { it() }, onClick)

  override fun sendAuthorized(title: String, body: String, authorizeAndSend: (() -> Unit) -> Unit, onClick: () -> Unit) {
    Thread({
      try {
        NotificationManager.initialize()
        authorizeAndSend { notification(title = title.take(120), message = body.take(240), onActivated = onClick).send() }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics.record("notification_failed")
      }
    }, "skillbill-notification").apply { isDaemon = true }.start()
  }
}
