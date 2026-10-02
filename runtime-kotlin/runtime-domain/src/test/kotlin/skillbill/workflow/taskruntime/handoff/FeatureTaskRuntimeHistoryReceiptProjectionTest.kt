package skillbill.workflow.taskruntime.handoff

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimeHistoryReceiptProjectionTest {
  private val def = FeatureTaskRuntimePhaseWorkflowDefinition

  @Test
  fun `history receipt projects the runtime-measured facts at contract 0_2`() {
    val receipt = historyReceipt(HANDOFF_VALIDATOR_HISTORY_PHASE_PAYLOAD)

    assertEquals("0.2", receipt.projectionContractVersion)
    assertEquals(
      mapOf(
        "changed_paths" to FeatureTaskRuntimeHandoffProjectionValue.TextList(listOf("agent/history.md")),
        "history_written" to FeatureTaskRuntimeHandoffProjectionValue.Text("true"),
        "decisions_recorded" to FeatureTaskRuntimeHandoffProjectionValue.Text("false"),
      ),
      receipt.fields.associate { it.name to it.value },
    )
  }

  @Test
  fun `a record written before measurement projects unknown facts and never reads history_result`() {
    val legacy =
      """{"contract_version":"0.6","phase_id":"write_history","status":"completed","summary":"done",""" +
        """"produced_outputs":{"history_result":{"changed_paths":["src/Claimed.kt"],""" +
        """"decisions_recorded":["claimed"]}}}"""

    val receipt = historyReceipt(legacy)

    assertEquals(
      mapOf(
        "changed_paths" to FeatureTaskRuntimeHandoffProjectionValue.Text("unknown"),
        "history_written" to FeatureTaskRuntimeHandoffProjectionValue.Text("unknown"),
        "decisions_recorded" to FeatureTaskRuntimeHandoffProjectionValue.Text("unknown"),
      ),
      receipt.fields.associate { it.name to it.value },
    )
  }

  private fun historyReceipt(writeHistoryPayload: String): FeatureTaskRuntimeHandoffProjection {
    val phaseDeclarations =
      FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationWithoutSteps(
        def.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
        setOf(def.PHASE_BUILD),
      ).projectionDeclarations
    val upstream =
      FeatureTaskRuntimeResolvedUpstreamOutputs(
        mapOf(
          def.PHASE_IMPLEMENT to proseOutput(def.PHASE_IMPLEMENT, "implemented"),
          def.PHASE_VALIDATE to recordedPhaseOutput(def.PHASE_VALIDATE, HANDOFF_VALIDATOR_VALIDATION_PHASE_PAYLOAD),
          def.PHASE_WRITE_HISTORY to recordedPhaseOutput(def.PHASE_WRITE_HISTORY, writeHistoryPayload),
        ),
      )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint("tree-1", workingTreeOwnedPaths = listOf("src/A.kt"))
    val envelope =
      FeatureTaskRuntimeHandoffProjectionValidator.validate(
        handoffProjectionValidatorInputs {
          consumerPhaseId = def.PHASE_COMMIT_PUSH
          declarations = phaseDeclarations
          resolvedUpstream = upstream
          resolvedCheckpoint = checkpoint
        },
      )
    return envelope.projections.single { it.projectionName == "history_receipt" }
  }
}
