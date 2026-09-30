package skillbill.engine.featuretask.phase.core

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal fun auditProseValue(outputObject: FeatureTaskRuntimeWorkflowArtifactMap?): String? =
  outputObject?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
    ?.let(JsonCodec::anyToStringAnyMap)
    ?.get(SharedPayloadKeys.VALUE)
    ?.toString()
    ?.takeIf(String::isNotBlank)
