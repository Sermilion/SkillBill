package skillbill.engine.work.observation.model

import java.time.Instant

public class WorkObservationRegistration internal constructor(
  public val nextLeaseExpiry: Instant?,
  private val registrations: List<AutoCloseable>,
) : AutoCloseable {
  override fun close(): Unit = registrations.forEach { it.close() }
}
