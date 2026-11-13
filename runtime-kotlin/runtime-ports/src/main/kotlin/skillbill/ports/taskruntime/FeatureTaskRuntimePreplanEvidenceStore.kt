package skillbill.ports.taskruntime

import skillbill.ports.taskruntime.model.PreplanEvidenceWriteRequest
import skillbill.ports.taskruntime.model.PreplanEvidenceWriteResult

interface FeatureTaskRuntimePreplanEvidenceStore {
  /**
   * Writes the preplan evidence file for the workflow in [request]: the preplan digest followed by the
   * checkout source ranges its references name. Replaces any earlier file for the workflow. Never throws for
   * an unreadable reference or an unwritable store; those degrade into the returned result.
   */
  fun write(request: PreplanEvidenceWriteRequest): PreplanEvidenceWriteResult
}
