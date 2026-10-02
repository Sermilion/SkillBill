package skillbill.application.review.recording

import skillbill.ports.taskruntime.DERIVING_SHARED_EVIDENCE_RESOLVER
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import java.util.concurrent.ConcurrentHashMap

class RecordingReviewEvidenceStore {
  private val payloads = ConcurrentHashMap<String, String>()
  val reader =
    FeatureTaskRuntimeSharedEvidenceLocatorReadPort { request ->
      requireNotNull(payloads[request.storePath])
    }
  val resolver =
    FeatureTaskRuntimeSharedEvidenceResolverPort { request, deriver ->
      val resolution =
        DERIVING_SHARED_EVIDENCE_RESOLVER.resolve(request, deriver)
          ?: return@FeatureTaskRuntimeSharedEvidenceResolverPort null
      val address = "recording-review-evidence/${request.checkpoint.fingerprint}"
      payloads[address] = resolution.diffPayload
      resolution.copy(storePath = address)
    }
}
