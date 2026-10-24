package skillbill.ports.projectstore

import skillbill.ports.projectstore.model.ProjectStoreLocation
import java.nio.file.Path

class FixedProjectStorePort(
  val root: Path,
  private val projectId: String = "test-project",
) : ProjectStorePort {
  private val location =
    ProjectStoreLocation(
      projectId = projectId,
      root = root,
      specsRoot = root.resolve("specs"),
      configPath = root.resolve("config.yaml"),
      runEvidenceRoot = root.resolve("run-evidence"),
      trackingRoot = root.resolve("feature-task-tracking"),
      acceptanceRepairRoot = root.resolve("acceptance-repair"),
    )

  override fun resolve(workingDirectory: Path): ProjectStoreLocation = location

  override fun link(
    workingDirectory: Path,
    projectId: String,
  ): ProjectStoreLocation = location
}
