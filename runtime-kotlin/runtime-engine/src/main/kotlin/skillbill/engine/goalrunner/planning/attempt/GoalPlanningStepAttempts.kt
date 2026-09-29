package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptOnce
import skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptScope
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
import skillbill.engine.goalrunner.planning.model.GoalPlanningLaunch
import skillbill.engine.goalrunner.planning.state.GoalPlanningRunProgress
import skillbill.ports.agentrun.model.AgentRunOutputSink

internal class GoalPlanningStepAttempts(
  private val progress: GoalPlanningRunProgress,
  private val subtaskId: Int? = null,
  private val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
) : PhaseStepAttempts {
  override fun run(
    run: PhaseRun,
    call: PhaseStepCall,
    context: PhaseRunLoopAttemptScope,
  ): PhaseOutcome {
    call.acceptedExecution.requireAcceptedAttempt(run, call)
    val owner = context.strategyFor(run.phaseId)
    val launch =
      GoalPlanningLaunch(
        runner = owner.runner,
        state = call.acceptedExecution,
        prompt = call.description.prompt,
        policy = call.description.policy,
        invariantFields = owner.briefingInvariantFields(run.phaseId),
      )
    val iteration = call.acceptedExecution.nextStepIteration()
    return try {
      PhaseAttemptOnce.persistRequiredStart(context, run, iteration)
      subtaskId?.let { id -> progress.producePlan(id, outputSink, launch) } ?: progress.settlePreplan(launch)
    } catch (rejection: RequiredPhaseWriteRejected) {
      PhaseAttemptOnce.blockRequiredWriteRejection(context, run, rejection)
    }
  }
}
