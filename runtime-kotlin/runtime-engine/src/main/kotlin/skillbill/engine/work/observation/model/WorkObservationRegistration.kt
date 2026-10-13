package skillbill.engine.work.observation.model

import java.time.Instant

class WorkObservationRegistration internal constructor(
  val nextLeaseExpiry: Instant?,
  private val registrations: List<AutoCloseable>,
) : AutoCloseable {
  override fun close() = registrations.forEach { it.close() }
}
