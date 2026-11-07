package skillbill.engine.goalrunner.launch

import org.junit.jupiter.api.io.TempDir
import skillbill.application.testHarnessClock
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerProgressReader
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.execution.core.testWorktreeEditJournalWriter
import skillbill.engine.goalrunner.launchFacts
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySubject
import skillbill.infrastructure.workflow.filesystem.FileSystemChildFailureOutputStore
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.diagnostics.ChildFailureOutputStore
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.workflow.decomposition.withWorkflowId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChildFailureOutputRetentionTest {
  @TempDir
  lateinit var root: Path

  @Test
  fun `failed child retains bounded output privately and references it in progress`() {
    val manifestStore = InMemoryGoalManifestStore(manifest(1).withWorkflowId(1, "wfl-child"))
    val outcomes = RecordingOutcomeStore()
    val projectStore = FixedProjectStorePort(root)
    val reconciler =
      GoalRunnerLaunchReconciler(
        manifestStore, outcomes, GoalRunnerProgressReader(outcomes), testActivityStampWriter(),
        testWorktreeEditJournalWriter(), testHarnessClock, NoopRuntimeDiagnostics, projectStore,
        FileSystemChildFailureOutputStore(),
      )
    val request = GoalRunnerRunRequest(issueKey = "SKILL-56", repoRoot = root, invokedAgentId = "codex")
    val state = requireNotNull(manifestStore.loadByIssueKey("SKILL-56", root))
    val stdout = "stdout-start" + "x".repeat(20000) + "stdout-end"
    val stderr = "stderr-start" + "y".repeat(20000) + "stderr-end"
    val facts = launchFacts(AgentRunTermination.Exited(1), stdout, stderr)

    val result = reconciler.reconcileLaunchOutcome(state, facts, 1, request)
    val name = requireNotNull(result.childFailureOutput)
    val file = root.resolve("run-evidence/wfl-child").resolve(name)
    val text = Files.readString(file)
    assertContains(text, "stdout-start")
    assertContains(text, "stdout-end")
    assertContains(text, "stderr-start")
    assertContains(text, "stderr-end")
    assertContains(text, "chars omitted")
    assertTrue(text.length < 17000)
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
    } else {
      val entry = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl.single()
      assertEquals(Files.getOwner(file), entry.principal())
      assertEquals(AclEntryType.ALLOW, entry.type())
    }

    GoalRunnerObservabilityEmitter(outcomes, testHarnessClock, NoopRuntimeDiagnostics).recordLaunchLifecycle(
      GoalRunnerObservabilitySubject("wfl-child", "SKILL-56", 1),
      "resume",
      null,
      facts,
      name,
    )
    val event = outcomes.observabilityRecords.last().activitySummary
    assertContains(event, "child_failure_output=$name")
    assertFalse(event.contains("stdout-start"))

    val success = reconciler.reconcileLaunchOutcome(state, launchFacts(), 1, request)
    assertNull(success.childFailureOutput)
    Files.list(file.parent).use { assertEquals(1L, it.count()) }
  }

  @Test
  fun `failed evidence write emits warning without replacing child outcome`() {
    val manifestStore = InMemoryGoalManifestStore(manifest(1).withWorkflowId(1, "wfl-child"))
    val outcomes = RecordingOutcomeStore()
    val warnings = mutableListOf<String>()
    val diagnostics =
      object : RuntimeDiagnostics {
        override fun warning(
          message: String,
          error: Throwable?,
        ) {
          warnings += message
        }

        override fun error(
          message: String,
          error: Throwable?,
        ) = Unit

        override fun info(message: String) = Unit
      }
    val reconciler =
      GoalRunnerLaunchReconciler(
        manifestStore, outcomes, GoalRunnerProgressReader(outcomes), testActivityStampWriter(),
        testWorktreeEditJournalWriter(), testHarnessClock, diagnostics, FixedProjectStorePort(root),
        ChildFailureOutputStore { _, _, _ -> throw IOException("disk full") },
      )
    val request = GoalRunnerRunRequest(issueKey = "SKILL-56", repoRoot = root, invokedAgentId = "codex")
    val state = requireNotNull(manifestStore.loadByIssueKey("SKILL-56", root))
    val result = reconciler.reconcileLaunchOutcome(state, launchFacts(AgentRunTermination.Exited(1)), 1, request)

    assertNull(result.childFailureOutput)
    assertTrue(warnings.any { it.contains("seam=child_failure_output") })
    assertEquals(
      AgentRunTermination.Exited(1),
      (result.launchOutcome as AgentRunLaunchFacts).termination,
    )
  }
}
