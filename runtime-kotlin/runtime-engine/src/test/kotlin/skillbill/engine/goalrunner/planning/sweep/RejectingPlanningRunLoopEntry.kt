package skillbill.engine.goalrunner.planning.sweep

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.featureTaskRuntimeRunLoopStepBinding
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement

internal class RejectingPlanningRunLoopEntry(
  private val phase: String,
  private val kind: RequiredPhaseWriteKind,
) : FeatureTaskRuntimeRunLoopEntry() {
  val terminalReasons = mutableListOf<String?>()

  override fun run(
    context: FeatureTaskRuntimeRunLoopContext,
    beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
  ): FeatureTaskRuntimeRunReport {
    val delegate = context.runState
    val records = rejecting(delegate.records)
    val intercepted =
      object : PhaseRunState by delegate {
        override val records = records

        override fun step(run: PhaseRun): PhaseAcceptedStepExecution {
          stepBinding.beginStepBinding(run)
          return featureTaskRuntimeRunLoopStepBinding(
            skillbill.engine.featuretask.slot.attempt.phaseAttemptCollaborationScope(
              PhaseAttemptRunHost(run.request, this, run.phaseId, this),
            ),
            run,
          )
        }

        override fun fanOut(stepId: String): PhaseRunFanOut {
          val fanOut = delegate.fanOut(stepId)
          return object : PhaseRunFanOut by fanOut {
            override fun unitState(
              unitId: Int,
              outputSink: AgentRunOutputSink,
            ): PhaseAcceptedStepExecution = fanOut.unitState(unitId, outputSink)
          }
        }
      }
    return super.run(context.copy(runState = intercepted), beforeDrive)
  }

  private fun rejecting(delegate: PhaseRunRecords): PhaseRunRecords =
    object : PhaseRunRecords by delegate {
      override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest) {
        if (request.phaseId == phase && kind == RequiredPhaseWriteKind.START) {
          throw RequiredPhaseWriteRejected(kind, request.workflowId, phase, request.attemptCount)
        }
        delegate.recordRequiredPhaseStart(request)
      }

      override fun recordPhaseBriefing(
        workflowId: String,
        briefing: FeatureTaskRuntimePhaseLaunchBriefing,
        sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
        attempt: Int,
      ) {
        if (briefing.phaseId == phase && kind == RequiredPhaseWriteKind.BRIEFING) {
          throw RequiredPhaseWriteRejected(kind, workflowId, phase, attempt)
        }
        delegate.recordPhaseBriefing(workflowId, briefing, sharedEvidenceMeasurement, attempt)
      }

      override fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean {
        if (request.phaseId == phase && request.status == "blocked") terminalReasons += request.blockedReason
        return delegate.recordPhaseState(request)
      }
    }
}
