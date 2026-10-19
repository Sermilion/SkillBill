package skillbill.ports.qa

/** Shape validation of a QA receipt against the canonical receipt schema. */
fun interface QaReceiptSchemaPort {
  /** The bounded schema violation of the [receiptJson] object, or null when it conforms. */
  fun violation(receiptJson: String): String?
}
