package dev.skillbill.desktop.core.ui.feedback

import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.designsystem.component.ToastHostState
import dev.skillbill.desktop.core.designsystem.component.ToastLead
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.di.WindowScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The window's toast stack. `ToastStack` renders [host] and dismisses each toast after five seconds. */
@Inject
@SingleIn(WindowScope::class)
class ToastQueue {
  val host = ToastHostState()

  fun show(message: ToastMessage) {
    val icon = if (message.tone == ToastTone.Confirmation) LucideIcons.checkCircle else LucideIcons.alert
    host.show(ToastData(message.title, message.body, ToastLead.Icon(icon)))
  }

  fun show(data: ToastData) = host.show(data)
}
