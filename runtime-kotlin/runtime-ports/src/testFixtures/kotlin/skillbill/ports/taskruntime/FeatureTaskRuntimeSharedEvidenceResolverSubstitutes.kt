package skillbill.ports.taskruntime

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef

private const val UNPERSISTED_PAYLOAD_NAME = "unpersisted.patch"

val DERIVING_SHARED_EVIDENCE_RESOLVER =
  FeatureTaskRuntimeSharedEvidenceResolverPort { request, deriver ->
    val derivation = deriver.derive(request.checkpoint) ?: return@FeatureTaskRuntimeSharedEvidenceResolverPort null
    FeatureTaskRuntimeSharedEvidenceResolution(
      artifact =
        FeatureTaskRuntimeSharedEvidenceArtifact(
          fingerprint = request.checkpoint.fingerprint,
          baseRef = derivation.baseRef,
          headRef = derivation.headRef,
          files = derivation.files,
          hunks = derivation.hunks,
          diffPayload =
            FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(
              relativePath = UNPERSISTED_PAYLOAD_NAME,
              sizeBytes = derivation.diffPayload.toByteArray().size.toLong(),
            ),
        ),
      diffPayload = derivation.diffPayload,
      outcome = FeatureTaskRuntimeSharedEvidenceResolveOutcome.DERIVATION,
    )
  }
