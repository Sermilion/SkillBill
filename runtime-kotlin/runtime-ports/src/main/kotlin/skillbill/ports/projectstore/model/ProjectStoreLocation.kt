package skillbill.ports.projectstore.model

import java.nio.file.Path

data class ProjectStoreLocation(
  val projectId: String,
  val root: Path,
  val specsRoot: Path,
  val configPath: Path,
  val runEvidenceRoot: Path,
  val trackingRoot: Path,
  val acceptanceRepairRoot: Path,
)
