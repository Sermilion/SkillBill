package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.ProductReportRecord
import java.time.Instant

/** Reads report identities and verifies stored evidence. */
interface ProductReportLookup {
  /** Returns due submissions and pending issue comments, bounded by [limit]. */
  fun due(
    now: Instant,
    limit: Int,
    credentialAvailable: Boolean,
  ): List<ProductReportRecord>

  /** Lists bounded eligible report identities without decoding their evidence bodies. */
  fun dueIds(
    now: Instant,
    limit: Int,
  ): List<String>

  /** Reloads one report and verifies its evidence digest. */
  fun byId(reportId: String): ProductReportRecord?

  /** Returns the report linked to [occurrenceToken], if any. */
  fun forIncident(token: String): ProductReportRecord?

  /** Returns the linked report identity without decoding its evidence body. */
  fun reportIdForIncident(token: String): String?
}
