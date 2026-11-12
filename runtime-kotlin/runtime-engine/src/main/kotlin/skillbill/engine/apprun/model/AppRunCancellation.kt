package skillbill.engine.apprun.model

import skillbill.ports.apprun.AppRunProcess
import java.util.concurrent.atomic.AtomicReference

public class AppRunCancellation {
  @Volatile private var cancelled = false
  private val active = AtomicReference<AppRunProcess?>()

  public fun cancel() {
    cancelled = true
    active.get()?.stop()
  }

  public fun isCancelled(): Boolean = cancelled

  internal fun attach(process: AppRunProcess) {
    active.set(process)
    if (cancelled) process.stop()
  }

  internal fun detach() {
    active.set(null)
  }
}
