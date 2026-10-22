package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.error.featuretask.DuplicatePhaseStrategyError
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.PhaseStrategyStepOutsideSlotError
import skillbill.error.featuretask.UnknownPhaseStrategyError
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import java.util.Collections

class PhaseStrategyRegistration(val strategy: PhaseStrategy, internal val runner: PhaseRunner)

class PhaseStrategyRegistry(registrations: List<PhaseStrategyRegistration>) {
  val strategies: List<PhaseStrategy> = Collections.unmodifiableList(registrations.map { it.strategy })
  private val byKey: Map<Pair<PhaseSlot, String>, PhaseStrategy>
  private val runners: Map<Pair<PhaseSlot, String>, PhaseRunner>

  init {
    val keyed = linkedMapOf<Pair<PhaseSlot, String>, PhaseStrategy>()
    val keyedRunners = linkedMapOf<Pair<PhaseSlot, String>, PhaseRunner>()
    registrations.forEach { registration ->
      val strategy = registration.strategy
      validateStrategy(strategy)
      strategy.steps.firstOrNull { it !in strategy.slot.steps }?.let { step ->
        throw PhaseStrategyStepOutsideSlotError(strategy.slot.wireValue, strategy.strategyId, step)
      }
      if (keyed.put(strategy.slot to strategy.strategyId, strategy) != null) {
        throw DuplicatePhaseStrategyError(strategy.slot.wireValue, strategy.strategyId)
      }
      keyedRunners[strategy.slot to strategy.strategyId] = registration.runner
    }
    byKey = keyed
    runners = keyedRunners
  }

  fun contains(
    slot: PhaseSlot,
    strategyId: String,
  ): Boolean = (slot to canonicalStrategyId(slot, strategyId)) in byKey

  fun strategy(
    slot: PhaseSlot,
    strategyId: String,
  ): PhaseStrategy =
    byKey[slot to canonicalStrategyId(slot, strategyId)] ?: throw UnknownPhaseStrategyError(slot.wireValue, strategyId)

  internal fun runner(
    slot: PhaseSlot,
    strategyId: String,
  ): PhaseRunner =
    runners[
      slot to
        canonicalStrategyId(
          slot,
          strategyId,
        ),
    ] ?: throw UnknownPhaseStrategyError(slot.wireValue, strategyId)

  internal fun canonicalStrategyId(
    slot: PhaseSlot,
    strategyId: String,
  ): String = HISTORICAL_STRATEGY_ALIASES[slot to strategyId] ?: strategyId
}

private val HISTORICAL_STRATEGY_ALIASES: Map<Pair<PhaseSlot, String>, String> =
  mapOf(
    PhaseSlot.PREPLAN to "agent-preplan-opus-5-5" to AgentPreplanStrategy.ID,
    PhaseSlot.PLAN to "goal-plan-fan-out-opus-5-5" to PlanFanOutStrategy.ID,
    PhaseSlot.IMPLEMENTATION to "implement-then-simplify-opus-5-5" to ImplementThenSimplifyStrategy.ID,
    PhaseSlot.AUDIT to "acceptance-audit-opus-5-5" to AcceptanceAuditStrategy.ID,
    PhaseSlot.CODE_REVIEW to "inline-opus-5-5" to InlineReviewStrategy.ID,
    PhaseSlot.STANDALONE_REVIEW to "inline-opus-5-5" to InlineStandaloneReviewStrategy.ID,
    PhaseSlot.STANDALONE_REVIEW to "delegated-opus-5-5" to DelegatedStandaloneReviewStrategy.ID,
    PhaseSlot.QUALITY_GATE to "pack-build-opus-5-5" to PackBuildStrategy.ID,
    PhaseSlot.QUALITY_GATE to "agent-validate-opus-5-5" to AgentValidateStrategy.ID,
    PhaseSlot.WRITE_HISTORY to "boundary-history-opus-5-5" to BoundaryHistoryStrategy.ID,
    PhaseSlot.PULL_REQUEST to "pr-description-opus-5-5" to PrDescriptionStrategy.ID,
    PhaseSlot.MONITOR to "monitor-ci-opus-5-5" to MonitorStrategy.ID,
    PhaseSlot.MONITOR to "standalone-monitor-ci-opus-5-5" to StandaloneMonitorStrategy.ID,
  )

private fun validateStrategy(strategy: PhaseStrategy) {
  val reason =
    when {
      strategy.semanticRevision <= 0 -> "has an invalid semantic revision"
      strategy.steps.isEmpty() -> "has no steps"
      strategy.strategyId.isBlank() || strategy.steps.any(String::isBlank) -> "has a blank identity"
      strategy.steps.size != strategy.steps.toSet().size -> "repeats a step"
      !strategy.steps.containsAll(strategy.optionalSteps) -> "marks an unowned step optional"
      strategy.entryStep !in strategy.steps -> "has an entry outside its steps"
      else -> return
    }
  throw InvalidPhaseStrategyCompositionError("strategy ${strategy.slot.wireValue}/${strategy.strategyId} $reason")
}
