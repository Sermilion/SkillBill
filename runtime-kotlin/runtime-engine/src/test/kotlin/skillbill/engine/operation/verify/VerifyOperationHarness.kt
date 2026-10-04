package skillbill.engine.operation.verify

import skillbill.application.realPlanningProjectionValidator
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.telemetry.model.FeatureVerifyFinishedRequest
import skillbill.application.telemetry.model.FeatureVerifyStartedRequest
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.service.WorkflowService
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.PhaseStepSession
import skillbill.engine.featuretask.slot.reviewStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationConfirmationGate
import skillbill.engine.operation.core.OperationExecutor
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.operation.core.OperationRequest
import skillbill.engine.operation.core.OperationStepRunner
import skillbill.engine.operation.unittestvalue.UnitTestValueCheckPromptRules
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.operation.SqliteOperationProposalRepository
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.model.EnvironmentContext
import skillbill.model.RepositoryRoot
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.pullrequest.PullRequestReviewThreadOperations
import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

internal class VerifyOperationHarness : AutoCloseable {
  private val root: Path = Files.createTempDirectory("verify-operation")
  val repo: Path = root.resolve("repo")
  private val dbPath: Path = root.resolve("metrics.db")
  val runner = VerifyScriptedRunner()
  val telemetry = RecordingVerifyTelemetry()
  val delegatedReviews = mutableListOf<ParallelCodeReviewRequest>()
  private val git = GitWorkflowGitOperations()
  private val clock = Clock.systemUTC()
  private val database =
    SQLiteDatabaseSessionFactory(
      EnvironmentContext(dbPathOverride = dbPath.toString(), environment = emptyMap(), userHome = root),
      clock,
      NoopRuntimeDiagnostics,
      WorkflowStateSchemaValidator(),
      "test-runtime-version",
    )
  private val executor: OperationExecutor
  private val workflows: WorkflowService
  val base: String
  val head: String

  init {
    Files.createDirectories(repo)
    git("init", "--quiet", "--initial-branch=main")
    git("config", "user.email", "verify@example.com")
    git("config", "user.name", "Verify Test")
    git("config", "commit.gpgsign", "false")
    write(SPEC, "# Greeting\n\n## Acceptance Criteria\n\n1. Greets by name.\n")
    write("src/main/kotlin/Greeter.kt", "fun greet() = \"hi\"\n")
    commitAll("base")
    base = git("rev-parse", "HEAD")
    write("src/main/kotlin/Greeter.kt", "fun greet(name: String) = \"hi \$name\"\n")
    write("src/test/kotlin/GreeterTest.kt", "class GreeterTest\n")
    commitAll("feature")
    head = git("rev-parse", "HEAD")
    git("update-ref", "refs/remotes/origin/HEAD", base)
    workflows =
      WorkflowService(
        database = database,
        gitOperations = git,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = WorkflowStateSchemaValidator(),
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = RepositoryRoot(repo),
        goalObservabilityEventValidator = realPlanningProjectionValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = clock,
      )
    val verify =
      VerifyOperation(
        workflows,
        git,
        UnusedPullRequests,
        telemetry,
        { request ->
          delegatedReviews += request
          ParallelCodeReviewRunOutcome.Reviewed(
            ParallelCodeReviewResult(
              mergeResult = ParallelReviewMergeResult(findings = emptyList(), formattedOutput = REVIEW_REGISTER),
              lane1 = ParallelReviewLaneStatus(agentId = AGENT, success = true),
            ),
          )
        },
        NoopRuntimeDiagnostics,
        clock,
      )
    val proposals = SqliteOperationProposalRepository(database)
    executor =
      OperationExecutor(
        OperationRegistry(listOf(verify)),
        OperationConfirmationGate(proposals, git, clock),
        OperationStepRunner(runner, git),
      )
  }

  val range: String get() = "$base..$head"

  fun propose(
    mode: String? = null,
    instructions: String? = null,
  ): OperationOutcome = invoke(OperationArguments(spec = SPEC, target = range, mode = mode), instructions)

  fun confirm(
    token: String,
    target: String? = null,
  ): OperationOutcome = invoke(OperationArguments(confirm = token, target = target))

  fun invoke(
    arguments: OperationArguments,
    instructions: String? = null,
  ): OperationOutcome = executor.execute(OperationRequest("verify", repo, AGENT, arguments, instructions)).outcome

  fun status(): String = git("status", "--porcelain")

  fun headCommit(): String = git("rev-parse", "HEAD")

  fun workflowStatus(workflowId: String): String? = column(workflowId, "workflow_status")

  fun currentStep(workflowId: String): String? = column(workflowId, "current_step_id")

  fun snapshot(workflowId: String): WorkflowSnapshotView =
    (workflows.get(WorkflowFamilyKind.VERIFY, workflowId) as WorkflowGetResult.Ok).snapshot

  fun editDuring(stepName: String) {
    runner.sideEffects[stepName] = { write("src/main/kotlin/Greeter.kt", "fun greet() = \"edited\"\n") }
  }

  fun seedSkillWorkflowAtCodeReview(): String {
    val opened = workflows.open(WorkflowServiceOpenArgs(WorkflowFamilyKind.VERIFY, sessionId = SKILL_SESSION_ID))
    val workflowId = (opened as WorkflowOpenResult.Ok).workflowId
    val checkpoint = (git.repositoryFingerprint(repo) as WorkflowGitOperationResult.Ok).value.orEmpty()
    val rubric = mapOf("contract_version" to "0.1", "rules" to "Skill-owned rubric.")
    seed(
      workflowId,
      VerifyWorkflow.GATHER_DIFF,
      listOf(
        stepEntry(VerifyWorkflow.COLLECT_INPUTS, WorkflowStepStatus.COMPLETED, 1),
        stepEntry(VerifyWorkflow.EXTRACT_CRITERIA, WorkflowStepStatus.COMPLETED, 1),
        stepEntry(VerifyWorkflow.GATHER_DIFF, WorkflowStepStatus.RUNNING, 1),
      ),
      mapOf(
        "input_context" to mapOf("spec" to SPEC, "pr" to range),
        "criteria_summary" to
          mapOf(
            "acceptance_criteria" to "1. Greets by name.",
            "non_goals" to "",
            "rollout_expectation" to "",
            "technical_constraints" to "",
          ),
      ),
    )
    seed(
      workflowId,
      VerifyWorkflow.FEATURE_FLAG_AUDIT,
      listOf(
        stepEntry(VerifyWorkflow.GATHER_DIFF, WorkflowStepStatus.COMPLETED, 1),
        stepEntry(VerifyWorkflow.FEATURE_FLAG_AUDIT, WorkflowStepStatus.RUNNING, 1),
      ),
      mapOf(
        "diff_projection" to
          mapOf(
            "checkpoint" to checkpoint,
            "comparison_scope" to range,
            "changed_files" to listOf("src/main/kotlin/Greeter.kt", "src/test/kotlin/GreeterTest.kt"),
          ),
        "feature_flag_policy" to rubric,
        "review_rubric" to rubric,
        "unit_test_value_rubric" to rubric,
        "completeness_rubric" to rubric,
      ),
    )
    seed(
      workflowId,
      VerifyWorkflow.CODE_REVIEW,
      listOf(
        stepEntry(VerifyWorkflow.FEATURE_FLAG_AUDIT, WorkflowStepStatus.SKIPPED, 1),
        stepEntry(VerifyWorkflow.CODE_REVIEW, WorkflowStepStatus.RUNNING, 1),
      ),
      mapOf(
        "feature_flag_audit_receipt" to
          mapOf("contract_version" to "0.1", "verdict" to "skipped", "findings" to listOf("No flag in scope.")),
      ),
    )
    return workflowId
  }

  fun row(workflowId: String): String? =
    column(workflowId, "contract_version || '|' || workflow_status || '|' || steps_json || '|' || artifacts_json")

  fun setContractVersion(
    workflowId: String,
    version: String,
  ) = sql { connection ->
    connection.prepareStatement("UPDATE feature_verify_workflows SET contract_version = ? WHERE workflow_id = ?")
      .use { update ->
        update.setString(1, version)
        update.setString(2, workflowId)
        check(update.executeUpdate() == 1)
      }
  }

  fun rowCount(table: String): Int =
    sql { connection ->
      val exists =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { query ->
          query.setString(1, table)
          query.executeQuery().use { rows -> rows.next() }
        }
      if (!exists) return@sql 0
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
          rows.next()
          rows.getInt(1)
        }
      }
    }

  override fun close() {
    root.toFile().deleteRecursively()
  }

  private fun column(
    workflowId: String,
    expression: String,
  ): String? =
    sql { connection ->
      connection.prepareStatement("SELECT $expression FROM feature_verify_workflows WHERE workflow_id = ?")
        .use { query ->
          query.setString(1, workflowId)
          query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

  private fun seed(
    workflowId: String,
    currentStepId: String,
    steps: List<Map<String, Any?>>,
    artifacts: Map<String, Any?>,
  ) {
    val request =
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = WorkflowStatus.RUNNING.wireValue,
        currentStepId = currentStepId,
        stepUpdates = WorkflowStepUpdates.from(steps),
        artifactsPatch = WorkflowArtifactPatch.from(artifacts),
        sessionId = SKILL_SESSION_ID,
      )
    val result = workflows.update(WorkflowFamilyKind.VERIFY, request)
    check(result is WorkflowUpdateResult.Ok) { "seeding $currentStepId failed: $result" }
  }

  private fun <T> sql(block: (Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite:$dbPath").use(block)

  private fun write(
    path: String,
    text: String,
  ) {
    repo.resolve(path).createParentDirectories().writeText(text)
  }

  private fun commitAll(message: String) {
    git("add", "--all")
    git("commit", "--quiet", "-m", message)
  }

  private fun git(vararg arguments: String): String {
    val process = ProcessBuilder("git", *arguments).directory(repo.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().decodeToString()
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    return output.trim()
  }

  companion object {
    const val SPEC = ".feature-specs/SKILL-9/spec.md"
    const val AGENT = "codex"
    const val REVIEW_REGISTER = "Verdict: approved\n"
    const val SKILL_SESSION_ID = "fvr-skill"
  }
}

internal class VerifyScriptedRunner : PhaseRunner {
  val inputs = mutableListOf<PhaseStepInput>()
  val sideEffects = mutableMapOf<String, () -> Unit>()
  var failAt: String? = null

  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): PhaseStepOutput {
    inputs += input
    sideEffects.remove(input.stepName)?.invoke()
    if (input.stepName == failAt) {
      failAt = null
      error("interrupted at ${input.stepName}")
    }
    return settled(REPLIES.getValue(input.stepName))
  }

  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
    session: PhaseStepSession,
  ): PhaseStepOutput {
    inputs += input
    val launch =
      session.execute(
        GoalRunnerSubtaskLaunchRequest(
          input.facts.invokedAgentId,
          null,
          SkillRunRequest(input.facts.issueKey, input.facts.repoRoot),
        ),
      )
    return settled((launch as AgentRunLaunchFacts).stdout)
  }

  fun stepNames(): List<String> = inputs.map(PhaseStepInput::stepName)

  fun input(stepName: String): PhaseStepInput = inputs.last { it.stepName == stepName }

  private fun settled(value: String): PhaseStepOutput =
    reviewStepOutput(value).copy(fileManifest = PhaseStepFileManifest(emptyList(), emptyList()))

  private companion object {
    val REPLIES =
      mapOf(
        VerifyPromptSections.EXTRACT_CRITERIA_STEP to
          "## Acceptance criteria\n1. Greets by name.\n\n## Non-goals\nNone.\n\n## Rollout expectation\nNo.\n\n" +
          "## Key technical constraints\nNone.\n",
        VerifyPromptSections.FEATURE_FLAG_AUDIT_STEP to "SKIPPED: no flag in spec or diff.\n",
        VerifyPromptSections.CODE_REVIEW_STEP to VerifyOperationHarness.REVIEW_REGISTER,
        UnitTestValueCheckPromptRules.REVIEW_STEP to "GreeterTest.kt: no assertions.\n",
        VerifyPromptSections.COMPLETENESS_AUDIT_STEP to "[PASS] 1. Greets by name.\n",
        VerifyPromptSections.VERDICT_STEP to "--- VERDICT ---\nAPPROVE\n",
      )
  }
}

internal class RecordingVerifyTelemetry : VerifyTelemetry {
  val started = mutableListOf<FeatureVerifyStartedRequest>()
  val finished = mutableListOf<FeatureVerifyFinishedRequest>()
  val imports = mutableListOf<String>()

  override fun started(request: FeatureVerifyStartedRequest): String {
    started += request
    return "fvr-test"
  }

  override fun finished(request: FeatureVerifyFinishedRequest) {
    finished += request
  }

  override fun reviewImported(reviewText: String) {
    imports += reviewText
  }
}

private object UnusedPullRequests : PullRequestReviewThreadOperations {
  override fun resolvePullRequest(
    repoRoot: Path,
    reference: String?,
  ) = error("verify resolved a pull request for a base..head target")

  override fun reviewThreads(
    repoRoot: Path,
    pullRequest: ReviewPullRequest,
  ) = error("verify read review threads")

  override fun replyToThread(
    repoRoot: Path,
    threadId: String,
    body: String,
  ) = error("verify posted a PR reply")
}
