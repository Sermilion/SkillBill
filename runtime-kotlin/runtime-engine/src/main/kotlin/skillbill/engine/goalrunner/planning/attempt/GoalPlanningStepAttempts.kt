package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptOnce
import skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptScope
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
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
    val iteration = call.acceptedExecution.nextStepIteration()
    return when (val start = PhaseAttemptOnce.persistRequiredStart(context, run, iteration)) {
      RequiredPhaseWrite.Acknowledged -> runAfterRequiredStart(run, call, context, owner)
      is RequiredPhaseWrite.Rejected -> PhaseAttemptOnce.blockRequiredWriteRejection(context, run, start)
    }
  }

  private fun runAfterRequiredStart(
    run: PhaseRun,
    call: PhaseStepCall,
    context: PhaseRunLoopAttemptScope,
    owner: PhaseStrategy,
  ): PhaseOutcome {
    val launch =
      GoalPlanningLaunch(
        runner = context.runnerForAcceptedAttempt(run, call),
        state = call.acceptedExecution,
        prompt = call.description.prompt,
        policy = call.description.policy,
        invariantFields = owner.briefingInvariantFields(run.phaseId),
      )
    val onRejected = { rejection: RequiredPhaseWrite.Rejected ->
      PhaseAttemptOnce.blockRequiredWriteRejection(context, run, rejection)
    }
    return subtaskId?.let { id -> progress.producePlan(id, outputSink, launch, onRejected) }
      ?: progress.settlePreplan(launch, onRejected)
  }
}
