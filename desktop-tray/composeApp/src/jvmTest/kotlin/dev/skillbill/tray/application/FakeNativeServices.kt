package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.WorkIdentity

/**
 * The desktop's native side as a test sees it. A notification waits in [pending] the way it waits for the operating system's
 * authorization, and a test decides when that authorization answers by calling [authorizeAll].
 */
internal class FakeNativeServices(var startAtLoginResult: Boolean = true) : NativeServices {
  val loginCalls = mutableListOf<Boolean>()
  val notified = mutableListOf<String>()
  private val pending = mutableListOf<() -> Unit>()

  override fun notify(title: String, body: String, identity: WorkIdentity, onClick: (WorkIdentity) -> Unit) {
    notified += title
  }

  override fun notifyAuthorized(
    title: String,
    body: String,
    identity: WorkIdentity,
    authorizeAndSend: (() -> Unit) -> Unit,
    onClick: (WorkIdentity) -> Unit,
  ) {
    pending += { authorizeAndSend { notify(title, body, identity, onClick) } }
  }

  override fun setStartAtLogin(enabled: Boolean): Boolean {
    loginCalls += enabled
    return startAtLoginResult
  }

  val queued: Int get() = pending.size

  fun authorizeAll() {
    pending.toList().also { pending.clear() }.forEach { it() }
  }
}
