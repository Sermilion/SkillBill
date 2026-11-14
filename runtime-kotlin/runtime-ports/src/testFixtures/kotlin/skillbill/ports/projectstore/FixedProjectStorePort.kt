package skillbill.ports.projectstore

import skillbill.contracts.projectstore.ProjectStoreLayout
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
      phasesRoot = root.resolve(ProjectStoreLayout.PHASES_DIRECTORY),
    )

  override fun resolve(workingDirectory: Path): ProjectStoreLocation = location

  override fun resolveCurrent(workingDirectory: Path): ProjectStoreLocation = location

  override fun link(
    workingDirectory: Path,
    projectId: String,
  ): ProjectStoreLocation = location
}
