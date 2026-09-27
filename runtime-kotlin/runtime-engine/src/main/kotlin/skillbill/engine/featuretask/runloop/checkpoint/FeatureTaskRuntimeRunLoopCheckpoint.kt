package skillbill.engine.featuretask.runloop.checkpoint

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.checkpoint.CheckpointScopePreparation
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMetadata
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointScope
import skillbill.engine.featuretask.lifecycle.checkpoint.goalScopedBaselinePaths
import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.engine.featuretask.lifecycle.checkpoint.phaseWrittenPaths
import skillbill.engine.featuretask.lifecycle.continuation.appendRemediationRollbackDegradationEvidence
import skillbill.engine.featuretask.lifecycle.remediation.RemediationDegradationSignal
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskCommitHeadState
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskCommitResolver
import skillbill.engine.featuretask.lifecycle.subtask.SubtaskCommitPreservationRequest
import skillbill.engine.featuretask.lifecycle.subtask.decide
import skillbill.engine.featuretask.lifecycle.subtask.writeSubtaskCommitPreservingHistory
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointDecision
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runloop.core.CheckpointCommitMessageArgs
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.RecordCheckpointIdentityArgs
import skillbill.engine.featuretask.runloop.core.SubtaskCommitLedgerState
import skillbill.engine.featuretask.runloop.core.isFeatureSpecPathForIssue
import skillbill.engine.featuretask.runloop.core.reconcileCheckpointPathInventory
import skillbill.engine.featuretask.runloop.core.remediationCheckpointBlockedReason
import skillbill.engine.featuretask.runloop.output.INVENTORY_EXTENDING_PHASES
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.persistence.task.runtime.checkpoint.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.persistence.task.runtime.checkpoint.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

object FeatureTaskRuntimeRunLoopCheckpoint {
  internal fun concurrentlyModifiedOwnedPaths(
    request: FeatureTaskRuntimeRunRequest,
    session: FeatureTaskRuntimeRunLoopSession,
    phaseGates: FeatureTaskRuntimePhaseGates,
    phaseId: String,
    ownedPaths: List<String>,
  ): List<String> {
    val captured = session.phaseContentIdentitiesFor(phaseId)
    if (captured.isEmpty()) return emptyList()
    val current = phaseGates.gitOperations.pathContentIdentities(request.repoRoot, ownedPaths)
    if (current !is WorkflowPathContentIdentitiesResult.Resolved) return emptyList()
    val now = current.identities
    return captured.filter { (path, identity) -> path in now && now[path] != identity }.keys.sorted()
  }

  internal fun blockCheckpointScope(
    context: FeatureTaskRuntimeRunLoopContext,
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (
      String,
      String,
    ) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? {
    with(context) {
      with(FeatureTaskRuntimeRunLoopCheckpoint) {
        FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(context, precedingPhaseId, branch, error, blockedReason)
      }
      return null
    }
  }

  internal fun checkpointWorktreeDelta(
    request: FeatureTaskRuntimeRunRequest,
    phaseGates: FeatureTaskRuntimePhaseGates,
    baselineOwnedPaths: List<String>,
  ): List<String>? {
    val owned = phaseGates.gitOperations.repositoryOwnedPaths(request.repoRoot)
    if (owned !is WorkflowGitNameListResult.Listed) return null
    val baseline = baselineOwnedPaths.toSet()
    return owned.names
      .map(String::trim)
      .filter(String::isNotBlank)
      .filterNot { it in baseline }
      .filterNot(::isRuntimePrivatePath)
      .distinct()
      .sorted()
  }

  internal fun resolveCheckpointScope(
    context: FeatureTaskRuntimeRunLoopContext,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (
      String,
      String,
    ) -> String,
  ): FeatureTaskRuntimeCheckpointDecision? {
    with(context) {
      val preparation =
        FeatureTaskRuntimeRunLoopCheckpoint.prepareCheckpointScope(
          context,
          precedingPhaseId,
          branch,
          blockedReason,
        ) ?: return null
      val ownedInventory = checkpointOwnedInventory(request, preparation)
      val resolved = recorder.loadResolvedBranch(request.workflowId)
      persistOwnedInventory(request, recorder, ownedInventory, resolved?.workflowOwnedPaths.orEmpty())
      session.markCheckpointOwnershipDecided()
      return FeatureTaskRuntimeCheckpointScope.decide(
        FeatureTaskRuntimeCheckpointScopeInput(
          issueKey = request.issueKey,
          ownedPaths = ownedInventory,
          phaseIntroducedPaths = preparation.phaseWritten,
          worktreeDeltaPaths = preparation.worktreeDelta,
          foreignStagedPaths = preparation.stagedPaths,
          concurrentlyModifiedOwnedPaths =
            FeatureTaskRuntimeRunLoopCheckpoint
              .concurrentlyModifiedOwnedPaths(request, session, phaseGates, precedingPhaseId, ownedInventory),
          deletedPaths = preparation.deletedPaths,
          workflowId = request.workflowId,
        ),
      )
    }
  }

  internal fun checkpointDeletedPaths(
    request: FeatureTaskRuntimeRunRequest,
    phaseGates: FeatureTaskRuntimePhaseGates,
  ): List<String> {
    val status = phaseGates.gitOperations.worktreeStatus(request.repoRoot)
    if (status !is WorkflowGitOperationResult.Ok) return emptyList()
    return FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(status.value.orEmpty())
  }

  internal fun absorbableDeletedPaths(
    deleted: List<String>,
    ownedOrIntroduced: List<String>,
  ): List<String> {
    if (deleted.isEmpty() || ownedOrIntroduced.isEmpty()) return emptyList()
    val anchors =
      ownedOrIntroduced.map { path -> path.substringBeforeLast('/', missingDelimiterValue = path) }
        .filter(String::isNotBlank)
        .distinct()
    return deleted.filter { removed ->
      val parent = removed.substringBeforeLast('/', missingDelimiterValue = removed)
      anchors.any { anchor ->
        parent == anchor ||
          anchor.startsWith("$parent/") ||
          parent.startsWith("$anchor/")
      }
    }
  }

  internal fun mayExtendOwnedInventory(phaseId: String): Boolean = phaseId in INVENTORY_EXTENDING_PHASES

  internal fun writingPhaseIntroducedPaths(
    request: FeatureTaskRuntimeRunRequest,
    recorder: FeatureTaskRuntimePhaseRecorder,
    diagnostics: RuntimeDiagnostics,
    worktreeDelta: List<String>,
  ): List<String> {
    val records =
      recorder.loadPhaseRecords(
        request.workflowId,
      ).orEmpty()
    val writingRecords = INVENTORY_EXTENDING_PHASES.mapNotNull { records[it] }
    if (writingRecords.isEmpty()) {
      if (worktreeDelta.isNotEmpty()) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Feature-task-runtime checkpoint has no durable file manifest for any writing phase; " +
            "the whole working-tree delta is treated as this workflow's own writes.",
        )
      }
      return worktreeDelta
    }
    val introduced = writingRecords.flatMap { it.fileManifestIntroduced + it.fileManifestAfter }.distinct()
    return skillbill.engine.featuretask.lifecycle.checkpoint.phaseWrittenPaths(worktreeDelta, introduced)
  }

  internal fun phaseWrittenPaths(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    worktreeDelta: List<String>,
    persistedInventory: List<String>,
  ): List<String> {
    with(context) {
      val record =
        recorder.loadPhaseRecords(
          request.workflowId,
        )?.get(phaseId)
      if (record == null) {
        if (worktreeDelta.isNotEmpty()) {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Feature-task-runtime checkpoint for phase '$phaseId' has no durable file manifest; " +
              "the whole working-tree delta is treated as the phase's own writes.",
          )
        }
        return worktreeDelta
      }
      val owned = persistedInventory.toSet()
      val ownedStillDirty = record.fileManifestAfter.filter { it in owned }
      val manifest = (record.fileManifestIntroduced + ownedStillDirty).distinct()
      return skillbill.engine.featuretask.lifecycle.checkpoint.phaseWrittenPaths(worktreeDelta, manifest)
    }
  }

  internal fun persistOwnedInventory(
    request: FeatureTaskRuntimeRunRequest,
    recorder: FeatureTaskRuntimePhaseRecorder,
    inventory: List<String>,
    persisted: List<String>,
  ) {
    if (inventory.sorted() == persisted.sorted()) return
    recorder.recordWorkflowOwnedPaths(request.workflowId, inventory)
  }

  private fun stagedCheckpointPaths(
    context: FeatureTaskRuntimeRunLoopContext,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (
      String,
      String,
    ) -> String,
  ): List<String>? {
    with(context) {
      val names =
        when (val staged = phaseGates.gitOperations.stagedPaths(request.repoRoot)) {
          is WorkflowGitNameListResult.Listed -> staged.names
          is WorkflowGitNameListResult.Failed -> {
            FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpointScope(
              context,
              precedingPhaseId,
              branch,
              staged.error,
              blockedReason,
            )
            return null
          }
        }
      return names
        .map(String::trim)
        .filter(String::isNotBlank)
    }
  }

  internal fun prepareCheckpointScope(
    context: FeatureTaskRuntimeRunLoopContext,
    precedingPhaseId: String,
    branch: String,
    blockedReason: (
      String,
      String,
    ) -> String,
  ): CheckpointScopePreparation? {
    with(context) {
      val resolved = recorder.loadResolvedBranch(request.workflowId)
      val worktreeDelta =
        FeatureTaskRuntimeRunLoopCheckpoint.checkpointWorktreeDelta(
          request,
          phaseGates,
          goalScopedBaselinePaths(
            resolved?.baselineOwnedPathsForCheckpoint().orEmpty(),
            recorder.goalStartBaselinePaths(request),
          ),
        )
          ?: run {
            FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpointScope(
              context,
              precedingPhaseId,
              branch,
              "the owned-path inventory could not be read",
              blockedReason,
            )
            return null
          }
      val stagedPaths =
        FeatureTaskRuntimeRunLoopCheckpoint.stagedCheckpointPaths(
          context,
          precedingPhaseId,
          branch,
          blockedReason,
        ) ?: return null
      val persistedOwned = resolved?.workflowOwnedPaths.orEmpty()
      val evictedFeatureSpecs =
        persistedOwned
          .filter { path -> isFeatureSpecPathForIssue(path, request.issueKey) }
          .toSet()
      val phaseWritten =
        FeatureTaskRuntimeRunLoopCheckpoint.phaseWrittenPaths(
          context,
          precedingPhaseId,
          worktreeDelta,
          persistedOwned,
        )
          .filterNot { it in evictedFeatureSpecs }
      val writingIntroduced = writingIntroducedPaths(context, worktreeDelta)
      val seedOwned =
        (
          resolved?.workflowOwnedPaths.orEmpty() +
            phaseWritten
              .takeIf { FeatureTaskRuntimeRunLoopCheckpoint.mayExtendOwnedInventory(precedingPhaseId) }
              .orEmpty() +
            writingIntroduced
        ).distinct()
      val deletedPaths =
        absorbableDeletedPaths(
          deleted = checkpointDeletedPaths(request, phaseGates),
          ownedOrIntroduced = seedOwned + phaseWritten,
        )
      return CheckpointScopePreparation(
        worktreeDelta = worktreeDelta,
        stagedPaths = stagedPaths,
        phaseWritten = phaseWritten,
        writingIntroduced = writingIntroduced,
        seedOwned = seedOwned,
        deletedPaths = deletedPaths,
      )
    }
  }

  private fun writingIntroducedPaths(
    context: FeatureTaskRuntimeRunLoopContext,
    worktreeDelta: List<String>,
  ): List<String> =
    with(context) {
      writingPhaseIntroducedPaths(
        request,
        recorder,
        diagnostics,
        worktreeDelta,
      )
    }

  internal fun checkpointOwnedInventory(
    request: FeatureTaskRuntimeRunRequest,
    preparation: CheckpointScopePreparation,
  ): List<String> =
    reconcileCheckpointPathInventory(
      repoRoot = request.repoRoot,
      issueKey = request.issueKey,
      specReference = request.runInvariants.specReference,
      workflowId = request.workflowId,
      paths =
        (preparation.seedOwned + preparation.deletedPaths)
          .filterNot { path -> isFeatureSpecPathForIssue(path, request.issueKey) },
    )

  internal fun checkpointIdentitiesForRollback(
    context: FeatureTaskRuntimeRunLoopContext,
    commitSha: String,
  ): List<FeatureTaskRuntimeCheckpointIdentity> {
    with(context) {
      require(commitSha.isNotBlank()) { "rollback requires a non-blank commit sha" }
      val subtaskId =
        request.goalContinuation?.subtaskId?.toString()
          ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
      return runCatching {
        recorder.loadCheckpointIdentities(request.workflowId)
      }.fold(
        onSuccess = { loaded -> loaded.orEmpty() },
        onFailure = { error ->
          recordRemediationRollbackDegradation(
            context,
            seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
            valueUsed = request.workflowId,
            valueExpected = "checkpoint identities for rollback",
            cause =
              "loadCheckpointIdentities failed: " +
                error.message.orEmpty().ifBlank { error::class.simpleName.orEmpty() },
          )
          emptyList()
        },
      )
        .filter { it.issueKey == request.issueKey && it.subtaskId == subtaskId }
        .sortedBy { it.sequenceNumber }
    }
  }

  internal fun recordRemediationRollbackDegradation(
    context: FeatureTaskRuntimeRunLoopContext,
    seam: String,
    valueUsed: String,
    valueExpected: String,
    cause: String,
  ) {
    with(context) {
      goalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
        workflowId = request.workflowId,
        signal =
          RemediationDegradationSignal(
            seam = seam,
            valueUsed = valueUsed,
            valueExpected = valueExpected,
            cause = cause,
          ),
      )
    }
  }

  internal fun subtaskCommitIdentity(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeSubtaskCommitIdentity =
    FeatureTaskRuntimeSubtaskCommitIdentity(
      issueKey = request.issueKey,
      subtaskId = request.goalContinuation?.subtaskId?.toString() ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
    )

  internal fun checkpointCommitMessage(
    request: FeatureTaskRuntimeRunRequest,
    state: FeatureTaskRuntimeRunState,
    diagnostics: RuntimeDiagnostics,
    args: CheckpointCommitMessageArgs,
  ): String {
    val branch = args.branch
    val phaseId = args.phaseId
    val loopId = args.loopId
    val identity = args.identity
    val intent = args.intent
    val subtaskName = request.goalContinuation?.subtaskName?.trim()?.takeIf(String::isNotBlank)
    if (subtaskName == null && request.goalContinuation != null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        FeatureTaskRuntimeCheckpointMessage.missingSubtaskNameRecord(identity.issueKey, identity.subtaskId),
      )
    }
    return FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = request.issueKey,
      subtaskName = subtaskName,
      metadata =
        FeatureTaskRuntimeCheckpointMetadata(
          phaseId = phaseId,
          loopId = loopId,
          generation = FeatureTaskRuntimeRunLoopCheckpoint.checkpointGeneration(state, loopId),
          branch = branch,
          intent = intent,
        ),
      identity = identity,
    )
  }

  internal fun subtaskCommitLedgerState(
    request: FeatureTaskRuntimeRunRequest,
    recorder: FeatureTaskRuntimePhaseRecorder,
    diagnostics: RuntimeDiagnostics,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): SubtaskCommitLedgerState {
    val read =
      runCatching {
        recorder.loadCheckpointIdentities(
          request.workflowId,
        )
      }
    val identities = read.getOrNull()
    val cause =
      read.exceptionOrNull()
        ?.let { "the checkpoint-identity store could not be read (${it.message ?: it::class.simpleName})" }
        ?: "no workflow row recorded any checkpoint identity for this run".takeIf { identities == null }
    if (cause != null) {
      val ledgerRecord = FeatureTaskRuntimeRunLoopCheckpoint.ledgerUnavailableRecord(identity, cause)
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, ledgerRecord)
      return SubtaskCommitLedgerState(commitSha = null, nextSequenceNumber = 0)
    }
    val recorded = requireNotNull(identities)
    return SubtaskCommitLedgerState(
      commitSha =
        recorded
          .filter { it.issueKey == identity.issueKey && it.subtaskId == identity.subtaskId }
          .maxByOrNull { it.sequenceNumber }
          ?.commitSha,
      nextSequenceNumber = (recorded.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
    )
  }

  internal fun ledgerUnavailableRecord(
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    cause: String,
  ): String =
    "seam=FeatureTaskRuntimeRunLoop.subtaskCommitLedgerState value_used='no durable pointer, sequence 0' " +
      "value_expected=the recorded checkpoint-identity ledger for '${identity.issueKey}/${identity.subtaskId}' " +
      "cause=$cause"

  internal fun writeSubtaskCommit(
    context: FeatureTaskRuntimeRunLoopContext,
    branch: String,
    message: String,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  ): WorkflowGitOperationResult {
    with(context) {
      val ledger =
        FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitLedgerState(
          request,
          recorder,
          diagnostics,
          identity,
        )
      val headSha =
        phaseGates.gitOperations.headCommitSha(request.repoRoot)
          .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.trim()?.takeIf(String::isNotBlank)
      val decision =
        FeatureTaskRuntimeSubtaskCommitResolver.decide(
          identity = identity,
          durableCommitSha = ledger.commitSha,
          head =
            FeatureTaskRuntimeSubtaskCommitHeadState(
              sha = headSha,
              commitMessage =
                if (ledger.commitSha == null && headSha != null) {
                  headCommitMessageOrNull(
                    request,
                    phaseGates,
                  )
                } else {
                  null
                },
              isUnpushed = branchHasUnpushedCommits(request, phaseGates, branch),
            ),
          sequenceNumber = ledger.nextSequenceNumber,
        )
      return phaseGates.gitOperations.writeSubtaskCommitPreservingHistory(
        SubtaskCommitPreservationRequest(
          repoRoot = request.repoRoot,
          decision = decision,
          identity = identity,
          message = message,
          allowUnchangedIndex = false,
          record = { record -> RuntimeDiagnosticsBestEffortWarning.record(diagnostics, record) },
        ),
      )
    }
  }

  internal fun headCommitMessageOrNull(
    request: FeatureTaskRuntimeRunRequest,
    phaseGates: FeatureTaskRuntimePhaseGates,
  ): String? =
    phaseGates.gitOperations.headCommitMessage(request.repoRoot)
      .takeIf { it is WorkflowGitOperationResult.Ok }?.value

  internal fun branchHasUnpushedCommits(
    request: FeatureTaskRuntimeRunRequest,
    phaseGates: FeatureTaskRuntimePhaseGates,
    branch: String,
  ): Boolean {
    val unpushed = phaseGates.gitOperations.localBranchHasUnpushedCommits(request.repoRoot, branch)
    return unpushed is WorkflowGitOperationResult.Ok &&
      unpushed.value.orEmpty().trim().equals("true", ignoreCase = true)
  }

  internal fun withIndexRestoreOutcome(
    request: FeatureTaskRuntimeRunRequest,
    phaseGates: FeatureTaskRuntimePhaseGates,
    error: String,
    ownedPaths: List<String>,
    snapshot: WorkflowGitIndexSnapshot,
  ): String {
    val restored = phaseGates.gitOperations.restoreIndexState(request.repoRoot, ownedPaths, snapshot)
    return if (restored is WorkflowGitOperationResult.Ok) {
      "$error; the pre-checkpoint index was restored and the working tree is unchanged"
    } else {
      "$error; the pre-checkpoint index could NOT be restored (${restored.error}) — inspect " +
        "`git status` before committing anything yourself"
    }
  }

  internal fun checkpointGeneration(
    state: FeatureTaskRuntimeRunState,
    loopId: String?,
  ): Int =
    loopId?.let {
      state.edgeIterationCount(it)
    } ?: 0

  internal fun recordCheckpointIdentity(
    context: FeatureTaskRuntimeRunLoopContext,
    args: RecordCheckpointIdentityArgs,
  ): Boolean {
    with(context) {
      val precedingPhaseId = args.precedingPhaseId
      val branch = args.branch
      val loopId = args.loopId
      val ownedPaths = args.ownedPaths
      val parentSha = args.parentSha
      val commitSha = args.commitSha
      val blockedReason = args.blockedReason
      val recorded =
        runCatching {
          recorder.appendCheckpointIdentity(
            AppendCheckpointIdentityArgs(
              workflowId = request.workflowId,
              issueKey = request.issueKey,
              subtaskId =
                request.goalContinuation?.subtaskId?.toString()
                  ?: FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
              branch = branch,
              phaseId = precedingPhaseId,
              loopId = loopId,
              generation = checkpointGeneration(state, loopId),
              parentSha = parentSha,
              ownedPaths = ownedPaths,
              commitSha = commitSha,
            ),
          )
        }
      return if (recorded.getOrDefault(false)) {
        true
      } else {
        with(FeatureTaskRuntimeRunLoopCheckpoint) {
          FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
            context,
            precedingPhaseId,
            branch,
            "checkpoint commit '$commitSha' was created but its durable identity record could not be " +
              "written (${recorded.exceptionOrNull()?.message ?: "the workflow row was absent"}), so the " +
              "commit cannot be attributed to this workflow's authority boundary",
            blockedReason,
          )
        }
      }
    }
  }

  internal fun remediationCheckpointBlockedReasonFor(): (String, String) -> String =
    { branch, error -> remediationCheckpointBlockedReason(branch, error) }

  internal fun blockCheckpoint(
    context: FeatureTaskRuntimeRunLoopContext,
    precedingPhaseId: String,
    branch: String,
    error: String,
    blockedReason: (
      String,
      String,
    ) -> String,
  ): Boolean {
    with(context) {
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
        request,
        state,
        session,
        precedingPhaseId,
        blockedReason(
          branch,
          error,
        ),
      )
      return false
    }
  }

  internal fun matchingBackwardEdge(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeBackwardEdge? =
    transitions.backwardEdges.firstOrNull { it.fromPhaseId == phaseId && it.triggeringVerdict == verdict }
}
