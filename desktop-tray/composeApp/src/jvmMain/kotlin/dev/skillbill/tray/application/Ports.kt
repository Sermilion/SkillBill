package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.WorkIdentity

interface NativeServices {
  suspend fun hasSystemTray(): Boolean = true
  fun notify(title: String, body: String, identity: WorkIdentity, onClick: (WorkIdentity) -> Unit)
  fun notifyAuthorized(title: String, body: String, identity: WorkIdentity, authorizeAndSend: (() -> Unit) -> Unit, onClick: (WorkIdentity) -> Unit) {
    authorizeAndSend { notify(title, body, identity, onClick) }
  }
  fun setStartAtLogin(enabled: Boolean): Boolean
}
