package skillbill.engine.featuretask.runloop.finalization

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts

object NoopCommitPushManifestCompletion : CommitPushManifestCompletion {
  override fun markCompleteBeforeFinalCommit(
    request: FeatureTaskRuntimeRunFacts,
    lastResumableStep: String,
  ): String? = null
}
