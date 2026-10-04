package skillbill.cli.featuretask

import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeatureTaskRuntimeGoalContinuationProtocolTest {
  @Test
  fun `CliRuntime parses contract-built continuation argv into the run request`() {
    val fixture = continuationFixture()
    var captured: FeatureTaskRuntimeRunInput? = null
    val result =
      CliRuntime.run(
        continuationArguments(fixture.specPath),
        CliRuntimeContext(
          dbPathOverride = fixture.home.resolve("metrics.db").toString(),
          environment =
            mapOf(
              FeatureTaskRuntimeGoalContinuationLaunchTokens.VALIDATION_DEPTH_ENV to
                ValidationDepth.FULL.wireValue,
              FeatureTaskRuntimeGoalContinuationLaunchTokens.QUALITY_GATE_SELECTION_ENV to
                FeatureTaskRuntimeQualityGateSelection.BUILD.wireValue,
            ),
          repositoryRoot = fixture.repositoryRoot,
          userHome = fixture.home,
          executableLookup = ExecutableLookup { true },
          workflowGitOperations =
            object : WorkflowGitOperations by NoopWorkflowGitOperations {
              override fun repositoryOwnedPaths(repoRoot: Path) = WorkflowGitNameListResult.Listed(listOf("Main.kt"))
            },
          featureTaskRuntimeRunOverride = { request ->
            captured = request
            FeatureTaskRuntimeRunReport.Completed(
              issueKey = request.issueKey,
              workflowId = request.explicitWorkflowId ?: "wfl-override",
              featureSize = "SMALL",
              completedPhaseIds = emptyList(),
              resolvedBranch = null,
            )
          },
        ),
      )

    assertEquals(0, result.exitCode, result.stderr)
    val request = assertNotNull(captured)
    assertEquals("SKILL-371", request.goalContinuation?.parentIssueKey)
    assertEquals(3, request.goalContinuation?.subtaskId)
    assertEquals("feat/SKILL-371", request.goalContinuation?.goalBranch)
    assertEquals("wfl-parent", request.goalContinuation?.parentWorkflowId)
    assertEquals("audit", request.goalContinuation?.lastResumableStep)
    assertEquals(CodeReviewExecutionMode.AUTO, request.goalContinuation?.codeReviewMode)
    assertEquals(ValidationDepth.FULL, request.goalContinuation?.validationDepth)
    assertEquals(FeatureTaskRuntimeQualityGateSelection.BUILD, request.goalContinuation?.qualityGateSelection)
    assertEquals(
      GoalSubtaskReviewBaseline("a".repeat(40), listOf("existing.txt")),
      request.goalContinuation?.reviewBaseline,
    )
  }

  @Test
  fun `contract-built argv and environment populate every CLI continuation field`() {
    val command = ProtocolProbeCommand()
    val tokens = FeatureTaskRuntimeGoalContinuationLaunchTokens
    val argv =
      listOf(
        tokens.GOAL_PARENT_ISSUE_KEY_FLAG,
        "SKILL-371",
        tokens.GOAL_SUBTASK_ID_FLAG,
        "3",
        tokens.GOAL_BRANCH_FLAG,
        "feat/SKILL-371",
        tokens.GOAL_PARENT_WORKFLOW_ID_FLAG,
        "wfl-parent",
        tokens.GOAL_LAST_RESUMABLE_STEP_FLAG,
        "audit",
        tokens.GOAL_REVIEW_BASE_SHA_FLAG,
        "a".repeat(40),
        tokens.GOAL_BASELINE_UNTRACKED_PATH_FLAG,
        "existing.txt",
        tokens.CODE_REVIEW_MODE_FLAG,
        CodeReviewExecutionMode.AUTO.wireValue,
        tokens.SUPPRESS_PR_FLAG,
        tokens.QUALITY_GATE_SELECTION_FLAG,
        FeatureTaskRuntimeQualityGateSelection.BUILD.wireValue,
      )
    val environment =
      mapOf(
        tokens.VALIDATION_DEPTH_ENV to ValidationDepth.FULL.wireValue,
        tokens.QUALITY_GATE_SELECTION_ENV to FeatureTaskRuntimeQualityGateSelection.BUILD.wireValue,
      )

    CommandLineParser.parseAndRun(command, argv) { it.run() }
    val continuation = assertNotNull(command.parseGoalContinuationContext(environment))

    assertEquals("SKILL-371", continuation.parentIssueKey)
    assertEquals(3, continuation.subtaskId)
    assertEquals("feat/SKILL-371", continuation.goalBranch)
    assertEquals(true, continuation.suppressPr)
    assertEquals("wfl-parent", continuation.parentWorkflowId)
    assertEquals("audit", continuation.lastResumableStep)
    assertEquals(CodeReviewExecutionMode.AUTO, continuation.codeReviewMode)
    assertEquals(ValidationDepth.FULL, continuation.validationDepth)
    assertEquals(FeatureTaskRuntimeQualityGateSelection.BUILD, continuation.qualityGateSelection)
    assertEquals(
      GoalSubtaskReviewBaseline("a".repeat(40), listOf("existing.txt")),
      continuation.reviewBaseline,
    )
  }
}

private data class ContinuationFixture(
  val repositoryRoot: Path,
  val specPath: Path,
  val home: Path,
)

private fun continuationFixture(): ContinuationFixture {
  val repositoryRoot = Files.createTempDirectory("skillbill-cli-continuation-contract")
  Files.createDirectories(repositoryRoot.resolve(".git"))
  val specPath = repositoryRoot.resolve(".feature-specs/SKILL-371/spec.md")
  Files.createDirectories(specPath.parent)
  Files.writeString(
    specPath,
    """
    # Runtime spec

    feature_size: SMALL

    ## Acceptance Criteria
    1. Preserve the runtime continuation contract.
    """.trimIndent(),
  )
  return ContinuationFixture(
    repositoryRoot = repositoryRoot,
    specPath = specPath,
    home = Files.createTempDirectory("skillbill-cli-continuation-home"),
  )
}

private fun continuationArguments(specPath: Path): List<String> {
  val tokens = FeatureTaskRuntimeGoalContinuationLaunchTokens
  return listOf(
    "feature-task",
    "run",
    "SKILL-371",
    specPath.toString(),
    tokens.GOAL_PARENT_ISSUE_KEY_FLAG,
    "SKILL-371",
    tokens.GOAL_SUBTASK_ID_FLAG,
    "3",
    tokens.GOAL_BRANCH_FLAG,
    "feat/SKILL-371",
    tokens.GOAL_PARENT_WORKFLOW_ID_FLAG,
    "wfl-parent",
    tokens.GOAL_LAST_RESUMABLE_STEP_FLAG,
    "audit",
    tokens.GOAL_REVIEW_BASE_SHA_FLAG,
    "a".repeat(40),
    tokens.GOAL_BASELINE_UNTRACKED_PATH_FLAG,
    "existing.txt",
    tokens.CODE_REVIEW_MODE_FLAG,
    CodeReviewExecutionMode.AUTO.wireValue,
    tokens.SUPPRESS_PR_FLAG,
    tokens.QUALITY_GATE_SELECTION_FLAG,
    FeatureTaskRuntimeQualityGateSelection.BUILD.wireValue,
    "--agent",
    "claude",
  )
}

private class ProtocolProbeCommand : FeatureTaskRuntimePhaseAgentCommand("probe", "probe") {
  override fun run() = Unit
}
