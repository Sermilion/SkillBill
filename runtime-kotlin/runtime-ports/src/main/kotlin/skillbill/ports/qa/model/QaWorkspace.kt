package skillbill.ports.qa.model

import java.nio.file.Path

data class QaWorkspace(
  val invocationId: String,
  val directory: Path,
  val reportPath: Path,
  val evidencePrefix: String,
)

data class QaReportSource(
  val content: String,
  val evidencePrefix: String,
)
