package skillbill.ports.idestatus

import java.time.Duration

interface StandaloneOutputCaptureScheduler {
  fun schedule(
    delay: Duration,
    action: () -> Unit,
  ): AutoCloseable
}
