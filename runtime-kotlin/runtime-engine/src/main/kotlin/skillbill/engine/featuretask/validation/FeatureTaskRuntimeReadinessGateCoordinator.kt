package skillbill.engine.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.baseBranch
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeReadinessEvidencePort
import skillbill.engine.featuretask.runloop.observability.emitFeatureTaskRuntimeEventSafely
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.PrCheckProcessRunner
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.ReadinessTreeIdentity
import skillbill.ports.workflow.gitops.model.WorkflowReadinessTreeIdentityResult
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessCheckResult
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessCheckStatus
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessEvidence
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

private fun WorkflowGitOperations.readinessTreeIdentityOrNull(
  repoRoot: Path,
  baseBranch: String,
  workflowId: String,
): ReadinessTreeIdentity? =
  (resolveReadinessTreeIdentity(repoRoot, baseBranch, workflowId) as? WorkflowReadinessTreeIdentityResult.Resolved)
    ?.identity

data class ReadinessPostValidateCaptureRequest(
  val workflowId: String,
  val repoRoot: Path,
  val baseBranch: String,
  val changedPaths: List<String>,
  val gitOperations: WorkflowGitOperations,
)

data class ReadinessCommitPushSettleRequest(
  val workflowId: String,
  val repoRoot: Path,
  val baseBranch: String,
  val changedPaths: List<String>,
  val changedPathsError: String? = null,
  val gitOperations: WorkflowGitOperations,
)

sealed interface ReadinessCommitPushSettleResult {
  data object Ready : ReadinessCommitPushSettleResult

  data class Blocked(
    val reason: String,
    val failureDisposition: FeatureTaskRuntimeFailureDisposition =
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    val lastResumableStep: String,
  ) : ReadinessCommitPushSettleResult
}

private sealed interface CommitPushPreparation {
  data class Ready(
    val identity: ReadinessTreeIdentity,
    val selectedChecks: List<ReadinessSelectedCheck>,
    val persisted: FeatureTaskRuntimeReadinessEvidence?,
    val invalidated: Set<String>,
  ) : CommitPushPreparation

  data class Blocked(val result: ReadinessCommitPushSettleResult.Blocked) : CommitPushPreparation
}

private sealed interface CheckExecution {
  data class Complete(val results: List<FeatureTaskRuntimeReadinessCheckResult>) : CheckExecution

  data class Blocked(
    val result: ReadinessCommitPushSettleResult.Blocked,
    val results: List<FeatureTaskRuntimeReadinessCheckResult>,
  ) : CheckExecution
}

@Inject
class FeatureTaskRuntimeReadinessGateCoordinator(
  private val checkSelection: ReadinessCheckSelection,
  private val prCheckRunner: PrCheckProcessRunner,
  private val readinessEvidence: FeatureTaskRuntimeReadinessEvidencePort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun capturePostValidateFragment(request: ReadinessPostValidateCaptureRequest) {
    val identity =
      request.gitOperations.readinessTreeIdentityOrNull(
        request.repoRoot,
        request.baseBranch,
        request.workflowId,
      )
    if (identity == null) {
      recordDegradation("readiness-post-validate-identity", "Could not resolve readiness tree identity after validate.")
      return
    }
    val selection =
      runCatching {
        checkSelection.select(request.repoRoot, request.changedPaths)
      }.getOrElse { error ->
        recordDegradation("readiness-post-validate-selection", error.message.orEmpty())
        return
      }
    if (selection is ReadinessCheckSelectionResult.Failed) {
      recordDegradation("readiness-post-validate-selection", selection.reason)
      return
    }
    val selected = (selection as ReadinessCheckSelectionResult.Selected).checks
    val packOnly = selected.filter { it.checkId == READINESS_PACK_COLLECT_ALL_CHECK_ID }
    val results =
      packOnly.map { check ->
        FeatureTaskRuntimeReadinessCheckResult(
          checkId = check.checkId,
          command = check.command,
          exitCode = 0,
          status = FeatureTaskRuntimeReadinessCheckStatus.PASSED,
        )
      }
    val evidence =
      FeatureTaskRuntimeReadinessEvidence(
        sourceTreeSha = identity.sourceTreeSha,
        baseRefSha = identity.baseRefSha,
        headSha = identity.headSha,
        selectedChecks = packOnly.map(ReadinessSelectedCheck::checkId),
        checkResults = results,
      )
    persistEvidence(request.workflowId, evidence, "readiness-post-validate-persistence")
  }

  fun settleBeforeCommitPush(request: ReadinessCommitPushSettleRequest): ReadinessCommitPushSettleResult =
    when (val preparation = prepareCommitPush(request)) {
      is CommitPushPreparation.Blocked -> preparation.result
      is CommitPushPreparation.Ready -> settlePreparedCommitPush(request, preparation)
    }

  private fun prepareCommitPush(request: ReadinessCommitPushSettleRequest): CommitPushPreparation =
    request.changedPathsError?.takeIf(String::isNotBlank)?.let { error ->
      recordDegradation("readiness-commit-push-changed-paths", error)
      CommitPushPreparation.Blocked(
        blocked("Readiness could not discover the to-be-committed paths: $error"),
      )
    } ?: prepareAfterPathDiscovery(request)

  private fun prepareAfterPathDiscovery(request: ReadinessCommitPushSettleRequest): CommitPushPreparation {
    val identity =
      request.gitOperations.readinessTreeIdentityOrNull(
        request.repoRoot,
        request.baseBranch,
        request.workflowId,
      ) ?: run {
        recordDegradation(
          "readiness-commit-push-identity",
          "Could not resolve readiness tree identity before commit_push.",
        )
        return CommitPushPreparation.Blocked(
          blocked("Readiness could not resolve repository identity before commit_push."),
        )
      }
    val selectedChecks =
      selectCommitPushChecks(request)
        ?: return CommitPushPreparation.Blocked(blocked("Readiness check discovery failed."))
    val persistedResult = runCatching { readinessEvidence.loadReadinessEvidence(request.workflowId) }
    val persisted = persistedResult.getOrNull()
    val staleReason = persisted?.let { persistedIdentityMismatch(it, identity) }
    return when {
      persistedResult.isFailure -> {
        val reason =
          "Could not load persisted readiness evidence: " +
            persistedResult.exceptionOrNull()?.message.orEmpty()
        recordDegradation("readiness-commit-push-persistence", reason)
        CommitPushPreparation.Blocked(
          blocked("Readiness evidence could not be loaded before commit_push."),
        )
      }
      staleReason != null -> {
        recordDegradation("readiness-commit-push-identity", staleReason)
        CommitPushPreparation.Blocked(blocked(staleReason))
      }
      else -> {
        if (persisted == null) {
          recordDegradation(
            "readiness-commit-push-persistence",
            "No post-validate readiness evidence was available; selected checks must execute now.",
          )
        }
        CommitPushPreparation.Ready(
          identity = identity,
          selectedChecks = selectedChecks,
          persisted = persisted,
          invalidated = checkSelection.invalidatedCheckIds(selectedChecks, request.changedPaths),
        )
      }
    }
  }

  private fun selectCommitPushChecks(request: ReadinessCommitPushSettleRequest): List<ReadinessSelectedCheck>? =
    runCatching { checkSelection.select(request.repoRoot, request.changedPaths) }.fold(
      onSuccess = { selection ->
        when (selection) {
          is ReadinessCheckSelectionResult.Failed -> {
            recordDegradation("readiness-commit-push-selection", selection.reason)
            null
          }
          is ReadinessCheckSelectionResult.Selected -> selection.checks
        }
      },
      onFailure = { error ->
        recordDegradation("readiness-commit-push-selection", error.message.orEmpty())
        null
      },
    )

  private fun persistedIdentityMismatch(
    persisted: FeatureTaskRuntimeReadinessEvidence,
    identity: ReadinessTreeIdentity,
  ): String? =
    when {
      persisted.baseRefSha != identity.baseRefSha -> identityMismatchReason(persisted, identity)
      else -> null
    }

  private fun settlePreparedCommitPush(
    request: ReadinessCommitPushSettleRequest,
    preparation: CommitPushPreparation.Ready,
  ): ReadinessCommitPushSettleResult {
    val execution = executeChecks(request, preparation)
    val results =
      when (execution) {
        is CheckExecution.Complete -> execution.results
        is CheckExecution.Blocked -> return persistBlockedResult(request, preparation, execution)
      }
    val evidence =
      FeatureTaskRuntimeReadinessEvidence(
        sourceTreeSha = preparation.identity.sourceTreeSha,
        baseRefSha = preparation.identity.baseRefSha,
        headSha = preparation.identity.headSha,
        selectedChecks = preparation.selectedChecks.map(ReadinessSelectedCheck::checkId),
        checkResults = results,
      )
    return settleReadyEvidence(request.workflowId, preparation.identity, evidence)
  }

  private fun executeChecks(
    request: ReadinessCommitPushSettleRequest,
    preparation: CommitPushPreparation.Ready,
  ): CheckExecution {
    val results = mutableListOf<FeatureTaskRuntimeReadinessCheckResult>()
    preparation.selectedChecks.forEach { check ->
      val reused =
        preparation.persisted?.let { existing ->
          reuseExistingResult(existing, check, preparation.identity, preparation.invalidated)
        }
      val executed = reused ?: executeCheckSafely(request, check)
      results += executed
      if (executed.status != FeatureTaskRuntimeReadinessCheckStatus.PASSED || executed.exitCode != 0) {
        return CheckExecution.Blocked(
          blocked(
            "Readiness check '${check.checkId}' is not passing " +
              "(exit=${executed.exitCode}, status=${executed.status.wireValue}).",
          ),
          results.toList(),
        )
      }
    }
    return CheckExecution.Complete(results)
  }

  private fun executeCheckSafely(
    request: ReadinessCommitPushSettleRequest,
    check: ReadinessSelectedCheck,
  ): FeatureTaskRuntimeReadinessCheckResult =
    runCatching { executeCheck(request, check) }.getOrElse { error ->
      recordDegradation(
        "readiness-commit-push-execution",
        "Selected check '${check.checkId}' could not execute: ${error.message.orEmpty()}",
      )
      FeatureTaskRuntimeReadinessCheckResult(
        checkId = check.checkId,
        command = check.command,
        exitCode = 1,
        status = FeatureTaskRuntimeReadinessCheckStatus.UNPERSISTED,
      )
    }

  private fun persistBlockedResult(
    request: ReadinessCommitPushSettleRequest,
    preparation: CommitPushPreparation.Ready,
    execution: CheckExecution.Blocked,
  ): ReadinessCommitPushSettleResult {
    val evidence =
      FeatureTaskRuntimeReadinessEvidence(
        sourceTreeSha = preparation.identity.sourceTreeSha,
        baseRefSha = preparation.identity.baseRefSha,
        headSha = preparation.identity.headSha,
        selectedChecks = preparation.selectedChecks.map(ReadinessSelectedCheck::checkId),
        checkResults = execution.results,
      )
    persistEvidence(request.workflowId, evidence, "readiness-commit-push-persistence")
    return execution.result
  }

  private fun settleReadyEvidence(
    workflowId: String,
    identity: ReadinessTreeIdentity,
    evidence: FeatureTaskRuntimeReadinessEvidence,
  ): ReadinessCommitPushSettleResult =
    try {
      evidence.requireReady("commit_push", identity.sourceTreeSha, identity.baseRefSha, identity.headSha)
      if (persistEvidence(workflowId, evidence, "readiness-commit-push-persistence")) {
        ReadinessCommitPushSettleResult.Ready
      } else {
        blocked("Readiness evidence could not be persisted before commit_push.")
      }
    } catch (error: InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError) {
      persistEvidence(workflowId, evidence, "readiness-commit-push-persistence")
      blocked(error.message.orEmpty())
    }

  fun bindCommittedHead(
    workflowId: String,
    repoRoot: Path,
    baseBranch: String,
    gitOperations: WorkflowGitOperations,
    commitSha: String,
  ): ReadinessCommitPushSettleResult {
    val current =
      gitOperations.readinessTreeIdentityOrNull(repoRoot, baseBranch, workflowId)
        ?: return identityAfterCommitBlocked()
    return bindCommittedHeadWithIdentity(workflowId, current, commitSha)
  }

  private fun bindCommittedHeadWithIdentity(
    workflowId: String,
    current: ReadinessTreeIdentity,
    commitSha: String,
  ): ReadinessCommitPushSettleResult {
    if (current.headSha != commitSha) {
      recordDegradation(
        "readiness-commit-push-identity",
        "Committed head '$commitSha' does not match current head '${current.headSha}'.",
      )
      return blocked(
        "Readiness committed head mismatch: committed '$commitSha' vs current '${current.headSha}'.",
      )
    }
    val evidenceResult = runCatching { readinessEvidence.loadReadinessEvidence(workflowId) }
    if (evidenceResult.isFailure) {
      val reason =
        "Could not load readiness evidence after commit: " +
          evidenceResult.exceptionOrNull()?.message.orEmpty()
      recordDegradation("readiness-commit-push-persistence", reason)
      return blocked("Readiness evidence could not be loaded after commit.")
    }
    val evidence =
      evidenceResult.getOrNull()
        ?: return missingEvidenceAfterCommitBlocked()
    return bindEvidenceAfterCommit(workflowId, current, evidence)
  }

  private fun bindEvidenceAfterCommit(
    workflowId: String,
    current: ReadinessTreeIdentity,
    evidence: FeatureTaskRuntimeReadinessEvidence,
  ): ReadinessCommitPushSettleResult {
    val aligned =
      evidence.copy(
        sourceTreeSha = current.sourceTreeSha,
        baseRefSha = current.baseRefSha,
      )
    val readinessError =
      runCatching {
        aligned.requireReady("commit_push", current.sourceTreeSha, current.baseRefSha, aligned.headSha)
      }.exceptionOrNull()
    if (readinessError != null) {
      recordDegradation("readiness-commit-push-persistence", readinessError.message.orEmpty())
      return blocked(readinessError.message.orEmpty())
    }
    return if (
      persistEvidence(workflowId, aligned.copy(headSha = current.headSha), "readiness-commit-push-persistence")
    ) {
      ReadinessCommitPushSettleResult.Ready
    } else {
      blocked("Readiness evidence could not be persisted after commit.")
    }
  }

  private fun identityAfterCommitBlocked(): ReadinessCommitPushSettleResult.Blocked {
    recordDegradation("readiness-commit-push-identity", "Could not resolve readiness identity after commit.")
    return blocked("Readiness identity could not be refreshed after commit.")
  }

  private fun missingEvidenceAfterCommitBlocked(): ReadinessCommitPushSettleResult.Blocked {
    recordDegradation("readiness-commit-push-persistence", "Readiness evidence is missing after commit.")
    return blocked("Readiness evidence is missing after commit.")
  }

  private fun reuseExistingResult(
    existing: FeatureTaskRuntimeReadinessEvidence,
    check: ReadinessSelectedCheck,
    identity: ReadinessTreeIdentity,
    invalidated: Set<String>,
  ): FeatureTaskRuntimeReadinessCheckResult? {
    val prior = existing.checkResults.lastOrNull { it.checkId == check.checkId }
    return when {
      check.checkId in invalidated -> null
      existing.baseRefSha != identity.baseRefSha || existing.headSha != identity.headSha -> null
      prior == null -> null
      prior.status != FeatureTaskRuntimeReadinessCheckStatus.PASSED || prior.exitCode != 0 -> null
      else -> prior
    }
  }

  private fun executeCheck(
    request: ReadinessCommitPushSettleRequest,
    check: ReadinessSelectedCheck,
  ): FeatureTaskRuntimeReadinessCheckResult {
    val pluginResult = prCheckRunner.run(check.command, request.repoRoot)
    return FeatureTaskRuntimeReadinessCheckResult(
      checkId = check.checkId,
      command = check.command,
      exitCode = pluginResult.exitCode,
      status =
        if (pluginResult.exitCode == 0) {
          FeatureTaskRuntimeReadinessCheckStatus.PASSED
        } else {
          FeatureTaskRuntimeReadinessCheckStatus.FAILED
        },
    )
  }

  private fun blocked(reason: String): ReadinessCommitPushSettleResult.Blocked =
    ReadinessCommitPushSettleResult.Blocked(
      reason = reason,
      lastResumableStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    )

  private fun persistEvidence(
    workflowId: String,
    evidence: FeatureTaskRuntimeReadinessEvidence,
    seam: String,
  ): Boolean =
    runCatching {
      readinessEvidence.persistReadinessEvidence(workflowId, evidence)
    }.onFailure { error ->
      recordDegradation(seam, "Could not persist readiness evidence: ${error.message.orEmpty()}")
    }.isSuccess

  private fun identityMismatchReason(
    captured: FeatureTaskRuntimeReadinessEvidence,
    current: ReadinessTreeIdentity,
  ): String =
    "Readiness identity is stale: source tree captured '${captured.sourceTreeSha}' vs current " +
      "'${current.sourceTreeSha}'; base captured '${captured.baseRefSha}' vs current " +
      "'${current.baseRefSha}'; head captured '${captured.headSha}' vs current '${current.headSha}'."

  private fun recordDegradation(
    seam: String,
    reason: String,
  ) {
    emitFeatureTaskRuntimeEventSafely(diagnostics, "readiness-gate-$seam") {
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, reason)
    }
  }
}
