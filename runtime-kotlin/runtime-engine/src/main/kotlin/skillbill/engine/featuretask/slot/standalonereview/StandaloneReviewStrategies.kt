package skillbill.engine.featuretask.slot.standalonereview

import skillbill.agentaddon.model.AgentAddonPromptFormatter
import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.application.review.model.InlineReviewRubricPlan
import skillbill.application.review.model.ParallelCodeReviewPlanned
import skillbill.application.review.model.ParallelCodeReviewReportContract
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelCodeReviewRunOutcome
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunner
import skillbill.application.review.service.StandaloneReviewReportReader
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.PhaseRepositoryObservations
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.PhaseStepSession
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.ReviewBindingFamily
import skillbill.engine.featuretask.slot.codereview.InlineReviewEnvelope
import skillbill.engine.featuretask.slot.codereview.InlineReviewLanesSection
import skillbill.engine.featuretask.slot.codereview.InlineReviewRubricResolver
import skillbill.engine.featuretask.slot.codereview.ReviewTargetResolver
import skillbill.engine.featuretask.slot.codereview.delegatedReviewRequest
import skillbill.engine.featuretask.slot.codereview.recordInlineLanes
import skillbill.engine.featuretask.slot.codereview.resolveInlineRubrics
import skillbill.engine.featuretask.slot.codereview.reviewSpecPath
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.stepFacts
import skillbill.error.core.catchingNonCancellation
import skillbill.error.featuretask.UnknownPhaseReviewTargetError
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.parallel.ParallelReviewFindingParser
import skillbill.review.parallel.ParallelReviewMerger
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.time.Duration.Companion.minutes

public open class InlineStandaloneReviewStrategy(
  runner: PhaseRunner,
  rubricResolver: InlineReviewRubricResolver? = null,
  comments: DiffCommentService? = null,
) : StandaloneReviewStrategy(runner, null, rubricResolver, comments) {
  override val strategyId: String = ID

  public companion object {
    public const val ID: String = "inline"
  }
}

public open class DelegatedStandaloneReviewStrategy(
  runner: PhaseRunner,
  reviewRunner: ParallelCodeReviewRunner,
  comments: DiffCommentService? = null,
) : StandaloneReviewStrategy(runner, reviewRunner, comments = comments) {
  override val strategyId: String = ID

  public companion object {
    public const val ID: String = "delegated"
  }
}

public abstract class StandaloneReviewStrategy(
  internal val runner: PhaseRunner,
  private val reviewRunner: ParallelCodeReviewRunner?,
  private val rubricResolver: InlineReviewRubricResolver? =
    reviewRunner?.let { InlineReviewRubricResolver(it::resolveInlineCodeQualityRubric) },
  private val comments: DiffCommentService? = null,
) : PhaseStrategy() {
  override val slot: PhaseSlot = PhaseSlot.STANDALONE_REVIEW
  override val steps: List<String> = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PRESENT_FINDINGS)
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PRESENT_FINDINGS

  override fun policyFor(stepId: String): PhaseStepPolicy {
    check(stepId == entryStep)
    return PhaseStepPolicy(
      mutating = false,
      singleAgentSession = reviewRunner == null,
      readOnlyIdle = true,
      fileMutating = false,
      generationScoped = false,
      extendsOwnedInventory = false,
    )
  }

  override fun directiveFor(stepId: String): String {
    check(stepId == entryStep)
    return "Review the resolved target and return findings only. Do not edit, stage, commit, amend, reset, " +
      "or launch another review command. Return a findings register, or NO_FINDINGS, followed by exactly one " +
      "canonical verdict line: verdict: approved or verdict: changes_requested."
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections = PhaseStepPromptSections(taskDirective = directiveFor(stepId), settles = false)

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> {
    check(stepId == entryStep)
    return PhaseStepDescriptor(ReviewBindingFamily, PhaseStepHooks.None) { run, state ->
      runReview(run, state)
    }
  }

  private fun runReview(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
  ): PhaseOutcome {
    val iteration = binding.nextStepIteration()
    binding.startReviewStep(run, iteration)?.let { return it }
    return runStartedReview(run, binding, iteration)
  }

  private fun runStartedReview(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    iteration: Int,
  ): PhaseOutcome {
    val context = binding.reviewExecutionContext()
    val target =
      resolveTarget(run, binding, context.gitOperations)
        ?: return block(binding, iteration, "Standalone review could not resolve its requested target.")
    val head = context.gitOperations.headCommitSha(run.request.repoRoot)
    val source = context.gitOperations.standaloneReviewSourceFingerprint(run.request.repoRoot)
    if (head !is WorkflowGitOperationResult.Ok || source !is WorkflowGitOperationResult.Ok) {
      val reason =
        if (head !is WorkflowGitOperationResult.Ok) {
          "could not read HEAD: ${head.error}"
        } else {
          "could not observe repository source: ${source.error}"
        }
      return block(binding, iteration, "Standalone review $reason")
    }
    val headSha = head.value.trim()
    val reviewInput = GoalSubtaskReviewInput(headSha, headSha, "", "")
    val invocation = run.request.reviewInvocation
    val reviewRunId = invocation?.reviewRunId ?: InlineReviewEnvelope.mintReviewRunId(context.clock)
    val rubricRequest =
      delegatedReviewRequest(
        run.resolvedAgent.resolvedAgentId,
        run.request.repoRoot,
        run.projectStoreRoot,
        target,
        reviewInput,
      )
    val lanePlan =
      if (reviewRunner == null) {
        rubricResolver.resolveInlineRubrics(
          rubricRequest,
          context.diagnostics,
        )
      } else {
        null
      }
    val rubricSection =
      if (reviewRunner == null) {
        lanePlan?.rubrics?.let(InlineReviewLanesSection::render)
      } else {
        when (val resolved = rubricResolver?.resolve(rubricRequest)) {
          null -> null
          is ParallelCodeReviewPlanned.Failed -> return block(binding, iteration, resolved.failure.message)
          is ParallelCodeReviewPlanned.Ready -> resolved.value?.let(::delegatedQualityRubricSection)
        }
      }
    val directive = standaloneDirective(run, binding, target, headSha, rubricSection)
    return runPreparedReview(
      run,
      binding,
      iteration,
      StandaloneReviewLaunchInput(target, reviewInput, reviewRunId, directive, lanePlan, source.value.orEmpty()),
    )
  }

  private fun runPreparedReview(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    iteration: Int,
    facts: StandaloneReviewLaunchInput,
  ): PhaseOutcome {
    val prompt = PhaseStepPromptSource { PhaseStepPromptSections(taskDirective = facts.directive) }
    prepareReport(binding, iteration, facts.reviewRunId, prompt, facts.input)?.let { return it }
    binding.reviewLaunched(iteration)
    val output = launch(run, binding, facts)
    return finishReport(run, binding, iteration, facts, output)
  }

  private fun prepareReport(
    binding: PhaseReviewStepBinding,
    iteration: Int,
    reviewRunId: String,
    prompt: PhaseStepPromptSource,
    reviewInput: GoalSubtaskReviewInput,
  ): PhaseOutcome? {
    val started = binding.startReview(iteration, reviewRunId)
    if (started is RequiredPhaseWrite.Rejected) return binding.blockRequiredReviewWrite(started)
    val prepared = binding.prepareReviewBriefing(iteration, prompt, reviewInput)
    return (prepared as? RequiredPhaseWrite.Rejected)?.let(binding::blockRequiredReviewWrite)
  }

  private fun finishReport(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    iteration: Int,
    facts: StandaloneReviewLaunchInput,
    output: StandaloneLaunch,
  ): PhaseOutcome {
    val retainedResult = retainRawReview(run, binding, iteration, output)
    val reviewRunId = facts.reviewRunId
    val report =
      StandaloneReviewReportAdmission.admit(
        output.rawOutput,
        retainedResult,
        output.truncated || retainedResult.reportTruncated,
        reviewRunner != null,
      )
    val observation =
      binding.reviewExecutionContext().gitOperations.standaloneReviewSourceFingerprint(run.request.repoRoot)
    val observationFailure =
      when {
        observation !is WorkflowGitOperationResult.Ok ->
          "Standalone review source observation failed after review: ${observation.error}"
        observation.value != facts.sourceFingerprint -> "Repository source changed during standalone review."
        else -> null
      }
    val verification =
      if (observationFailure == null && report.admitted && output.failureReason == null) {
        verifyFindings(run = run, binding = binding, facts = facts, report = report)
      } else {
        VerificationResult.Blocked(
          observationFailure ?: report.rejectionReasons.joinToString("; ").ifBlank { output.failureReason.orEmpty() },
        )
      }
    val failure = output.failureReason
    val blockers =
      listOfNotNull(
        failure,
        report.rejectionReasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
        (verification as? VerificationResult.Blocked)?.reason,
      ).distinct()
    val diagnostics =
      report.diagnostics.take(MAX_FAILURE_DIAGNOSTICS).map { it.take(MAX_FAILURE_DIAGNOSTIC_CHARS) }
    val rejection = if (blockers.isEmpty()) "" else (blockers + diagnostics).joinToString("; ")
    val recordedResult = recordableResult(binding, iteration, retainedResult, output, verification)
    binding.recordReviewRun(reviewRunId, recordedResult, reviewRunner != null)
    rubricResolver.recordInlineLanes(
      reviewRunId,
      facts.lanePlan,
      rejection.isBlank(),
      binding.reviewExecutionContext().diagnostics,
    )
    if (rejection.isNotBlank()) {
      val presented =
        if (report.admitted && report.census.isNotEmpty()) "$rejection\n\n${presentedFindings(report)}" else rejection
      binding.blockReviewStep(
        iteration,
        presented,
        FeatureTaskRuntimeFailureDisposition.RETRYABLE,
      )
      return PhaseOutcome.blocked(presented)
    }
    val accepted = verification as VerificationResult.Accepted
    postStandaloneThreads(run, binding, reviewRunId, report)
    refuteRejectedStandaloneThreads(run, binding, reviewRunId, report, accepted)
    return completeReport(
      binding,
      iteration,
      report.copy(
        registerOutput = accepted.register,
        verdict = accepted.verdict,
      ),
    )
  }

  private fun postStandaloneThreads(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    reviewRunId: String,
    report: StandaloneReviewReport,
  ) {
    val comments = comments ?: return
    val findings = report.findingGroups.mapNotNull { group -> group.finding?.copy(fNumber = group.identity) }
    val diagnostics = binding.reviewExecutionContext().diagnostics
    val agentId = run.resolvedAgent.resolvedAgentId
    val posted =
      catchingNonCancellation {
        comments.recordReviewFindings(run.request.repoRoot, "review:$reviewRunId", agentId, findings)
      }
    val failure = posted.exceptionOrNull()
    val counts = posted.getOrNull()
    if (failure != null || counts?.failed?.let { it > 0 } == true) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Feature-task-runtime could not post review diff comments for issue ${run.request.issueKey}, " +
          "review run $reviewRunId; operation post review diff comments; written=${counts?.written ?: 0}, " +
          "reused=${counts?.reused ?: 0}, failed=${counts?.failed ?: 0}, " +
          "first_failure=${counts?.firstFailureReason ?: failure?.message ?: "none"}.",
        failure,
      )
    }
  }

  private fun refuteRejectedStandaloneThreads(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    reviewRunId: String,
    report: StandaloneReviewReport,
    accepted: VerificationResult.Accepted,
  ) {
    val comments = comments ?: return
    val diagnostics = binding.reviewExecutionContext().diagnostics
    val agentId = run.resolvedAgent.resolvedAgentId
    report.findingGroups
      .filter { group ->
        group.finding != null &&
          group.members.all { member -> accepted.sourceFindingDispositions[member.identity] == "rejected" }
      }
      .forEach { group ->
        val result =
          catchingNonCancellation {
            comments.replyReviewOutcome(
              run.request.repoRoot,
              "review:$reviewRunId:${group.identity}",
              "Refuted during review verification.",
              DiffCommentOutcome.REFUTED,
              agentId,
            )
          }
        val error = result.exceptionOrNull()
        val value = result.getOrNull()
        if (error != null || value !is DiffCommentThreadResult.Saved) {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Feature-task-runtime could not reply refuted to review finding ${group.identity} for review run " +
              "$reviewRunId; operation reply refuted.",
            error,
          )
        }
      }
  }

  private fun retainRawReview(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    iteration: Int,
    output: StandaloneLaunch,
  ): ParallelCodeReviewResult {
    val result =
      output.result
        ?: failedResult(run.resolvedAgent.resolvedAgentId, output.failureReason ?: "review did not produce a result")
    val raw = output.rawOutput
    val retainedResult =
      result.copy(
        mergeResult =
          result.mergeResult.copy(
            formattedOutput = ParallelReviewMerger.formattedOutput(result.mergeResult.findings),
          ),
        rawOutput = raw,
        reportTruncated = output.truncated || result.reportTruncated,
      )
    binding.retainReviewOutput(iteration, raw)
    return retainedResult
  }

  private fun recordableResult(
    binding: PhaseReviewStepBinding,
    iteration: Int,
    retainedResult: ParallelCodeReviewResult,
    output: StandaloneLaunch,
    verification: VerificationResult,
  ): ParallelCodeReviewResult {
    return if (verification is VerificationResult.Accepted) {
      retainedResult.copy(
        mergeResult = retainedResult.mergeResult.copy(formattedOutput = verification.register),
      )
    } else {
      val verifierEvidence = (verification as? VerificationResult.Blocked)?.evidence.orEmpty()
      val retainedEvidence = unacceptedReviewEvidence(output, retainedResult, verifierEvidence)
      binding.retainReviewOutput(iteration, retainedEvidence)
      retainedResult.copy(
        mergeResult = retainedResult.mergeResult.copy(formattedOutput = retainedEvidence),
        rawOutput = retainedEvidence,
      )
    }
  }

  private fun unacceptedReviewEvidence(
    output: StandaloneLaunch,
    retainedResult: ParallelCodeReviewResult,
    verifierEvidence: String,
  ): String {
    return buildList {
      if (output.rawOutput.isBlank()) {
        val completeness = if (output.truncated || retainedResult.reportTruncated) " and truncated" else ""
        add("Unaccepted parent report output was blank$completeness.")
      } else {
        val text = output.rawOutput
        val completeness = if (output.truncated || retainedResult.reportTruncated) " (truncated)" else ""
        add("Unaccepted parent report output$completeness:\n$text")
      }
      retainedResult.integration?.let { integration ->
        integration.rawOutput.takeIf(String::isNotBlank)?.let { text ->
          val completeness = if (integration.outputTruncated) " (truncated)" else ""
          add("Unaccepted delegated integration output$completeness:\n$text")
        }
        if (integration.completed && integration.rawOutput.isBlank()) {
          val completeness = if (integration.outputTruncated) " (truncated)" else ""
          add("Unaccepted delegated integration output was blank$completeness; no source text was captured.")
        }
      }
      retainedResult.mergeResult.formattedOutput
        .takeIf { distinctMergedEvidence(it, output, retainedResult) }
        ?.let { add("Unaccepted merged review output:\n$it") }
      verifierEvidence.takeIf(String::isNotBlank)?.let { add("Unaccepted verifier output:\n$it") }
    }.joinToString("\n\n")
  }

  private fun distinctMergedEvidence(
    text: String,
    output: StandaloneLaunch,
    retainedResult: ParallelCodeReviewResult,
  ): Boolean = text.isNotBlank() && text != output.rawOutput && text != retainedResult.integration?.rawOutput

  private fun verifyFindings(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    facts: StandaloneReviewLaunchInput,
    report: StandaloneReviewReport,
  ): VerificationResult {
    if (report.census.isEmpty() && report.reviewWarnings.isEmpty()) {
      return verifyEmptyReport(run, binding, facts, report)
    }
    val input = verificationInput(run, facts, report)
    val launched = runner.run(input, binding.launchState)
    val after = binding.reviewExecutionContext().gitOperations.standaloneReviewSourceFingerprint(run.request.repoRoot)
    val failure =
      if (after !is WorkflowGitOperationResult.Ok || after.value != facts.sourceFingerprint) {
        "Repository source changed or could not be observed after verification."
      } else {
        launchFailureReason(launched)?.let { "Standalone verifier failed: $it" }
      }
    if (failure != null) return VerificationResult.Blocked(failure, unacceptedVerifierEvidence(launched))
    val verification =
      parseVerificationRegister(
        launched.stdout.text,
        report.census.map { it.identity }.toSet(),
        report.reviewWarnings.map { it.identity }.toSet(),
      ) ?: return VerificationResult.Blocked(
        "Standalone verifier returned an incomplete or invalid finding or warning register.",
        unacceptedVerifierEvidence(launched),
      )
    return acceptedVerification(report, verification)
  }

  private fun verifyEmptyReport(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    facts: StandaloneReviewLaunchInput,
    report: StandaloneReviewReport,
  ): VerificationResult {
    val after = binding.reviewExecutionContext().gitOperations.standaloneReviewSourceFingerprint(run.request.repoRoot)
    if (after !is WorkflowGitOperationResult.Ok || after.value != facts.sourceFingerprint) {
      return VerificationResult.Blocked("Repository source changed or could not be observed after review.")
    }
    val verdict =
      if (!report.hasIncompleteReviewWarning) {
        FeatureTaskRuntimeVerdict.APPROVED.wireValue
      } else {
        FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue
      }
    val register =
      buildString {
        appendLine("NO_FINDINGS")
        report.coverage?.let { appendLine(it) }
        report.warnings.forEach { appendLine("warning: $it") }
        append("verdict: $verdict")
      }
    return VerificationResult.Accepted(register, verdict)
  }

  private fun acceptedVerification(
    report: StandaloneReviewReport,
    verification: ParsedVerificationRegister,
  ): VerificationResult {
    val warningClassifications =
      report.reviewWarnings.associate { warning ->
        warning.identity to
          if (warning.alreadyIncomplete) {
            "incomplete"
          } else {
            verification.warningClassifications.getValue(warning.identity)
          }
      }
    val findingDispositions =
      report.findingGroups.associate { group ->
        group.identity to disposition(group.members.map { verification.findingDispositions.getValue(it.identity) })
      }
    val verdict = verifiedFindingVerdict(report, findingDispositions, warningClassifications)
    val register =
      buildString {
        appendLine(presentedFindings(report))
        appendLine()
        appendLine(StandaloneReviewReportReader.VERIFICATION_HEADING)
        report.findingGroups.forEach { group ->
          appendLine("- ${presentedLabel(group)}: ${findingDispositions.getValue(group.identity)}")
          appendLine(
            "Source classifications for ${group.identity}: " +
              group.members.joinToString(", ") { member ->
                "${member.identity}=${verification.findingDispositions.getValue(member.identity)}"
              },
          )
        }
        report.reviewWarnings.forEach { warning ->
          appendLine("- ${warning.identity} (${warning.source}): ${warningClassifications.getValue(warning.identity)}")
        }
        append("verdict: $verdict")
      }
    return VerificationResult.Accepted(register, verdict, findingDispositions, verification.findingDispositions)
  }

  private fun presentedFindings(report: StandaloneReviewReport): String =
    report.registerOutput.lineSequence()
      .filterNot { VERDICT_LINE.matches(it.trim()) }
      .joinToString("\n")
      .trimEnd()

  private fun presentedLabel(group: StandaloneReviewFindingGroup): String = group.identity

  private fun disposition(sourceDispositions: List<String>): String =
    when {
      sourceDispositions.any { it == "unresolved" } -> "unresolved"
      sourceDispositions.any { it == "confirmed" } -> "confirmed"
      else -> "rejected"
    }

  private fun verificationInput(
    run: PhaseRun,
    facts: StandaloneReviewLaunchInput,
    report: StandaloneReviewReport,
  ): PhaseStepInput {
    val directive =
      buildString {
        appendLine(
          "Independently verify each finding against current repository source and classify each retained review " +
            "warning or coverage block by whether it shows incomplete review. Do not edit, stage, commit, or repair.",
        )
        appendLine(
          "Classify warnings from their supplied text and coverage facts. Do not infer or reconstruct " +
            "what the original reviewer inspected.",
        )
        appendLine("Resolved target: ${facts.target}")
        appendLine("Repository: ${run.request.repoRoot}")
        appendLine("Source observation: ${facts.sourceFingerprint}")
        appendLine(
          "For each finding return exactly one line using its exact ID: " +
            "F-001: confirmed|rejected|unresolved.",
        )
        appendLine(
          "Some entries are prose lifted from the report. Confirm only text that claims a defect present in " +
            "current source. Reject text that claims no defect, such as a summary or a count of the other findings.",
        )
        report.census.forEach { appendLine("${it.identity}: source=${it.source} | ${it.text}") }
        appendLine(
          "For each warning or coverage block return exactly one line using its exact ID: " +
            "W-001: incomplete|complete.",
        )
        report.reviewWarnings.forEach { appendLine("${it.identity}: source=${it.source} | ${it.text}") }
      }
    return PhaseStepInput(
      entryStep,
      directive,
      emptyMap(),
      "Read-only independent verification and warning classification. No edits, staging, commits, or repair.",
      run.stepFacts(run.request.workflowId.ifBlank { "standalone-review-verify" }, null),
      PhaseStepPolicy(
        mutating = false,
        singleAgentSession = true,
        readOnlyIdle = true,
        fileMutating = false,
        generationScoped = false,
        extendsOwnedInventory = false,
      ),
    )
  }

  private fun unacceptedVerifierEvidence(output: PhaseStepOutput): String =
    buildString {
      if (output.processStarted || output.stdout.text.isNotBlank() || output.stdout.truncated) {
        append("Unaccepted verifier stdout")
        if (output.stdout.truncated) append(" (truncated)")
        appendLine(":")
        appendLine(output.stdout.text.ifBlank { "<blank>" })
      }
      if (output.stderr.isNotBlank()) {
        appendLine("Unaccepted verifier stderr:")
        appendLine(output.stderr)
      }
    }.trimEnd()

  private fun completeReport(
    binding: PhaseReviewStepBinding,
    iteration: Int,
    report: StandaloneReviewReport,
  ): PhaseOutcome {
    val verdict = requireNotNull(report.verdict)
    val value = report.registerOutput
    val envelope =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to entryStep,
        SharedPayloadKeys.STATUS to STATUS_COMPLETED,
        SharedPayloadKeys.SUMMARY to value.take(MAX_REPORT_SUMMARY_CHARS),
        SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to value),
        SharedPayloadKeys.VERDICT to verdict,
      )
    val normalized =
      NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
    binding.completeReview(
      iteration,
      value,
      normalized,
      PhaseStepFileManifest(emptyList(), emptyList()),
    )
      ?.let { reason -> return PhaseOutcome.blocked(reason) }
    binding.stepCompleted(iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        entryStep,
        iteration,
        normalized.output,
        normalized,
      ),
    )
  }

  private fun resolveTarget(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    gitOperations: PhaseRepositoryObservations,
  ): ReviewTarget? {
    val status = gitOperations.worktreeStatus(run.request.repoRoot)
    if (status !is WorkflowGitOperationResult.Ok) return null
    val target = ReviewTargetResolver.resolve(run.request.reviewInvocation?.target, status.value.orEmpty())
    val revisions =
      when (target) {
        is ReviewTarget.Commit -> listOf(target.sha)
        is ReviewTarget.Scoped -> listOfNotNull(target.baseRevision, target.headRevision)
        else -> emptyList()
      }
    revisions.forEach { revision ->
      if (gitOperations.resolveCommit(run.request.repoRoot, revision) !is WorkflowGitOperationResult.Ok) {
        throw UnknownPhaseReviewTargetError(revision)
      }
    }
    return binding.pinnedReviewTarget { target }
  }

  private fun standaloneDirective(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    target: ReviewTarget,
    head: String,
    rubricSection: String?,
  ): String =
    buildString {
      target.openingLines("$head^", head).forEach(::appendLine)
      appendLine("Start the report with one line naming every rubric you applied: 'lanes: <skill name>, <skill name>'.")
      appendLine(
        "Return a findings register with one finding per line, written as " +
          "'[F-001] Severity | Confidence | specialist=<rubric skill name> | path/File.kt:12 | defect description', " +
          "or the exact line NO_FINDINGS when empty.",
      )
      appendLine("End with exactly one canonical line: verdict: approved or verdict: changes_requested.")
      append(AgentAddonPromptFormatter.format(run.request.agentAddonSelection))
      appendLine()
      appendLine(
        "This is report-only. Do not apply recommendations, edit, stage, commit, amend, reset, " +
          "or launch another review command.",
      )
      appendLine("A changes_requested verdict is a valid completed report. The runtime will not repair findings.")
      rubricSection?.let {
        appendLine()
        append(it)
      }
      supplement(run, binding)?.let {
        appendLine()
        append(it)
      }
    }

  private fun supplement(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
  ): String? = binding.modelDirectiveSupplements.directiveFor(run, binding.acceptedOwner)

  private fun delegatedQualityRubricSection(rubric: String): String =
    buildString {
      appendLine(DELEGATED_QUALITY_RUBRIC_HEADING)
      appendLine("Use this complete owner rubric and attribute its findings with the exact specialist identity.")
      appendLine(rubric)
    }

  private fun launch(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    facts: StandaloneReviewLaunchInput,
  ): StandaloneLaunch {
    val operatorInstructions =
      listOfNotNull(
        run.request.phaseInstructions?.forStep(entryStep),
        "Report-only review. Do not apply fixes or make repository changes.",
      ).joinToString("\n\n")
    val phaseInput =
      PhaseStepInput(
        entryStep,
        facts.directive,
        emptyMap(),
        operatorInstructions,
        run.stepFacts(run.request.workflowId.ifBlank { "code-review" }, null),
        policyFor(entryStep),
      )
    return if (reviewRunner == null) {
      launchInline(run, phaseInput, binding)
    } else {
      launchDelegated(run, binding, facts, phaseInput)
    }
  }

  private fun launchInline(
    run: PhaseRun,
    phaseInput: PhaseStepInput,
    binding: PhaseReviewStepBinding,
  ): StandaloneLaunch {
    val launched = runner.run(phaseInput, binding.launchState)
    val parsed = ParallelReviewFindingParser.parse(launched.stdout.text)
    val launchFailure = launchFailureReason(launched)
    val merged =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult(run.resolvedAgent.resolvedAgentId, parsed.findings),
        ParallelReviewLaneResult(run.resolvedAgent.resolvedAgentId, emptyList()),
      )
    return StandaloneLaunch(
      launched.stdout.text,
      ParallelCodeReviewResult(
        merged.copy(formattedOutput = launched.stdout.text),
        ParallelReviewLaneStatus(
          run.resolvedAgent.resolvedAgentId,
          launched.termination is AgentRunTermination.Exited && launched.termination.code == 0,
          launchFailure,
          reviewDisposition =
            if (launchFailure == null) {
              ReviewLaneReviewDisposition.COMPLETE
            } else {
              ReviewLaneReviewDisposition.INCOMPLETE
            },
        ),
        citationDiagnostics = parsed.citationDiagnostics,
        rawOutput = launched.stdout.text,
        rejectedCandidateCount = parsed.rejections.size,
      ),
      launchFailure,
      launched.stdout.truncated,
    )
  }

  private fun launchDelegated(
    run: PhaseRun,
    binding: PhaseReviewStepBinding,
    facts: StandaloneReviewLaunchInput,
    phaseInput: PhaseStepInput,
  ): StandaloneLaunch {
    val delegatedRunner = requireNotNull(reviewRunner)
    var result: ParallelCodeReviewResult? = null
    var failure: String? = null
    val session =
      PhaseStepSession { launch ->
        val delegatedTarget =
          if (facts.target == ReviewTarget.LastCommit) ReviewTarget.Commit(facts.input.currentHeadSha) else facts.target
        val request =
          delegatedReviewRequest(
            run.resolvedAgent.resolvedAgentId,
            run.request.repoRoot,
            run.projectStoreRoot,
            delegatedTarget,
            facts.input,
          ).copy(
            timeout = launch.skillRunRequest.timeout,
            reviewRunId = facts.reviewRunId,
            reviewSessionId = run.request.reviewInvocation?.reviewSessionId,
            prelaunchExpansions = run.request.reviewInvocation?.prelaunchExpansions.orEmpty(),
            baselineUntrackedPolicy =
              run.request.reviewInvocation?.baselineUntrackedPolicy
                ?: ParallelCodeReviewRequest.baselineUntrackedPolicy(emptyList(), emptyList()),
            specPath = reviewSpecPath(run),
            selectedAgentAddonsSection = AgentAddonPromptFormatter.format(run.request.agentAddonSelection),
            reportContract = ParallelCodeReviewReportContract.STANDALONE_REPORT_ONLY,
            laneProgressIdleTimeout =
              launch.skillRunRequest.progressIdleTimeout
                ?: READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes,
            modelOverride = launch.skillRunRequest.modelOverride,
            effortOverride = launch.skillRunRequest.effortOverride,
            directiveSuffix = supplement(run, binding).orEmpty(),
          )
        when (val outcome = delegatedRunner.run(request)) {
          is ParallelCodeReviewRunOutcome.PlanningFailed -> {
            failure = outcome.failure.message
            UnsupportedAgentRunLaunch(
              SupportedAgent.fromWire(run.resolvedAgent.resolvedAgentId),
              outcome.failure.message,
            )
          }
          is ParallelCodeReviewRunOutcome.Reviewed -> {
            result = outcome.result
            val text = outcome.result.output
            AgentRunLaunchFacts(
              SupportedAgent.fromWire(run.resolvedAgent.resolvedAgentId),
              AgentRunTermination.Exited(0),
              text,
              "",
              text.encodeToByteArray().size.toLong(),
              "",
            )
          }
        }
      }
    val launched = runner.run(phaseInput, binding.launchState, session)
    val text = result?.rawOutput ?: launched.stdout.text
    return StandaloneLaunch(
      text,
      result,
      failure ?: launchFailureReason(launched),
      launched.stdout.truncated || result?.reportTruncated == true,
    )
  }

  private fun launchFailureReason(output: PhaseStepOutput): String? =
    when {
      output.launchFailure != null -> output.launchFailure.reason
      output.termination !is AgentRunTermination.Exited -> "review execution did not exit normally"
      output.termination.code != 0 ->
        "review exited with status ${output.termination.code}"
      output.stdout.truncated -> "review output was truncated"
      else -> null
    }

  private fun failedResult(
    agentId: String,
    reason: String,
  ) = ParallelCodeReviewResult(
    ParallelReviewMergeResult(emptyList(), ""),
    ParallelReviewLaneStatus(agentId, false, reason),
  )

  private fun block(
    binding: PhaseReviewStepBinding,
    iteration: Int,
    reason: String,
  ): PhaseOutcome {
    binding.blockReviewStep(
      iteration,
      reason,
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )
    return PhaseOutcome.blocked(reason)
  }
}

internal fun verifiedFindingVerdict(
  report: StandaloneReviewReport,
  findingDispositions: Map<String, String>,
  warningClassifications: Map<String, String>,
): String {
  val changes =
    warningClassifications.values.any { it == "incomplete" } ||
      report.findingGroups.any { group ->
        when (findingDispositions.getValue(group.identity)) {
          "unresolved" -> true
          "rejected" -> false
          "confirmed" -> group.members.any(StandaloneReviewFinding::requiresChangesWhenConfirmed)
          else -> true
        }
      }
  return if (changes) {
    FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue
  } else {
    FeatureTaskRuntimeVerdict.APPROVED.wireValue
  }
}

internal fun parseVerificationRegister(
  output: String,
  findingIdentities: Set<String>,
  warningIdentities: Set<String>,
): ParsedVerificationRegister? {
  val findings = mutableMapOf<String, String>()
  val warnings = mutableMapOf<String, String>()
  output.lineSequence().forEach { line ->
    val entry = VERIFICATION_REGISTER_LINE.matchEntire(line.trim()) ?: return@forEach
    val identity = entry.groupValues[1].uppercase()
    val status = entry.groupValues[2].lowercase()
    val register =
      when {
        identity in findingIdentities && status in FINDING_DISPOSITIONS -> findings
        identity in warningIdentities && status in WARNING_CLASSIFICATIONS -> warnings
        else -> return@forEach
      }
    val earlier = register.putIfAbsent(identity, status)
    if (earlier != null && earlier != status) return null
  }
  if (findings.keys != findingIdentities || warnings.keys != warningIdentities) return null
  return ParsedVerificationRegister(findings, warnings)
}

private data class StandaloneLaunch(
  val rawOutput: String,
  val result: ParallelCodeReviewResult?,
  val failureReason: String?,
  val truncated: Boolean,
)

private data class StandaloneReviewLaunchInput(
  val target: ReviewTarget,
  val input: GoalSubtaskReviewInput,
  val reviewRunId: String,
  val directive: String,
  val lanePlan: InlineReviewRubricPlan? = null,
  val sourceFingerprint: String,
)

private sealed interface VerificationResult {
  data class Accepted(
    val register: String,
    val verdict: String,
    val findingDispositions: Map<String, String> = emptyMap(),
    val sourceFindingDispositions: Map<String, String> = emptyMap(),
  ) : VerificationResult

  data class Blocked(val reason: String, val evidence: String = "") : VerificationResult
}

internal data class ParsedVerificationRegister(
  val findingDispositions: Map<String, String>,
  val warningClassifications: Map<String, String>,
)

private val VERDICT_LINE = Regex("^verdict:\\s*\\S+$", RegexOption.IGNORE_CASE)
private val FINDING_DISPOSITIONS = setOf("confirmed", "rejected", "unresolved")
private val WARNING_CLASSIFICATIONS = setOf("incomplete", "complete")
private val VERIFICATION_REGISTER_LINE =
  Regex(
    "^(?:[-*+]\\s+|\\d+[.)]\\s+)?[*_`\\[]*([FW]-\\d{3})[*_`\\]]*\\s*[:=\\-–—]?\\s*[*_`]*\\s*" +
      "(confirmed|rejected|unresolved|incomplete|complete)\\b.*$",
    RegexOption.IGNORE_CASE,
  )

private const val DELEGATED_QUALITY_RUBRIC_HEADING = "## Selected local code-quality rubric"
private const val MAX_FAILURE_DIAGNOSTICS = 5
private const val MAX_FAILURE_DIAGNOSTIC_CHARS = 200
private const val MAX_REPORT_SUMMARY_CHARS = 2000
