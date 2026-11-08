package skillbill.infrastructure.host.scheduler

import me.tatarka.inject.annotations.Inject
import skillbill.ports.approval.ApprovalDeadlineScheduler
import skillbill.ports.approval.ApprovalScheduledDeadline
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Inject
class JdkApprovalDeadlineScheduler(private val clock: Clock) : ApprovalDeadlineScheduler {
  private val executor: ScheduledThreadPoolExecutor by lazy {
    ScheduledThreadPoolExecutor(1) { task ->
      Thread(task, "approval-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
  }

  override fun scheduleAt(
    deadline: Instant,
    task: Runnable,
  ): ApprovalScheduledDeadline {
    val delay = Duration.between(clock.instant(), deadline).coerceAtLeast(Duration.ZERO)
    val handle = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS)
    return ApprovalScheduledDeadline { handle.cancel(false) }
  }
}
