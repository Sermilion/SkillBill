package skillbill.engine.goalrunner.assessment

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testProjectStore
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.phaserun.phaseRunEntry
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.engine.goalrunner.execution.core.testActivityStampWriter
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.engine.goalrunner.qa.GoalPostChangeValidationResult
import skillbill.engine.goalrunner.qa.GoalRunnerPostChangeValidation
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.infrastructure.workflow.featuretask.FileSystemPhaseInstructionFileSource
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

internal const val ASSESSMENT_BASE_SHA = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"
internal const val ASSESSMENT_FIRST_SHA = "c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1"
internal const val ASSESSMENT_HEAD_SHA = "d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2"
internal const val PARENT_SPEC_MARKER = "PARENT-SPEC-MARKER"
internal const val FIRST_SUBTASK_SPEC_MARKER = "FIRST-SUBTASK-SPEC-MARKER"
internal const val SECOND_SUBTASK_SPEC_MARKER = "SECOND-SUBTASK-SPEC-MARKER"

internal enum class AssessmentCall { FIRST_SUBTASK, SECOND_SUBTASK, CROSS_SUBTASK, VERIFIER, FIX }

internal fun assessmentCallOf(prompt: String): AssessmentCall =
  when {
    "# Goal assessment fix:" in prompt -> AssessmentCall.FIX
    "Verify each candidate defect below" in prompt -> AssessmentCall.VERIFIER
    "Assess how the subtasks fit together" in prompt -> AssessmentCall.CROSS_SUBTASK
    "Assess subtask 1 (" in prompt -> AssessmentCall.FIRST_SUBTASK
    "Assess subtask 2 (" in prompt -> AssessmentCall.SECOND_SUBTASK
    else -> error("The prompt names no goal assessment role: $prompt")
  }

internal class AssessmentOutcomeStore(
  var stored: GoalAssessmentRecordRead = GoalAssessmentRecordRead.Absent,
  private val goalQaOutcome: FeatureTaskQaOutcome? = null,
) : GoalRunnerWorkflowOutcomeStore by EmptyOutcomeStore {
  val persisted = mutableListOf<GoalAssessmentRecord>()

  override fun readGoalAssessmentRecord(parentWorkflowId: String): GoalAssessmentRecordRead = stored

  override fun recordGoalAssessmentRecord(
    parentWorkflowId: String,
    record: GoalAssessmentRecord,
  ): Boolean {
    stored = GoalAssessmentRecordRead.Recorded(record)
    persisted += record
    return true
  }

  override fun recordedGoalQaOutcome(parentWorkflowId: String): FeatureTaskQaOutcome? = goalQaOutcome

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean = true
}

internal class AssessmentDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun info(message: String) = Unit

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
}

internal class GoalAssessmentGateFixture(
  private val home: Path,
  val store: AssessmentOutcomeStore = AssessmentOutcomeStore(),
  private val subtaskOf: (Int) -> DecompositionSubtask = { id -> committedSubtask(id) },
  private val fixedWorktreeStatus: String = "",
  private val script: (AssessmentCall) -> String?,
) {
  private val clock = Clock.systemUTC()
  val diagnostics = AssessmentDiagnostics()
  val calls = mutableListOf<AssessmentCall>()
  val prompts = mutableMapOf<AssessmentCall, String>()
  var validationResult: GoalPostChangeValidationResult = GoalPostChangeValidationResult.Passed
  var validations = 0
  private val validation =
    GoalRunnerPostChangeValidation { _, _, _ ->
      validations++
      validationResult
    }
  val launcher =
    RuntimeRecordingLauncher { request ->
      val prompt = requireNotNull(request.skillRunRequest.promptOverride)
      val call = assessmentCallOf(prompt)
      calls += call
      prompts[call] = prompt
      if (call == AssessmentCall.FIX) git.worktreeStatusValue = fixedWorktreeStatus
      script(call)?.let { prose -> facts(settledOutput(call, prose)) }
        ?: agentRunLaunchFacts(SupportedAgent.CLAUDE, termination = AgentRunTermination.Exited(1))
    }
  val git =
    RecordingWorkflowGitOperations(currentBranchValue = FEATURE_BRANCH).apply {
      headCommitShaValue = ASSESSMENT_HEAD_SHA
      mergeBaseWithHeadValue = ASSESSMENT_BASE_SHA
      changedPathsBetweenCommitsValue = listOf("src/Feature.kt", "src/FeatureTest.kt")
      worktreeStatusValue = ""
      onCreateCommit = { worktreeStatusValue = "" }
    }

  init {
    Files.writeString(home.resolve(PARENT_SPEC), PARENT_SPEC_TEXT)
    Files.writeString(home.resolve(FIRST_SPEC), "# Subtask 1\n\n$FIRST_SUBTASK_SPEC_MARKER\n")
    Files.writeString(home.resolve(SECOND_SPEC), "# Subtask 2\n\n$SECOND_SUBTASK_SPEC_MARKER\n")
  }

  fun gate(): DefaultGoalRunnerAssessmentGate {
    val database = phaseRunDatabase(home, clock)
    val strategies =
      testPhaseStrategies(
        launcher = launcher,
        gitOperations = git,
        phaseInstructionSource = FileSystemPhaseInstructionFileSource(),
      )
    val phaseEntry = phaseRunEntry(strategies, git, database, clock, TestFeatureTaskRuntimeRunLoopEntry(git))
    return DefaultGoalRunnerAssessmentGate(
      phaseEntry,
      store,
      git,
      TestDecompositionManifestStore,
      testProjectStore,
      clock,
      testActivityStampWriter(),
      validation,
      diagnostics,
    )
  }

  fun state(): GoalRunnerManifestState =
    GoalRunnerManifestState(
      parentWorkflowId = "goal-parent",
      dbPath = home.resolve("metrics.db").toString(),
      manifest =
        DecompositionManifest(
          issueKey = "SKILL-442",
          featureName = "goal-assessment",
          parentSpecPath = PARENT_SPEC,
          baseBranch = "main",
          featureBranch = FEATURE_BRANCH,
          currentSubtaskIntent = CurrentSubtaskIntent(2, "complete"),
          subtasks = listOf(subtaskOf(1), subtaskOf(2)),
        ),
    )

  fun request() = GoalRunnerRunRequest(issueKey = "SKILL-442", repoRoot = home, invokedAgentId = "claude")

  private fun settledOutput(
    call: AssessmentCall,
    prose: String,
  ): String =
    JsonCodec.mapToJsonString(
      mapOf(
        "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        "phase_id" to if (call == AssessmentCall.FIX) "goal_assessment_fix" else "goal_assessment",
        "status" to "completed",
        "summary" to "The assessment run settled.",
        "produced_outputs" to mapOf("value" to prose),
      ),
    )

  companion object {
    const val FEATURE_BRANCH = "feat/SKILL-442-goal"
    const val PARENT_SPEC = "parent-spec.md"
    const val FIRST_SPEC = "subtask-1.md"
    const val SECOND_SPEC = "subtask-2.md"

    fun committedSubtask(
      id: Int,
      blockedReason: String? = null,
      lastResumableStep: String? = null,
    ): DecompositionSubtask =
      DecompositionSubtask(
        id = id,
        name = "Subtask $id",
        specPath = if (id == 1) FIRST_SPEC else SECOND_SPEC,
        status = "completed",
        commitSha = if (id == 1) ASSESSMENT_FIRST_SHA else ASSESSMENT_HEAD_SHA,
        blockedReason = blockedReason,
        lastResumableStep = lastResumableStep,
      )

    fun qaOutcome(reason: String): FeatureTaskQaOutcome =
      FeatureTaskQaOutcome("goal-parent", StandaloneQaOutcome.PASSED, reason, 0, "2".repeat(64), "2026-10-25T00:00Z")

    private const val SCENARIO_LINE =
      "- IS-001. Criteria: AC-001, AC-002. Subtasks: 1, 2. Entry point: the operator runs the goal. " +
        "Preconditions: both subtasks are committed. Steps: run it twice. Outcome: both runs agree."

    private val PARENT_SPEC_TEXT =
      """
      # SKILL-442 goal
      $PARENT_SPEC_MARKER

      ## Acceptance Criteria

      1. The gate assesses the goal.
      2. The gate verifies defects.

      ## Interaction Scenarios

      $SCENARIO_LINE

      ## Transitional Regressions

      - TR-001. Behavior: the legacy path is off. Disabled by: subtask 1. Restored by: subtask 2.
      """.trimIndent()
  }
}
