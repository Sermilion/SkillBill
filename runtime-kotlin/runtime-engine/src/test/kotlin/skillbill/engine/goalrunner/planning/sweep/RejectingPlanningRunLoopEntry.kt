package skillbill.engine.goalrunner.planning.sweep

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindings
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.withRunState
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.attempt.phaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.engine.goalrunner.planning.state.GoalPlanningPhaseRunState
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement

internal class RejectingPlanningRunLoopEntry(
  private val phase: String,
  private val kind: RequiredPhaseWriteKind,
) : TestFeatureTaskRuntimeRunLoopEntry() {
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
          return FeatureTaskRuntimeRunLoopStepBindings.create(
            phaseAttemptLaunchCollaborationScope(
              PhaseAttemptRunHost(run, this),
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
            ): PhaseAcceptedStepExecution {
              val run = delegate.stepBinding.requireAuthorizedFanOutWave()
              val unit = (delegate as GoalPlanningPhaseRunState).planningUnitState(unitId, outputSink)
              val interceptedUnit =
                object : PhaseRunState by unit {
                  override val records = rejecting(unit.records)
                }
              delegate.stepBinding.beginStepBinding(run, unitId)
              return FeatureTaskRuntimeRunLoopStepBindings.create(
                phaseAttemptLaunchCollaborationScope(
                  PhaseAttemptRunHost(run, interceptedUnit),
                ),
                run,
                unitId,
                bindingCoordinator = delegate.stepBinding,
              )
            }
          }
        }
      }
    return super.run(context.withRunState(intercepted), beforeDrive)
  }

  private fun rejecting(delegate: PhaseRunRecords): PhaseRunRecords =
    object : PhaseRunRecords by delegate {
      override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite {
        if (request.phaseId == phase && kind == RequiredPhaseWriteKind.START) {
          return RequiredPhaseWrite.Rejected(kind, request.workflowId, phase, request.attemptCount)
        }
        return delegate.recordRequiredPhaseStart(request)
      }

      override fun recordPhaseBriefing(
        workflowId: String,
        briefing: FeatureTaskRuntimePhaseLaunchBriefing,
        sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement?,
        attempt: Int,
      ): RequiredPhaseWrite {
        if (briefing.phaseId == phase && kind == RequiredPhaseWriteKind.BRIEFING) {
          return RequiredPhaseWrite.Rejected(kind, workflowId, phase, attempt)
        }
        return delegate.recordPhaseBriefing(workflowId, briefing, sharedEvidenceMeasurement, attempt)
      }

      override fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean {
        if (request.phaseId == phase && request.status == "blocked") terminalReasons += request.blockedReason
        return delegate.recordPhaseState(request)
      }
    }
}
