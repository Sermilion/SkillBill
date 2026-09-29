package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputBlocked
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputReady
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassCarryForward
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassInFlight
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReserved
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeScopedReviewBaseline
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.error.core.DatabaseBusyError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import java.nio.file.Path

internal sealed interface InlineReviewPrepared {
  data class Ready(
    val input: GoalSubtaskReviewInput,
  ) : InlineReviewPrepared

  data class Settled(
    val outcome: PhaseOutcome,
  ) : InlineReviewPrepared
}

internal object InlineReviewPreparation {
  fun prepare(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
  ): InlineReviewPrepared =
    when {
      run.request.reviewInvocation != null -> preparePhaseReview(run, context, state)
      isGoalContinuationRun(run.request) -> reserveGoalReview(run, context, state)
      else -> prepareStandaloneReview(run, context, state)
    }

  private fun preparePhaseReview(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
  ): InlineReviewPrepared {
    val head = context.gitOperations.headCommitSha(run.request.repoRoot)
    if (head !is WorkflowGitOperationResult.Ok) {
      return blocked(state, "Phase review could not resolve HEAD: ${head.error}")
    }
    val sha = head.value.trim()
    return InlineReviewPrepared.Ready(GoalSubtaskReviewInput(sha, sha, trackedDelta = "", ownedUntrackedPatches = ""))
  }

  fun goalReviewPreparationFailure(
    stage: String,
    error: Throwable,
  ): String {
    val location =
      error.stackTrace
        .firstOrNull { frame -> frame.className.startsWith("skillbill.") }
        ?.let { frame -> " at ${frame.className}.${frame.methodName}:${frame.lineNumber}" }
        .orEmpty()
    return "Goal-subtask review $stage failed$location: ${error.message.orEmpty()}"
  }

  fun goalReviewPreparationDisposition(error: Throwable): FeatureTaskRuntimeFailureDisposition =
    if (generateSequence(error, Throwable::cause).any { it is DatabaseBusyError }) {
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    } else {
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION
    }

  private fun prepareStandaloneReview(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
  ): InlineReviewPrepared {
    val resolved =
      state.resolvedBranch()
        ?: return blocked(state, "Standalone review is missing its durable resolved branch.")
    val reviewBaseSha =
      resolved.reviewBaseSha
        ?: return blocked(
          state,
          "Standalone review is missing the immutable review base captured before implementation.",
        )
    val gitOperations = context.gitOperations
    val repoRoot = run.request.repoRoot
    val result =
      gitOperations.buildGoalSubtaskReviewInput(
        repoRoot,
        FeatureTaskRuntimeScopedReviewBaseline.of(gitOperations, repoRoot, resolved, reviewBaseSha),
        resolved.branch,
      )
    return result.input?.let(InlineReviewPrepared::Ready)
      ?: blocked(state, result.error.ifBlank { "Standalone review input failed." })
  }

  private fun reserveGoalReview(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
  ): InlineReviewPrepared =
    runCatching { state.reserveReviewPass() }.fold(
      onSuccess = { reservation ->
        when (reservation) {
          GoalSubtaskReviewPassReservation.MissingState ->
            blocked(
              state,
              "Goal-subtask review state is missing; review_base_sha must be captured before implementation " +
                "and cannot be substituted.",
            )
          is GoalSubtaskReviewPassCarryForward -> settleCarriedForward(run, context, state)
          is GoalSubtaskReviewPassInFlight,
          is GoalSubtaskReviewPassReserved,
          -> buildGoalReviewInput(run, context, state)
        }
      },
      onFailure = { error ->
        blocked(state, goalReviewPreparationFailure("reservation", error), goalReviewPreparationDisposition(error))
      },
    )

  private fun buildGoalReviewInput(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
  ): InlineReviewPrepared =
    runCatching {
      val resolved = state.resolvedBranch()
      state.prepareGoalReviewInput(
        scopedUntrackedExclusions =
          resolved?.let {
            FeatureTaskRuntimeScopedReviewBaseline.untrackedExclusions(
              context.gitOperations,
              run.request.repoRoot,
              it,
            )
          },
        ownedPathspec = resolved?.workflowOwnedPaths.orEmpty(),
      )
    }.fold(
      onSuccess = { prepared ->
        when (prepared) {
          GoalSubtaskReviewInputPreparation.MissingState ->
            blocked(state, "Goal-subtask review state disappeared before review launch.")
          is GoalSubtaskReviewInputBlocked -> blocked(state, prepared.reason)
          is GoalSubtaskReviewInputReady -> InlineReviewPrepared.Ready(prepared.input)
        }
      },
      onFailure = { error ->
        blocked(
          state,
          goalReviewPreparationFailure("input persistence", error),
          goalReviewPreparationDisposition(error),
        )
      },
    )

  private fun settleCarriedForward(
    run: PhaseRun,
    context: PhaseReviewExecutionContext,
    state: PhaseReviewStepBinding,
  ): InlineReviewPrepared {
    val accepted =
      runCatching {
        val output = state.carriedForwardReviewResult() ?: throw MissingCarriedForwardGoalReviewResultException()
        context.outputValidator
          .validatePhaseOutput(output, sourceLabel = run.phaseId)
          .requireAcceptedOutput(run.phaseId)
      }.getOrElse { error ->
        val detail =
          if (error is MissingCarriedForwardGoalReviewResultException) {
            "missing."
          } else {
            "malformed: ${error.message.orEmpty()}"
          }
        val reason = "Goal-subtask review pass budget is exhausted but its durable raw review result is $detail"
        state.blockReviewPreparation(
          state.nextStepIteration(),
          reason,
          FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        )
        return InlineReviewPrepared.Settled(PhaseOutcome.blocked(reason))
      }
    val iteration = state.nextStepIteration()
    state.completeCarriedForwardReview(iteration, accepted)?.let { failure ->
      state.blockReviewPreparation(iteration, failure, FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE, accepted)
      return InlineReviewPrepared.Settled(PhaseOutcome.blocked(failure))
    }
    state.stepCompleted(iteration)
    return InlineReviewPrepared.Settled(PhaseOutcome.completed(completedOutput(run, iteration, accepted)))
  }

  private fun blocked(
    state: PhaseReviewStepBinding,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ): InlineReviewPrepared {
    state.blockReviewPreparation(1, reason, disposition)
    return InlineReviewPrepared.Settled(PhaseOutcome.blocked(reason))
  }
}

internal fun completedOutput(
  run: PhaseRun,
  iteration: Int,
  output: AcceptedFeatureTaskRuntimePhaseOutput,
): FeatureTaskRuntimePhaseOutput =
  FeatureTaskRuntimePhaseOutput(
    run.phaseId,
    iteration,
    output.normalizedOutput.canonicalJson,
    output.normalizedOutput,
    output.repairEvidence,
  )

internal fun reviewSpecPath(run: PhaseRun): Path? =
  if (run.request.reviewInvocation != null) null else Path.of(run.request.runInvariants.specReference)

object ReviewTargetResolver {
  fun resolve(
    requested: ReviewTarget?,
    porcelainStatus: String,
  ): ReviewTarget =
    requested ?: if (FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(porcelainStatus).isNotEmpty()) {
      ReviewTarget.Uncommitted
    } else {
      ReviewTarget.Commit(HEAD_REVISION)
    }

  private const val HEAD_REVISION: String = "HEAD"
}

class MissingCarriedForwardGoalReviewResultException :
  SkillBillRuntimeException(
    "Goal review result was not carried forward from the prior phase.",
  )
