package skillbill.ports.workflow

import skillbill.contracts.JsonCodec
import skillbill.workflow.engine.model.DurableWorkflowArtifacts

object PlainWorkflowArtifactWireEncoder : WorkflowArtifactWireEncoder {
  override fun encode(artifacts: DurableWorkflowArtifacts): String = JsonCodec.mapToJsonString(artifacts)

  override fun decode(wire: String): DurableWorkflowArtifacts =
    DurableWorkflowArtifacts.fromMap(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(wire)).orEmpty())
}
