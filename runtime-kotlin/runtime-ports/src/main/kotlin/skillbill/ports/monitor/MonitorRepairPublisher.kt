package skillbill.ports.monitor

import skillbill.ports.monitor.model.MonitorPublicationRequest
import skillbill.ports.monitor.model.MonitorPublicationResult

/**
 * Publishes one invocation-owned repair commit to the pull request head the standalone monitor pinned. Publication is
 * a normal, non-force update of exactly one remote ref that is refused unless the remote still advertises the
 * expected last-published head.
 */
interface MonitorRepairPublisher {
  fun publish(request: MonitorPublicationRequest): MonitorPublicationResult
}
