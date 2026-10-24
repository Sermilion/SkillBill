package skillbill.engine.work

import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.ports.work.model.WorkItemKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class IdeStatusServiceGoalSpecBundleTest {
  @Test
  fun `goal detail lists every file of its spec bundle with absolute paths`() {
    val fixture = gitRepoFixture("goal-detail-spec-bundle")
    val identity = testGoalRepositoryIdentity(fixture)
    val storeRoot = Files.createTempDirectory("goal-detail-spec-bundle-store")
    val bundleDirectory = "${ProjectStoreLayout.SPECS_PREFIX}SKILL-148-ide-status"
    val bundle = Files.createDirectories(storeRoot.resolve(bundleDirectory))
    val files =
      listOf("spec.md", "spec_1.md", "spec_2.md", "decomposition-manifest.yaml")
        .map { Files.writeString(bundle.resolve(it), "# $it\n") }
    val state = goalManifestState(fixture, identity, childWorkflowId = "w-child")
    val manifest =
      state.manifest.copy(
        parentSpecPath = "$bundleDirectory/spec.md",
        subtasks = state.manifest.subtasks.map { it.copy(specPath = "$bundleDirectory/${it.specPath}") },
      )
    val service =
      ideStatusService(
        TrackingDatabase(
          work = listOf(workItem("goal-1", WorkItemKind.FEATURE_GOAL, "running", "2026-08-06T10:00:00Z")),
          workflows = IdeStatusWorkflowStates(),
          goalPage = WorkGoalSubtaskPage(manifest, 2, null),
          controls =
            object : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
              override fun controlState(parentWorkflowId: String) = GoalRunnerControlState(repositoryIdentity = identity)
            },
        ),
        manifestStore = StubGoalManifestStore(state.copy(manifest = manifest)),
        projectStore = FixedProjectStorePort(storeRoot),
      )

    val detail = requireNotNull(service.detail(WorkDetailRequest("goal-1", observedAt = ideStatusObservedAt)).item)

    val specBundle = requireNotNull(detail.goalSpecBundle)
    assertEquals(bundle.resolve("spec.md").toAbsolutePath().normalize().toString(), specBundle.parentSpecPath)
    assertEquals(files.map { it.toAbsolutePath().normalize().toString() }.sorted(), specBundle.bundleFiles)
  }
}
