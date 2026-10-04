package skillbill.goalrunner

import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSubtaskAction
import skillbill.goalrunner.model.GoalRunnerSubtaskDecision
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskSchedulingResult
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection

object GoalRunnerQualityGateSelectionResolver {
  fun resolve(
    manifest: DecompositionManifest,
    subtaskId: Int,
  ): FeatureTaskRuntimeQualityGateSelection {
    val lastNonSkippedId =
      manifest.subtasks.lastOrNull {
        it.status.decompositionStatus() != DecompositionStatus.SKIPPED
      }?.id
    return if (lastNonSkippedId == subtaskId) {
      FeatureTaskRuntimeQualityGateSelection.VALIDATE
    } else {
      FeatureTaskRuntimeQualityGateSelection.BUILD
    }
  }
}

object GoalRunnerPlanner {
  fun selectNext(manifest: DecompositionManifest): GoalRunnerSelection {
    val intended =
      manifest.currentSubtaskIntent.subtaskId
        .takeIf { it > 0 }
        ?.let { id -> manifest.subtasks.firstOrNull { it.id == id } }
    val candidate =
      intended?.takeUnless {
        it.status.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
      }
        ?: manifest.subtasks.firstOrNull {
          it.status.decompositionStatus() == DecompositionStatus.IN_PROGRESS
        }
        ?: manifest.subtasks.firstOrNull { it.status.decompositionStatus() == DecompositionStatus.BLOCKED }
        ?: manifest.subtasks.firstOrNull {
          it.status.decompositionStatus() == DecompositionStatus.PENDING && dependenciesComplete(manifest, it)
        }
    return when {
      candidate == null -> {
        val blockedByDependency =
          manifest.subtasks.firstOrNull {
            it.status.decompositionStatus() == DecompositionStatus.PENDING
          }
        if (blockedByDependency == null) {
          GoalRunnerSelection.Done
        } else {
          GoalRunnerSelection.Blocked(
            subtask = blockedByDependency,
            reason = "Subtask ${blockedByDependency.id} is waiting for incomplete dependencies.",
          )
        }
      }
      candidate.status.decompositionStatus() == DecompositionStatus.PENDING &&
        !dependenciesComplete(manifest, candidate) ->
        GoalRunnerSelection.Blocked(
          subtask = candidate,
          reason = "Subtask ${candidate.id} is waiting for incomplete dependencies.",
        )
      else ->
        GoalRunnerSelection.Run(
          GoalRunnerSubtaskDecision(
            subtask = candidate,
            action =
              if (candidate.status.decompositionStatus() == DecompositionStatus.PENDING) {
                GoalRunnerSubtaskAction.START
              } else {
                GoalRunnerSubtaskAction.RESUME
              },
          ),
        )
    }
  }

  private fun dependenciesComplete(
    manifest: DecompositionManifest,
    subtask: DecompositionSubtask,
  ): Boolean {
    val subtasksById = manifest.subtasks.associateBy(DecompositionSubtask::id)
    return subtask.dependencies.all { dependency ->
      val dependencySubtask = subtasksById[dependency.subtaskId] ?: return@all false
      dependencySubtask.status.decompositionStatus() == DecompositionStatus.COMPLETE ||
        dependencySubtask.status.decompositionStatus() == DecompositionStatus.SKIPPED ||
        dependency.optional && dependency.skipped
    }
  }
}

object GoalRunnerWorkerSubtaskScheduler {
  fun scheduleQueuedRequests(
    manifest: DecompositionManifest,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
  ): GoalRunnerWorkerSubtaskSchedulingResult {
    var nextManifest = manifest
    val scheduledOutcomes =
      outcomes.map { outcome ->
        when (outcome) {
          is GoalRunnerWorkerSubtaskRequestOutcome.Queued -> {
            val duplicate =
              nextManifest.subtasks.any { subtask ->
                subtask.name.equals(outcome.request.name, ignoreCase = true) ||
                  subtask.specPath == outcome.request.specPath
              }
            if (duplicate) {
              GoalRunnerWorkerSubtaskRequestOutcome.Rejected(
                sourceStream = outcome.sourceStream,
                reason = GoalRunnerWorkerSubtaskRequestRejectionReason.DUPLICATE,
                message = "Worker subtask request duplicates already scheduled sibling work.",
              )
            } else {
              val subtask = outcome.request.toSubtask(nextManifest)
              nextManifest = nextManifest.copy(subtasks = nextManifest.subtasks + subtask)
              GoalRunnerWorkerSubtaskRequestOutcome.Accepted(outcome.request, subtask)
            }
          }
          is GoalRunnerWorkerSubtaskRequestOutcome.Accepted,
          is GoalRunnerWorkerSubtaskRequestOutcome.Rejected,
          is GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation,
          -> outcome
        }
      }
    return GoalRunnerWorkerSubtaskSchedulingResult(nextManifest.withParentStatusForWorkerRequests(), scheduledOutcomes)
  }

  private fun GoalRunnerWorkerSubtaskRequest.toSubtask(manifest: DecompositionManifest): DecompositionSubtask {
    val id = manifest.nextSubtaskId()
    val dependencies = normalizedDependencies(manifest).map(::DecompositionDependency)
    return DecompositionSubtask(
      id = id,
      name = name,
      specPath = specPath,
      status = DecompositionStatus.PENDING.wireValue,
      dependencies = dependencies,
    )
  }

  private fun GoalRunnerWorkerSubtaskRequest.normalizedDependencies(manifest: DecompositionManifest): List<Int> {
    val requestedDependencies =
      dependsOnSubtaskIds.ifEmpty {
        manifest.currentSubtaskIntent.subtaskId.takeIf { it > 0 }?.let(::listOf).orEmpty()
      }
    return requestedDependencies
      .filter { dependency -> manifest.subtasks.any { subtask -> subtask.id == dependency } }
      .distinct()
  }

  private fun DecompositionManifest.withParentStatusForWorkerRequests(): DecompositionManifest =
    if (status.decompositionStatus() == DecompositionStatus.COMPLETE) {
      copy(status = DecompositionStatus.IN_PROGRESS.wireValue)
    } else {
      this
    }
}

object GoalRunnerOutcomeReconciler {
  fun reconcile(
    subtaskId: Int,
    launchFacts: GoalRunnerLaunchFacts,
    storedOutcome: GoalRunnerStoredOutcome?,
  ): GoalRunnerReconciledOutcome =
    when {
      launchFacts.interrupted ->
        stop(
          reason = GoalRunnerStopReason.INTERRUPTED,
          blockedReason = "Subtask $subtaskId was interrupted before a terminal workflow-store outcome was written.",
          storedOutcome = storedOutcome,
          liveness = launchFacts.liveness,
        )
      launchFacts.timedOut ->
        stop(
          reason = GoalRunnerStopReason.TIMEOUT,
          blockedReason = "Subtask $subtaskId timed out before reaching a terminal workflow-store outcome.",
          storedOutcome = storedOutcome,
          liveness = launchFacts.liveness,
        )
      launchFacts.spawnFailed ->
        stop(
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason =
            launchFacts.stderrExcerpt
              ?.let { excerpt -> "Subtask $subtaskId could not start a fresh agent process — $excerpt" }
              ?: "Subtask $subtaskId could not start a fresh agent process.",
          storedOutcome = storedOutcome,
          liveness = launchFacts.liveness,
        )
      storedOutcome == null ->
        GoalRunnerReconciledOutcome.Stop(
          reason = GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
          blockedReason = noTerminalStoreOutcomeReason(subtaskId, launchFacts),
          workflowId = null,
          commitSha = null,
          lastResumableStep = "preplan",
          liveness = launchFacts.liveness,
        )
      !storedOutcome.suppressPr ->
        stop(
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason = "Subtask $subtaskId did not suppress per-subtask PR creation.",
          storedOutcome = storedOutcome,
          liveness = launchFacts.liveness,
        )
      else -> reconcileStoredOutcome(subtaskId, storedOutcome, launchFacts.liveness)
    }

  private fun reconcileStoredOutcome(
    subtaskId: Int,
    storedOutcome: GoalRunnerStoredOutcome,
    liveness: GoalRunnerLivenessSnapshot?,
  ): GoalRunnerReconciledOutcome =
    when (storedOutcome.status) {
      GoalRunnerTerminalStatus.COMPLETE -> completeOutcome(subtaskId, storedOutcome)
      GoalRunnerTerminalStatus.FAILED ->
        stop(
          reason = GoalRunnerStopReason.FAILED,
          blockedReason = storedOutcome.blockedReason.orEmpty().ifBlank { "Subtask $subtaskId failed." },
          storedOutcome = storedOutcome,
          liveness = liveness,
        )
      GoalRunnerTerminalStatus.BLOCKED ->
        stop(
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason = storedOutcome.blockedReason.orEmpty().ifBlank { "Subtask $subtaskId is blocked." },
          storedOutcome = storedOutcome,
          liveness = liveness,
        )
      GoalRunnerTerminalStatus.TIMEOUT ->
        stop(
          reason = GoalRunnerStopReason.TIMEOUT,
          blockedReason =
            storedOutcome.blockedReason.orEmpty()
              .ifBlank { "Subtask $subtaskId timed out before completion." },
          storedOutcome = storedOutcome,
          liveness = liveness,
        )
      GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME ->
        stop(
          reason = GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
          blockedReason =
            storedOutcome.blockedReason.orEmpty()
              .ifBlank { "Subtask $subtaskId has no terminal workflow-store outcome." },
          storedOutcome = storedOutcome,
          liveness = liveness,
        )

      GoalRunnerTerminalStatus.RECONCILABLE ->
        stop(
          reason = GoalRunnerStopReason.RECONCILED_RESUMABLE,
          blockedReason =
            storedOutcome.blockedReason.orEmpty().ifBlank {
              "Subtask $subtaskId was interrupted by a crashed child and reconciled to a resumable state; " +
                "resume the goal to continue from its last step."
            },
          storedOutcome = storedOutcome,
          liveness = liveness,
        )

      GoalRunnerTerminalStatus.PAUSED ->
        stop(
          reason = GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
          blockedReason =
            storedOutcome.blockedReason.orEmpty().ifBlank {
              "Subtask $subtaskId paused with an unresolved Blocker or Major after remediation; " +
                "choose retry_fix, accept_and_advance, or abandon_subtask, then resume the goal. " +
                "Location-bearing evidence: skill-bill goal findings --issue-key <KEY>."
            },
          storedOutcome = storedOutcome,
          liveness = liveness,
        )
    }

  private fun completeOutcome(
    subtaskId: Int,
    storedOutcome: GoalRunnerStoredOutcome,
  ): GoalRunnerReconciledOutcome {
    val commitSha = storedOutcome.commitSha
    return if (commitSha.isNullOrBlank()) {
      stop(
        reason = GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
        blockedReason = "Subtask $subtaskId completed without commit_sha in the workflow store.",
        storedOutcome = storedOutcome,
      )
    } else {
      GoalRunnerReconciledOutcome.Complete(
        workflowId = storedOutcome.workflowId,
        commitSha = commitSha,
        lastResumableStep = storedOutcome.lastResumableStep.orEmpty().ifBlank { "commit_push" },
      )
    }
  }

  private fun noTerminalStoreOutcomeReason(
    subtaskId: Int,
    launchFacts: GoalRunnerLaunchFacts,
  ): String {
    val exitStatus = launchFacts.exitStatus
    val lead =
      when {
        exitStatus != null && exitStatus != 0 ->
          "Subtask $subtaskId finished without a terminal workflow-store outcome: the child process " +
            "exited with status $exitStatus before its workflow row reached a terminal state (complete " +
            "or durably blocked). A non-zero exit means the child errored out rather than stopping cleanly."
        else ->
          "Subtask $subtaskId finished without a terminal workflow-store outcome: the child process " +
            "exited cleanly (status ${exitStatus ?: "unknown"}) but its workflow row never reached a " +
            "terminal state. A clean exit without a terminal store-write usually means a model/usage " +
            "limit or a missed terminal MCP call before persisting."
      }
    val guidance = " Inspect the child workflow and its transcript to confirm, then resume from last_resumable_step."
    val stderrDetail =
      launchFacts.stderrExcerpt
        ?.takeIf(String::isNotBlank)
        ?.let { excerpt -> " Child stderr (head+tail):\n$excerpt" }
        .orEmpty()
    return "$lead$guidance$stderrDetail"
  }

  private fun stop(
    reason: GoalRunnerStopReason,
    blockedReason: String,
    storedOutcome: GoalRunnerStoredOutcome?,
    liveness: GoalRunnerLivenessSnapshot? = null,
  ): GoalRunnerReconciledOutcome.Stop =
    GoalRunnerReconciledOutcome.Stop(
      reason = reason,
      blockedReason = blockedReason,
      workflowId = storedOutcome?.workflowId,
      commitSha = storedOutcome?.commitSha,
      lastResumableStep = storedOutcome?.lastResumableStep.orEmpty().ifBlank { "preplan" },
      liveness = liveness,
    )
}
