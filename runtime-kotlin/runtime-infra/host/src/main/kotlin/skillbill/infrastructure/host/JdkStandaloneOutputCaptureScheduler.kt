package skillbill.infrastructure.host

import me.tatarka.inject.annotations.Inject
import skillbill.ports.idestatus.StandaloneOutputCaptureScheduler
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@Inject
class JdkStandaloneOutputCaptureScheduler : StandaloneOutputCaptureScheduler {
  private val executor: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { task ->
      Thread(task, "skill-bill-output-capture").apply { isDaemon = true }
    }

  override fun schedule(
    delay: Duration,
    action: () -> Unit,
  ): AutoCloseable {
    val future = executor.schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS)
    return AutoCloseable { future.cancel(false) }
  }
}
