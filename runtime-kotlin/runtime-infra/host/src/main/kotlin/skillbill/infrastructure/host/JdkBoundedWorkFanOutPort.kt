package skillbill.infrastructure.host

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private const val PEER_STOP_TIMEOUT_SECONDS = 30L

@Inject
class JdkBoundedWorkFanOutPort : BoundedWorkFanOutPort {
  private val exclusion = Any()
  private val threadOrdinal = AtomicLong()

  override fun <T> runBounded(
    maxInFlight: Int,
    units: List<() -> T>,
  ): List<Result<T>> {
    require(maxInFlight >= 1) { "maxInFlight must be at least 1." }
    if (units.size <= 1) return units.map(::capture)
    val pool = Executors.newFixedThreadPool(minOf(maxInFlight, units.size), ::fanOutThread)
    val tasks = units.map { unit -> pool.submit<Result<T>> { capture(unit) } }
    val results =
      try {
        tasks.map(::await)
      } catch (cancelled: CancellationException) {
        stopPeers(pool, tasks, cancelled)
        throw cancelled
      }
    pool.shutdown()
    return results
  }

  override fun <T> runExclusively(action: () -> T): T = synchronized(exclusion) { action() }

  private fun fanOutThread(runnable: Runnable): Thread =
    Thread(runnable, "skill-bill-plan-fanout-${threadOrdinal.incrementAndGet()}").apply { isDaemon = true }
}

private fun <T> capture(unit: () -> T): Result<T> {
  return catchingNonCancellation { unit() }
}

private fun <T> await(task: Future<Result<T>>): Result<T> =
  try {
    task.get()
  } catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    Result.failure(interrupted)
  } catch (failed: ExecutionException) {
    val cause = failed.cause ?: failed
    if (cause is CancellationException) throw cause
    Result.failure(cause)
  }

private fun stopPeers(
  pool: ExecutorService,
  tasks: List<Future<*>>,
  cancelled: CancellationException,
) {
  tasks.forEach { it.cancel(true) }
  pool.shutdownNow()
  try {
    pool.awaitTermination(PEER_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  } catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    cancelled.addSuppressed(interrupted)
  }
}
