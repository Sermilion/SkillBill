package skillbill.engine.featuretask.lifecycle.core

import skillbill.engine.featuretask.runloop.core.resolveLaunchRejectionAttribution
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeLaunchRejectionAttributionTest {
  @Test
  fun `audit rejection uses the rejected projection contract and its current producer iteration`() {
    val declarations =
      requireNotNull(
        FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations[
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        ],
      ).projectionDeclarations
    val rejectedPlan =
      declarations.single {
        it.producerIteration.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
      }

    val attribution =
      resolveLaunchRejectionAttribution(
        declarations = declarations,
        projectionName = rejectedPlan.projectionName,
        currentProducerIteration = { phaseId ->
          when (phaseId) {
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN -> 2
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT -> 7
            else -> null
          }
        },
        fallbackProducerIteration =
          FeatureTaskRuntimeProducerIteration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
            7,
          ),
      )

    assertEquals(rejectedPlan.projectionContractId, attribution.projectionContractId)
    assertEquals(
      FeatureTaskRuntimeProducerIteration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, 2),
      attribution.producerIteration,
    )
  }

  @Test
  fun `an implementation projection rejection preserves the rejected declaration identity`() {
    val declarations =
      requireNotNull(
        FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations[
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        ],
      ).projectionDeclarations
    val rejectedImplementation =
      declarations.single {
        it.producerIteration.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
      }

    val attribution =
      resolveLaunchRejectionAttribution(
        declarations = declarations,
        projectionName = rejectedImplementation.projectionName,
        currentProducerIteration = { phaseId ->
          when (phaseId) {
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN -> 5
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT -> 3
            else -> null
          }
        },
        fallbackProducerIteration =
          FeatureTaskRuntimeProducerIteration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
            5,
          ),
      )

    assertEquals(rejectedImplementation.projectionContractId, attribution.projectionContractId)
    assertEquals(
      FeatureTaskRuntimeProducerIteration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT, 3),
      attribution.producerIteration,
    )
  }
}
