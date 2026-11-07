package skillbill.di.core

import org.junit.jupiter.api.io.TempDir
import skillbill.application.DesktopFixtureCatalog
import skillbill.engine.specbrowser.model.SpecBrowserNode
import skillbill.engine.specbrowser.model.SpecBrowserStatus
import skillbill.engine.specbrowser.model.SpecBundleDeletion
import skillbill.engine.specbrowser.model.SpecBundleRunState
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusLiveSection
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.model.EnvironmentContext
import skillbill.ports.UnitOfWork
import skillbill.ports.idestatus.model.WorkBoardReadModelKey
import skillbill.ports.idestatus.model.WorkBoardReadModelPage.Entry.Decoded
import skillbill.ports.idestatus.model.WorkBoardReadModelPage.Entry.Undecodable
import skillbill.ports.idestatus.model.WorkBoardReadModelPageQuery
import skillbill.ports.idestatus.model.WorkLiveSection
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.testing.repoRootFromTest
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopFixtureCoverageTest {
  @Test
  fun `fixture persists workflow verify standalone and bundle states`(
    @TempDir directory: Path,
  ) {
    val temporaryDirectory = directory.toRealPath()
    val primaryCheckout = CanonicalRepositoryRoot.primaryRepositoryRoot(repoRootFromTest())
    val linkedCheckout = temporaryDirectory.resolve("linked-checkout")
    git(primaryCheckout, "worktree", "add", "-q", "--detach", linkedCheckout.toString(), "HEAD")
    val checkoutRoot = CanonicalRepositoryRoot.canonicalPath(linkedCheckout)
    val home = temporaryDirectory.resolve("fixture-home")
    try {
      val fixture =
        writeDesktopFixture(
          home,
          checkoutRoot,
          hostEnvironment = mapOf("HOME" to temporaryDirectory.resolve("unrelated-home").toString()),
        )
      val runtime = openRuntime(home, checkoutRoot)
      assertWorkflowStatuses(runtime)
      assertPersistedRows(runtime)
      assertDerivedEnumCoverage(runtime)
      assertBundleStates(runtime, fixture.projectId, checkoutRoot, primaryCheckout)
      assertGoalRepositoryIdentities(runtime, checkoutRoot)
      assertOperatorHomeGuard(temporaryDirectory, checkoutRoot)
    } finally {
      git(primaryCheckout, "worktree", "remove", "--force", linkedCheckout.toString())
    }
  }

  private fun openRuntime(
    home: Path,
    checkoutRoot: Path,
  ): RuntimeComponent {
    val stateDirectory = home.resolve(".skill-bill")
    val databasePath = stateDirectory.resolve("review-metrics.db")
    return RuntimeComponent::class.create(
      RuntimeContext(
        environment =
          EnvironmentContext(
            dbPathOverride = databasePath.toString(),
            environment =
              mapOf(
                "SKILL_BILL_STATE_DIR" to stateDirectory.toString(),
                "SKILL_BILL_DB" to databasePath.toString(),
                "SKILL_BILL_TEST" to "1",
              ),
            userHome = home,
            repositoryRoot = checkoutRoot,
          ),
        transport = TransportContext(),
        workflowOps = WorkflowOpsContext(),
        callbacks = OptionalCallbacks(),
      ),
    )
  }

  private fun assertWorkflowStatuses(runtime: RuntimeComponent) {
    val taskStatuses =
      runtime.databaseSession.transaction { unit ->
        unit.workflowStates.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, 100)
          .map { WorkflowStatus.fromWire(it.workflowStatus) }
      }
    val verifyStatuses =
      runtime.databaseSession.transaction { unit ->
        unit.workflowStates.list(WorkflowFamily.VERIFY, 100).map { it.workflowStatus }
      }
    assertEquals(WorkflowStatus.entries.toSet(), (taskStatuses + verifyStatuses).filterNotNull().toSet())
  }

  private fun assertPersistedRows(runtime: RuntimeComponent) {
    runtime.databaseSession.transaction { unit ->
      val timeoutId = "fixture-workflow-timed_out"
      val timeoutRow = assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(timeoutId))
      assertEquals(WorkflowStatus.TIMED_OUT.wireValue, timeoutRow.workflowStatus)
      val timeoutHeader = assertNotNull(unit.workflowStates.readFeatureTaskWorkflowHeader(timeoutId))
      assertEquals(timeoutRow.currentStepId, timeoutHeader.currentStepId)
      val timeoutPhases = assertNotNull(unit.workflowStates.readFeatureTaskPhaseLedgerPage(timeoutId, 0, 20))
      assertTrue(timeoutPhases.entries.any { it.phaseId == timeoutHeader.currentStepId })
      val boardPage =
        unit.workBoardReadModel.page(
          WorkBoardReadModelPageQuery(Instant.EPOCH, filter = null, afterKey = null, limit = 500),
        )
      val timeoutBoardEntry = boardPage.entries.filterIsInstance<Decoded>().single { it.workId == timeoutId }
      assertEquals(IdeStatusLifecycleState.FAILED, timeoutBoardEntry.row.item.snapshot.lifecycleState)
      assertVerifyCases(unit)
      assertStandaloneCases(unit)
    }
  }

  private fun assertDerivedEnumCoverage(runtime: RuntimeComponent) {
    val observations =
      runtime.databaseSession.transaction { unit ->
        val taskRows =
          unit.workflowStates.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, 100)
        val verifyRows = unit.workflowStates.list(WorkflowFamily.VERIFY, 100)
        val standaloneRows =
          DesktopFixtureCatalog.standalone.mapNotNull { case ->
            unit.standalonePhaseStatuses.readByInvocation("fixture-${case.id}")
          }
        val boardEntries = readBoardEntries(unit)
        val lifecycleStates =
          buildSet {
            boardEntries.mapTo(this) { it.row.item.snapshot.lifecycleState }
            standaloneRows.mapTo(this) { IdeStatusPolicy.standaloneLifecycle(it.lifecycleState) }
            (taskRows.map { it.workflowStatus } + verifyRows.map { it.workflowStatus.wireValue })
              .mapNotNullTo(this) { IdeStatusPolicy.workflowLifecycle(it) }
            val observedAt = verifyRows.maxOf { assertNotNull(it.updatedAt) }
            verifyRows.mapTo(this) {
              IdeStatusPolicy.verifyLifecycle(
                IdeStatusPolicy.workflowLifecycle(it.workflowStatus.wireValue)
                  ?: error("Unknown verify status: ${it.workflowStatus.wireValue}"),
                assertNotNull(it.updatedAt),
                observedAt,
              )
            }
          }
        val routeScopes =
          taskRows.mapNotNull { row ->
            unit.workflowStates.getFeatureTaskExecutionIdentity(row.workflowId)?.routeScope
          }
        val skeletonIds =
          buildSet {
            routeScopes.mapTo(this) { scope ->
              when (scope) {
                FeatureTaskRouteScope.STANDALONE -> "standalone"
                FeatureTaskRouteScope.GOAL_CHILD -> "goal-child"
              }
            }
            standaloneRows.mapTo(this) { it.phaseId }
          }
        val liveSections = boardEntries.mapNotNull { it.row.item.liveSection }.toSet()
        val displayGroups = boardEntries.mapNotNull { it.row.item.displayGroup }.toSet()
        DerivedEnumObservations(
          lifecycleStates = lifecycleStates,
          skeletonKinds = skeletonIds.mapTo(mutableSetOf()) { SkeletonDefinition.byId(it).runStateKind },
          displayGroups = displayGroups,
          liveSections = liveSections,
        )
      }
    assertEquals(IdeStatusLifecycleState.entries.toSet(), observations.lifecycleStates)
    assertEquals(SkeletonRunStateKind.entries.toSet(), observations.skeletonKinds)
    assertEquals(IdeStatusDisplayGroup.entries.toSet(), observations.displayGroups)
    assertEquals(WorkLiveSection.entries.toSet(), observations.liveSections)
    assertEquals(
      IdeStatusLiveSection.entries.map { it.name }.toSet(),
      observations.liveSections.map { it.name }.toSet(),
    )
  }

  private fun readBoardEntries(unit: UnitOfWork): List<Decoded> {
    val rows = mutableListOf<Decoded>()
    var cursor: WorkBoardReadModelKey? = null
    do {
      val page =
        unit.workBoardReadModel.page(
          WorkBoardReadModelPageQuery(Instant.EPOCH, filter = null, afterKey = cursor, limit = 100),
        )
      assertTrue(page.entries.none { it is Undecodable })
      val decoded = page.entries.filterIsInstance<Decoded>()
      rows += decoded
      cursor = decoded.lastOrNull()?.key
    } while (page.entries.size == 100 && cursor != null)
    return rows
  }

  private fun assertVerifyCases(unit: UnitOfWork) {
    DesktopFixtureCatalog.verifies.forEach { case ->
      val verify = assertNotNull(unit.workflowStates.get(WorkflowFamily.VERIFY, "fixture-${case.id}"))
      assertEquals(case.status, verify.workflowStatus)
      assertEquals(case.currentStep, verify.currentStepId)
      val notes = verify.artifacts["session_notes"]
      val supersededBy = if (notes is Map<*, *>) notes["superseded_by"] as? String else null
      assertEquals(case.supersededBy, supersededBy)
    }
  }

  private fun assertStandaloneCases(unit: UnitOfWork) {
    DesktopFixtureCatalog.standalone.forEach { case ->
      val row = assertNotNull(unit.standalonePhaseStatuses.readByInvocation("fixture-${case.id}"))
      assertEquals(case.lifecycle, row.lifecycleState)
      assertEquals(case.phaseId, row.phaseId)
    }
  }

  private fun assertBundleStates(
    runtime: RuntimeComponent,
    projectId: String,
    linkedCheckout: Path,
    primaryCheckout: Path,
  ) {
    val project = assertNotNull(runtime.specBrowserService.projects().singleOrNull { it.projectId == projectId })
    assertNotNull(project.repoRoot)
    val preferredRoots = listOf(null, primaryCheckout, linkedCheckout)
    preferredRoots.forEach { preferredRoot ->
      val snapshot = runtime.specBrowserService.snapshot(projectId, preferredRoot)
      val bundles = specBundles(snapshot.roots).associateBy { it.name }
      val expectedStatuses =
        DesktopFixtureCatalog.bundles.associate { bundle ->
          bundle.directory to
            when (bundle.expectedRunState) {
              "RUNNING" -> SpecBrowserStatus.RUNNING
              "PAUSE_REQUESTED" -> SpecBrowserStatus.PAUSING
              "PAUSED" ->
                if (bundle.goalId == "goal-idle") SpecBrowserStatus.PENDING else SpecBrowserStatus.PAUSED
              "BLOCKED" -> SpecBrowserStatus.BLOCKED
              "CANCELED" -> SpecBrowserStatus.CANCELED
              "COMPLETE" -> SpecBrowserStatus.COMPLETE
              else -> SpecBrowserStatus.PENDING
            }
        }
      assertEquals(expectedStatuses, bundles.mapValues { it.value.status })
      val bundleStates =
        DesktopFixtureCatalog.bundles.associate { bundle ->
          bundle.directory to
            runtime.specBrowserService.control(projectId, bundle.directory, preferredRoot).runState.name
        }
      assertEquals(DesktopFixtureCatalog.bundles.associate { it.directory to it.expectedRunState }, bundleStates)
      assertTrue(SpecBundleRunState.entries.all { it.name in bundleStates.values })
      DesktopFixtureCatalog.bundles.filter { it.goalId != null }.forEach { bundle ->
        val control = runtime.specBrowserService.control(projectId, bundle.directory, preferredRoot)
        assertEquals("fixture-${bundle.goalId}", control.workflowId)
        assertEquals(CanonicalRepositoryRoot.primaryRepositoryIdentity(linkedCheckout), control.repositoryIdentity)
      }
      listOf("QAFIX-101-running", "QAFIX-102-pause-requested").forEach { directory ->
        assertIs<SpecBundleDeletion.Refused>(
          runtime.specBrowserService.deleteBundle(projectId, directory, preferredRoot),
        )
      }
    }
  }

  private fun assertGoalRepositoryIdentities(
    runtime: RuntimeComponent,
    linkedCheckout: Path,
  ) {
    val checkoutIdentity = CanonicalRepositoryRoot.repositoryIdentity(linkedCheckout)
    val goalIdentity = CanonicalRepositoryRoot.primaryRepositoryIdentity(linkedCheckout)
    runtime.databaseSession.transaction { unit ->
      DesktopFixtureCatalog.standalone.forEach { case ->
        val row = assertNotNull(unit.standalonePhaseStatuses.readByInvocation("fixture-${case.id}"))
        assertEquals(checkoutIdentity, row.repositoryIdentity)
      }
      DesktopFixtureCatalog.goals.forEach { case ->
        val parentId = "fixture-${case.id}"
        val childIdentity = assertNotNull(unit.workflowStates.getFeatureTaskExecutionIdentity("$parentId-child"))
        assertEquals(goalIdentity, childIdentity.repositoryIdentity)
        assertEquals(goalIdentity, unit.goalRunnerControls.controlState(parentId).repositoryIdentity)
      }
    }
  }

  private fun specBundles(nodes: List<SpecBrowserNode>): List<SpecBrowserNode.Bundle> =
    nodes.flatMap { node ->
      when (node) {
        is SpecBrowserNode.Bundle -> listOf(node) + specBundles(node.children)
        is SpecBrowserNode.Folder -> specBundles(node.children)
        is SpecBrowserNode.File -> emptyList()
      }
    }

  private fun git(
    repository: Path,
    vararg arguments: String,
  ): String {
    val process =
      ProcessBuilder("git", "-C", repository.toString(), *arguments)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    assertEquals(0, process.waitFor(), output)
    return output.trim()
  }

  private fun assertOperatorHomeGuard(
    temporaryDirectory: Path,
    checkoutRoot: Path,
  ) {
    val guardedHome = temporaryDirectory.resolve("operator-home")
    val marker = guardedHome.resolve(".skill-bill/keep.txt")
    Files.createDirectories(marker.parent)
    Files.writeString(marker, "keep")
    assertFailsWith<IllegalArgumentException> {
      writeDesktopFixture(guardedHome, checkoutRoot, hostEnvironment = mapOf("HOME" to guardedHome.toString()))
    }
    assertTrue(Files.isRegularFile(marker))
  }

  private data class DerivedEnumObservations(
    val lifecycleStates: Set<IdeStatusLifecycleState>,
    val skeletonKinds: Set<SkeletonRunStateKind>,
    val displayGroups: Set<IdeStatusDisplayGroup>,
    val liveSections: Set<WorkLiveSection>,
  )
}
