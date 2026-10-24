package skillbill.engine.specbrowser

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.goalPreflightServiceForTests
import skillbill.engine.specbrowser.model.SpecBrowserFileKind
import skillbill.engine.specbrowser.model.SpecBrowserNode
import skillbill.engine.specbrowser.model.SpecBrowserSnapshot
import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.engine.work.control.WorkRunActions
import skillbill.engine.work.fixtureCheckedOutBranch
import skillbill.engine.work.goalManifestState
import skillbill.engine.work.ideStatusClock
import skillbill.engine.work.ideStatusObservedAt
import skillbill.engine.work.ideStatusService
import skillbill.engine.work.runtimeRecord
import skillbill.engine.work.testGoalRepositoryIdentity
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.projectstore.ProjectCatalogPort
import skillbill.ports.projectstore.model.ProjectCatalogEntry
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecBrowserServiceTest {
  @Test
  fun `tree is classified, statused from manifests and ordered active, pending, then latest finished`() {
    val root = Files.createTempDirectory("spec-browser-tree").toRealPath()
    val specsRoot = Files.createDirectories(root.resolve("store").resolve("specs"))
    val subtasks = listOf(1 to "complete", 2 to "pending", 3 to "pending")
    writeBundle(specsRoot, "SKILL-100-first-feature", "SKILL-100", "in_progress", subtasks)
    Files.writeString(specsRoot.resolve("SKILL-100-first-feature").resolve("notes.txt"), "notes")
    bareBundle(specsRoot, "SKILL-99-plain-bundle")
    writeBundle(specsRoot, "SKILL-50-finished-first", "SKILL-50", "complete", listOf(1 to "complete"))
    writeBundle(specsRoot, "SKILL-51-finished-later", "SKILL-51", "complete", listOf(1 to "complete"))
    writeBundle(specsRoot, "group/SKILL-30-grouped", "SKILL-30", "complete", listOf(1 to "complete"))
    finishedAt(specsRoot, "SKILL-50-finished-first", "2026-09-01T10:00:00Z")
    finishedAt(specsRoot, "SKILL-51-finished-later", "2026-09-02T10:00:00Z")
    val environment = mapOf("HOME" to root.toString())
    val database = sqliteSessionFactoryForTests(root, root.resolve("absent.db").toString(), environment)

    val snapshot = harness(database, root).service.snapshot("test-project", root)

    assertNull(snapshot.failure)
    assertEquals(
      listOf(
        "SKILL-100-first-feature",
        "SKILL-99-plain-bundle",
        "SKILL-51-finished-later",
        "SKILL-50-finished-first",
        "group",
      ),
      snapshot.roots.map { it.name },
    )
    val first = snapshot.roots[0] as SpecBrowserNode.Bundle
    val plain = snapshot.roots[1] as SpecBrowserNode.Bundle
    val group = snapshot.roots[4] as SpecBrowserNode.Folder
    assertEquals(SpecBrowserStatus.PENDING, plain.status)
    assertEquals(SpecBrowserStatus.IN_PROGRESS, first.status)
    assertEquals(1 to 3, first.subtasksDone to first.subtasksTotal)
    assertEquals("First feature", first.title)
    assertEquals("SKILL-100", first.issueKey)
    val files = first.children.filterIsInstance<SpecBrowserNode.File>()
    assertEquals(
      listOf(
        "spec.md",
        "spec_subtask_1.md",
        "spec_subtask_2.md",
        "spec_subtask_3.md",
        "decomposition-manifest.yaml",
        "notes.txt",
      ),
      files.map { it.name },
    )
    assertEquals(
      listOf(
        SpecBrowserFileKind.PARENT_SPEC,
        SpecBrowserFileKind.SUBTASK_SPEC,
        SpecBrowserFileKind.SUBTASK_SPEC,
        SpecBrowserFileKind.SUBTASK_SPEC,
        SpecBrowserFileKind.OTHER,
        SpecBrowserFileKind.OTHER,
      ),
      files.map { it.kind },
    )
    assertEquals(
      listOf(SpecBrowserStatus.COMPLETE, SpecBrowserStatus.PENDING, SpecBrowserStatus.PENDING),
      files.mapNotNull { it.status },
    )
    assertEquals(SpecBrowserStatus.COMPLETE, (group.children.single() as SpecBrowserNode.Bundle).status)
    assertEquals(1, group.bundleCount)
    assertEquals(mapOf(SpecBrowserStatus.COMPLETE to 1), group.statusCounts)
    assertEquals(listOf(5, 1, 1, 3), snapshot.counts.let { listOf(it.all, it.active, it.pending, it.done) })
    assertTrue(snapshot.anyInProgress)
  }

  @Test
  fun `a bundle without a readable manifest is pending wherever it sits`() {
    val root = Files.createTempDirectory("spec-browser-bare").toRealPath()
    val specsRoot = Files.createDirectories(root.resolve("store").resolve("specs"))
    bareBundle(specsRoot, "done/SKILL-51-bare")
    val environment = mapOf("HOME" to root.toString())
    val database = sqliteSessionFactoryForTests(root, root.resolve("absent.db").toString(), environment)

    val snapshot = harness(database, root).service.snapshot("test-project", root)

    val folder = snapshot.roots.single() as SpecBrowserNode.Folder
    assertEquals(SpecBrowserStatus.PENDING, (folder.children.single() as SpecBrowserNode.Bundle).status)
  }

  @Test
  fun `a live goal overrides the manifest status`() {
    assertLiveGoalOverrides(manifestStatus = "in_progress", subtasks = listOf(1 to "complete", 2 to "pending"))
  }

  @Test
  fun `a finished manifest still shows its goal while that goal has live work`() {
    assertLiveGoalOverrides(manifestStatus = "complete", subtasks = listOf(1 to "complete", 2 to "complete"))
  }

  private fun assertLiveGoalOverrides(
    manifestStatus: String,
    subtasks: List<Pair<Int, String>>,
  ) {
    establishTemporarySchemaReadiness("spec-browser-live").use { schema ->
      val repo = schema.tempDir.toRealPath()
      val specsRoot = Files.createDirectories(repo.resolve("store").resolve("specs"))
      writeBundle(specsRoot, "SKILL-148-ide-status", "SKILL-148", manifestStatus, subtasks)
      writeBundle(specsRoot, "SKILL-149-finished", "SKILL-149", "complete", listOf(1 to "complete"))
      val environment = mapOf("HOME" to repo.toString())
      val database = sqliteSessionFactoryForTests(repo, schema.dbPath.toString(), environment, ideStatusClock)
      val manifests = testWorkflowGoalRunnerManifestStore(database, TestDecompositionManifestStore, ideStatusClock)
      val state = goalManifestState(repo, testGoalRepositoryIdentity(repo), "w-child")
      database.transaction { unit ->
        listOf("goal-1", "w-child").forEach { id ->
          unit.workflowStates.saveFeatureTaskWorkflow(
            runtimeRecord(id, ideStatusObservedAt.toString()),
            FeatureTaskWorkflowMode.RUNTIME,
          )
        }
      }
      schema.connection.prepareStatement(
        "INSERT INTO goal_issue_progress " +
          "(parent_workflow_id, issue_key, first_started_at, status, last_activity_at, state_entered_at) " +
          "VALUES ('goal-1', 'SKILL-148', ?, 'running', ?, ?)",
      ).use {
        it.setString(1, ideStatusObservedAt.minusSeconds(60).toString())
        it.setString(2, ideStatusObservedAt.minusSeconds(30).toString())
        it.setString(3, ideStatusObservedAt.minusSeconds(30).toString())
        it.executeUpdate()
      }
      manifests.save(state)
      manifests.persistControlState(
        "goal-1",
        state.controlState.copy(paused = true, pauseReason = "operator_request", pausedAt = "2026-08-02T10:00:00Z"),
      )

      val snapshot: SpecBrowserSnapshot = harness(database, repo).service.snapshot("test-project", repo)

      val bundles = snapshot.roots.map { it as SpecBrowserNode.Bundle }
      assertEquals(listOf("SKILL-148", "SKILL-149"), bundles.map { it.issueKey })
      assertEquals(listOf(SpecBrowserStatus.PAUSED, SpecBrowserStatus.COMPLETE), bundles.map { it.status })
      assertTrue(!snapshot.anyInProgress)
    }
  }

  private class Harness(val service: SpecBrowserService)

  private fun harness(
    database: DatabaseSessionFactory,
    repo: Path,
  ): Harness {
    val storeRoot = repo.resolve("store")
    val manifests = testWorkflowGoalRunnerManifestStore(database, TestDecompositionManifestStore, ideStatusClock)
    val status = ideStatusService(database, manifests)
    val goals = testGoalRunnerStatusService(manifests, EmptyOutcomeStore, database = database, clock = ideStatusClock)
    val launcher =
      object : WorkRunLauncher {
        override fun start(request: WorkRunLaunchRequest) = WorkRunLaunchResult.Refused("Unused")

        override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
      }
    val runs =
      WorkRunActions(
        database,
        status,
        goals,
        CheckedOutBranchSource(::fixtureCheckedOutBranch),
        NoopFeatureTaskRuntimeWorkerSupervisor,
        launcher,
        ideStatusClock,
        goalPreflightServiceForTests(database),
        EnvironmentContext(environment = mapOf("HOME" to repo.toString()), userHome = repo),
        TestRepositoryEnclosingRoot,
      )
    val catalog =
      object : ProjectCatalogPort {
        override fun list() =
          listOf(ProjectCatalogEntry("test-project", storeRoot, storeRoot.resolve("specs"), listOf(repo), null))
      }
    return Harness(
      SpecBrowserService(
        catalog,
        FixedProjectStorePort(storeRoot),
        TestDecompositionManifestStore,
        DecompositionManifestSchemaValidator(),
        database,
        status,
        runs,
        manifests,
        TestRepositoryEnclosingRoot,
      ),
    )
  }

  private fun finishedAt(
    specsRoot: Path,
    relativePath: String,
    instant: String,
  ) {
    val manifest = specsRoot.resolve(relativePath).resolve("decomposition-manifest.yaml")
    Files.setLastModifiedTime(manifest, FileTime.from(Instant.parse(instant)))
  }

  private fun bareBundle(
    specsRoot: Path,
    relativePath: String,
  ) {
    val directory = Files.createDirectories(specsRoot.resolve(relativePath))
    Files.writeString(directory.resolve("spec.md"), "# spec")
  }

  private fun writeBundle(
    specsRoot: Path,
    relativePath: String,
    issueKey: String,
    status: String,
    subtasks: List<Pair<Int, String>>,
  ) {
    val directory = Files.createDirectories(specsRoot.resolve(relativePath))
    val folder = directory.fileName.toString()
    val featureName = folder.removePrefix("$issueKey-").replace('-', ' ').replaceFirstChar(Char::uppercase)
    val subtaskYaml =
      subtasks.joinToString("\n") { (id, subtaskStatus) ->
        "- id: $id\n  name: subtask $id\n  spec_path: \".feature-specs/$folder/spec_subtask_$id.md\"\n" +
          "  status: $subtaskStatus\n  branch: null\n  commit_sha: null\n  workflow_id: null\n" +
          "  blocked_reason: null\n  last_resumable_step: null\n  dependencies: []"
      }
    Files.writeString(
      directory.resolve("decomposition-manifest.yaml"),
      "contract_version: \"0.5\"\nissue_key: \"$issueKey\"\nfeature_name: \"$featureName\"\n" +
        "parent_spec_path: \".feature-specs/$folder/spec.md\"\nspec_source: local\nstatus: $status\n" +
        "execution_model: same_branch_commit_per_subtask\nbase_branch: main\nfeature_branch: feat/$folder\n" +
        "stack_branches: []\ncurrent_subtask_intent:\n  subtask_id: ${subtasks.first().first}\n  action: resume\n" +
        "subtasks:\n$subtaskYaml\n",
    )
    Files.writeString(directory.resolve("spec.md"), "# spec")
    subtasks.forEach { (id, _) -> Files.writeString(directory.resolve("spec_subtask_$id.md"), "# subtask $id") }
  }
}
