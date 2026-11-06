package skillbill.ports.db

import skillbill.ports.db.model.RuntimeWorkerLease
import java.time.Instant

interface RuntimeWorkerLeaseQuery {
  /**
   * Lists worker leases recorded in the existing database that are still unexpired at [now]: standalone phase
   * leases, goal runner execution leases, and feature-task runtime worker leases. Reads without migrating the
   * database, so it works on a schema older than this runtime. A lease whose expiry cannot be read counts as
   * unexpired. Returns an empty list when no database exists.
   */
  fun unexpiredLeases(now: Instant): List<RuntimeWorkerLease>
}
