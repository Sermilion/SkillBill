package skillbill.ports.qa.model

import java.nio.file.Path

data class QaWorkspace(
  val invocationId: String,
  val directory: Path,
  val receiptPath: Path,
  val evidencePrefix: String,
)

data class QaReceiptSource(
  val content: String,
  val evidencePrefix: String,
  val receiptReference: String? = null,
)
