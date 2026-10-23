package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.ProductReportDeliveryOwnership
import java.nio.file.Path

/** Holds one report's delivery ownership until the complete remote operation finishes. */
interface ProductReportDeliveryOwnershipPort {
  /** Tries to own [reportId] for the database at [databasePath]. */
  fun acquire(
    databasePath: Path,
    reportId: String,
  ): ProductReportDeliveryOwnership
}
