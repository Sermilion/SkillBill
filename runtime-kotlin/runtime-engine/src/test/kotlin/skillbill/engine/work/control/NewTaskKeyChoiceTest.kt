package skillbill.engine.work.control

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.work.control.model.NewTaskIntakeRequest
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NewTaskKeyChoiceTest {
  private val repoRoot: Path = Files.createTempDirectory("new-task-key-choice")
  private val subject =
    WorkNewTaskStart(
      FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
      GoalNewWorkAdmissionPort { intake, _ ->
        LEADING_KEY.find(intake.trim())?.let { GoalNewWorkAdmission.Admitted(it.groupValues[1]) }
          ?: GoalNewWorkAdmission.NoIssueKey
      },
      NewTaskIssueKeyAllocator(
        projectStore(repoRoot, repoRoot.resolve("project-specs")),
        TestDecompositionManifestStore,
        NoopRuntimeDiagnostics,
      ),
      object : GoalPreflightPort {
        override fun preflight(request: GoalPreflightRequest) =
          GoalPreflightResult(GoalPreflightVerdict.NEW_WORK, request.issueKey)
      },
      object : WorkRunLauncher {
        override fun start(request: WorkRunLaunchRequest) = WorkRunLaunchResult.Refused("Unused")

        override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
      },
      EnvironmentContext(),
      TestRepositoryEnclosingRoot,
      NoopRuntimeDiagnostics,
    )

  init {
    bundle(repoRoot.resolve("project-specs"), "WE-5188-dashboard", "", "")
  }

  @Test
  fun `a tracker link anywhere in the intake supplies the key`() {
    val ready =
      ready(
        "lets start goal to work on this https://linear.app/acme/issue/WE-5249/follow-up-fixes " +
          "use feat/WE-5188-dashboard as base branch. Analytics moved to WE-5187.",
      )

    assertEquals("WE-5249", ready.key)
    assertFalse(ready.allocated)
  }

  @Test
  fun `a single named key with a known prefix supplies the key`() {
    assertEquals("WE-5249", ready("Implement **WE-5249** on top of the dashboard.").key)
  }

  @Test
  fun `a named key with an unknown prefix is not taken as the key`() {
    val choices = needsKey("Hash it with SHA-256 before upload.")

    assertEquals(listOf("WE-5189"), choices)
  }

  @Test
  fun `an intake without a key asks and suggests the next free key`() {
    assertEquals(listOf("WE-5189"), needsKey("Add a dark mode setting."))
  }

  @Test
  fun `several named keys ask with each of them and the next free key`() {
    val choices = needsKey("Follow up WE-5249 after WE-5187 lands.")

    assertEquals(listOf("WE-5249", "WE-5187", "WE-5189"), choices)
  }

  @Test
  fun `a chosen prefix allocates the next free key`() {
    val ready = ready("Add a dark mode setting.", keyChoice = "we")

    assertEquals("WE-5189", ready.key)
    assertTrue(ready.allocated)
  }

  @Test
  fun `a chosen key is used even when the intake names another`() {
    val ready = ready("https://linear.app/acme/issue/WE-5249/slug follow-ups", keyChoice = "WE-6000")

    assertEquals("WE-6000", ready.key)
    assertFalse(ready.allocated)
  }

  private fun prepare(
    requirements: String,
    keyChoice: String?,
  ): NewTaskPreparation =
    subject.prepareNewTask(repoRoot, NewTaskIntakeRequest(requirements, "Follow-up fixes", null, keyChoice, emptySet()))

  private fun ready(
    requirements: String,
    keyChoice: String? = null,
  ): NewTaskPreparation.Ready = assertIs<NewTaskPreparation.Ready>(prepare(requirements, keyChoice))

  private fun needsKey(requirements: String): List<String> =
    assertIs<NewTaskPreparation.NeedsKey>(prepare(requirements, null)).choices

  private companion object {
    val LEADING_KEY = Regex("^([A-Z][A-Z0-9]*-\\d+)(?:\\s|$)")
  }
}
