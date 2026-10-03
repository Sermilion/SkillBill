package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.label()

class FeatureTaskRuntimeStatelessAuditBoundaryTest {
  @Test
  fun `audit inspects the tree after simplify edits and before review`() {
    val root = Files.createTempDirectory("stateless-audit-post-simplify")
    try {
      Files.writeString(root.resolve("Calculator.kt"), IMPLEMENTATION)
      var simplifyLaunches = 0
      var auditSawSimplifiedTree = false
      val launcher =
        RuntimeRecordingLauncher { request ->
          val phase = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          when (phase) {
            "simplify" -> {
              simplifyLaunches += 1
              assertEquals(IMPLEMENTATION, Files.readString(root.resolve("Calculator.kt")))
              Files.writeString(root.resolve("Calculator.kt"), SIMPLIFIED_IMPLEMENTATION)
              facts(defaultPhaseOutput(request))
            }
            "audit" -> {
              auditSawSimplifiedTree = Files.readString(root.resolve("Calculator.kt")) == SIMPLIFIED_IMPLEMENTATION
              facts(auditSatisfiedOutput())
            }
            else -> facts(defaultPhaseOutput(request))
          }
        }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            repoRoot = root,
            acceptanceCriteria = CRITERIA,
            launcher = launcher,
          ),
        )

      val report = harness.runner.run(harness.request())

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
      assertEquals(1, simplifyLaunches)
      assertTrue(auditSawSimplifiedTree)
      assertTrue(harness.launchOrder().indexOf("audit") < harness.launchOrder().indexOf("review"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `failed repair resumes its findings without replaying implementation and returns to full audit`() {
    val root = Files.createTempDirectory("audit-repair-resume")
    try {
      Files.writeString(root.resolve("Calculator.kt"), IMPLEMENTATION)
      Files.writeString(root.resolve("CalculatorTest.kt"), "fun twiceTest() {}")
      val checked = mutableListOf<String>()
      var auditLaunches = 0
      var repairLaunches = 0
      var resumeAllowed = false
      val launcher =
        RuntimeRecordingLauncher { request ->
          val prompt = requireNotNull(request.skillRunRequest.promptOverride)
          when (phaseIdFromPrompt(prompt)) {
            "audit" -> {
              auditLaunches += 1
              CRITERIA.forEach { assertContains(prompt, it) }
              if (auditLaunches == 1) {
                facts(auditRemainingAcOutput(CRITERIA.last()))
              } else {
                inspectBothCriteria(root, checked)
                facts(auditSatisfiedOutput())
              }
            }
            "audit_implement_fix" -> {
              repairLaunches += 1
              assertContains(prompt, CRITERIA.last())
              if (!resumeAllowed) {
                Files.writeString(root.resolve("CalculatorTest.kt"), TEST_SOURCE)
                (facts(defaultPhaseOutput(request)) as AgentRunLaunchFacts).copy(
                  termination = AgentRunTermination.Exited(1),
                  stderr = "Process failed after saving repairs.",
                )
              } else {
                assertEquals(TEST_SOURCE, Files.readString(root.resolve("CalculatorTest.kt")))
                facts(defaultPhaseOutput(request))
              }
            }
            else -> facts(defaultPhaseOutput(request))
          }
        }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            repoRoot = root,
            acceptanceCriteria = CRITERIA,
            launcher = launcher,
          ),
        )
      val interrupted = harness.runner.run(harness.request())
      assertIs<FeatureTaskRuntimeRunReport.Blocked>(interrupted, interrupted.toString())
      assertEquals("audit_implement_fix", interrupted.lastIncompletePhase)
      assertEquals(1, auditLaunches)
      assertTrue("review" !in harness.launchOrder())
      val repairAttemptsBeforeResume = repairLaunches
      resumeAllowed = true
      val restarted = harness.runner.run(harness.request())
      assertIs<FeatureTaskRuntimeRunReport.Completed>(restarted, restarted.toString())
      assertEquals(2, auditLaunches)
      assertEquals(repairAttemptsBeforeResume + 1, repairLaunches)
      assertEquals(CRITERIA, checked)
      assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `external repair dependency blocks without launching repair or downstream agents`() {
    val reason = "Private SDK is unavailable and its API is required to repair AC-002."
    val launcher =
      RuntimeRecordingLauncher { request ->
        val phase = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phase == "audit") auditBlockedOutput(reason) else defaultPhaseOutput(request))
      }
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(launcher = launcher),
      )
    val result = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(result)
    assertEquals("audit", result.lastIncompletePhase)
    assertContains(result.blockedReason, reason)
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("audit"))
    assertEquals(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION, record.failureDisposition)
    assertTrue("audit" !in result.completedPhaseIds)
    assertEquals(listOf("preplan", "plan", "implement", "simplify", "audit"), harness.launchedPromptPhaseOrder())
  }

  @Test
  fun `missing or malformed planned criteria block before any agent can claim audit completion`() {
    val field = FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA.wireValue
    listOf(null, "not a criterion list", emptyList<String>(), listOf(" ")).forEach { invalid ->
      val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
      harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
      harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
      harness.seedPhase("implement", "completed", 1, "claude", IMPLEMENT_OUTPUT)
      val invariants = harness.request().runInvariants.asWorkflowArtifactEntry().toWorkflowArtifactMap().toMutableMap()
      if (invalid == null) invariants.remove(field) else invariants[field] = invalid
      harness.repository.replaceTaskRuntimeArtifacts(
        WORKFLOW_ID,
        harness.repository.taskRuntimeArtifacts(WORKFLOW_ID) +
          (FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY to invariants),
      )
      val result = harness.runner.run(harness.request())
      assertIs<FeatureTaskRuntimeRunReport.Blocked>(result)
      assertContains(result.blockedReason, "Planning inputs are unreadable")
      assertEquals("simplify", result.lastIncompletePhase)
      assertContains(result.blockedReason.lowercase(), "acceptance")
      assertTrue(harness.launcher.requests.isEmpty())
      assertTrue("audit" !in result.completedPhaseIds)
    }
  }

  private fun inspectBothCriteria(
    root: Path,
    checked: MutableList<String>,
  ) {
    assertContains(Files.readString(root.resolve("Calculator.kt")), "value * 2")
    checked += CRITERIA.first()
    assertContains(Files.readString(root.resolve("CalculatorTest.kt")), "assertEquals(6, twice(3))")
    checked += CRITERIA.last()
  }

  private companion object {
    val CRITERIA =
      listOf(
        "AC-007. twice returns twice its input.",
        "AC-023. A test asserts that twice(3) returns 6.",
      )
    const val IMPLEMENTATION = "fun twice(value: Int) = value * 2"
    const val SIMPLIFIED_IMPLEMENTATION = "fun twice(value: Int) = value shl 1"
    val TEST_SOURCE =
      """

      class CalculatorTest {
        @Test
        fun twiceTest() { assertEquals(6, twice(3)) }
      }
      """.trimIndent()
  }
}
