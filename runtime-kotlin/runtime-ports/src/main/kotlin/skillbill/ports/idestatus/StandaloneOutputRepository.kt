package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.StandalonePhaseOutputLine
import skillbill.ports.idestatus.model.StandalonePhaseOutputSnapshot

interface StandaloneOutputRepository {
  fun writeOutputSnapshot(
    executionId: String,
    snapshot: StandalonePhaseOutputSnapshot,
  )

  fun readOutputLines(
    executionId: String,
    beforeSequence: Long?,
    limit: Int,
  ): List<StandalonePhaseOutputLine>

  fun readOutput(executionId: String): StandalonePhaseOutputSnapshot?
}
