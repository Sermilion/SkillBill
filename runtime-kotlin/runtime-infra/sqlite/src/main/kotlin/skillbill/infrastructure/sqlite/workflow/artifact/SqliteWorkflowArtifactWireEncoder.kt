package skillbill.infrastructure.sqlite.workflow.artifact

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.workflow.WorkflowArtifactWireEncoder
import skillbill.workflow.engine.model.DurableWorkflowArtifacts

@Inject
class SqliteWorkflowArtifactWireEncoder : WorkflowArtifactWireEncoder {
  override fun encode(artifacts: DurableWorkflowArtifacts): String =
    JsonCodec.valueToJsonString(FeatureTaskWorkflowArtifactsCodec.encode(artifacts))

  override fun decode(wire: String): DurableWorkflowArtifacts =
    FeatureTaskWorkflowArtifactsCodec.decode(
      JsonCodec.parseObjectOrNull(wire)
        ?: throw invalidWorkflowStateSchemaError("Workflow state artifacts must decode to a JSON object."),
    )
}
