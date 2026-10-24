package skillbill.application

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.install.model.InstallPlanWireMap
import skillbill.model.RepositoryRoot
import skillbill.ports.install.InstallPlanWireValidator
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock

val testHarnessClock: Clock = Clock.systemUTC()

val testRepositoryRoot: RepositoryRoot = RepositoryRoot(Path.of("").toAbsolutePath().normalize())

val testWorkflowSnapshotValidator: WorkflowSnapshotValidator =
  object : WorkflowSnapshotValidator {
    override fun validate(
      snapshot: WorkflowStateSnapshot,
      slug: String,
    ) = Unit
  }

internal val testInstallPlanWireValidator: InstallPlanWireValidator =
  object : InstallPlanWireValidator {
    override fun validate(plan: InstallPlanWireMap) = Unit
  }

val testProjectStore: ProjectStorePort = FixedProjectStorePort(Files.createTempDirectory("test-project-store"))

val testDecompositionManifestWriter = DecompositionManifestWriter(testProjectStore)

fun seedHarnessSpecIntentProjection(
  repoRoot: Path,
  specReference: String,
  acceptanceCriteria: List<String> = listOf("AC-1", "AC-2"),
) {
  val specPath = repoRoot.resolve(specReference)
  val existing = if (Files.isRegularFile(specPath)) Files.readString(specPath) else null
  if (existing != null && !existing.startsWith(HARNESS_SPEC_HEADING)) return
  val criteria = acceptanceCriteria.mapIndexed { index, criterion -> "${index + 1}. ${criterion.trim()}" }
  val content =
    listOf(
      HARNESS_SPEC_HEADING,
      "",
      "## Intended Outcome",
      "Exercise the feature-task runtime harness.",
      "",
      "## Acceptance Criteria",
      "",
    ).plus(criteria).joinToString("\n")
  if (existing != content) writeTextAtomically(specPath, content)
}

private const val HARNESS_SPEC_HEADING = "# Harness spec intent"

private fun writeTextAtomically(
  target: Path,
  content: String,
) {
  Files.createDirectories(target.parent)
  val temp = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
  Files.writeString(temp, content)
  try {
    Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(temp, target, REPLACE_EXISTING)
  }
}
