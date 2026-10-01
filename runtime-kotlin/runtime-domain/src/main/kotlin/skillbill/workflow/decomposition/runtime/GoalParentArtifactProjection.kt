package skillbill.workflow.decomposition.runtime

import skillbill.workflow.engine.model.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_REVIEW_POLICY_ARTIFACT_KEY
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

fun goalParentArtifactProjection(
  existing: DurableWorkflowArtifacts,
  encodedManifest: FeatureTaskRuntimeWorkflowArtifactMap,
): WorkflowArtifactPatch =
  WorkflowArtifactPatch.from(
    LinkedHashMap<String, Any?>(existing).apply {
      remove(GOAL_REVIEW_POLICY_ARTIFACT_KEY)
      remove(GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY)
      put(DECOMPOSITION_RUNTIME_ARTIFACT_KEY, LinkedHashMap(encodedManifest))
    },
  ) ?: WorkflowArtifactPatch.EMPTY
