package skillbill.engine.featuretask.runloop.output

import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.GoalReviewPhaseCompletionRequest
import skillbill.engine.featuretask.runloop.core.BlockAndPersistArgs
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseReviewPersistenceArgs
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.RuntimeOwnedPersistenceFailureCode
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence

internal data class ReviewOutputPersistenceContext(
  val request: FeatureTaskRuntimeRunFacts,
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val transitions: FeatureTaskRuntimeRunTransitionOwner,
  val session: FeatureTaskRuntimeRunSessionObservations,
  val recorder: PhaseRunRecords,
  val observability: FeatureTaskRuntimeRunObservability,
  val goalContinuationRecorder: PhaseRunGoal,
)

internal fun isGoalReviewRun(
  run: PhaseRun,
  state: FeatureTaskRuntimeProgressSnapshotAccess,
): Boolean = state.resumeRules(run.phaseId).tracksReviewPasses && isGoalContinuationRun(run.request)

object FeatureTaskRuntimeRunLoopReviewCompletion {
  internal fun ReviewOutputPersistenceContext.persistStandaloneReviewCompletion(
    args: PhaseReviewPersistenceArgs,
    outputText: String,
    acceptedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): PhaseOutcome? {
    val run = args.run
    val iteration = args.iteration
    val observability = args.observability
    val persisted =
      try {
        recordStandaloneReviewCompletion(args, outputText, acceptedOutput)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.code == RuntimeOwnedPersistenceFailureCode.FACT_UNAVAILABLE)
        return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
          state,
          transitions,
          recorder,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason =
              "Runtime-owned review settlement could not establish its persistence fact: " +
                error.message.orEmpty(),
            observability = observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          ),
        )
      }
    return if (persisted) {
      null
    } else {
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        state,
        transitions,
        recorder,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned review settlement could not be persisted.",
          observability = observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
  }

  private fun ReviewOutputPersistenceContext.recordStandaloneReviewCompletion(
    args: PhaseReviewPersistenceArgs,
    outputText: String,
    acceptedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): Boolean {
    val phaseState =
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        request,
        state,
        goalContinuationRecorder,
        PhaseStateRequestArgs(
          write =
            PhaseStateWriteArgs(
              run = args.run,
              iteration = args.iteration,
              status = STATUS_COMPLETED,
              finished = true,
              outputArtifact = outputText,
            ),
          extras =
            PhaseStateRequestAttachments(
              fileManifest = args.fileManifest,
              normalizedOutput = acceptedOutput,
              repairEvidence = null,
              reviewRunId = state.phase(args.run.phaseId).record?.reviewRunId,
            ),
        ),
      )
    return transitions.persistAuthoritativePhaseCompletion(
      recorder = recorder,
      phaseState = phaseState,
      inMemoryOutput =
        FeatureTaskRuntimePhaseOutput(
          args.run.phaseId,
          args.iteration,
          outputText,
          acceptedOutput,
          null,
        ),
    )
  }

  internal fun ReviewOutputPersistenceContext.persistGoalReviewCompletion(
    args: PhaseReviewPersistenceArgs,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): PhaseOutcome? {
    val run = args.run
    val iteration = args.iteration
    val observability = args.observability
    val fileManifest = args.fileManifest
    val completion = goalReviewPhaseCompletionRequest(args, normalizedOutput, repairEvidence)
    val completed =
      runCatching {
        transitions.persistGoalReviewPhaseCompletion(
          recorder = recorder,
          completion = completion,
        )
      }.getOrElse { error ->
        val inPhase =
          phaseBlockArgs(
            run,
            iteration,
            "Goal-subtask review could not atomically persist its pass and completed phase: " +
              error.message.orEmpty(),
            observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
          )
        return FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersist(
          state,
          transitions,
          recorder,
          goalContinuationRecorder,
          BlockAndPersistArgs(
            run = inPhase.run,
            attemptCount = inPhase.attemptCount,
            reason = inPhase.reason,
            observability = inPhase.observability,
            loopId = inPhase.run.reentry?.loopId,
            edgeIteration = inPhase.run.reentry?.edgeIteration,
            failureDisposition = inPhase.failureDisposition,
            payload = inPhase.payload,
          ),
        )
      }
    return if (completed) {
      null
    } else {
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        state,
        transitions,
        recorder,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Goal-subtask review could not atomically persist its reserved pass and completed phase.",
          observability = observability,
          payload = BlockAndPersistPayload(fileManifest = fileManifest),
        ),
      )
    }
  }

  private fun ReviewOutputPersistenceContext.goalReviewPhaseCompletionRequest(
    args: PhaseReviewPersistenceArgs,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): GoalReviewPhaseCompletionRequest {
    val outputText = normalizedOutput.canonicalJson
    val outputMap = normalizedOutput.envelopeWireMap()
    val recordedVerdicts = recorder.recordedFindingVerdicts(outputMap)
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
    return GoalReviewPhaseCompletionRequest(
      phaseState =
        FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
          request,
          state,
          goalContinuationRecorder,
          PhaseStateRequestArgs(
            write =
              PhaseStateWriteArgs(
                run = args.run,
                iteration = args.iteration,
                status = STATUS_COMPLETED,
                finished = true,
                outputArtifact = outputText,
              ),
            extras =
              PhaseStateRequestAttachments(
                fileManifest = args.fileManifest,
                normalizedOutput = normalizedOutput,
                repairEvidence = repairEvidence,
              ),
          ),
        ),
      verdict = outcome.verdict,
      unresolvedFindingCount = outcome.unresolvedFindingCount,
      findings = findings,
      rawReviewResult = outputText,
      blockerDispositions =
        GoalSubtaskReviewSummaryReducer.blockerDispositions(
          outputMap,
          FeatureTaskRuntimeRunLoopPhaseBlocking.priorBlockerFindingIds(request, goalContinuationRecorder),
        ),
      commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(outputMap),
    )
  }
}
