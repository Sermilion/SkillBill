package skillbill.infrastructure.skills

import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import kotlin.coroutines.cancellation.CancellationException

internal fun Throwable.rethrowIfCooperativeCancellationOrInterruption() {
  when (this) {
    is CancellationException, is InterruptedException -> throw this
    is InterruptedIOException, is ClosedByInterruptException -> {
      Thread.currentThread().interrupt()
      throw this
    }
  }
}
