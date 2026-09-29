package skillbill.engine.featuretask.runloop.core

import skillbill.application.decomposition.baseBranch
import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetup
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskFinalisation
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpoint
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunProgressObservations
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.goalrunner.execution.support.protectedBranchName
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.task.runtime.checkpoint.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput

object FeatureTaskRuntimeRunLoopSubtaskCommit {
  internal fun unownedWorktreeCommitSha(args: UnownedWorktreeCommitShaArgs): CommitPushFinalisation {
    val request = args.request
    val outputValidator = args.outputValidator
    val diagnostics = args.diagnostics
    val phaseGates = args.phaseGates
    val run = args.run
    val normalizedOutput = args.normalizedOutput
    val head = phaseGates.gitOperations.headCommitSha(request.repoRoot)
    val sha =
      head.value.orEmpty().trim().takeIf { head is WorkflowGitOperationResult.Ok && it.isNotBlank() }
        ?: return CommitPushNotApplicable
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=FeatureTaskRuntimeRunLoop.finaliseSubtaskCommit value_used='measured HEAD $sha' " +
        "value_expected=a runtime-finalised subtask commit for '${request.issueKey}' " +
        "cause=the run has no resolved, unprotected, checked-out branch, so finalisation could not " +
        "stage, amend, or push and the commit sha degrades to whatever HEAD already names",
    )
    return CommitPushSettled(
      revalidated(
        outputValidator,
        run.phaseId,
        FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(
          normalizedOutput.envelopeWireMap(),
          sha,
        ),
      ),
    )
  }

  internal fun commitPushChangedPaths(
    request: FeatureTaskRuntimeRunFacts,
    phaseGates: FeatureTaskRuntimePhaseGates,
    baseBranch: String,
  ): ReadinessChangedPaths {
    return when (
      val changed = phaseGates.gitOperations.readinessChangedPathsAgainstBase(request.repoRoot, baseBranch)
    ) {
      is WorkflowGitNameListResult.Listed -> ReadinessChangedPaths(paths = changed.names, error = null)
      is WorkflowGitNameListResult.Failed -> ReadinessChangedPaths(emptyList(), changed.error)
    }
  }

  internal fun finalisationBranch(
    request: FeatureTaskRuntimeRunFacts,
    session: FeatureTaskRuntimeRunLoopSessionObservations,
    phaseGates: FeatureTaskRuntimePhaseGates,
  ): String? {
    val branch =
      session.resolvedBranch
        ?.takeIf { FeatureTaskRuntimeBranchSetup.protectedBranchName(it) == null }
        ?: return null
    val head = phaseGates.gitOperations.currentBranch(request.repoRoot)
    return branch.takeIf { head is WorkflowGitOperationResult.Ok && head.value.trim() == branch.trim() }
  }

  internal fun recordFinalisedCheckpointIdentity(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunProgressObservations,
    recorder: PhaseRunRecords,
    diagnostics: RuntimeDiagnostics,
    args: RecordFinalisedCheckpointIdentityArgs,
  ): String? {
    val phaseId = args.phaseId
    val branch = args.branch
    val ledger = args.ledger
    val commitSha = args.commitSha
    val stagedPaths = args.stagedPaths
    val appended =
      runCatching {
        recorder.appendCheckpointIdentity(
          AppendCheckpointIdentityArgs(
            workflowId = request.workflowId,
            issueKey = request.issueKey,
            subtaskId =
              request.goalContinuation?.subtaskId?.toString()
                ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
            branch = branch,
            phaseId = phaseId,
            loopId = null,
            generation = FeatureTaskRuntimeRunLoopCheckpoint.checkpointGeneration(state, null),
            parentSha = ledger.commitSha,
            ownedPaths = stagedPaths,
            commitSha = commitSha,
          ),
        )
      }
    if (appended.getOrDefault(false)) return null
    val cause = appended.exceptionOrNull()?.message ?: "the workflow row was absent"
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=FeatureTaskRuntimeRunLoop.recordFinalisedCheckpointIdentity " +
        "value_used='no durable identity for finalised commit $commitSha' " +
        "value_expected=an appended checkpoint identity for '${request.issueKey}' " +
        "cause=$cause",
    )
    return "needs_human: the finalised subtask commit '$commitSha' was written but its durable " +
      "checkpoint identity could not be recorded ($cause), so it was not pushed. Without that pointer " +
      "a resumed run would open a second commit for this subtask instead of amending this one. Repair " +
      "the workflow store and resume; the commit is already on the branch."
  }

  internal fun revalidated(
    outputValidator: FeatureTaskRuntimePhaseOutputValidator,
    phaseId: String,
    envelope: Map<String, Any?>,
  ): NormalizedFeatureTaskRuntimePhaseOutput =
    outputValidator
      .validatePhaseOutput(JsonCodec.mapToJsonString(envelope), sourceLabel = phaseId)
      .requireAcceptedOutput(phaseId)
      .normalizedOutput
}

internal data class ReadinessChangedPaths(
  val paths: List<String>,
  val error: String?,
)
