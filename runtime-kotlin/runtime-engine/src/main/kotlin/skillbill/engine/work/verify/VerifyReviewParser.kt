package skillbill.engine.work.verify

import skillbill.engine.operation.verify.VerifyWorkflow
import skillbill.ports.idestatus.model.VerifyReviewFinding
import skillbill.ports.idestatus.model.VerifyReviewSection

internal object VerifyReviewParser {
  private const val REF_GROUP = 1
  private const val SEVERITY_GROUP = 2
  private const val CONFIDENCE_GROUP = 3
  private const val LOCATION_GROUP = 4
  private const val DESCRIPTION_GROUP = 5
  private const val SPECIALISTS_GROUP = 6
  private val REGISTER_LINE =
    Regex(
      """^\s*-\s*\[(F-\d+)]\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*(.+?)""" +
        """(?:\s*\|\s*specialist=([^|]+))?\s*$""",
    )
  private val SPECIALIST_PREFIX = Regex("""^\*{0,2}(bill-[A-Za-z0-9-]*):\*{0,2}\s*""")

  fun parse(receipt: Any?): VerifyReviewSection? {
    if (receipt == null) return null
    val findings = VerifyWorkflow.strings(receipt, VerifyWorkflow.FINDINGS)
    return VerifyReviewSection(
      VerifyWorkflow.string(receipt, VerifyWorkflow.VERDICT_FIELD),
      findings,
      findings.mapNotNull(::item),
    )
  }

  private fun item(line: String): VerifyReviewFinding? {
    val groups = REGISTER_LINE.find(line)?.groupValues ?: return null
    val location = groups[LOCATION_GROUP]
    val description = groups[DESCRIPTION_GROUP]
    val prefixed = SPECIALIST_PREFIX.find(description)?.groupValues?.get(1)
    val listed = groups[SPECIALISTS_GROUP].split(',').map(String::trim).filter(String::isNotEmpty)
    return VerifyReviewFinding(
      ref = groups[REF_GROUP],
      severity = groups[SEVERITY_GROUP],
      confidence = groups[CONFIDENCE_GROUP],
      location = location,
      description = description.replace(SPECIALIST_PREFIX, "").trim(),
      specialists = listed.ifEmpty { listOfNotNull(prefixed) },
      locations = VerifyReceiptLocations.of(location),
    )
  }
}
