package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePhaseStatus
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.state.PhaseHistoricalInterpreter
import skillbill.engine.featuretask.slot.state.PhaseHistoricalPolicy
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PhaseHistoricalInterpreterTest {
  @Test
  fun `interpreting an unselected historical build never authorizes execution`() {
    var launches = 0
    val runner =
      object : PhaseRunner {
        override fun run(
          input: PhaseStepInput,
          state: PhaseLaunchState,
        ): PhaseStepOutput {
          launches++
          error("Unselected historical steps must not launch")
        }
      }
    val registry = PhaseStrategyRegistry(listOf(PhaseStrategyRegistration(AgentValidateStrategy(), runner)))
    val lookup =
      PhaseStrategyLookup(
        registry,
        PhaseStrategySelection(
          registry,
          mapOf(
            SkeletonDefinition.VALIDATION to
              mapOf(
                PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID),
              ),
          ),
        ),
      )
    val plan = lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.VALIDATION, emptySet()))
    assertEquals(setOf(PHASE_VALIDATE), plan.selectedStepIds)
    assertFailsWith<InvalidPhaseStrategyCompositionError> { lookup.strategyFor(PHASE_BUILD, plan) }

    val history = PhaseHistoricalInterpreter(PhaseHistoricalPolicy.REVISION_1)
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = PHASE_BUILD,
        status = WorkflowStepStatus.COMPLETED,
        attemptCount = 3,
        startedAt = Instant.EPOCH,
        finishedAt = Instant.EPOCH.plusSeconds(10),
        resolvedAgentId = "original-builder",
        outputArtifact = "retained-output",
      )
    val normalized = history.normalize(mapOf(PHASE_BUILD to record), emptyList())
    val execution =
      history.currentExecution(
        FeatureTaskRuntimeCurrentPhaseExecutionContext(
          currentPhaseId = PHASE_BUILD,
          records = normalized.records,
          phases =
            listOf(
              FeatureTaskRuntimePhaseStatus(
                PHASE_BUILD,
                WorkflowStepStatus.COMPLETED.wireValue,
                3,
                "original-builder",
                true,
              ),
            ),
          ledger = emptyList(),
          gateRunCount = 2,
        ),
      )

    assertSame(record, normalized.records[PHASE_BUILD])
    assertEquals(IdeStatusCurrentPhaseExecutionKind.GATE_RUN, execution?.kind)
    assertEquals(2, execution?.count)
    assertSame(history.resumeRules(PHASE_BUILD), lookup.resumeRules(plan)(PHASE_BUILD))
    assertFailsWith<InvalidPhaseStrategyCompositionError> { lookup.resumeRules(plan)("unknown-step") }
    assertFailsWith<InvalidPhaseStrategyCompositionError> { lookup.strategyFor(PHASE_BUILD, plan) }
    assertEquals(0, launches)
  }

  @Test
  fun `unknown historical semantics retain raw records and have no resume rules`() {
    val history = PhaseHistoricalInterpreter(PhaseHistoricalPolicy.REVISION_1)
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "unknown-step",
        status = WorkflowStepStatus.COMPLETED,
        attemptCount = 4,
        startedAt = Instant.EPOCH,
        resolvedAgentId = "original-agent",
        outputArtifact = "retain-original-evidence",
        loopId = "unknown-loop",
        edgeIteration = 2,
      )

    assertSame(record, history.normalize(mapOf(record.phaseId to record), emptyList()).records[record.phaseId])
    assertFailsWith<InvalidPhaseStrategyCompositionError> { history.resumeRules(record.phaseId) }
  }
}
