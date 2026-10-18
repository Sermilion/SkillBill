package skillbill.ports.qa.model

import java.nio.file.Path

data class QaWorkspace(
  val invocationId: String,
  val directory: Path,
  val receiptPath: Path,
  val evidencePrefix: String,
)
