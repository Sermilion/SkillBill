package skillbill.engine.featuretask.phaserun.qa

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolution
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.receipt.QaReceipt
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.engine.featuretask.qa.receipt.QaRetainedResource
import skillbill.engine.featuretask.qa.receipt.QaSkipReason
import skillbill.engine.operation.core.WorktreeBaseline
import skillbill.engine.operation.core.worktreeBaseline
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.incompatibleQaCapability
import skillbill.error.featuretask.qa.isQaDeviceLeaseFailure
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.error.featuretask.qa.qaDeviceLeaseReason
import skillbill.error.featuretask.qa.qaRepositoryUnreadable
import skillbill.error.featuretask.qa.qaWorktreeChanged
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaWorkspace
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path

@Inject
class QaStandalonePhaseRun(
  private val workspaces: QaWorkspacePort,
  private val leases: QaDeviceLeasePort,
  private val capabilities: QaCapabilityResolver,
  private val settlement: QaReceiptSettlement,
  private val validator: QaReceiptValidator,
  private val gitOperations: WorkflowGitOperations,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun run(
    request: PhaseRunRequest,
    invocationId: String,
    launch: (PhaseRunRequest) -> PhaseRunResult,
  ): PhaseRunResult =
    try {
      execute(request, invocationId, launch)
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaFailure)
      blocked(request, invocationId, failure.message.orEmpty())
    }

  private fun execute(
    request: PhaseRunRequest,
    invocationId: String,
    launch: (PhaseRunRequest) -> PhaseRunResult,
  ): PhaseRunResult {
    val repoRoot = request.repoRoot
    val workspace = workspaces.open(repoRoot, invocationId)
    val baseline = gitOperations.worktreeBaseline(repoRoot, ::cannotStart)
    val expected = QaExpectedIdentity(baseline.head, treeIdentity(repoRoot), workspace.evidencePrefix)
    val retained = mutableListOf<QaRetainedResource>()
    val outcome =
      try {
        attempt(request, workspace, expected, launch)
      } finally {
        retained += releaseLeases(repoRoot, invocationId)
        retained += closeWorkspace(workspace)
      }
    requireUnchanged(repoRoot, baseline)
    return when (outcome) {
      is QaAttempt.Stopped -> outcome.result
      is QaAttempt.Settled -> {
        val receipt = validator.withRetainedResources(outcome.receipt, retained, RETAINED_GUIDANCE, expected)
        receipt.fallbacks.forEach { fallback ->
          diagnostics.info(
            "qa fallback: seam=qa_receipt value_expected=requested_target value_used=${fallback.kind.wireValue} " +
              "reason=${fallback.reason.take(MAX_RECORD_REASON)}",
          )
        }
        PhaseRunResult.Completed(
          invocationId = invocationId,
          completedStepIds = outcome.completedStepIds,
          reviewResult = null,
          value = receipt.reasonDetail ?: "QA verdict: ${receipt.verdict.wireValue}.",
          targetReport = receipt.targetReport(),
          qaOutcome = receipt.qaOutcome(),
        )
      }
    }
  }

  private fun attempt(
    request: PhaseRunRequest,
    workspace: QaWorkspace,
    expected: QaExpectedIdentity,
    launch: (PhaseRunRequest) -> PhaseRunResult,
  ): QaAttempt {
    val declared =
      when (val resolution = capabilities.resolve(trackedPaths(request.repoRoot))) {
        is QaCapabilityResolution.Declared -> resolution
        is QaCapabilityResolution.Incompatible -> throw incompatibleQaCapability(resolution.reason)
        is QaCapabilityResolution.Absent ->
          return QaAttempt.Settled(
            validator.skipped(QaSkipReason.UNSUPPORTED_TARGET, resolution.detail, expected),
            emptyList(),
          )
      }
    val qa = QaExecutionContext(workspace, expected, declared.guidancePath, declared.declaration.platforms)
    return when (val result = launch(request.copy(qaExecution = qa))) {
      is PhaseRunResult.Blocked -> QaAttempt.Stopped(result)
      is PhaseRunResult.Completed -> QaAttempt.Settled(settlement.settle(qa), result.completedStepIds)
    }
  }

  private fun releaseLeases(
    repoRoot: Path,
    invocationId: String,
  ): List<QaRetainedResource> =
    leases.releaseAllFor(repoRoot, invocationId).filterIsInstance<QaLeaseReleaseOutcome.Failed>().map { failed ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "qa device lease retained: seam=qa_cleanup value_expected=released value_used=retained " +
          "device_fingerprint=${failed.retainedIdentity} reason=${failed.detail.take(MAX_RECORD_REASON)}",
      )
      QaRetainedResource(RETAINED_LEASE_KIND, failed.retainedIdentity)
    }

  private fun closeWorkspace(workspace: QaWorkspace): List<QaRetainedResource> =
    try {
      workspaces.close(workspace)
      emptyList()
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaDeviceLeaseFailure)
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "qa invocation owner retained: seam=qa_cleanup value_expected=removed value_used=retained " +
          "invocation=${workspace.invocationId} reason=${failure.qaDeviceLeaseReason.take(MAX_RECORD_REASON)}",
      )
      listOf(QaRetainedResource(RETAINED_OWNER_KIND, workspace.invocationId))
    }

  private fun requireUnchanged(
    repoRoot: Path,
    baseline: WorktreeBaseline,
  ) {
    val after =
      gitOperations.worktreeBaseline(repoRoot) { anchor, error -> changed("could not reread $anchor: $error") }
    when {
      after.head != baseline.head -> changed("HEAD moved from ${baseline.head} to ${after.head}.")
      after.status != baseline.status -> changed("the worktree status differs from the status before the run.")
    }
  }

  private fun treeIdentity(repoRoot: Path): String =
    when (val result = gitOperations.repositoryFingerprint(repoRoot)) {
      is WorkflowGitOperationResult.Ok -> result.value.trim()
      else -> cannotStart(TREE_IDENTITY_ANCHOR, result.error)
    }

  private fun trackedPaths(repoRoot: Path): List<String> =
    when (val result = gitOperations.trackedPaths(repoRoot)) {
      is WorkflowGitNameListResult.Listed -> result.names
      is WorkflowGitNameListResult.Failed -> cannotStart(TRACKED_PATHS_ANCHOR, result.error)
    }

  private fun cannotStart(
    anchor: String,
    error: String,
  ): Nothing = throw qaRepositoryUnreadable("could not read $anchor: $error")

  private fun changed(detail: String): Nothing = throw qaWorktreeChanged(detail)

  private fun blocked(
    request: PhaseRunRequest,
    invocationId: String,
    reason: String,
  ): PhaseRunResult.Blocked {
    val stepId = SkeletonDefinition.byId(request.definitionId).stepIds.first()
    return PhaseRunResult.Blocked(invocationId, emptyList(), null, stepId, reason)
  }

  private sealed interface QaAttempt {
    data class Settled(
      val receipt: QaReceipt,
      val completedStepIds: List<String>,
    ) : QaAttempt

    data class Stopped(val result: PhaseRunResult.Blocked) : QaAttempt
  }

  private companion object {
    const val TREE_IDENTITY_ANCHOR = "tree identity"
    const val TRACKED_PATHS_ANCHOR = "tracked paths"
    const val RETAINED_LEASE_KIND = "device_lease"
    const val RETAINED_OWNER_KIND = "invocation_owner"
    const val MAX_RECORD_REASON = 200
    const val RETAINED_GUIDANCE =
      "Run `skill-bill qa lease release --invocation <id> --device <serial>` or delete the lease file under " +
        ".git/skill-bill/qa/leases/ once no QA run uses the device."
  }
}
