package skillbill.engine.featuretask.slot.runner

import skillbill.engine.featuretask.slot.PhaseLaunchFailureKind
import skillbill.engine.featuretask.slot.PhaseStepFacts
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class DefaultPhaseRunnerTest {
  @Test
  fun `a step captures its stdout as process output without reading an envelope from it`() {
    val stdout =
      """
      Tried to publish the release notes.
      {"status": "blocked", "value": "Release notes need an approved changelog.", "verdict": "needs_changelog", "failure_disposition": "operator_action"}
      """.trimIndent()
    val runner = DefaultPhaseRunner(launcher(stdout), NoopWorkflowGitOperations)

    val output = runner.run(input("release_notes"), PrepareOnlyPhaseRunState)

    assertEquals(stdout, output.stdout.text)
    assertEquals(PhaseSettledEnvelopeRead.None, output.settledEnvelope)
  }

  @Test
  fun `manifest capture failures report the git cause without losing launched output`() {
    val boundaries =
      listOf("before-file manifest", "before commit", "after-file manifest", "after commit", "committed file changes")
    boundaries.forEachIndexed { index, boundary ->
      var calls = 0
      var launches = 0
      val cause = "git command failed with exit code 128: repository unavailable"
      val git =
        object : WorkflowGitOperations by NoopWorkflowGitOperations {
          override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult = capture()

          override fun runtimePhaseHeadCommit(repoRoot: Path): WorkflowGitOperationResult = capture()

          override fun runtimePhaseChangedPathsBetweenCommits(
            repoRoot: Path,
            beforeCommit: String,
            afterCommit: String,
          ): WorkflowGitNameListResult =
            if (++calls == index + 1) {
              WorkflowGitNameListResult.Failed(
                cause,
              )
            } else {
              WorkflowGitNameListResult.Listed(emptyList())
            }

          private fun capture(): WorkflowGitOperationResult =
            if (++calls == index + 1) {
              WorkflowGitOperationResult.Failed(
                error = cause,
              )
            } else {
              WorkflowGitOperationResult.Ok("head")
            }
        }
      val runner =
        DefaultPhaseRunner(
          GoalRunnerSubtaskLauncher {
            launches++
            agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = "Merge conflicts resolved.")
          },
          git,
        )
      val input = input("monitor_fix").let { it.copy(facts = it.facts.copy(attempt = 1)) }

      val output = runner.run(input, TrackedPhaseRunState)

      assertEquals(
        "Feature-task-runtime phase 'monitor_fix' could not capture its $boundary: $cause",
        output.launchFailure?.reason,
      )
      assertEquals(
        if (index < 2) PhaseLaunchFailureKind.BEFORE_CAPTURE_FAILED else PhaseLaunchFailureKind.AFTER_CAPTURE_FAILED,
        output.launchFailure?.kind,
      )
      assertEquals(if (index < 2) 0 else 1, launches)
      assertEquals(if (index < 2) "" else "Merge conflicts resolved.", output.stdout.text)
      assertNull(output.fileManifest)
    }
  }

  @Test
  fun `a pre-dirty source path is attributed only when the launched repair changes its content or presence`() {
    for (after in listOf("before", "after", null)) {
      var reads = 0
      val git =
        object : WorkflowGitOperations by NoopWorkflowGitOperations {
          override fun worktreeStatus(repoRoot: Path) = WorkflowGitOperationResult.Ok(" M source.kt")

          override fun pathContentIdentities(
            repoRoot: Path,
            paths: List<String>,
          ): WorkflowPathContentIdentitiesResult {
            assertEquals(listOf("source.kt"), paths)
            val identity = if (reads++ == 0) "before" else after
            return WorkflowPathContentIdentitiesResult.Resolved(identity?.let { mapOf("source.kt" to it) }.orEmpty())
          }
        }
      val input =
        input("monitor_fix").let {
          it.copy(facts = it.facts.copy(attempt = 1), policy = it.policy.copy(mutating = true))
        }

      val output = DefaultPhaseRunner(launcher("Repair complete."), git).run(input, TrackedPhaseRunState)

      assertNull(output.launchFailure)
      assertEquals(if (after == "before") emptyList() else listOf("source.kt"), output.fileManifest?.reeditedPaths)
    }
  }

  @Test
  fun `unreadable content identities block attribution before or after the repair without losing its output`() {
    for (failedRead in listOf(1, 2)) {
      var reads = 0
      var launches = 0
      val git =
        object : WorkflowGitOperations by NoopWorkflowGitOperations {
          override fun worktreeStatus(repoRoot: Path) = WorkflowGitOperationResult.Ok(" M source.kt")

          override fun pathContentIdentities(
            repoRoot: Path,
            paths: List<String>,
          ): WorkflowPathContentIdentitiesResult =
            if (++reads == failedRead) {
              WorkflowPathContentIdentitiesResult.Failed("object database unreadable")
            } else {
              WorkflowPathContentIdentitiesResult.Resolved(mapOf("source.kt" to "before"))
            }
        }
      val input =
        input("monitor_fix").let {
          it.copy(facts = it.facts.copy(attempt = 1), policy = it.policy.copy(mutating = true))
        }
      val runner =
        DefaultPhaseRunner(
          GoalRunnerSubtaskLauncher {
            launches++
            agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = "Repair output.")
          },
          git,
        )

      val output = runner.run(input, TrackedPhaseRunState)

      assertEquals(
        if (failedRead == 1) {
          PhaseLaunchFailureKind.BEFORE_CAPTURE_FAILED
        } else {
          PhaseLaunchFailureKind.AFTER_CAPTURE_FAILED
        },
        output.launchFailure?.kind,
      )
      assertEquals(failedRead - 1, launches)
      assertEquals(if (failedRead == 1) "" else "Repair output.", output.stdout.text)
      assertNull(output.fileManifest)
    }
  }

  @Test
  fun `one shared runner carries no failure or launch state from one invocation to the next`() {
    var statusReads = 0
    var launches = 0
    val git =
      object : WorkflowGitOperations by NoopWorkflowGitOperations {
        override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
          if (++statusReads == 1) {
            WorkflowGitOperationResult.Failed(error = "repository unavailable")
          } else {
            WorkflowGitOperationResult.Ok("")
          }
      }
    val runner =
      DefaultPhaseRunner(
        GoalRunnerSubtaskLauncher { agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = "launch ${++launches}") },
        git,
      )
    val tracked = input("monitor_fix").let { it.copy(facts = it.facts.copy(attempt = 1)) }

    val first = runner.run(tracked, TrackedPhaseRunState)
    val second = runner.run(tracked, TrackedPhaseRunState)

    assertEquals(PhaseLaunchFailureKind.BEFORE_CAPTURE_FAILED, first.launchFailure?.kind)
    assertEquals("", first.stdout.text)
    assertNull(second.launchFailure)
    assertEquals("launch 1", second.stdout.text)
  }

  private fun launcher(stdout: String) =
    GoalRunnerSubtaskLauncher { agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = stdout) }

  private fun input(stepName: String) =
    PhaseStepInput(
      stepName = stepName,
      directive = "Publish the release notes.",
      priorValues = emptyMap(),
      operatorInstructions = null,
      facts =
        PhaseStepFacts(
          issueKey = "SKILL-380",
          repoRoot = Path.of("."),
          timeout = null,
          invokedAgentId = "claude",
          configuredAgentOverrideId = null,
          modelOverride = null,
          effortOverride = null,
          compaction = null,
          attempt = null,
          observeLaunch = false,
          briefingText = "",
        ),
      policy = PhaseStepPolicy(false, false, false, false, false),
    )
}

private val PrepareOnlyPhaseRunState: PhaseLaunchState =
  Proxy.newProxyInstance(
    PhaseLaunchState::class.java.classLoader,
    arrayOf(PhaseLaunchState::class.java),
  ) { _, method, args ->
    when (method.name) {
      "prepareLaunch" -> args?.single()
      "toString" -> "PrepareOnlyPhaseRunState"
      "hashCode" -> 0
      "equals" -> false
      else -> fail("an untracked launch reached PhaseLaunchState.${method.name}")
    }
  } as PhaseLaunchState

private val TrackedPhaseRunState: PhaseLaunchState =
  Proxy.newProxyInstance(
    PhaseLaunchState::class.java.classLoader,
    arrayOf(PhaseLaunchState::class.java),
  ) { _, method, args ->
    when (method.name) {
      "prepareLaunch" -> args?.single()
      "settlementTarget", "recordTokenUsage" -> null
      else -> fail("a tracked launch reached PhaseLaunchState.${method.name}")
    }
  } as PhaseLaunchState
