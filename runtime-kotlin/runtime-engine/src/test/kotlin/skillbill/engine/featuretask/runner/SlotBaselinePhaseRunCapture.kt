package skillbill.engine.featuretask.runner

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.phaserun.PhaseRunSpecBundle
import skillbill.engine.featuretask.phaserun.phaseRunEntry
import skillbill.engine.featuretask.slot.REVIEW_FIX_BLOCKER_FINDING_ID
import skillbill.engine.featuretask.slot.codereview.DELEGATED_REVIEWED_PATH
import skillbill.engine.featuretask.slot.codereview.LaneScript
import skillbill.engine.featuretask.slot.codereview.scriptedDelegatedReviewRunner
import skillbill.engine.featuretask.slot.scriptedReviewPhaseRunner
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.slot.verifyFindingsOutput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.goalreview.toReviewAccountingBoundedJson
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import java.nio.file.Files
import java.nio.file.Path

internal object SlotBaselinePhaseRunCapture {
  fun encodedFiles(): Map<String, String> {
    val inline = captureReview(CodeReviewExecutionMode.INLINE)
    val delegated = captureReview(CodeReviewExecutionMode.DELEGATED)
    val validation = captureValidation()
    return mapOf(
      SlotBaselinePaths.PHASE_REVIEW_INLINE_OUTPUT to inline.output,
      SlotBaselinePaths.PHASE_REVIEW_INLINE_TELEMETRY to inline.telemetry,
      SlotBaselinePaths.PHASE_REVIEW_DELEGATED_OUTPUT to delegated.output,
      SlotBaselinePaths.PHASE_REVIEW_DELEGATED_TELEMETRY to delegated.telemetry,
      SlotBaselinePaths.PHASE_VALIDATION_OUTPUT to validation.output,
      SlotBaselinePaths.PHASE_VALIDATION_TELEMETRY to validation.telemetry,
    ).entries.associate { (fileName, value) ->
      "${SlotBaselinePaths.PHASE}/$fileName" to SlotBaselineJson.encode(value)
    } +
      captureAgentPhase(SkeletonDefinition.PLAN.id, PLAN_INTAKE).encodedFiles(SlotBaselinePaths.PHASE_PLAN) +
      captureAgentPhase(SkeletonDefinition.PR.id, intake = null).encodedFiles(SlotBaselinePaths.PHASE_PR)
  }

  private fun captureAgentPhase(
    definitionId: String,
    intake: String?,
  ): AgentPhaseRunCapture =
    SlotBaselinePhaseRunHarness.use(seedSpecIntent = definitionId != SkeletonDefinition.PLAN.id) { harness ->
      val launcher =
        RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          facts(if (phaseId == PHASE_PLAN) harness.authorPlanBundle() else defaultPhaseOutput(request))
        }
      val result =
        harness.agentEntry(launcher).run(harness.request(definitionId, mode = null).copy(intake = intake))
      AgentPhaseRunCapture(
        capture = PhaseRunCapture(result.printedFields(), harness.outboxRows()),
        prompts =
          launcher.requests
            .map { request -> requireNotNull(request.skillRunRequest.promptOverride) }
            .groupBy(::phaseIdFromPrompt)
            .mapValues { (_, prompts) -> prompts.joinToString(PROMPT_ATTEMPT_SEPARATOR) },
        specBundle = (result as? PhaseRunResult.Completed)?.specBundle?.let(harness::specBundleFiles),
      )
    }

  private data class AgentPhaseRunCapture(
    val capture: PhaseRunCapture,
    val prompts: Map<String, String>,
    val specBundle: Map<String, String>?,
  ) {
    fun encodedFiles(resourcePrefix: String): Map<String, String> =
      buildMap {
        put("$resourcePrefix/${SlotBaselinePaths.PHASE_RUN_OUTPUT}", SlotBaselineJson.encode(capture.output))
        put("$resourcePrefix/${SlotBaselinePaths.PHASE_RUN_TELEMETRY}", SlotBaselineJson.encode(capture.telemetry))
        specBundle?.let { files ->
          put("$resourcePrefix/${SlotBaselinePaths.PHASE_PLAN_SPEC_BUNDLE}", SlotBaselineJson.encode(files))
        }
        prompts.forEach { (stepId, prompt) ->
          put("$resourcePrefix/${SlotBaselinePaths.PROMPTS_DIR}/$stepId.txt", SlotBaselineJson.encode(prompt))
        }
      }
  }

  private const val PHASE_PLAN = "plan"
  private const val PLAN_INTAKE = "SKILL-380 slot baseline phase plan"
  private const val PROMPT_ATTEMPT_SEPARATOR = "\n---\n"

  private fun captureReview(mode: CodeReviewExecutionMode): PhaseRunCapture =
    SlotBaselinePhaseRunHarness.use { harness ->
      val result = harness.reviewEntry(mode).run(harness.request(SkeletonDefinition.REVIEW.id, mode))
      PhaseRunCapture(result.printedFields(), harness.outboxRows())
    }

  private fun captureValidation(): PhaseRunCapture =
    SlotBaselinePhaseRunHarness.use { harness ->
      val result = harness.validationEntry().run(harness.request(SkeletonDefinition.VALIDATION.id, mode = null))
      PhaseRunCapture(result.printedFields(), harness.outboxRows())
    }

  private data class PhaseRunCapture(
    val output: Map<String, Any?>,
    val telemetry: List<Map<String, Any?>>,
  )
}

internal fun PhaseRunResult.printedFields(): Map<String, Any?> =
  mapOf(
    "status" to if (this is PhaseRunResult.Completed) "completed" else "blocked",
    "completed_step_ids" to completedStepIds,
    "review_result" to reviewResult?.printedFields(),
    "value" to (this as? PhaseRunResult.Completed)?.value?.let(SlotBaselineJson::parseEmbedded),
    "blocked_step_id" to (this as? PhaseRunResult.Blocked)?.stepId,
    "blocked_reason" to (this as? PhaseRunResult.Blocked)?.reason,
    "invocation_id" to invocationId,
  ) + listOfNotNull((this as? PhaseRunResult.Completed)?.specBundle?.let { bundle -> "spec_bundle" to bundle.fields() })

private fun PhaseRunSpecBundle.fields(): Map<String, Any?> =
  mapOf(
    "parent_spec_path" to parentSpecPath,
    "decomposition_manifest_path" to decompositionManifestPath,
    "subtask_spec_paths" to subtaskSpecPaths,
  )

private fun ParallelCodeReviewResult.printedFields(): Map<String, Any?> =
  mapOf(
    "output" to output,
    "lane1" to
      mapOf(
        "agent_id" to lane1.agentId,
        "success" to lane1.success,
        "failure_reason" to lane1.failureReason,
        "dropped_candidate_diagnostic" to lane1.droppedCandidateDiagnostic,
      ),
    "review_session_id" to reviewSessionId,
    "applied_learnings" to appliedLearnings,
    "coverage" to coverage?.render(),
    "accounting_summary" to
      accountingSummary?.toReviewAccountingBoundedJson()?.let(SlotBaselineJson::parseEmbedded),
  )

internal class SlotBaselinePhaseRunHarness private constructor(
  private val repoRoot: Path,
  private val home: Path,
) {
  val database = SlotBaselineFullRunCapture.sqliteDatabase(home).also { it.transaction { } }
  private val git = committedRepoBranchSetup().gitOperations.also { it.repositoryFingerprintValue = "before-fix" }
  private val lanes = LaneScript()
  private val source: Path =
    repoRoot.resolve(DELEGATED_REVIEWED_PATH).also { path ->
      Files.createDirectories(path.parent)
      Files.writeString(path, LEAKY_SOURCE)
    }

  fun request(
    definitionId: String,
    mode: CodeReviewExecutionMode?,
  ): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = definitionId,
      repoRoot = repoRoot,
      invokedAgentId = REVIEW_AGENT,
      codeReviewMode = mode,
    )

  fun reviewEntry(mode: CodeReviewExecutionMode?): PhaseRunEntry {
    val delegated = mode == CodeReviewExecutionMode.DELEGATED
    return entryFor(
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        launcher = fixLauncher(),
        agentAssignment =
          FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = mapOf(PHASE_REVIEW to REVIEW_AGENT))
            .takeIf { delegated },
        reviewRunner = scriptedReviewPhaseRunner { if (isFixed()) APPROVED_REVIEW else BLOCKER_REVIEW },
        delegatedReviewRunner = scriptedDelegatedReviewRunner(database, home, lanes).takeIf { delegated },
      ),
    )
  }

  fun validationEntry(): PhaseRunEntry {
    git.ownedPathsValue = listOf(DELEGATED_REVIEWED_PATH)
    git.trackedPathsValue = listOf(DELEGATED_REVIEWED_PATH)
    return entryFor(
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        validationGatePlatformManifests = listOf(kotlinPackWithBuildGate()),
        launcher = RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) },
      ),
    )
  }

  fun agentEntry(launcher: RuntimeRecordingLauncher): PhaseRunEntry =
    entryFor(
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        launcher = launcher,
      ),
    )

  fun authorPlanBundle(): String {
    writePlanBundle(repoRoot, PLAN_ISSUE_KEY)
    return PLAN_BUNDLE_PROSE
  }

  fun specBundleFiles(bundle: PhaseRunSpecBundle): Map<String, String> =
    (listOf(bundle.parentSpecPath, bundle.decompositionManifestPath) + bundle.subtaskSpecPaths)
      .associateWith { path -> Files.readString(repoRoot.resolve(path)) }

  fun outboxRows(): List<Map<String, Any?>> = SlotBaselineSqlite.rows(database.resolveDbPath(), "telemetry_outbox")

  private fun entryFor(config: RuntimeHarnessConfig): PhaseRunEntry {
    val harness =
      telemetryRunnerHarness(runtimeConfig = config.copy(seedDurableWorkflow = false), databaseFactory = {
        database
      })
    return phaseRunEntry(
      harness.strategies,
      config.harnessGitOperations,
      database,
      SlotBaselineFullRunCapture.sqliteClock,
      harness.runLoopEntry,
    )
  }

  private fun isFixed(): Boolean = Files.readString(source) == FIXED_SOURCE

  private fun fixLauncher(): RuntimeRecordingLauncher {
    var verifies = 0
    return RuntimeRecordingLauncher { request ->
      when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
        PHASE_VERIFY_FINDINGS -> {
          verifies += 1
          facts(verifyFindingsOutput(if (verifies == 1) listOf(REVIEW_FIX_BLOCKER_FINDING_ID) else emptyList()))
        }
        PHASE_IMPLEMENT_FIX -> {
          Files.writeString(source, FIXED_SOURCE)
          git.repositoryFingerprintValue = "after-fix"
          git.goalReviewTrackedDelta = "phase-review-fix\n"
          lanes.fixed = true
          facts(validJsonOutput(phaseId))
        }
        else -> facts(defaultPhaseOutput(request))
      }
    }
  }

  companion object {
    const val REVIEW_AGENT = "claude"
    const val PLAN_ISSUE_KEY = "SKILL-380"
    const val LEAKY_SOURCE = "val connection = open()\n"
    const val FIXED_SOURCE = "open().use { connection -> connection }\n"
    const val BLOCKER_REVIEW =
      "- [F-001] Blocker | High | $DELEGATED_REVIEWED_PATH:1 | $REVIEW_BLOCKER_MESSAGE\nverdict: changes_requested"
    const val APPROVED_REVIEW = "verdict: approved"

    fun <T> use(
      seedSpecIntent: Boolean = true,
      block: (SlotBaselinePhaseRunHarness) -> T,
    ): T {
      val repoRoot =
        if (seedSpecIntent) SlotBaselineFullRunCapture.seededRepoRoot() else SlotBaselineNormalizer.newRepoRoot()
      val home = SlotBaselineNormalizer.newTempHome()
      try {
        return block(SlotBaselinePhaseRunHarness(repoRoot, home))
      } finally {
        repoRoot.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
      }
    }
  }
}
