package skillbill.engine.operation.verify

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.model.FeatureVerifyStartedRequest
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.service.WorkflowService
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRefusal
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.core.SelfConfirmingOperation
import skillbill.engine.operation.core.anchorUnreadable
import skillbill.engine.operation.core.closedVerifyWorkflow
import skillbill.engine.operation.core.gitValueOr
import skillbill.engine.operation.core.invalidArgument
import skillbill.engine.operation.core.missingIntake
import skillbill.engine.operation.core.pullRequestNotFound
import skillbill.engine.operation.core.unknownVerifyWorkflow
import skillbill.engine.operation.core.unresolvableVerifyTarget
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.pullrequest.PullRequestReviewThreadOperations
import skillbill.ports.review.pullrequest.model.ReviewPullRequestResolution
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.model.WorkflowContinueStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import java.time.Clock

@Inject
class VerifyOperation(
  private val workflows: WorkflowService,
  private val gitOperations: WorkflowGitOperations,
  private val pullRequests: PullRequestReviewThreadOperations,
  private val telemetry: VerifyTelemetry,
  delegatedReviewer: VerifyDelegatedReviewer,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
) : SelfConfirmingOperation {
  override val id: String = "verify"

  private val store = VerifyWorkflowStore(workflows)
  private val budget = VerifyBudget(diagnostics)
  private val sequence =
    VerifyStepSequence(store, gitOperations, telemetry, VerifyCodeReviewStep(delegatedReviewer), budget, clock)

  override fun pre(context: OperationContext): OperationRefusal? {
    val arguments = context.arguments
    arguments.mode?.let { mode ->
      if (VerifyReviewMode.fromWire(mode) == null) return invalidArgument("mode", mode, "inline|delegated")
    }
    if (!context.confirming && arguments.spec.isNullOrBlank() && context.instructions.isNullOrBlank()) {
      return missingIntake(id, INTAKE)
    }
    return null
  }

  override fun run(context: OperationContext): OperationRunResult {
    val baseline = worktreeBaseline(context) { return OperationRunResult.Finished(it) }
    val outcome = context.arguments.confirm?.let { token -> confirm(context, token.trim()) } ?: propose(context)
    if (outcome is OperationRefusal) return OperationRunResult.Finished(outcome)
    val changed = worktreeBaseline(context) { return OperationRunResult.Finished(it) } != baseline
    return OperationRunResult.Finished(
      if (changed) OperationOutcome.Failed(WORKTREE_CHANGED) else outcome,
    )
  }

  private fun propose(context: OperationContext): OperationOutcome {
    val intake = intakeOf(context) { return it }
    val requestedTarget = context.arguments.target?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_TARGET
    val target = resolveTarget(context, requestedTarget) { return it }
    return openAndExtract(context, intake, target)
  }

  private fun openAndExtract(
    context: OperationContext,
    intake: VerifyIntake,
    target: VerifyTarget,
  ): OperationOutcome {
    val mode = VerifyReviewMode.fromWire(context.arguments.mode) ?: VerifyReviewMode.INLINE
    val openArgs = WorkflowServiceOpenArgs(WorkflowFamilyKind.VERIFY, currentStepId = VerifyWorkflow.EXTRACT_CRITERIA)
    val workflowId =
      when (val opened = workflows.open(openArgs)) {
        is WorkflowOpenResult.Ok -> opened.workflowId
        is WorkflowOpenResult.Error -> return OperationOutcome.Failed("Verify workflow open failed: ${opened.error}")
      }
    val inputContext =
      mapOf(
        VerifyWorkflow.REPO_ROOT to repoRootOf(context),
        intake.storageKey to intake.value,
        VerifyWorkflow.TARGET to target.label,
        VerifyWorkflow.BASE_REVISION to target.baseRevision,
        VerifyWorkflow.HEAD_REVISION to target.headRevision,
        VerifyWorkflow.COMPARISON_SCOPE to target.comparisonScope,
        VerifyWorkflow.REVIEW_MODE to mode.wireValue,
      )
    val started =
      store.write(
        workflowId,
        WorkflowStatus.RUNNING,
        VerifyWorkflow.EXTRACT_CRITERIA,
        listOf(
          stepEntry(VerifyWorkflow.COLLECT_INPUTS, WorkflowStepStatus.COMPLETED, 1),
          stepEntry(VerifyWorkflow.EXTRACT_CRITERIA, WorkflowStepStatus.RUNNING, 1),
        ),
        mapOf(VerifyWorkflow.INPUT_CONTEXT to inputContext),
      )
    if (started is VerifyWrite.Rejected) return OperationOutcome.Failed(started.error)
    return extractAndPark(context, workflowId, intake, target)
  }

  private fun extractAndPark(
    context: OperationContext,
    workflowId: String,
    intake: VerifyIntake,
    target: VerifyTarget,
  ): OperationOutcome {
    val directive = VerifyPromptSections.extractCriteriaDirective(intake, target.label)
    val extracted =
      when (val step = context.steps.runReadOnly(context, VerifyPromptSections.EXTRACT_CRITERIA_STEP, directive)) {
        is OperationStepResult.Failed -> return failExtraction(workflowId, step.reason)
        is OperationStepResult.Refused -> return step.refusal
        is OperationStepResult.Settled -> step.value
      }
    val criteria = VerifyCriteria.parse(extracted).bounded(budget, workflowId)
    val parked =
      store.write(
        workflowId,
        WorkflowStatus.PENDING,
        VerifyWorkflow.EXTRACT_CRITERIA,
        listOf(stepEntry(VerifyWorkflow.EXTRACT_CRITERIA, WorkflowStepStatus.PENDING, 1)),
        mapOf(VerifyWorkflow.CRITERIA_SUMMARY to criteria.toArtifact()),
      )
    if (parked is VerifyWrite.Rejected) return failExtraction(workflowId, parked.error)
    supersedeParked(context, workflowId)
    return OperationOutcome.AwaitingConfirmation(
      workflowId,
      "Verify criteria for ${intake.label} against ${target.label}:\n\n${criteria.summary()}\n\n" +
        "Confirm or adjust the criteria before the review runs.",
    )
  }

  private fun failExtraction(
    workflowId: String,
    reason: String,
  ): OperationOutcome {
    store.write(
      workflowId,
      WorkflowStatus.FAILED,
      VerifyWorkflow.EXTRACT_CRITERIA,
      listOf(stepEntry(VerifyWorkflow.EXTRACT_CRITERIA, WorkflowStepStatus.FAILED, 1)),
    )
    return OperationOutcome.Failed("Verify criteria extraction failed: $reason\nVerify workflow: $workflowId")
  }

  private fun confirm(
    context: OperationContext,
    workflowId: String,
  ): OperationOutcome {
    val snapshot = confirmableSnapshot(context, workflowId) { return it }
    val inputContext = snapshot.artifacts[VerifyWorkflow.INPUT_CONTEXT]
    val target =
      context.arguments.target
        ?.takeIf(String::isNotBlank)
        ?.let { raw -> resolveTarget(context, raw.trim()) { return it } }
        ?: storedTarget(inputContext)
        ?: return missingIntake(id, "target:<pr-number|branch|base..head> for this workflow")
    return confirmTarget(context, workflowId, snapshot, target)
  }

  private fun confirmTarget(
    context: OperationContext,
    workflowId: String,
    snapshot: WorkflowSnapshotView,
    target: VerifyTarget,
  ): OperationOutcome {
    val inputContext = snapshot.artifacts[VerifyWorkflow.INPUT_CONTEXT]
    val storedMode = VerifyWorkflow.string(inputContext, VerifyWorkflow.REVIEW_MODE)
    val mode = VerifyReviewMode.fromWire(context.arguments.mode ?: storedMode) ?: VerifyReviewMode.INLINE
    val criteriaArtifact = snapshot.artifacts[VerifyWorkflow.CRITERIA_SUMMARY]
    val criteria = VerifyCriteria.fromArtifact(criteriaArtifact)
    val intake = VerifyIntake.stored(inputContext)?.label.orEmpty()
    val attempts = snapshot.steps.associate { step -> step.stepId to step.attemptCount }
    val run = VerifyRun(context, workflowId, snapshot.sessionId, intake, target, mode, criteria, clock.instant())
    if (snapshot.currentStepId in PARKED_STEPS) {
      return if (criteriaArtifact == null) {
        OperationOutcome.Failed(
          "Verify workflow '$workflowId' has no extracted criteria to confirm; rerun operation verify.",
        )
      } else {
        confirmParked(run, attempts)
      }
    }
    refreshStaleCheckpoint(run, snapshot)?.let { failure -> return failure }
    val start = resumeStep(workflowId) { failure -> return failure }
    return sequence.run(run, start, attempts)
  }

  private inline fun confirmableSnapshot(
    context: OperationContext,
    workflowId: String,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): WorkflowSnapshotView {
    val snapshot = verifyWorkflow(workflowId, refuse)
    val repoRoot = repoRootOf(context)
    val storedRoot = VerifyWorkflow.string(snapshot.artifacts[VerifyWorkflow.INPUT_CONTEXT], VerifyWorkflow.REPO_ROOT)
    if (storedRoot != null && storedRoot != repoRoot) {
      refuse(OperationOutcome.Blocked("Verify workflow '$workflowId' does not belong to '$repoRoot'."))
    }
    if (snapshot.workflowStatus in CLOSED_STATUSES) {
      val notes = snapshot.artifacts[VerifyWorkflow.SESSION_NOTES]
      val supersededBy = VerifyWorkflow.string(notes, VerifyWorkflow.SUPERSEDED_BY)
      refuse(closedVerifyWorkflow(workflowId, snapshot.workflowStatus.wireValue, supersededBy))
    }
    return snapshot
  }

  private inline fun verifyWorkflow(
    workflowId: String,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): WorkflowSnapshotView =
    when (val found = workflows.get(WorkflowFamilyKind.VERIFY, workflowId)) {
      is WorkflowGetResult.Ok -> found.snapshot
      is WorkflowGetResult.Error -> refuse(unknownVerifyWorkflow(workflowId))
    }

  private fun confirmParked(
    run: VerifyRun,
    attempts: Map<String, Int>,
  ): OperationOutcome {
    val sessionId =
      telemetry.started(
        FeatureVerifyStartedRequest(
          acceptanceCriteriaCount = run.criteria.acceptanceCriteriaCount,
          rolloutRelevant = run.criteria.rolloutRelevant,
          specSummary = run.intake,
          orchestrated = false,
        ),
      )
    return sequence.run(
      run.copy(sessionId = sessionId),
      VerifyWorkflow.GATHER_DIFF,
      attempts,
      settledBefore =
        PARKED_STEPS.map { step -> stepEntry(step, WorkflowStepStatus.COMPLETED, attempts[step] ?: 1) },
    )
  }

  private fun refreshStaleCheckpoint(
    run: VerifyRun,
    snapshot: WorkflowSnapshotView,
  ): OperationOutcome? {
    val stored = snapshot.artifacts[VerifyWorkflow.DIFF_PROJECTION] ?: return null
    val current = gitOperations.repositoryFingerprint(run.context.repoRoot) as? WorkflowGitOperationResult.Ok
    if (current != null && VerifyWorkflow.string(stored, VerifyWorkflow.CHECKPOINT) == current.value.orEmpty()) {
      return null
    }
    val projection =
      when (val refreshed = sequence.diffProjection(run.context, run.target, run.workflowId)) {
        is VerifyDiffProjection.Ready -> refreshed.artifact
        is VerifyDiffProjection.Unavailable -> return OperationOutcome.Failed(refreshed.reason)
      }
    val written =
      store.write(
        run.workflowId,
        snapshot.workflowStatus,
        snapshot.currentStepId,
        emptyList(),
        mapOf(VerifyWorkflow.DIFF_PROJECTION to projection),
      )
    return (written as? VerifyWrite.Rejected)?.let { rejected -> OperationOutcome.Failed(rejected.error) }
  }

  private inline fun resumeStep(
    workflowId: String,
    onFailure: (OperationOutcome) -> Nothing,
  ): String {
    val view =
      when (val continued = workflows.continueWorkflow(WorkflowFamilyKind.VERIFY, workflowId)) {
        is WorkflowContinueResult.Standard -> continued.view
        is WorkflowContinueResult.UnknownWorkflow -> onFailure(unknownVerifyWorkflow(workflowId))
        else -> onFailure(OperationOutcome.Failed("Verify workflow '$workflowId' cannot be continued."))
      }
    val step = view.continueStepId
    return when (view.continueStatus) {
      WorkflowContinueStatus.DONE ->
        onFailure(closedVerifyWorkflow(workflowId, WorkflowStatus.COMPLETED.wireValue, supersededBy = null))
      WorkflowContinueStatus.BLOCKED ->
        onFailure(
          OperationOutcome.Failed(
            "Verify workflow '$workflowId' cannot resume at $step; missing " +
              view.resume.missingArtifacts.joinToString(", ") + ".",
          ),
        )
      else ->
        step.takeIf { it in VerifyWorkflow.CONFIRMED_STEPS }
          ?: onFailure(OperationOutcome.Failed("Verify workflow '$workflowId' cannot resume at '$step'."))
    }
  }

  private fun supersedeParked(
    context: OperationContext,
    workflowId: String,
  ) {
    val repoRoot = repoRootOf(context)
    val rows =
      skipUnreadable(workflowId) { workflows.list(WorkflowFamilyKind.VERIFY, SUPERSEDE_SCAN_LIMIT).workflows }
        ?: return
    rows
      .filter { row ->
        row.workflowId != workflowId && row.currentStepId in PARKED_STEPS && row.workflowStatus !in CLOSED_STATUSES
      }
      .forEach { row ->
        skipUnreadable(row.workflowId) {
          val snapshot = (workflows.get(WorkflowFamilyKind.VERIFY, row.workflowId) as? WorkflowGetResult.Ok)?.snapshot
          val inputContext = snapshot?.artifacts?.get(VerifyWorkflow.INPUT_CONTEXT)
          val rowRoot = VerifyWorkflow.string(inputContext, VerifyWorkflow.REPO_ROOT)
          if (rowRoot == repoRoot) {
            store.write(
              row.workflowId,
              WorkflowStatus.ABANDONED,
              row.currentStepId,
              emptyList(),
              mapOf(VerifyWorkflow.SESSION_NOTES to mapOf(VerifyWorkflow.SUPERSEDED_BY to workflowId)),
            )
          }
        }
      }
  }

  private inline fun <T> skipUnreadable(
    workflowId: String,
    read: () -> T,
  ): T? =
    try {
      read()
    } catch (error: InvalidWorkflowStateSchemaError) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=verify_supersede value_expected=readable_verify_rows value_used=skipped workflow_id=$workflowId " +
          "error=${error.message.orEmpty()}",
        error,
      )
      null
    }

  private inline fun intakeOf(
    context: OperationContext,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): VerifyIntake {
    val specPath = context.arguments.spec?.trim()?.takeIf(String::isNotEmpty)
    if (specPath == null) return VerifyIntake.Text(context.instructions.orEmpty().trim())
    requireSpec(context, specPath, refuse)
    return VerifyIntake.SpecFile(specPath)
  }

  private inline fun requireSpec(
    context: OperationContext,
    specPath: String,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ) {
    val spec = specPath.trimEnd('/')
    val parent = spec.substringBeforeLast('/', missingDelimiterValue = "")
    val candidates =
      listOf(spec, "$spec/spec.md", "$spec/$MANIFEST", if (parent.isEmpty()) MANIFEST else "$parent/$MANIFEST")
    when (val present = gitOperations.pathContentIdentities(context.repoRoot, candidates)) {
      is WorkflowPathContentIdentitiesResult.Resolved ->
        if (present.identities.isEmpty()) {
          refuse(
            OperationOutcome.Blocked(
              "rehydrate-needed: neither spec '$specPath' nor its decomposition-manifest.yaml exists; rehydrate " +
                "the spec from Linear to that path, then invoke operation verify again.",
            ),
          )
        }
      is WorkflowPathContentIdentitiesResult.Failed -> refuse(anchorUnreadable(SPEC_ANCHOR, present.error))
    }
  }

  private inline fun resolveTarget(
    context: OperationContext,
    raw: String,
    refuse: (OperationRefusal) -> Nothing,
  ): VerifyTarget {
    if (RANGE in raw) {
      val (base, head) = raw.split(RANGE, limit = 2)
      return VerifyTarget(raw, commit(context, raw, base, refuse), commit(context, raw, head, refuse))
    }
    val (head, baseRef) =
      if (raw.all(Char::isDigit)) {
        pullRequestHead(context, raw, refuse)
      } else {
        commit(context, raw, raw, refuse) to DEFAULT_BASE_REF
      }
    val current = gitOperations.runtimePhaseHeadCommit(context.repoRoot).gitValueOr(HEAD_ANCHOR, refuse)
    if (head != current) {
      refuse(
        OperationOutcome.Blocked(
          "Verify target '$raw' is at $head but HEAD is $current; check the target out, or pass " +
            "target:<base>..HEAD.",
        ),
      )
    }
    return VerifyTarget(raw, mergeBase(context, raw, baseRef, refuse), head)
  }

  private inline fun pullRequestHead(
    context: OperationContext,
    number: String,
    refuse: (OperationRefusal) -> Nothing,
  ): Pair<String, String> =
    when (val resolved = pullRequests.resolvePullRequest(context.repoRoot, number)) {
      is ReviewPullRequestResolution.Found ->
        resolved.pullRequest.headOid to "origin/${resolved.pullRequest.baseRefName}"
      ReviewPullRequestResolution.Absent -> refuse(pullRequestNotFound(number))
      is ReviewPullRequestResolution.Unavailable -> refuse(unresolvableVerifyTarget(number, resolved.reason))
    }

  private inline fun mergeBase(
    context: OperationContext,
    raw: String,
    baseRef: String,
    refuse: (OperationRefusal) -> Nothing,
  ): String =
    (gitOperations.mergeBaseWithHead(context.repoRoot, baseRef) as? WorkflowGitOperationResult.Ok)
      ?.value?.trim()?.takeIf(String::isNotEmpty)
      ?: refuse(unresolvableVerifyTarget(raw, "no merge base with $baseRef."))

  private inline fun commit(
    context: OperationContext,
    raw: String,
    revision: String,
    refuse: (OperationRefusal) -> Nothing,
  ): String =
    (gitOperations.resolveCommit(context.repoRoot, revision) as? WorkflowGitOperationResult.Ok)
      ?.value?.trim()?.takeIf(String::isNotEmpty)
      ?: refuse(unresolvableVerifyTarget(raw, "'$revision' is not a commit."))

  private fun storedTarget(inputContext: Any?): VerifyTarget? {
    val base = VerifyWorkflow.string(inputContext, VerifyWorkflow.BASE_REVISION) ?: return null
    val head = VerifyWorkflow.string(inputContext, VerifyWorkflow.HEAD_REVISION) ?: return null
    return VerifyTarget(VerifyWorkflow.string(inputContext, VerifyWorkflow.TARGET) ?: "$base..$head", base, head)
  }

  private inline fun worktreeBaseline(
    context: OperationContext,
    refuse: (OperationOutcome.Blocked) -> Nothing,
  ): Pair<String, String> {
    val status =
      when (val result = gitOperations.worktreeStatus(context.repoRoot)) {
        is WorkflowGitOperationResult.Ok -> result.value.orEmpty()
        else -> refuse(anchorUnreadable(WORKTREE_ANCHOR, result.error))
      }
    return status to gitOperations.runtimePhaseHeadCommit(context.repoRoot).gitValueOr(HEAD_ANCHOR, refuse)
  }

  private fun repoRootOf(context: OperationContext): String = context.repoRoot.toAbsolutePath().normalize().toString()

  private companion object {
    const val INTAKE = "a Linear issue key or URL, requirements text, or spec:<path>"
    const val DEFAULT_TARGET = "HEAD"
    const val MANIFEST = "decomposition-manifest.yaml"
    const val RANGE = ".."
    const val DEFAULT_BASE_REF = "origin/HEAD"
    const val SPEC_ANCHOR = "spec"
    const val HEAD_ANCHOR = "HEAD"
    const val WORKTREE_ANCHOR = "worktree status"
    const val SUPERSEDE_SCAN_LIMIT = 100
    const val WORKTREE_CHANGED = "operation verify is read-only, but the worktree or HEAD changed during the run."

    val PARKED_STEPS = setOf(VerifyWorkflow.COLLECT_INPUTS, VerifyWorkflow.EXTRACT_CRITERIA)
    val CLOSED_STATUSES = setOf(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.ABANDONED)
  }
}
